package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

func keyPair(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	rand.Read(priv)
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return priv, pub
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// An AmneziaWG 3 server inside this test: header protection, ranged headers,
// paddings and signature packets, all of which the client must match.
func startAWGServer(t *testing.T, serverPriv, clientPub, hpk []byte, port int) *netstack.Net {
	tunDev, tnet, err := netstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "server: "))
	uapi := fmt.Sprintf(`private_key=%s
listen_port=%d
s1=15
s2=18
s3=20
s4=23
h1=1000-2000
h2=3000-4000
h3=5000-6000
h4=7000-8000
header_protection_key=%s
random_trailers=true
public_key=%s
allowed_ip=10.9.0.2/32
`, hex.EncodeToString(serverPriv), port, hex.EncodeToString(hpk), hex.EncodeToString(clientPub))
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(dev.Close)
	return tnet
}

func TestAWGThroughSocks(t *testing.T) {
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	hpk := make([]byte, 32)
	rand.Read(hpk)
	port := freeUDPPort(t)
	server := startAWGServer(t, serverPriv, clientPub, hpk, port)

	// A web page and a UDP echo inside the server's network.
	ln, err := server.ListenTCP(&net.TCPAddr{Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, "hello through awg")
	}))
	echo, err := server.ListenUDPAddrPort(netip.MustParseAddrPort("10.9.0.1:7"))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := echo.ReadFrom(buf)
			if err != nil {
				return
			}
			echo.WriteTo(buf[:n], from)
		}
	}()

	b64 := base64.StdEncoding.EncodeToString
	conf := fmt.Sprintf(`# exported by Amnezia
[Interface]
PrivateKey = %s
Address = 10.9.0.2/32
DNS = 10.9.0.1
MTU = 1280
Jc = 4
Jmin = 40
Jmax = 70
S1 = 15
S2 = 18
S3 = 20
S4 = 23
H1 = 1000-2000
H2 = 3000-4000
H3 = 5000-6000
H4 = 7000-8000
I1 = <r 32><b 0x0102><t>
HeaderProtectionKey = %s
RandomTrailers = on
PostUp = something wg-quick only

[Peer]
PublicKey = %s
Endpoint = 127.0.0.1:%d
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`, b64(clientPriv), b64(hpk), b64(serverPub), port)

	up, err := newUpstream("awg", conf)
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	proxy, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	go serveSocks(proxy, up)

	// TCP: a page from inside the tunnel.
	client := &http.Client{
		Timeout:   15 * time.Second,
		Transport: &http.Transport{Proxy: http.ProxyURL(&url.URL{Scheme: "socks5", Host: proxy.Addr().String()})},
	}
	resp, err := client.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "hello through awg" {
		t.Fatalf("body %q", body)
	}

	sess, err := up.ListenUDP(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if err := sess.WriteTo([]byte("direct"), "10.9.0.1", 7); err != nil {
		t.Fatalf("direct write: %v", err)
	}
	got := make([]byte, 100)
	gotN, gotHost, gotPort, err := sess.ReadFrom(got)
	t.Logf("direct read: %q %s %d %v", got[:gotN], gotHost, gotPort, err)
	sess.Close()

	// UDP: an echo through a SOCKS5 UDP association.
	reply := socksUDPRoundTrip(t, proxy.Addr().String(), "10.9.0.1", 7, []byte("ping over udp"))
	if string(reply) != "ping over udp" {
		t.Fatalf("udp reply %q", reply)
	}

	// The probe Volta uses to ping such a server.
	ms, err := probe(up, "http://10.9.0.1/", 10*time.Second)
	if err != nil || ms <= 0 {
		t.Fatalf("probe %d %v", ms, err)
	}
}

func socksUDPRoundTrip(t *testing.T, proxy, host string, port uint16, payload []byte) []byte {
	t.Helper()
	ctrl, err := net.Dial("tcp", proxy)
	if err != nil {
		t.Fatal(err)
	}
	defer ctrl.Close()
	ctrl.SetDeadline(time.Now().Add(15 * time.Second))
	ctrl.Write([]byte{5, 1, 0})
	br := bufio.NewReader(ctrl)
	if _, err := io.ReadFull(br, make([]byte, 2)); err != nil {
		t.Fatal(err)
	}
	ctrl.Write(appendAddr([]byte{5, cmdAssociate, 0}, "0.0.0.0", 0))
	head := make([]byte, 3)
	if _, err := io.ReadFull(br, head); err != nil || head[1] != 0 {
		t.Fatalf("associate refused: %v %v", head, err)
	}
	relayHost, relayPort, err := readAddr(br)
	if err != nil {
		t.Fatal(err)
	}
	u, err := net.Dial("udp", hostPort(relayHost, relayPort))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	packet := append(appendAddr([]byte{0, 0, 0}, host, port), payload...)
	buf := make([]byte, 2048)
	// The first datagram may race the handshake; retry a few times.
	for i := 0; i < 10; i++ {
		u.Write(packet)
		u.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
		n, err := u.Read(buf)
		if err != nil {
			continue
		}
		r := bufio.NewReader(strings.NewReader(string(buf[3:n])))
		fromHost, fromPort, err := readAddr(r)
		if err != nil {
			t.Fatal(err)
		}
		if fromHost != host || fromPort != port {
			t.Fatalf("reply from %s:%d", fromHost, fromPort)
		}
		rest, _ := io.ReadAll(r)
		return rest
	}
	t.Fatal("no UDP reply")
	return nil
}

func TestAWGConfigKeys(t *testing.T) {
	_, pub := keyPair(t)
	priv, _ := keyPair(t)
	b64 := base64.StdEncoding.EncodeToString
	cfg, err := parseAWG(fmt.Sprintf(`[Interface]
Address = 10.8.1.3/32, fd00::3/128
DNS = 1.1.1.1, example.internal
PrivateKey = %s
Jc = 5
Jmin = 10
Jmax = 50
S1 = 0
S2 = 0
H1 = 1
H2 = 2
H3 = 3
H4 = 4
J1 = <b 0xaa>
Itime = 60
[Peer]
AllowedIPs = 0.0.0.0/0
PresharedKey = %s
PublicKey = %s
Endpoint = 192.0.2.10:51820
`, b64(priv), b64(pub), b64(pub)))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"jc=5\n", "h4=4\n", "preshared_key=", "endpoint=192.0.2.10:51820\n", "allowed_ip=0.0.0.0/0\n"} {
		if !strings.Contains(cfg.uapi, want) {
			t.Errorf("uapi lacks %q:\n%s", want, cfg.uapi)
		}
	}
	// The peer section opens with its public key even when the file lists it last.
	peerStart := strings.Index(cfg.uapi, "public_key=")
	if peerStart < 0 || strings.Index(cfg.uapi, "preshared_key=") < peerStart {
		t.Errorf("peer section out of order:\n%s", cfg.uapi)
	}
	// AWG 1.5 keys the engine no longer has are dropped, not fatal.
	if strings.Contains(cfg.uapi, "j1=") || strings.Contains(cfg.uapi, "itime=") {
		t.Errorf("unsupported keys passed through:\n%s", cfg.uapi)
	}
	if len(cfg.addresses) != 2 || len(cfg.dns) != 1 {
		t.Errorf("addresses %v dns %v", cfg.addresses, cfg.dns)
	}
}

func TestHysteriaLinks(t *testing.T) {
	h, err := parseHysteriaLink("hysteria://example.com:443?protocol=udp&auth=secret&peer=sni.example&insecure=1&upmbps=30&downmbps=200&alpn=hysteria&obfs=xplus&obfsParam=xyz#Name", false)
	if err != nil {
		t.Fatal(err)
	}
	if h.auth != "secret" || h.sni != "sni.example" || !h.insecure || h.upMbps != 30 || h.downMbps != 200 || h.obfs != "xyz" || h.port != 443 {
		t.Errorf("v1 parsed as %+v", h)
	}
	if _, err := parseHysteriaLink("hysteria://example.com:443?protocol=faketcp&auth=x", false); err == nil {
		t.Error("faketcp should be refused")
	}

	h, err = parseHysteriaLink("hysteria2://user:pa%24s@example.com:20000-30000/?sni=a.example&obfs=salamander&obfs-password=cry&pinSHA256=AB:CD&up=50%20mbps#x", true)
	if err != nil {
		t.Fatal(err)
	}
	if h.auth != "user:pa$s" || h.obfs != "cry" || h.port != 20000 || len(h.ports) != 1 || h.ports[0] != "20000-30000" || h.pinSHA256 != "abcd" || h.upMbps != 50 {
		t.Errorf("v2 parsed as %+v", h)
	}
	if _, err := newHysteria2("hysteria2://pass@example.com:443?insecure=1"); err != nil {
		t.Errorf("hysteria2 client: %v", err)
	}
	if _, err := newHysteria("hysteria://example.com:443?auth=x"); err != nil {
		t.Errorf("hysteria client: %v", err)
	}
}

func TestSocksAddrRoundTrip(t *testing.T) {
	for _, host := range []string{"1.2.3.4", "2001:db8::1", "example.com"} {
		b := appendAddr(nil, host, 8443)
		h, p, err := readAddr(bufio.NewReader(strings.NewReader(string(b))))
		if err != nil || h != host || p != 8443 {
			t.Errorf("%s -> %s:%d %v", host, h, p, err)
		}
	}
	_ = binary.BigEndian
}
