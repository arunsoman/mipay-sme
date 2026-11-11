package gateway

import (
	"context"
	"crypto"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"trustplane/internal/breaker"
	"trustplane/internal/ctxauth"
	"trustplane/internal/mtlsx"
	"trustplane/internal/jwtx"
	"trustplane/internal/ratelimit"
	"github.com/jackc/pgx/v5/pgxpool"
)

// compiledRoute is a route plus its matcher segments.
type compiledRoute struct {
	route    Route
	segs     []seg
	wildcard bool // path ends with "/**"
	id       string
}

type seg struct {
	lit   string
	param string
}

type Gateway struct {
	cfg     *Config
	routes  []*compiledRoute
	up      map[string]*upstream
	limiter *ratelimit.Limiter
	global  chan struct{}
	jwks    *jwksCache
	intro   *introspector
	rbac    *rbacClient
	dpopOK  *replayCache
	reqID   uint64
	reqMu   sync.Mutex
	secret  []byte

	// Lane A: browser BFF
	store    *SessionStore
	bffID    string      // gateway-bff OAuth client id
	bffKey   crypto.Signer // gateway-bff private key
	bffKid   string
	bffIDP     string      // https://idp:8443 (mesh, server-to-server)
	bffIDPPublic string    // https://idp.fapi.local:8443 (browser redirect)
	bffAbs   time.Duration // session absolute lifetime
	bffIdle  time.Duration // session idle timeout
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

func Run(ctx context.Context, cfgPath, listen string) error {
	cfg, err := Load(cfgPath)
	if err != nil {
		return err
	}
	secret, err := os.ReadFile(cfg.Server.ContextSecret)
	if err != nil {
		return fmt.Errorf("gateway: context secret: %w", err)
	}
	if err := setTrustedProxies(cfg.Limits.TrustedProxies); err != nil {
		return err
	}
	g := &Gateway{
		cfg:     cfg,
		up:      map[string]*upstream{},
		limiter: ratelimit.New(),
		global:  make(chan struct{}, cfg.Limits.GlobalConcurrency),
		dpopOK:  newReplayCache(),
		secret:  secret,
	}
	for name, u := range cfg.Upstreams {
		upc, err := newUpstream(u, cfg.Limits.UpstreamConcurrency, 30*time.Second)
		if err != nil {
			return fmt.Errorf("upstream %s: %w", name, err)
		}
		g.up[name] = upc
	}
	idpUp := g.up["idp"]
	if idpUp == nil {
		return fmt.Errorf("an upstream named 'idp' is required for JWKS/introspection")
	}
	jwksURL := strings.TrimSuffix(idpUp.cfg.BaseURL, "/") + "/jwks.json"
	g.jwks = newJWKSCache(5*time.Minute, func() ([]map[string]any, error) {
		resp, err := idpUp.client.Get(jwksURL)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil || resp.StatusCode != 200 {
			return nil, fmt.Errorf("jwks fetch: status=%d err=%v", resp.StatusCode, err)
		}
		var out struct {
			Keys []map[string]any `json:"keys"`
		}
		if err := json.Unmarshal(b, &out); err != nil {
			return nil, err
		}
		return out.Keys, nil
	})
	if cfg.Introspection.Enabled {
		timeout := time.Duration(cfg.Introspection.TimeoutMS) * time.Millisecond
		if timeout == 0 {
			timeout = 3 * time.Second
		}
		iclient := &http.Client{Timeout: timeout, Transport: idpUp.client.Transport}
		ttl := 3 * time.Second
		endpoint := strings.TrimSuffix(cfg.Introspection.BaseURL, "/") + "/introspect"
		// kid must match the IdP's client JWKS: derived from the key filename
		// (same convention as internal/idpserver key registry).
		introsKid := strings.TrimSuffix(filepath.Base(cfg.Introspection.AssertionKey), ".pem")
		g.intro, err = newIntrospector(iclient, endpoint, cfg.Introspection.ClientID,
			cfg.Introspection.AssertionKey, introsKid, cfg.Server.Issuer, ttl, breaker.New(3, 5*time.Second))
		if err != nil {
			return fmt.Errorf("introspection: %w", err)
		}
	}
	if cfg.RBACGrpc.Endpoint != "" {
		g.rbac, err = dialRBAC(cfg.RBACGrpc)
		if err != nil {
			return fmt.Errorf("rbac: %w", err)
		}
		go g.rbac.WatchLoop(ctx)
	}

	// ---- Lane A: browser BFF session store + client credentials ----
	dbURL := cfg.BFF.DBURL
	if dbURL == "" {
		dbURL = os.Getenv("GATEWAY_DB_URL")
	}
	if dbURL != "" {
		pool, err := pgxpool.New(ctx, dbURL)
		if err != nil {
			return fmt.Errorf("gateway: session db pool: %w", err)
		}
		defer pool.Close()
		encKey, err := ensureSessionKey(os.Getenv("GATEWAY_SESSION_KEY"), secret)
		if err != nil {
			return fmt.Errorf("gateway: session key: %w", err)
		}
		s, err := NewSessionStore(pool, encKey)
		if err != nil {
			return fmt.Errorf("gateway: session store: %w", err)
		}
		g.store = s
	}
	if cfg.BFF.ClientID != "" && cfg.BFF.ClientKey != "" {
		signKey, err := jwtx.LoadPrivateKeyFile(cfg.BFF.ClientKey)
		if err != nil {
			return fmt.Errorf("gateway: bff client key: %w", err)
		}
		g.bffID = cfg.BFF.ClientID
		g.bffKey = signKey
		g.bffKid = cfg.BFF.ClientKid
		g.bffIDP = cfg.BFF.IDPBase
		g.bffIDPPublic = cfg.BFF.IDPPublicBase
		g.bffAbs = time.Duration(cfg.BFF.AbsLifeSec) * time.Second
		g.bffIdle = time.Duration(cfg.BFF.IdleLifeSec) * time.Second
		log.Printf("[gateway] bff: client=%s idp=%s absLife=%v idleLife=%v",
			g.bffID, g.bffIDP, g.bffAbs, g.bffIdle)
	}


	for i := range cfg.Routes {
		cr, err := compile(&cfg.Routes[i], i)
		if err != nil {
			return err
		}
		g.routes = append(g.routes, cr)
	}
	log.Printf("[gateway] %d routes, %d upstreams, issuer=%s, rbac=%t", len(g.routes), len(g.up), cfg.Server.Issuer, g.rbac != nil)

	tlsCfg, err := mtlsx.ServerConfig(cfg.Server.Cert, cfg.Server.Key, cfg.Server.ClientCA, false)
	if err != nil {
		return fmt.Errorf("gateway tls: %w", err)
	}
	srv := &http.Server{
		Handler:           http.HandlerFunc(g.serve),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    cfg.Server.MaxHeaderBytes,
		TLSConfig:         tlsCfg,
	}
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return err
	}
	go func() {
		if err := srv.Serve(tls.NewListener(ln, tlsCfg)); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[gateway] serve: %v", err)
		}
	}()
	<-ctx.Done()
	shCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shCtx)
	return nil
}

// ---------------------------------------------------------------------------
// Route matching
// ---------------------------------------------------------------------------

func compile(r *Route, idx int) (*compiledRoute, error) {
	p := strings.TrimSuffix(r.Path, "/**")
	wildcard := p != r.Path
	rawSegs := strings.Split(strings.Trim(p, "/"), "/")
	cr := &compiledRoute{route: *r, wildcard: wildcard}
	for _, s := range rawSegs {
		if s == "" {
			continue
		}
		if strings.HasPrefix(s, "{") && strings.HasSuffix(s, "}") {
			cr.segs = append(cr.segs, seg{param: strings.Trim(s, "{}")})
		} else {
			cr.segs = append(cr.segs, seg{lit: s})
		}
	}
	cr.id = fmt.Sprintf("r%d", idx)
	return cr, nil
}

func (g *Gateway) match(host, method, escapedPath string) (*compiledRoute, map[string]string, bool, bool) {
	target := strings.ToLower(host)
	target = strings.Split(target, ":")[0]
	plain := escapedPath
	segments := strings.Split(strings.Trim(plain, "/"), "/")
	var pathMatch, methodHit *compiledRoute
	var params map[string]string
	for _, cr := range g.routes {
		if strings.ToLower(cr.route.Host) != target {
			continue
		}
		pp, ok := matchSegments(cr, segments)
		if !ok {
			continue
		}
		if pathMatch == nil {
			pathMatch, params = cr, pp
		}
		for _, m := range cr.route.Methods {
			if strings.EqualFold(m, method) && methodHit == nil {
				methodHit, params = cr, pp
			}
		}
	}
	if pathMatch == nil {
		return nil, nil, false, false
	}
	if methodHit != nil {
		return methodHit, params, true, true
	}
	return pathMatch, params, true, false
}

func matchSegments(cr *compiledRoute, segments []string) (map[string]string, bool) {
	params := map[string]string{}
	if cr.wildcard {
		if len(segments) < len(cr.segs) {
			return nil, false
		}
		for i, s := range cr.segs {
			if s.lit != "" && s.lit != segments[i] {
				return nil, false
			}
			if s.param != "" {
				params[s.param] = segments[i]
			}
		}
		return params, true
	}
	if len(cr.segs) != len(segments) {
		return nil, false
	}
	for i, s := range cr.segs {
		if s.lit != "" && s.lit != segments[i] {
			return nil, false
		}
		if s.param != "" {
			params[s.param] = segments[i]
		}
	}
	return params, true
}

// ---------------------------------------------------------------------------
// Hardening (reject malformed/suspicious requests before any work)
// ---------------------------------------------------------------------------

var suspiciousPath = regexp.MustCompile(`(?i)(%2e|%2f|%5c|%00|%0d|%0a)`)

func (g *Gateway) harden(r *http.Request) (int, string) {
	esc := r.URL.EscapedPath()
	if suspiciousPath.MatchString(esc) {
		return 400, "bad_request: suspicious encoding"
	}
	if strings.Contains(esc, "//") || strings.Contains(esc, "/./") || strings.Contains(esc, "/../") {
		return 400, "bad_request: path traversal"
	}
	dec := r.URL.Path
	if strings.Contains(dec, "\\") || strings.Contains(dec, "\x00") {
		return 400, "bad_request: suspicious path"
	}
	clean := path.Clean(dec)
	if dec != clean && !(dec == "/" && clean == "/") {
		return 400, "bad_request: non-canonical path"
	}
	// conflicting HTTP framing
	if len(r.TransferEncoding) > 1 {
		return 400, "bad_request: multiple transfer codings"
	}
	if len(r.TransferEncoding) == 1 && r.TransferEncoding[0] != "chunked" {
		return 400, "bad_request: unsupported transfer coding"
	}
	if r.Header.Get("Content-Length") != "" && len(r.TransferEncoding) > 0 {
		return 400, "bad_request: conflicting framing (Content-Length + Transfer-Encoding)"
	}
	return 0, ""
}

func requestID(r *http.Request) string {
	if v := r.Header.Get("X-Request-Id"); len(v) > 0 && len(v) < 65 && !strings.ContainsAny(v, "\r\n") {
		return v
	}
	return fmt.Sprintf("req-%d", time.Now().UnixNano())
}

// trustedProxies holds limits.trusted_proxies, parsed once at start-up.
var trustedProxies []*net.IPNet

func setTrustedProxies(cidrs []string) error {
	trustedProxies = nil
	for _, c := range cidrs {
		_, n, err := net.ParseCIDR(strings.TrimSpace(c))
		if err != nil {
			return fmt.Errorf("gateway: limits.trusted_proxies %q: %w", c, err)
		}
		trustedProxies = append(trustedProxies, n)
	}
	return nil
}

func isTrustedProxy(ip net.IP) bool {
	for _, n := range trustedProxies {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// remoteIPKey is the caller's address: the TCP peer, or - when the peer is a trusted
// proxy - the X-Real-IP that proxy set. Untrusted peers can never choose their own key.
func remoteIPKey(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		h = r.RemoteAddr
	}
	if peer := net.ParseIP(h); peer != nil && isTrustedProxy(peer) {
		if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" && net.ParseIP(v) != nil {
			return v
		}
	}
	return h
}

// forwardedHostRe accepts exactly one host[:port]: no lists, paths, userinfo or spaces.
var forwardedHostRe = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(?::[0-9]{1,5})?$`)

// externalHost is the host the caller actually addressed. Behind the host nginx, r.Host
// is the internal vhost (portal.fapi.local), so the public host comes from
// X-Forwarded-Host - but only when the TCP peer is a trusted proxy (the same rule as
// remoteIPKey) and the header is a single well-formed host. Anyone else gets r.Host.
// Used wherever the gateway rebuilds a public URL: the DPoP htu check (RFC 9449 §4.3)
// and the BFF redirect_uri.
func externalHost(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		h = r.RemoteAddr
	}
	if peer := net.ParseIP(h); peer != nil && isTrustedProxy(peer) {
		if v := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); v != "" && forwardedHostRe.MatchString(v) {
			return strings.ToLower(v)
		}
	}
	return r.Host
}

// ---------------------------------------------------------------------------
// Error writer with WWW-Authenticate semantics
// ---------------------------------------------------------------------------

func writeAuthError(w http.ResponseWriter, status int, code string) {
	switch {
	case strings.HasPrefix(code, "use_dpop") || strings.HasPrefix(code, "invalid_dpop"):
		w.Header().Set("WWW-Authenticate", `DPoP error="invalid_request", algs="ES256 EdDSA"`)
	case strings.HasPrefix(code, "use_tls"):
		w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token", error_description="client certificate required"`)
	default:
		w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer error="invalid_token", error_description=%q`, code))
	}
	writeErr(w, status, code)
}

// ---------------------------------------------------------------------------
// Main request pipeline: match -> harden -> budgets -> authn -> authz -> proxy
// ---------------------------------------------------------------------------

func (g *Gateway) serve(w http.ResponseWriter, r *http.Request) {
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	// Lane A: browser BFF handles /auth/* directly (login, callback, session, logout).
	// These are "auth: none" routes; hardening + rate limiting still apply.
	if strings.HasPrefix(r.URL.Path, "/auth/") {
		if status, msg := g.harden(r); status != 0 {
			writeErr(w, status, msg)
			return
		}
		rip := remoteIPKey(r)
		if !g.limiter.Allow("bff|ip|"+rip, 600, 600) {
			w.Header().Set("Retry-After", "10")
			writeErr(w, 429, "rate_limited_ip")
			return
		}
		g.bff(w, r)
		return
	}

	route, params, pathOK, methodOK := g.match(host, r.Method, r.URL.EscapedPath())
	ctxauth.StripSpoofed(r.Header) // callers may never forge internal context
	if !pathOK {
		writeErr(w, 404, "not_found")
		return
	}
	if !methodOK {
		w.Header().Set("Allow", strings.Join(route.route.Methods, ", "))
		writeErr(w, 405, "method_not_allowed")
		return
	}
	if status, msg := g.harden(r); status != 0 {
		writeErr(w, status, msg)
		return
	}
	rip := remoteIPKey(r)
	if !g.limiter.Allow(route.id+"|ip|"+rip, float64(route.route.Rate.IPPerMin), float64(route.route.Rate.IPPerMin)) {
		w.Header().Set("Retry-After", "10")
		writeErr(w, 429, "rate_limited_ip")
		return
	}
	select {
	case g.global <- struct{}{}:
		defer func() { <-g.global }()
	default:
		writeErr(w, 503, "gateway_saturated: bounded queue full")
		return
	}
	// bounded body (single read; small bodies by policy)
	body := []byte{}
	if r.Body != nil {
		b, err := io.ReadAll(io.LimitReader(r.Body, route.route.BodyLimit+1))
		if err != nil {
			writeErr(w, 400, "bad_request: body read error")
			return
		}
		if int64(len(b)) > route.route.BodyLimit {
			writeErr(w, 413, "request_too_large")
			return
		}
		body = b
	}

	certThumb, _ := peerThumb(r)
	ctxVal := ""
	ident := (*identity)(nil)

	if route.route.Auth == "token" {
		id, status, errCode := g.authenticate(r)
		if status != 0 {
			writeAuthError(w, status, errCode)
			return
		}
		ident = id
		if !g.limiter.Allow(route.id+"|client|"+ident.principal(), float64(route.route.Rate.ClientPerMin), float64(route.route.Rate.ClientPerMin)) {
			w.Header().Set("Retry-After", "10")
			writeErr(w, 429, "rate_limited_client")
			return
		}
		for _, sc := range route.route.Scopes {
			if !ident.hasScope(sc) {
				writeAuthError(w, 403, fmt.Sprintf("insufficient_scope: route requires scope %q", sc))
				return
			}
		}
		if route.route.Permission != "" {
			if g.rbac == nil {
				writeAuthError(w, 503, "upstream_unavailable: authorization service not configured")
				return
			}
			tenant := params["tenant"]
			allowed, err := g.rbac.Check(r.Context(), principalID(ident), tenant, route.route.Permission, route.route.Fresh)
			if err != nil {
				writeAuthError(w, 503, "upstream_unavailable: authorization service unreachable")
				return
			}
			if !allowed {
				writeAuthError(w, 403, fmt.Sprintf("insufficient_permission: subject lacks %q in tenant %q", route.route.Permission, tenant))
				return
			}
			perms, err := g.rbac.Perms(r.Context(), principalID(ident), tenant)
			if err != nil {
				log.Printf("[gateway] perms load failed for %s: %v", principalID(ident), err)
				writeAuthError(w, 503, "upstream_unavailable: cannot load permissions")
				return
			}
			c := ctxauth.Context{
				Sub: ident.sub, ClientID: ident.clientID, TokenID: ident.jti,
				CertThumb: ident.certThumb, Scopes: ident.scopes, Perms: perms,
				Tenant: tenant, TS: time.Now().Unix(),
			}
			ctxVal = ctxauth.Sign(c, g.secret)
		}
	}
	g.forward(w, r, &route.route, body, ctxVal, certThumbForProxy(&route.route, certThumb))
}

func peerThumb(r *http.Request) (string, bool) {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return "", false
	}
	return mtlsx.LeafThumbprint(r.TLS)
}

func certThumbForProxy(route *Route, thumb string) string {
	// IdP consumes the external client cert thumb (tls_client_auth, mTLS
	// binding); API upstreams get identity via the signed context header.
	if route.Upstream == "idp" {
		return thumb
	}
	return ""
}

func principalID(id *identity) string {
	if id.sub != "" {
		return id.sub
	}
	return id.clientID
}
