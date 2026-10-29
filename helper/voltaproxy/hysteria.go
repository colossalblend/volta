package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-quic/hysteria"
	"github.com/sagernet/sing-quic/hysteria2"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	aTLS "github.com/sagernet/sing/common/tls"
)

// hysteriaLink is what both share-link versions carry.
type hysteriaLink struct {
	host      string
	port      uint16
	ports     []string // port hopping: "20000-30000", "443"
	auth      string
	sni       string
	insecure  bool
	pinSHA256 string
	alpn      []string
	obfs      string // v1: xplus password; v2: salamander password
	upMbps    uint64
	downMbps  uint64
}

func parseHysteriaLink(link string, v2 bool) (*hysteriaLink, error) {
	link, portSpec := cutPortRange(strings.TrimSpace(link))
	u, err := url.Parse(link)
	if err != nil {
		return nil, fmt.Errorf("ссылка не читается: %w", err)
	}
	q := u.Query()
	get := func(keys ...string) string {
		for _, k := range keys {
			if v := q.Get(k); v != "" {
				return v
			}
		}
		return ""
	}
	h := &hysteriaLink{host: u.Hostname()}
	if h.host == "" {
		return nil, errors.New("в ссылке нет адреса")
	}
	portPart := u.Port()
	if portSpec != "" {
		portPart = portSpec
	}
	switch {
	case portPart == "":
		h.port = 443
	case strings.ContainsAny(portPart, "-,"):
		h.ports = splitList(portPart)
		first := strings.SplitN(h.ports[0], "-", 2)[0]
		h.port = mustPort(first)
	default:
		h.port = mustPort(portPart)
	}
	if mport := get("mport", "ports"); mport != "" {
		h.ports = splitList(mport)
	}
	if h.port == 0 {
		return nil, errors.New("в ссылке нет порта")
	}

	if v2 {
		if u.User != nil {
			h.auth = u.User.Username()
			if pass, ok := u.User.Password(); ok {
				h.auth += ":" + pass
			}
		}
		if h.auth == "" {
			h.auth = get("auth", "password")
		}
		if strings.EqualFold(get("obfs"), "salamander") {
			h.obfs = get("obfs-password", "obfsParam", "obfs_password")
		}
	} else {
		h.auth = get("auth", "auth_str", "auth-str")
		if strings.EqualFold(get("obfs"), "xplus") || get("obfsParam") != "" {
			h.obfs = get("obfsParam", "obfs-password", "obfs_param")
		}
		if p := strings.ToLower(get("protocol")); p != "" && p != "udp" {
			return nil, fmt.Errorf("Hysteria в режиме %s не поддерживается, только udp", p)
		}
	}
	h.sni = get("sni", "peer", "server_name")
	h.insecure = isTrue(get("insecure", "allowInsecure", "skip-cert-verify"))
	h.pinSHA256 = strings.ToLower(strings.ReplaceAll(get("pinSHA256", "pinsha256", "pcs"), ":", ""))
	if a := get("alpn"); a != "" {
		h.alpn = splitList(a)
	}
	h.upMbps = mbps(get("upmbps", "up", "up_mbps"))
	h.downMbps = mbps(get("downmbps", "down", "down_mbps"))
	return h, nil
}

// cutPortRange takes a multi-port spec ("host:20000-30000", "host:443,8443") out of
// the link, which url.Parse would refuse, and leaves the first port in its place.
func cutPortRange(link string) (string, string) {
	scheme, rest, ok := strings.Cut(link, "://")
	if !ok {
		return link, ""
	}
	end := strings.IndexAny(rest, "/?#")
	if end < 0 {
		end = len(rest)
	}
	authority := rest[:end]
	at := strings.LastIndex(authority, "@")
	hostPortPart := authority[at+1:]
	i := strings.LastIndex(hostPortPart, ":")
	if i < 0 || strings.HasSuffix(hostPortPart, "]") {
		return link, ""
	}
	spec := hostPortPart[i+1:]
	if !strings.ContainsAny(spec, "-,") {
		return link, ""
	}
	first := strings.SplitN(strings.SplitN(spec, ",", 2)[0], "-", 2)[0]
	authority = authority[:at+1] + hostPortPart[:i+1] + first
	return scheme + "://" + authority + rest[end:], spec
}

func isTrue(v string) bool {
	switch strings.ToLower(v) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

// mbps reads "100", "100 mbps", "100Mbps".
func mbps(v string) uint64 {
	digits := strings.TrimFunc(strings.ToLower(v), func(r rune) bool { return r < '0' || r > '9' })
	if i := strings.IndexFunc(digits, func(r rune) bool { return r < '0' || r > '9' }); i >= 0 {
		digits = digits[:i]
	}
	n, _ := strconv.ParseUint(digits, 10, 64)
	return n
}

func (h *hysteriaLink) tls(defaultALPN []string) aTLS.Config {
	cfg := &tls.Config{
		ServerName: h.sni,
		NextProtos: h.alpn,
		MinVersion: tls.VersionTLS13,
	}
	if cfg.ServerName == "" {
		cfg.ServerName = h.host
	}
	if len(cfg.NextProtos) == 0 {
		cfg.NextProtos = defaultALPN
	}
	if h.pinSHA256 != "" {
		pin := h.pinSHA256
		cfg.InsecureSkipVerify = true
		cfg.VerifyPeerCertificate = func(raw [][]byte, _ [][]*x509.Certificate) error {
			for _, c := range raw {
				sum := sha256.Sum256(c)
				if hex.EncodeToString(sum[:]) == pin {
					return nil
				}
			}
			return errors.New("сертификат сервера не совпал с закреплённым")
		}
	} else if h.insecure {
		cfg.InsecureSkipVerify = true
	}
	return &stdTLS{cfg: cfg, timeout: 10 * time.Second}
}

func (h *hysteriaLink) server() M.Socksaddr {
	return M.ParseSocksaddrHostPort(h.host, h.port)
}

// stdTLS hands sing-quic a plain crypto/tls configuration.
type stdTLS struct {
	cfg     *tls.Config
	timeout time.Duration
}

func (s *stdTLS) ServerName() string                   { return s.cfg.ServerName }
func (s *stdTLS) SetServerName(name string)            { s.cfg.ServerName = name }
func (s *stdTLS) NextProtos() []string                 { return s.cfg.NextProtos }
func (s *stdTLS) SetNextProtos(p []string)             { s.cfg.NextProtos = p }
func (s *stdTLS) HandshakeTimeout() time.Duration      { return s.timeout }
func (s *stdTLS) SetHandshakeTimeout(t time.Duration)  { s.timeout = t }
func (s *stdTLS) STDConfig() (*aTLS.STDConfig, error)  { return s.cfg, nil }
func (s *stdTLS) Client(c net.Conn) (aTLS.Conn, error) { return tls.Client(c, s.cfg), nil }
func (s *stdTLS) Clone() aTLS.Config                   { return &stdTLS{cfg: s.cfg.Clone(), timeout: s.timeout} }

// directDialer reaches the server over the phone's own network: this process is
// outside the VPN.
type directDialer struct{}

func (directDialer) DialContext(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, dest.String())
}

func (directDialer) ListenPacket(ctx context.Context, dest M.Socksaddr) (net.PacketConn, error) {
	var lc net.ListenConfig
	return lc.ListenPacket(ctx, "udp", "")
}

// quicClient is what the v1 and v2 clients have in common.
type quicClient interface {
	DialConn(ctx context.Context, destination M.Socksaddr) (net.Conn, error)
	CloseWithError(err error) error
}

type hysteriaUpstream struct {
	client quicClient
	listen func(ctx context.Context, first M.Socksaddr) (net.PacketConn, error)
}

func newHysteria(link string) (upstream, error) {
	h, err := parseHysteriaLink(link, false)
	if err != nil {
		return nil, err
	}
	// Hysteria v1 needs both speeds; providers often leave them out.
	up, down := h.upMbps, h.downMbps
	if up == 0 {
		up = 20
	}
	if down == 0 {
		down = 100
	}
	client, err := hysteria.NewClient(hysteria.ClientOptions{
		Context:       context.Background(),
		Dialer:        directDialer{},
		Logger:        logger.NOP(),
		ServerAddress: h.server(),
		ServerPorts:   h.ports,
		HopInterval:   30 * time.Second,
		SendBPS:       up * 125_000,
		ReceiveBPS:    down * 125_000,
		XPlusPassword: h.obfs,
		Password:      h.auth,
		TLSConfig:     h.tls([]string{hysteria.DefaultALPN}),
	})
	if err != nil {
		return nil, fmt.Errorf("Hysteria: %w", err)
	}
	return &hysteriaUpstream{
		client: client,
		listen: func(ctx context.Context, first M.Socksaddr) (net.PacketConn, error) {
			return client.ListenPacket(ctx, first)
		},
	}, nil
}

func newHysteria2(link string) (upstream, error) {
	h, err := parseHysteriaLink(link, true)
	if err != nil {
		return nil, err
	}
	client, err := hysteria2.NewClient(hysteria2.ClientOptions{
		Context:            context.Background(),
		Dialer:             directDialer{},
		Logger:             logger.NOP(),
		ServerAddress:      h.server(),
		ServerPorts:        h.ports,
		HopInterval:        30 * time.Second,
		SendBPS:            h.upMbps * 125_000,
		ReceiveBPS:         h.downMbps * 125_000,
		SalamanderPassword: h.obfs,
		Password:           h.auth,
		TLSConfig:          h.tls([]string{"h3"}),
	})
	if err != nil {
		return nil, fmt.Errorf("Hysteria2: %w", err)
	}
	return &hysteriaUpstream{
		client: client,
		listen: func(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
			return client.ListenPacket(ctx)
		},
	}, nil
}

func (u *hysteriaUpstream) DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error) {
	return u.client.DialConn(ctx, M.ParseSocksaddrHostPort(host, port))
}

func (u *hysteriaUpstream) ListenUDP(ctx context.Context) (udpSession, error) {
	return &hysteriaUDP{up: u, ctx: ctx, ready: make(chan struct{})}, nil
}

func (u *hysteriaUpstream) Close() error {
	return u.client.CloseWithError(net.ErrClosed)
}

// hysteriaUDP opens its server session on the first datagram: Hysteria v1 names a
// destination when it does.
type hysteriaUDP struct {
	up    *hysteriaUpstream
	ctx   context.Context
	once  sync.Once
	ready chan struct{}
	conn  net.PacketConn
	err   error
}

func (s *hysteriaUDP) WriteTo(p []byte, host string, port uint16) error {
	dest := M.ParseSocksaddrHostPort(host, port)
	s.once.Do(func() {
		s.conn, s.err = s.up.listen(s.ctx, dest)
		close(s.ready)
	})
	if s.err != nil {
		return s.err
	}
	_, err := s.conn.WriteTo(p, dest)
	return err
}

func (s *hysteriaUDP) ReadFrom(p []byte) (int, string, uint16, error) {
	select {
	case <-s.ready:
	case <-s.ctx.Done():
		return 0, "", 0, s.ctx.Err()
	}
	if s.err != nil {
		return 0, "", 0, s.err
	}
	n, addr, err := s.conn.ReadFrom(p)
	if err != nil {
		return 0, "", 0, err
	}
	from := M.SocksaddrFromNet(addr)
	return n, from.AddrString(), from.Port, nil
}

func (s *hysteriaUDP) Close() error {
	s.once.Do(func() {
		s.err = net.ErrClosed
		close(s.ready)
	})
	if s.conn != nil {
		return s.conn.Close()
	}
	return nil
}
