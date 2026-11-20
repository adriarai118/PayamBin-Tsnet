// Package tsnetmobile is the gomobile-exported wrapper around
// tailscale.com/tsnet for PayamBin.
//
// Only gomobile-expressible types cross the boundary: strings, ints and
// bools. All status is returned as a single JSON string so the Java side
// can parse it without any generated struct bindings.
//
// Design (see TSNET.md): the tsnet node runs in userspace inside the app
// process — no Android VpnService, no system VPN slot. It listens on the
// tailnet on the same port as PayamBin's WebServer and proxies every
// accepted tailnet connection to 127.0.0.1:<port>, where WebServer already
// listens. WebServer.java is unchanged.
package tsnetmobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"regexp"
	"strings"
	"sync"
	"time"

	tailtsnet "tailscale.com/tsnet"
)

// status is the JSON shape returned by Status().
type status struct {
	State    string `json:"state"`    // stopped | starting | needs-login | running | error
	IP       string `json:"ip"`       // Tailscale IPv4, e.g. 100.x.y.z
	Hostname string `json:"hostname"` // MagicDNS name, e.g. payambin
	DNSName  string `json:"dnsName"`  // fully-qualified, e.g. payambin.tailxxxxx.ts.net
	LoginURL string `json:"loginUrl"` // non-empty while the node needs interactive login
	Error    string `json:"error"`    // non-empty when state == "error"
	Address  string `json:"address"`  // http://<hostname>:<port> once running
	Port     int    `json:"port"`
}

var (
	mu       sync.Mutex
	srv      *tailtsnet.Server
	listener net.Listener
	cancel   context.CancelFunc
	cur      status
	running  bool
)

var loginURLRe = regexp.MustCompile(`https://login\.tailscale\.com/[^\s"']+`)

// setLocked updates the shared status; caller must hold mu.
func setLocked(s status) { cur = s }

// captureLoginURL is wired to tsnet's UserLogf: while the node needs
// interactive login, tsnet prints the auth URL there every few seconds.
func captureLoginURL(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if u := loginURLRe.FindString(msg); u != "" {
		mu.Lock()
		if cur.LoginURL == "" {
			cur.LoginURL = u
		}
		if cur.State == "starting" {
			cur.State = "needs-login"
		}
		mu.Unlock()
	}
}

// Start brings the tsnet node up in the background and returns "" on
// success, or an error string if it cannot even be started. It returns
// immediately; use Status() to follow progress (starting -> needs-login
// -> running). Calling Start while already running is a no-op returning "".
//
// hostname: tailnet hostname, e.g. "payambin" (empty = default).
// authKey:  optional reusable/ephemeral auth key from the Tailscale admin
//
//	console; empty means interactive login via the login URL.
//
// stateDir: directory where tsnet persists node state (must be writable).
// localPort: PayamBin WebServer port; the tailnet listener uses the same
//
//	port and proxies to 127.0.0.1:localPort.
func Start(hostname, authKey, stateDir string, localPort int) string {
	mu.Lock()
	if running {
		mu.Unlock()
		return ""
	}
	if localPort <= 0 || localPort > 65535 {
		mu.Unlock()
		return "invalid port"
	}
	if strings.TrimSpace(stateDir) == "" {
		mu.Unlock()
		return "empty state dir"
	}
	running = true
	cur = status{State: "starting", Port: localPort}
	mu.Unlock()

	// Android 11+ blocks net.Interfaces() for app UIDs; register the
	// netlink-free interface getter before the tsnet server starts
	// (its netmon calls net.Interfaces() during Up()).
	registerAndroidInterfaceGetter()

	go runNode(hostname, authKey, stateDir, localPort)
	return ""
}

// runNode is the background goroutine that owns the tsnet server.
func runNode(hostname, authKey, stateDir string, localPort int) {
	ctx, cancelFn := context.WithCancel(context.Background())
	mu.Lock()
	cancel = cancelFn
	mu.Unlock()

	s := &tailtsnet.Server{
		Hostname: hostname,
		AuthKey:  authKey,
		Dir:      stateDir,
		UserLogf: captureLoginURL,
		// Logf left unset: backend logs are discarded (verbose).
	}
	mu.Lock()
	srv = s
	mu.Unlock()

	// Poll the local client for the login URL / IPs while Up() blocks.
	// Up() itself only returns once the node is Running (or on error), and
	// it deliberately does not return an error in the NeedsLogin state, so
	// the URL has to come from the status watcher / UserLogf.
	go pollStatus(s, ctx)

	st, err := s.Up(ctx)
	if err != nil {
		mu.Lock()
		if running { // not stopped meanwhile
			cur.State = "error"
			cur.Error = err.Error()
			cur.LoginURL = ""
			running = false // allow a later Start() to retry
		}
		mu.Unlock()
		return
	}

	ip := ""
	name := hostname
	dns := ""
	if st != nil {
		if len(st.TailscaleIPs) > 0 {
			ip = st.TailscaleIPs[0].String()
		}
		if st.Self != nil {
			if st.Self.DNSName != "" {
				dns = strings.TrimSuffix(st.Self.DNSName, ".")
			}
			if len(st.Self.TailscaleIPs) > 0 {
				ip = st.Self.TailscaleIPs[0].String()
			}
		}
	}
	if ip == "" {
		if ip4, _ := s.TailscaleIPs(); ip4.IsValid() {
			ip = ip4.String()
		}
	}
	if dns != "" {
		name = strings.SplitN(dns, ".", 2)[0]
	}

	ln, err := s.Listen("tcp", fmt.Sprintf(":%d", localPort))
	if err != nil {
		mu.Lock()
		if running {
			cur.State = "error"
			cur.Error = "listen: " + err.Error()
			running = false // allow a later Start() to retry
		}
		mu.Unlock()
		return
	}
	mu.Lock()
	listener = ln
	cur.State = "running"
	cur.IP = ip
	cur.Hostname = name
	cur.DNSName = dns
	cur.LoginURL = ""
	cur.Error = ""
	cur.Address = fmt.Sprintf("http://%s:%d", name, localPort)
	mu.Unlock()

	acceptLoop(ln, localPort)
}

// pollStatus refreshes the login URL / IP from the local client until the
// node is running or the context is cancelled.
func pollStatus(s *tailtsnet.Server, ctx context.Context) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			mu.Lock()
			if cur.State == "running" || cur.State == "error" || cur.State == "stopped" {
				mu.Unlock()
				return
			}
			mu.Unlock()
			lc, err := s.LocalClient()
			if err != nil {
				continue
			}
			st, err := lc.Status(ctx)
			if err != nil || st == nil {
				continue
			}
			mu.Lock()
			if st.AuthURL != "" {
				cur.LoginURL = st.AuthURL
				if cur.State == "starting" {
					cur.State = "needs-login"
				}
			}
			if st.BackendState == "Running" && len(st.TailscaleIPs) > 0 {
				cur.IP = st.TailscaleIPs[0].String()
			}
			mu.Unlock()
		}
	}
}

// acceptLoop proxies tailnet connections to the local WebServer.
func acceptLoop(ln net.Listener, localPort int) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return // listener closed by Stop()
		}
		go proxyConn(c, localPort)
	}
}

func proxyConn(remote net.Conn, localPort int) {
	local, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 5*time.Second)
	if err != nil {
		remote.Close()
		return
	}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(local, remote); done <- struct{}{} }()
	go func() { _, _ = io.Copy(remote, local); done <- struct{}{} }()
	<-done
	remote.Close()
	local.Close()
}

// Status returns the current node status as a JSON string. It never fails:
// on encoding error it returns a minimal error JSON.
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	b, err := json.Marshal(cur)
	if err != nil {
		return `{"state":"error","error":"status encode failed"}`
	}
	return string(b)
}

// IsRunning reports whether the node is up and reachable on the tailnet.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running && cur.State == "running"
}

// TailscaleIP returns the node's Tailscale IPv4 address, or "".
func TailscaleIP() string {
	mu.Lock()
	defer mu.Unlock()
	return cur.IP
}

// Stop shuts the node down. Safe to call when not running.
func Stop() string {
	mu.Lock()
	if !running {
		cur = status{State: "stopped"}
		mu.Unlock()
		return ""
	}
	running = false
	ln := listener
	s := srv
	c := cancel
	mu.Unlock()

	if ln != nil {
		ln.Close()
	}
	if c != nil {
		c()
	}
	if s != nil {
		_ = s.Close()
	}
	mu.Lock()
	listener = nil
	srv = nil
	cancel = nil
	cur = status{State: "stopped"}
	mu.Unlock()
	return ""
}
