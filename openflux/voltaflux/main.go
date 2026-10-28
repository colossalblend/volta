// Command voltaflux runs one OpenFlux client for Volta: a SOCKS5 proxy on a local
// port whose connections leave through the transports of an openflux:// link.
//
// Volta starts it as a child process. The first line on stdin is the request,
// {"link","listen","dataDir","debug"}; the link carries the encryption key, so it
// never shows up on a command line. After that stdin carries commands, one JSON
// object per line, and when it closes (Volta stopped it, or died) the proxy stops
// and the process exits, so no tunnel outlives the app.
//
// Events go to stdout, one JSON object per line: started, connected,
// disconnected, captcha, error. The core's own log goes to stderr.
//
// It is built inside the OpenFlux tree (mobile/cmd/voltaflux), against the same
// package mobile the OpenFlux apps use, so links are read and sessions built
// exactly as in those apps. OpenFlux is GPL-3.0-or-later and so is this file.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	mobile "openflux-mobile"

	"github.com/p1neappleXpress/OpenFlux/share"
)

type request struct {
	Link    string `json:"link"`
	Listen  string `json:"listen"`
	DataDir string `json:"dataDir"`
	Debug   int    `json:"debug"`
}

type command struct {
	// "cookies" (Cookies: a Cookie header from the captcha page),
	// "captchaData" (Data: what a script transport's own page submitted),
	// "cancelCaptcha".
	Cmd     string `json:"cmd"`
	Cookies string `json:"cookies,omitempty"`
	Data    string `json:"data,omitempty"`
}

type event struct {
	Event     string `json:"event"`
	Message   string `json:"message,omitempty"`
	URL       string `json:"url,omitempty"`
	Proxy     string `json:"proxy,omitempty"`
	Reason    string `json:"reason,omitempty"`
	Transport string `json:"transport,omitempty"`
	Code      string `json:"code,omitempty"`
}

// recheck makes watch report a captcha that is still pending after an answer, so
// Volta can show it again instead of waiting on a page that was refused.
var recheck atomic.Bool

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

func main() {
	in := bufio.NewReaderSize(os.Stdin, 64*1024)
	line, err := in.ReadString('\n')
	if err != nil && line == "" {
		emit(event{Event: "error", Message: "no request on stdin"})
		os.Exit(2)
	}
	var req request
	if err := json.Unmarshal([]byte(line), &req); err != nil {
		emit(event{Event: "error", Message: "bad request: " + err.Error()})
		os.Exit(2)
	}
	if req.Listen == "" {
		req.Listen = "127.0.0.1:10850"
	}
	mobile.SetDebugLevel(req.Debug)
	if req.DataDir != "" {
		_ = os.MkdirAll(req.DataDir, 0o700)
		if msg := mobile.SetCookieStorePath(filepath.Join(req.DataDir, "cookies.json")); msg != "" {
			fmt.Fprintln(os.Stderr, "cookie store:", msg)
		}
	}

	if code, msg := start(req); msg != "" {
		flushLogs()
		emit(event{Event: "error", Message: msg, Code: code})
		os.Exit(1)
	}
	emit(event{Event: "started"})

	done := make(chan struct{})
	go watch(done)

	// Commands until Volta closes stdin.
	for {
		line, err := in.ReadString('\n')
		if strings.TrimSpace(line) != "" {
			handle(line)
		}
		if err != nil {
			break
		}
	}
	close(done)
	mobile.StopProxy()
	flushLogs()
}

// start brings the proxy up the way the OpenFlux apps do for this kind of link.
// Returns the link's problem code and a message on failure.
func start(req request) (string, string) {
	res := share.Read(req.Link)
	if res.Config == nil {
		return res.Code, "ссылка OpenFlux не читается: " + res.Error
	}
	c := *res.Config
	if len(c.Transports) == 0 {
		return share.CodeNoTransports, "в ссылке нет транспортов"
	}
	first := c.Transports[0]
	switch {
	case c.Mode == share.ModeStream:
		return "", mobile.StartStreamProxy(first.Type, first.URL, req.Listen, "", "", "")
	case len(c.Transports) == 1 && !c.Negotiate:
		return "", mobile.StartProxy(first.Type, first.URL, c.Secret, c.Codec, "", "", req.Listen, "", "", "")
	default:
		specs, err := mobile.ShareSessionSpecs(req.Link)
		if err != nil {
			return "", err.Error()
		}
		return "", mobile.StartSessionProxy(specs, c.Secret, req.Listen, "", "", "")
	}
}

// watch reports the tunnel coming up or dropping, captcha requests, and the log.
func watch(done chan struct{}) {
	connected := false
	captcha := ""
	ticker := time.NewTicker(300 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
		}
		flushLogs()
		if recheck.Swap(false) {
			captcha = ""
		}
		if now := mobile.ProxyIsConnected(); now != connected {
			connected = now
			if now {
				emit(event{Event: "connected", Transport: mobile.CurrentTransport()})
			} else {
				emit(event{Event: "disconnected"})
			}
		}
		url := mobile.PendingCaptchaURL()
		if url == "" && mobile.PendingCaptchaHTML() != "" {
			url = "about:openflux-setup"
		}
		if url != captcha {
			captcha = url
			if url != "" {
				emit(event{
					Event:     "captcha",
					URL:       url,
					Proxy:     mobile.PendingCaptchaProxy(),
					Reason:    mobile.PendingCaptchaReason(),
					Transport: mobile.PendingCaptchaTransport(),
				})
			}
		}
	}
}

func handle(line string) {
	var cmd command
	if err := json.Unmarshal([]byte(line), &cmd); err != nil {
		return
	}
	defer recheck.Store(true)
	switch cmd.Cmd {
	case "cookies":
		if msg := mobile.SubmitCaptchaCookies(cmd.Cookies); msg != "" {
			emit(event{Event: "captchaFailed", Message: msg})
		}
	case "captchaData":
		if msg := mobile.SubmitCaptchaData(cmd.Data); msg != "" {
			emit(event{Event: "captchaFailed", Message: msg})
		}
	case "cancelCaptcha":
		mobile.CancelCaptcha()
	}
}

func flushLogs() {
	if logs := mobile.ReadLogs(); logs != "" {
		fmt.Fprintln(os.Stderr, logs)
	}
}
