package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// upstream is the server the proxy hands connections to.
type upstream interface {
	DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error)
	// ListenUDP opens a datagram session through the server.
	ListenUDP(ctx context.Context) (udpSession, error)
	Close() error
}

// udpSession carries datagrams to any destination and back.
type udpSession interface {
	WriteTo(p []byte, host string, port uint16) error
	// ReadFrom returns the sender, an IP literal or a domain.
	ReadFrom(p []byte) (n int, host string, port uint16, err error)
	Close() error
}

const (
	socksVersion   = 5
	cmdConnect     = 1
	cmdAssociate   = 3
	atypIPv4       = 1
	atypDomain     = 3
	atypIPv6       = 4
	repSucceeded   = 0
	repFailure     = 1
	repHostUnreach = 4
	repCmdNotSupp  = 7
)

func serveSocks(ln net.Listener, up upstream) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handleSocks(c, up)
	}
}

func handleSocks(c net.Conn, up upstream) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	br := bufio.NewReader(c)

	// Greeting: only "no authentication" is offered; the port is local.
	head := make([]byte, 2)
	if _, err := io.ReadFull(br, head); err != nil || head[0] != socksVersion {
		return
	}
	if _, err := io.CopyN(io.Discard, br, int64(head[1])); err != nil {
		return
	}
	if _, err := c.Write([]byte{socksVersion, 0}); err != nil {
		return
	}

	req := make([]byte, 3)
	if _, err := io.ReadFull(br, req); err != nil || req[0] != socksVersion {
		return
	}
	host, port, err := readAddr(br)
	if err != nil {
		return
	}

	switch req[1] {
	case cmdConnect:
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		target, err := up.DialTCP(ctx, host, port)
		cancel()
		if err != nil {
			writeReply(c, repHostUnreach, nil)
			return
		}
		defer target.Close()
		if writeReply(c, repSucceeded, nil) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		relay(c, br, target)

	case cmdAssociate:
		associate(c, br, up)

	default:
		writeReply(c, repCmdNotSupp, nil)
	}
}

// relay copies both ways until both directions are done.
func relay(client net.Conn, clientReader io.Reader, target net.Conn) {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		io.Copy(target, clientReader)
		closeWrite(target)
	}()
	io.Copy(client, target)
	closeWrite(client)
	wg.Wait()
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
		return
	}
	// No half-close on this kind of connection: end it so the other side stops too.
	c.Close()
}

// associate serves one UDP ASSOCIATE: a local datagram port for the client that
// lives as long as the control connection does.
func associate(c net.Conn, br *bufio.Reader, up upstream) {
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		writeReply(c, repFailure, nil)
		return
	}
	defer local.Close()
	if writeReply(c, repSucceeded, local.LocalAddr().(*net.UDPAddr)) != nil {
		return
	}
	c.SetDeadline(time.Time{})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var (
		client  atomic.Pointer[net.UDPAddr]
		session udpSession
		once    sync.Once
		openErr error
	)
	open := func() (udpSession, error) {
		once.Do(func() {
			session, openErr = up.ListenUDP(ctx)
			if openErr != nil {
				return
			}
			// Answers from the server back to the client, with the SOCKS header.
			go func() {
				buf := make([]byte, 65535)
				for {
					n, host, port, err := session.ReadFrom(buf)
					if err != nil {
						return
					}
					to := client.Load()
					if to == nil {
						continue
					}
					packet := appendAddr([]byte{0, 0, 0}, host, port)
					packet = append(packet, buf[:n]...)
					local.WriteToUDP(packet, to)
				}
			}()
		})
		return session, openErr
	}
	defer func() {
		if session != nil {
			session.Close()
		}
	}()

	// From the client out to the server.
	go func() {
		buf := make([]byte, 65535)
		for {
			n, from, err := local.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if n < 4 || buf[2] != 0 {
				continue // too short, or fragmented: SOCKS fragments are not supported
			}
			client.Store(from)
			r := bufio.NewReader(&sliceReader{b: buf[3:n]})
			host, port, err := readAddr(r)
			if err != nil {
				continue
			}
			payload, _ := io.ReadAll(r)
			s, err := open()
			if err != nil {
				return
			}
			s.WriteTo(payload, host, port)
		}
	}()

	// The association ends with its control connection.
	io.Copy(io.Discard, br)
}

type sliceReader struct{ b []byte }

func (s *sliceReader) Read(p []byte) (int, error) {
	if len(s.b) == 0 {
		return 0, io.EOF
	}
	n := copy(p, s.b)
	s.b = s.b[n:]
	return n, nil
}

func readAddr(r *bufio.Reader) (string, uint16, error) {
	atyp, err := r.ReadByte()
	if err != nil {
		return "", 0, err
	}
	var host string
	switch atyp {
	case atypIPv4:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = netip.AddrFrom4([4]byte(b)).String()
	case atypIPv6:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = netip.AddrFrom16([16]byte(b)).Unmap().String()
	case atypDomain:
		n, err := r.ReadByte()
		if err != nil {
			return "", 0, err
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	default:
		return "", 0, errors.New("bad address type")
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(r, p); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(p), nil
}

func appendAddr(b []byte, host string, port uint16) []byte {
	if ip, err := netip.ParseAddr(host); err == nil {
		ip = ip.Unmap()
		if ip.Is4() {
			a := ip.As4()
			b = append(append(b, atypIPv4), a[:]...)
		} else {
			a := ip.As16()
			b = append(append(b, atypIPv6), a[:]...)
		}
	} else {
		if len(host) > 255 {
			host = host[:255]
		}
		b = append(append(b, atypDomain, byte(len(host))), host...)
	}
	return binary.BigEndian.AppendUint16(b, port)
}

func writeReply(c net.Conn, rep byte, bound *net.UDPAddr) error {
	host, port := "0.0.0.0", uint16(0)
	if bound != nil {
		host, port = bound.IP.String(), uint16(bound.Port)
	}
	_, err := c.Write(appendAddr([]byte{socksVersion, rep, 0}, host, port))
	return err
}

func hostPort(host string, port uint16) string {
	return net.JoinHostPort(host, strconv.Itoa(int(port)))
}
