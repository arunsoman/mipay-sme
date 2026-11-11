package gateway

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"trustplane/internal/breaker"
	"trustplane/internal/ctxauth"
	"trustplane/internal/mtlsx"
)

type upstream struct {
	cfg    Upstream
	client *http.Client
	sem    chan struct{}
}

func newUpstream(cfg Upstream, conc int, defaultTimeout time.Duration) (*upstream, error) {
	transport := &http.Transport{
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          256,
		MaxIdleConnsPerHost:   64,
		MaxConnsPerHost:       conc,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: defaultTimeout,
		ExpectContinueTimeout: 1 * time.Second,
	}
	if !cfg.PlainHTTP {
		tlsCfg, err := mtlsx.MeshClientConfig(cfg.ClientCert, cfg.ClientKey, cfg.CA, cfg.ServerName)
		if err != nil {
			return nil, err
		}
		transport.TLSClientConfig = tlsCfg
	}
	if conc == 0 {
		conc = 128
	}
	return &upstream{
		cfg: cfg,
		client: &http.Client{
			Timeout: defaultTimeout,
			// The gateway is a pass-through: 3xx responses (e.g. the IdP's
			// consent redirect to the client's redirect_uri) must reach the
			// caller untouched — never follow them upstream-side.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
			Transport: transport,
		},
		sem: make(chan struct{}, conc),
	}, nil
}

// forward sends the (already authorized) request upstream over pooled
// HTTP/2 mTLS connections, carrying only an allow-list of headers plus the
// signed gateway context. The authorized path is forwarded unchanged.
func (g *Gateway) forward(w http.ResponseWriter, r *http.Request, route *Route, body []byte, ctxVal string, certThumb string) {
	up := g.up[route.Upstream]
	target := up.cfg.BaseURL + r.URL.EscapedPath()
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(route.TimeoutMS)*time.Millisecond)
	defer cancel()
	outReq, err := http.NewRequestWithContext(ctx, r.Method, target, bytes.NewReader(body))
	if err != nil {
		writeErr(w, 502, "bad_gateway: internal error")
		return
	}
	// header allow-list (everything else is dropped — identity cannot leak,
	// spoofed forwarding headers are never copied)
	for _, h := range []string{"Cookie", "Accept", "Content-Type", "User-Agent", "Referer"} {
		if v := r.Header.Get(h); v != "" {
			outReq.Header.Set(h, v)
		}
	}
	if up.cfg.ForwardAuthorization {
		if v := r.Header.Get("Authorization"); v != "" {
			outReq.Header.Set("Authorization", v)
		}
	}
	if up.cfg.ForwardDPoP {
		if v := r.Header.Get("DPoP"); v != "" {
			outReq.Header.Set("DPoP", v)
		}
	}
	outReq.Header.Set("X-Forwarded-For", remoteIPKey(r))
	outReq.Header.Set("X-Forwarded-Proto", "https")
	outReq.Header.Set("X-Forwarded-Host", externalHost(r))
	outReq.Header.Set(ctxauth.RequestID, requestID(r))
	if ctxVal != "" {
		outReq.Header.Set(ctxauth.Header, ctxVal)
	}
	if certThumb != "" {
		outReq.Header.Set(ctxauth.CertHeader, certThumb)
	}
	if outReq.Header.Get("Content-Type") == "" && len(body) > 0 {
		outReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	outReq.ContentLength = int64(len(body))

	// bounded queue per upstream: saturation sheds load with 503
	select {
	case up.sem <- struct{}{}:
		defer func() { <-up.sem }()
	default:
		writeErr(w, 503, "upstream_unavailable: concurrency limit reached")
		return
	}

	resp, err := up.client.Do(outReq)
	if err != nil {
		writeErr(w, 503, "upstream_unavailable: "+err.Error())
		return
	}
	defer resp.Body.Close()

	for k, vals := range resp.Header {
		switch http.CanonicalHeaderKey(k) {
		case "Connection", "Keep-Alive", "Transfer-Encoding", "Upgrade", "Proxy-Authenticate", "Proxy-Authorization", "Te", "Trailer":
			continue
		}
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	// bounded response copy
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 64<<20))
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	fmt.Fprintf(w, `{"error":%q}`, msg)
}

var _ = breaker.New
