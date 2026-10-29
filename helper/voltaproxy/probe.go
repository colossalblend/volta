package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"time"
)

// probe times one HTTP request through the server, connection setup included, the
// way the core's own real-delay test does for the protocols it speaks itself.
func probe(up upstream, target string, timeout time.Duration) (int64, error) {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			p, _ := strconv.Atoi(port)
			return up.DialTCP(ctx, host, uint16(p))
		},
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: timeout,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	start := time.Now()
	resp, err := client.Get(target)
	if err != nil {
		return 0, fmt.Errorf("сервер не ответил: %w", err)
	}
	resp.Body.Close()
	ms := time.Since(start).Milliseconds()
	if ms < 1 {
		ms = 1
	}
	return ms, nil
}
