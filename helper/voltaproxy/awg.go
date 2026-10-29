package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// awgConfig is a wg-quick style .conf turned into what the engine needs.
type awgConfig struct {
	addresses []netip.Addr
	dns       []netip.Addr
	mtu       int
	uapi      string
}

// The .conf [Interface] keys the engine understands, by their UAPI name. Keys of
// AmneziaWG 1.x, 2.x and 3.x are all here; anything else (wg-quick's PostUp, a
// client's own extras) is ignored instead of failing the whole config.
var awgInterfaceKeys = map[string]string{
	"jc": "jc", "jmin": "jmin", "jmax": "jmax",
	"s1": "s1", "s2": "s2", "s3": "s3", "s4": "s4",
	"h1": "h1", "h2": "h2", "h3": "h3", "h4": "h4",
	"i1": "i1", "i2": "i2", "i3": "i3", "i4": "i4", "i5": "i5",
	"contentpaddingaddition": "content_padding_addition",
	"rekeyaftertime":         "rekey_after_time",
	"rekeytimeout":           "rekey_timeout",
	"rejectaftertime":        "reject_after_time",
	"keepalivetimeout":       "keepalive_timeout",
	"maxhandshakeattempts":   "max_handshake_attempts",
	"randomtrailers":         "random_trailers",
	"disablecookies":         "disable_cookies",
}

func parseAWG(text string) (*awgConfig, error) {
	cfg := &awgConfig{mtu: 1280}
	var dev strings.Builder
	section := ""
	peers := 0
	var publicKey, presharedKey, endpoint, keepalive string
	var allowed []string
	for _, raw := range strings.Split(strings.ReplaceAll(text, "\r", ""), "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				peers++
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(k))
		value := strings.TrimSpace(v)
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				h, err := keyHex(value)
				if err != nil {
					return nil, fmt.Errorf("PrivateKey: %w", err)
				}
				fmt.Fprintf(&dev, "private_key=%s\n", h)
			case "address":
				for _, a := range splitList(value) {
					if p, err := netip.ParsePrefix(a); err == nil {
						cfg.addresses = append(cfg.addresses, p.Addr())
					} else if ip, err := netip.ParseAddr(a); err == nil {
						cfg.addresses = append(cfg.addresses, ip)
					}
				}
			case "dns":
				for _, a := range splitList(value) {
					if ip, err := netip.ParseAddr(a); err == nil {
						cfg.dns = append(cfg.dns, ip)
					}
				}
			case "mtu":
				if n, err := strconv.Atoi(value); err == nil && n >= 576 && n <= 9000 {
					cfg.mtu = n
				}
			case "headerprotectionkey":
				h, err := keyHex(value)
				if err != nil {
					return nil, fmt.Errorf("HeaderProtectionKey: %w", err)
				}
				fmt.Fprintf(&dev, "header_protection_key=%s\n", h)
			default:
				if name, ok := awgInterfaceKeys[key]; ok && value != "" {
					if name == "random_trailers" || name == "disable_cookies" {
						value = boolValue(value)
					}
					fmt.Fprintf(&dev, "%s=%s\n", name, value)
				}
			}
		case "peer":
			if peers > 1 {
				continue // one exit is all a proxy needs
			}
			switch key {
			case "publickey":
				h, err := keyHex(value)
				if err != nil {
					return nil, fmt.Errorf("PublicKey: %w", err)
				}
				publicKey = h
			case "presharedkey":
				h, err := keyHex(value)
				if err != nil {
					return nil, fmt.Errorf("PresharedKey: %w", err)
				}
				presharedKey = h
			case "endpoint":
				endpoint = value
			case "allowedips":
				for _, a := range splitList(value) {
					if _, err := netip.ParsePrefix(a); err == nil {
						allowed = append(allowed, a)
					}
				}
			case "persistentkeepalive":
				if value != "" && value != "off" && value != "0" {
					keepalive = value
				}
			}
		}
	}
	if !strings.Contains(dev.String(), "private_key=") {
		return nil, errors.New("в конфиге нет PrivateKey")
	}
	if publicKey == "" {
		return nil, errors.New("в конфиге нет [Peer] с PublicKey")
	}
	if endpoint == "" {
		return nil, errors.New("в конфиге нет Endpoint")
	}
	if len(cfg.addresses) == 0 {
		return nil, errors.New("в конфиге нет Address")
	}
	ep, err := resolveEndpoint(endpoint)
	if err != nil {
		return nil, err
	}
	if len(cfg.dns) == 0 {
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}
	}
	if len(allowed) == 0 {
		allowed = []string{"0.0.0.0/0", "::/0"}
	}
	// public_key opens the peer section, so it goes first.
	var peer strings.Builder
	fmt.Fprintf(&peer, "public_key=%s\n", publicKey)
	if presharedKey != "" {
		fmt.Fprintf(&peer, "preshared_key=%s\n", presharedKey)
	}
	fmt.Fprintf(&peer, "endpoint=%s\n", ep)
	if keepalive != "" {
		fmt.Fprintf(&peer, "persistent_keepalive_interval=%s\n", keepalive)
	}
	for _, a := range allowed {
		fmt.Fprintf(&peer, "allowed_ip=%s\n", a)
	}
	cfg.uapi = dev.String() + peer.String()
	return cfg, nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func boolValue(v string) string {
	switch strings.ToLower(v) {
	case "on", "true", "1", "yes":
		return "true"
	}
	return "false"
}

// keyHex turns a key as written in a .conf (base64) into the engine's hex form.
func keyHex(v string) (string, error) {
	v = strings.TrimSpace(v)
	if len(v) == 64 {
		if _, err := hex.DecodeString(v); err == nil {
			return strings.ToLower(v), nil
		}
	}
	b, err := base64.StdEncoding.DecodeString(v)
	if err != nil {
		b, err = base64.RawStdEncoding.DecodeString(strings.TrimRight(v, "="))
	}
	if err != nil || len(b) != 32 {
		return "", errors.New("ключ не читается")
	}
	return hex.EncodeToString(b), nil
}

// resolveEndpoint turns host:port into ip:port; the engine takes addresses only.
// This process is outside the VPN, so the lookup uses the phone's own network.
func resolveEndpoint(endpoint string) (string, error) {
	host, port, err := net.SplitHostPort(endpoint)
	if err != nil {
		return "", fmt.Errorf("Endpoint %q: %w", endpoint, err)
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(ip.Unmap(), mustPort(port)).String(), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return "", fmt.Errorf("адрес сервера %s не найден", host)
	}
	pick := ips[0]
	for _, ip := range ips {
		if ip.Unmap().Is4() {
			pick = ip
			break
		}
	}
	return netip.AddrPortFrom(pick.Unmap(), mustPort(port)).String(), nil
}

func mustPort(s string) uint16 {
	n, _ := strconv.ParseUint(s, 10, 16)
	return uint16(n)
}

type awgUpstream struct {
	dev  *device.Device
	tnet *netstack.Net
	// The tunnel's own addresses: a datagram socket must be bound to one of them,
	// gVisor finds no route from an unspecified source.
	local4, local6 netip.Addr
}

func newAWG(text string) (upstream, error) {
	cfg, err := parseAWG(text)
	if err != nil {
		return nil, err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}
	level := device.LogLevelError
	if os.Getenv("VOLTA_DEBUG") != "" {
		level = device.LogLevelVerbose
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(level, "awg: "))
	if err := dev.IpcSet(cfg.uapi); err != nil {
		dev.Close()
		return nil, fmt.Errorf("конфиг AmneziaWG не принят: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("AmneziaWG не запустился: %w", err)
	}
	u := &awgUpstream{dev: dev, tnet: tnet}
	for _, a := range cfg.addresses {
		a = a.Unmap()
		if a.Is4() && !u.local4.IsValid() {
			u.local4 = a
		} else if a.Is6() && !u.local6.IsValid() {
			u.local6 = a
		}
	}
	return u, nil
}

func (u *awgUpstream) DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error) {
	return u.tnet.DialContext(ctx, "tcp", hostPort(host, port))
}

func (u *awgUpstream) resolve(ctx context.Context, host string) (netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip.Unmap(), nil
	}
	addrs, err := u.tnet.LookupContextHost(ctx, host)
	if err != nil {
		return netip.Addr{}, err
	}
	var fallback netip.Addr
	for _, a := range addrs {
		ip, err := netip.ParseAddr(a)
		if err != nil {
			continue
		}
		if ip.Is4() && u.local4.IsValid() {
			return ip, nil
		}
		if ip.Is6() && u.local6.IsValid() && !fallback.IsValid() {
			fallback = ip
		}
	}
	if fallback.IsValid() {
		return fallback, nil
	}
	return netip.Addr{}, fmt.Errorf("%s: no usable address", host)
}

func (u *awgUpstream) ListenUDP(ctx context.Context) (udpSession, error) {
	s := &awgUDP{up: u, in: make(chan awgPacket, 256), done: make(chan struct{}), names: map[netip.Addr]string{}}
	return s, nil
}

func (u *awgUpstream) Close() error {
	u.dev.Close()
	return nil
}

type awgPacket struct {
	data []byte
	from netip.AddrPort
}

// awgUDP is one datagram session inside the tunnel: an IPv4 socket, plus an IPv6
// one once something is sent to an IPv6 address.
type awgUDP struct {
	up    *awgUpstream
	mu    sync.Mutex
	v4    net.PacketConn
	v6    net.PacketConn
	in    chan awgPacket
	done  chan struct{}
	once  sync.Once
	names map[netip.Addr]string
}

func (s *awgUDP) socket(ip netip.Addr) (net.PacketConn, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ip.Is4() {
		if !s.up.local4.IsValid() {
			return nil, errors.New("в туннеле нет IPv4-адреса")
		}
		if s.v4 == nil {
			c, err := s.up.tnet.ListenUDPAddrPort(netip.AddrPortFrom(s.up.local4, 0))
			if err != nil {
				return nil, err
			}
			s.v4 = c
			go s.read(c)
		}
		return s.v4, nil
	}
	if !s.up.local6.IsValid() {
		return nil, errors.New("в туннеле нет IPv6-адреса")
	}
	if s.v6 == nil {
		c, err := s.up.tnet.ListenUDPAddrPort(netip.AddrPortFrom(s.up.local6, 0))
		if err != nil {
			return nil, err
		}
		s.v6 = c
		go s.read(c)
	}
	return s.v6, nil
}

func (s *awgUDP) read(c net.PacketConn) {
	buf := make([]byte, 65535)
	for {
		n, addr, err := c.ReadFrom(buf)
		if err != nil {
			return
		}
		ua, ok := addr.(*net.UDPAddr)
		if !ok {
			continue
		}
		data := make([]byte, n)
		copy(data, buf[:n])
		select {
		case s.in <- awgPacket{data: data, from: ua.AddrPort()}:
		case <-s.done:
			return
		}
	}
}

func (s *awgUDP) WriteTo(p []byte, host string, port uint16) error {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	ip, err := s.up.resolve(ctx, host)
	if err != nil {
		return err
	}
	if _, err := netip.ParseAddr(host); err != nil {
		s.mu.Lock()
		s.names[ip] = host
		s.mu.Unlock()
	}
	c, err := s.socket(ip)
	if err != nil {
		return err
	}
	_, err = c.WriteTo(p, net.UDPAddrFromAddrPort(netip.AddrPortFrom(ip, port)))
	return err
}

func (s *awgUDP) ReadFrom(p []byte) (int, string, uint16, error) {
	select {
	case pkt := <-s.in:
		n := copy(p, pkt.data)
		host := pkt.from.Addr().Unmap().String()
		s.mu.Lock()
		if name, ok := s.names[pkt.from.Addr().Unmap()]; ok {
			host = name
		}
		s.mu.Unlock()
		return n, host, pkt.from.Port(), nil
	case <-s.done:
		return 0, "", 0, net.ErrClosed
	}
}

func (s *awgUDP) Close() error {
	s.once.Do(func() {
		close(s.done)
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.v4 != nil {
			s.v4.Close()
		}
		if s.v6 != nil {
			s.v6.Close()
		}
	})
	return nil
}
