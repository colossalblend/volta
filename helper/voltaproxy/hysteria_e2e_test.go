package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/sagernet/sing-quic/hysteria"
	"github.com/sagernet/sing-quic/hysteria2"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	aTLS "github.com/sagernet/sing/common/tls"
)

type serverTLS struct{ stdTLS }

func (s *serverTLS) Start() error                         { return nil }
func (s *serverTLS) Close() error                         { return nil }
func (s *serverTLS) Server(c net.Conn) (aTLS.Conn, error) { return tls.Server(c, s.cfg), nil }
func (s *serverTLS) Clone() aTLS.Config {
	return &serverTLS{stdTLS{cfg: s.cfg.Clone(), timeout: s.timeout}}
}

// directHandler connects whatever the client asks for, like a real exit.
type directHandler struct{}

func (directHandler) NewConnectionEx(ctx context.Context, conn net.Conn, _ M.Socksaddr, dest M.Socksaddr, onClose N.CloseHandlerFunc) {
	target, err := net.Dial("tcp", dest.String())
	if err != nil {
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
		return
	}
	go func() { io.Copy(target, conn); target.Close() }()
	io.Copy(conn, target)
	conn.Close()
	if onClose != nil {
		onClose(nil)
	}
}

func (directHandler) NewPacketConnectionEx(context.Context, N.PacketConn, M.Socksaddr, M.Socksaddr, N.CloseHandlerFunc) {
}

func selfSigned(t *testing.T) tls.Certificate {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "hy.test"},
		DNSNames:     []string{"hy.test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func TestHysteria2ThroughSocks(t *testing.T) {
	web, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(web, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, "hello through hysteria2")
	}))

	service, err := hysteria2.NewService[int](hysteria2.ServiceOptions{
		Context:            context.Background(),
		Logger:             logger.NOP(),
		SalamanderPassword: "obfs-secret",
		TLSConfig: &serverTLS{stdTLS{cfg: &tls.Config{
			Certificates: []tls.Certificate{selfSigned(t)},
			NextProtos:   []string{"h3"},
		}, timeout: 10 * time.Second}},
		Handler: directHandler{},
	})
	if err != nil {
		t.Fatal(err)
	}
	service.UpdateUsers([]int{1}, []string{"user:pass"})
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if err := service.Start(udp); err != nil {
		t.Fatal(err)
	}
	defer service.Close()

	link := fmt.Sprintf("hysteria2://user:pass@127.0.0.1:%d/?sni=hy.test&insecure=1&obfs=salamander&obfs-password=obfs-secret#t",
		udp.LocalAddr().(*net.UDPAddr).Port)
	up, err := newUpstream("hysteria2", link)
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	proxy, _ := net.Listen("tcp", "127.0.0.1:0")
	defer proxy.Close()
	go serveSocks(proxy, up)

	client := &http.Client{
		Timeout:   15 * time.Second,
		Transport: &http.Transport{Proxy: http.ProxyURL(&url.URL{Scheme: "socks5", Host: proxy.Addr().String()})},
	}
	resp, err := client.Get("http://" + web.Addr().String() + "/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "hello through hysteria2" {
		t.Fatalf("body %q", body)
	}
	if ms, err := probe(up, "http://"+web.Addr().String()+"/", 10*time.Second); err != nil || ms <= 0 {
		t.Fatalf("probe %d %v", ms, err)
	}
}

func TestHysteria1ThroughSocks(t *testing.T) {
	web, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(web, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, "hello through hysteria1")
	}))

	service, err := hysteria.NewService[int](hysteria.ServiceOptions{
		Context:       context.Background(),
		Logger:        logger.NOP(),
		SendBPS:       100 * 125_000,
		ReceiveBPS:    100 * 125_000,
		XPlusPassword: "xplus-secret",
		TLSConfig: &serverTLS{stdTLS{cfg: &tls.Config{
			Certificates: []tls.Certificate{selfSigned(t)},
			NextProtos:   []string{hysteria.DefaultALPN},
		}, timeout: 10 * time.Second}},
		Handler: directHandler{},
	})
	if err != nil {
		t.Fatal(err)
	}
	service.UpdateUsers([]int{1}, []string{"v1-auth"})
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if err := service.Start(udp); err != nil {
		t.Fatal(err)
	}
	defer service.Close()

	// No speeds in the link, as providers often do: the client fills them in.
	link := fmt.Sprintf("hysteria://127.0.0.1:%d?protocol=udp&auth=v1-auth&peer=hy.test&insecure=1&obfs=xplus&obfsParam=xplus-secret#v1",
		udp.LocalAddr().(*net.UDPAddr).Port)
	up, err := newUpstream("hysteria", link)
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	ms, err := probe(up, "http://"+web.Addr().String()+"/", 10*time.Second)
	if err != nil || ms <= 0 {
		t.Fatalf("probe %d %v", ms, err)
	}
}
