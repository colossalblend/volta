// Command voltaproxy runs one client for a protocol Volta's Xray core cannot speak
// itself: AmneziaWG (1.x, 2.x and 3.x configs, on amneziawg-go) or Hysteria v1, and
// Hysteria2 with obfuscation (both on sing-quic). It serves a SOCKS5 proxy (TCP and
// UDP) on a local port; Volta's core sends the tunnel's traffic there, so routing,
// DNS and split tunnelling work exactly as for any other server.
//
// Volta starts it as a child process. The first line on stdin is the request,
// {"kind","config","listen"} — the config carries keys, so it never shows up on a
// command line. When stdin closes (Volta stopped it, or died) the proxy stops and
// the process exits, so no tunnel outlives the app.
//
// With "probe" set it serves nothing: it makes one request to that URL through the
// server, reports {"event":"delay","ms":N} and exits. That is how Volta pings these
// servers.
//
// Events go to stdout, one JSON object per line: ready, delay, error.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

type request struct {
	// "awg" (an AmneziaWG / WireGuard .conf), "hysteria" (hysteria:// link) or
	// "hysteria2" (hysteria2:// link).
	Kind   string `json:"kind"`
	Config string `json:"config"`
	Listen string `json:"listen"`
	// Measure one request to this URL through the server, then exit.
	Probe     string `json:"probe,omitempty"`
	TimeoutMs int    `json:"timeoutMs,omitempty"`
}

type event struct {
	Event   string `json:"event"`
	Message string `json:"message,omitempty"`
	Ms      int64  `json:"ms,omitempty"`
}

var out = struct {
	sync.Mutex
	w *bufio.Writer
}{w: bufio.NewWriter(os.Stdout)}

func emit(e event) {
	b, _ := json.Marshal(e)
	out.Lock()
	defer out.Unlock()
	out.w.Write(b)
	out.w.WriteByte('\n')
	out.w.Flush()
}

func fail(msg string) {
	emit(event{Event: "error", Message: msg})
	os.Exit(1)
}

func main() {
	in := bufio.NewReaderSize(os.Stdin, 64*1024)
	line, err := in.ReadString('\n')
	if err != nil && line == "" {
		fail("no request on stdin")
	}
	var req request
	if err := json.Unmarshal([]byte(line), &req); err != nil {
		fail("bad request: " + err.Error())
	}

	up, err := newUpstream(req.Kind, req.Config)
	if err != nil {
		fail(err.Error())
	}

	if req.Probe != "" {
		timeout := time.Duration(req.TimeoutMs) * time.Millisecond
		if timeout <= 0 {
			timeout = 8 * time.Second
		}
		ms, err := probe(up, req.Probe, timeout)
		up.Close()
		if err != nil {
			fail(err.Error())
		}
		emit(event{Event: "delay", Ms: ms})
		return
	}

	if req.Listen == "" {
		req.Listen = "127.0.0.1:10860"
	}
	ln, err := net.Listen("tcp", req.Listen)
	if err != nil {
		up.Close()
		fail(fmt.Sprintf("cannot listen on %s: %v", req.Listen, err))
	}
	go serveSocks(ln, up)
	emit(event{Event: "ready"})

	// Runs until Volta closes stdin.
	io.Copy(io.Discard, in)
	ln.Close()
	up.Close()
}

func newUpstream(kind, config string) (upstream, error) {
	switch strings.ToLower(kind) {
	case "awg", "amneziawg", "wireguard":
		return newAWG(config)
	case "hysteria", "hysteria1":
		return newHysteria(config)
	case "hysteria2", "hy2":
		return newHysteria2(config)
	}
	return nil, fmt.Errorf("unknown kind %q", kind)
}
