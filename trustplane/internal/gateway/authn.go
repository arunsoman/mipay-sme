package gateway

import (
	"crypto"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"trustplane/internal/breaker"
	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
	"trustplane/internal/mtlsx"
)

// ---------------------------------------------------------------------------
// JWKS cache (fetches IdP public keys over the mTLS mesh)
// ---------------------------------------------------------------------------

type keyEntry struct {
	pub crypto.PublicKey
}

type jwksCache struct {
	mu      sync.RWMutex
	keys    map[string]keyEntry
	ttl     time.Duration
	fetched time.Time
	fetch   func() ([]map[string]any, error)
}

func newJWKSCache(ttl time.Duration, fetch func() ([]map[string]any, error)) *jwksCache {
	return &jwksCache{keys: map[string]keyEntry{}, ttl: ttl, fetch: fetch}
}

func (c *jwksCache) refreshLocked() error {
	jwks, err := c.fetch()
	if err != nil {
		return err
	}
	keys := map[string]keyEntry{}
	for _, k := range jwks {
		kid, _ := k["kid"].(string)
		if kid == "" {
			continue
		}
		pub, err := jwtx.KeyFromJWK(k)
		if err != nil {
			log.Printf("[gateway] jwks: bad key %s: %v", kid, err)
			continue
		}
		keys[kid] = keyEntry{pub: pub}
	}
	if len(keys) == 0 {
		return fmt.Errorf("jwks: no usable keys")
	}
	c.keys = keys
	c.fetched = time.Now()
	log.Printf("[gateway] jwks: loaded %d keys", len(keys))
	return nil
}

func (c *jwksCache) size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.keys)
}

// verify validates an access token against cached JWKS keys.
func (c *jwksCache) verify(token string) (map[string]any, map[string]any, error) {
	c.mu.RLock()
	need := time.Since(c.fetched) > c.ttl || len(c.keys) == 0
	c.mu.RUnlock()
	if need {
		c.mu.Lock()
		if time.Since(c.fetched) > c.ttl || len(c.keys) == 0 {
			_ = c.refreshLocked()
		}
		c.mu.Unlock()
	}
	keyFn := func(h map[string]any) (crypto.PublicKey, error) {
		kid, _ := h["kid"].(string)
		c.mu.RLock()
		defer c.mu.RUnlock()
		e, ok := c.keys[kid]
		if !ok {
			return nil, fmt.Errorf("unknown kid %q", kid)
		}
		return e.pub, nil
	}
	hdr, claims, err := jwtx.VerifyJWS(token, jwtx.AllowedJWSAlgs(), keyFn)
	if err == nil {
		return hdr, claims, nil
	}
	if strings.Contains(err.Error(), "unknown kid") {
		c.mu.Lock()
		rerr := c.refreshLocked()
		c.mu.Unlock()
		if rerr == nil {
			return jwtx.VerifyJWS(token, jwtx.AllowedJWSAlgs(), keyFn)
		}
	}
	return nil, nil, err
}

// ---------------------------------------------------------------------------
// DPoP jti replay cache
// ---------------------------------------------------------------------------

type replayCache struct {
	mu sync.Mutex
	m  map[string]time.Time
}

func newReplayCache() *replayCache { return &replayCache{m: map[string]time.Time{}} }

func (r *replayCache) seenOnce(id string, ttl time.Duration) bool {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.m) > 200000 {
		for k, v := range r.m {
			if v.Before(now) {
				delete(r.m, k)
			}
		}
	}
	if t, ok := r.m[id]; ok && t.After(now) {
		return false
	}
	r.m[id] = now.Add(ttl)
	return true
}

// ---------------------------------------------------------------------------
// Introspection (revocation status) with cache + circuit breaker, fail-closed
// ---------------------------------------------------------------------------

type introspector struct {
	mu       sync.Mutex
	cache    map[string]introsEntry
	client   *http.Client
	endpoint string
	clientID string
	key      crypto.Signer
	kid      string
	issuer   string
	ttl      time.Duration
	brk      *breaker.Breaker
}

type introsEntry struct {
	active bool
	exp    time.Time
}

func newIntrospector(client *http.Client, endpoint, clientID, assertionKey, kid, issuer string, ttl time.Duration, brk *breaker.Breaker) (*introspector, error) {
	key, err := jwtx.LoadPrivateKeyFile(assertionKey)
	if err != nil {
		return nil, err
	}
	return &introspector{
		cache: map[string]introsEntry{}, client: client, endpoint: endpoint,
		clientID: clientID, key: key, kid: kid, issuer: issuer, ttl: ttl,
		brk: brk,
	}, nil
}

// status returns (active, known). known=false means the IdP could not be
// consulted (caller must fail closed with 503).
func (i *introspector) status(jti, accessToken string) (bool, bool) {
	i.mu.Lock()
	if e, ok := i.cache[jti]; ok && e.exp.After(time.Now()) {
		i.mu.Unlock()
		return e.active, true
	}
	i.mu.Unlock()
	if !i.brk.Allow() {
		return false, false
	}
	assertion, err := jwtx.SignJWS(jwtx.AlgES256, "JWT", i.kid, map[string]any{
		"iss": i.clientID, "sub": i.clientID, "aud": i.issuer,
		"exp": time.Now().Add(300 * time.Second).Unix(),
		"iat": time.Now().Unix(), "jti": jwtx.RandB64(16),
	}, i.key)
	if err != nil {
		i.brk.Record(false)
		return false, false
	}
	form := fmt.Sprintf(
		"token=%s&token_type_hint=access_token&client_id=%s&client_assertion_type=urn%%3Aietf%%3Aparams%%3Aoauth%%3Aclient-assertion-type%%3Ajwt-bearer&client_assertion=%s",
		queryEscape(accessToken), queryEscape(i.clientID), queryEscape(assertion))
	req, err := http.NewRequest(http.MethodPost, i.endpoint, strings.NewReader(form))
	if err != nil {
		i.brk.Record(false)
		return false, false
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := i.client.Do(req)
	if err != nil {
		i.brk.Record(false)
		return false, false
	}
	defer resp.Body.Close()
	cap := 65536
	body := make([]byte, cap)
	n, _ := resp.Body.Read(body)
	var out struct {
		Active bool `json:"active"`
	}
	if resp.StatusCode != 200 || json.Unmarshal(body[:n], &out) != nil {
		i.brk.Record(false)
		return false, false
	}
	i.brk.Record(true)
	i.mu.Lock()
	if len(i.cache) > 50000 {
		i.cache = map[string]introsEntry{}
	}
	i.cache[jti] = introsEntry{active: out.Active, exp: time.Now().Add(i.ttl)}
	i.mu.Unlock()
	return out.Active, true
}

func queryEscape(s string) string {
	const hex = "0123456789ABCDEF"
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '.' || c == '_' || c == '~' {
			out = append(out, c)
		} else {
			out = append(out, '%', hex[c>>4], hex[c&0xf])
		}
	}
	return string(out)
}

func hashKey(s string) string {
	sum := sha256.Sum256([]byte(s))
	return fmt.Sprintf("%x", sum)
}

// ---------------------------------------------------------------------------
// Identity returned by the authentication pipeline
// ---------------------------------------------------------------------------

type identity struct {
	sub       string
	clientID  string
	scopes    []string
	jti       string
	exp       int64
	scheme    string // "DPoP" or "Bearer" (as accepted on this request)
	certThumb string
}

func (id *identity) hasScope(s string) bool {
	for _, sc := range id.scopes {
		if sc == s {
			return true
		}
	}
	return false
}

func (id *identity) principal() string {
	if id.sub != "" {
		return "u:" + id.sub
	}
	return "c:" + id.clientID
}

// authenticate performs the full FAPI token validation for API routes:
// cryptographic verification, issuer/audience/expiry, revocation status and
// — crucially — sender-constraint enforcement (DPoP or mTLS) on the
// ORIGINAL external request, before any rewriting.
// Returns (identity, httpStatus, errorCode).
func (g *Gateway) authenticate(r *http.Request) (*identity, int, string) {
	auth := r.Header.Get("Authorization")
	parts := strings.SplitN(auth, " ", 2)
	if len(parts) != 2 || strings.TrimSpace(parts[1]) == "" {
		return nil, 401, "invalid_token: missing bearer/DPoP token"
	}
	scheme := strings.ToLower(parts[0])
	token := strings.TrimSpace(parts[1])

	hdr, claims, err := g.jwks.verify(token)
	if err != nil {
		return nil, 401, "invalid_token: " + err.Error()
	}
	_ = hdr
	// issuer + audience
	if jwtx.ClaimString(claims, "iss") != g.cfg.Server.Issuer {
		return nil, 401, "invalid_token: issuer mismatch"
	}
	audOK := false
	for _, a := range jwtx.ClaimStrings(claims, "aud") {
		if a == g.cfg.Server.Issuer {
			audOK = true
		}
	}
	if !audOK {
		return nil, 401, "invalid_token: audience mismatch"
	}
	now := time.Now().Unix()
	exp, _ := jwtx.ClaimInt64(claims, "exp")
	nbf, _ := jwtx.ClaimInt64(claims, "nbf")
	iat, _ := jwtx.ClaimInt64(claims, "iat")
	if exp == 0 || now >= exp {
		return nil, 401, "invalid_token: expired"
	}
	if nbf > now+60 {
		return nil, 401, "invalid_token: not yet valid"
	}
	if iat > now+300 {
		return nil, 401, "invalid_token: iat in the future"
	}
	jti := jwtx.ClaimString(claims, "jti")
	if jti == "" {
		return nil, 401, "invalid_token: missing jti"
	}
	// revocation (fail-closed)
	if g.intro != nil {
		active, known := g.intro.status(jti, token)
		if !known {
			return nil, 503, "upstream_unavailable: cannot verify token status"
		}
		if !active {
			return nil, 401, "invalid_token: revoked"
		}
	}
	// sender constraint (RFC 8705 mTLS binding / RFC 9449 DPoP binding)
	cnf, _ := claims["cnf"].(map[string]any)
	certThumb := ""
	if th, ok := peerThumb(r); ok {
		certThumb = th
	}
	if cnf == nil {
		return nil, 401, "invalid_token: token is not sender-constrained (FAPI 2.0 requires DPoP or mTLS binding)"
	}
	if jkt, ok := cnf["jkt"].(string); ok {
		if scheme != "dpop" {
			return nil, 401, `invalid_dpop_request: DPoP-bound tokens must be sent with "Authorization: DPoP <token>"`
		}
		proof := r.Header.Get("DPoP")
		if proof == "" {
			return nil, 401, "use_dpop_header: proof required (DPoP: <jwt>)"
		}
		htu, cerr := dpop.CanonicalHTU("https://" + externalHost(r) + r.URL.EscapedPath())
		if cerr != nil {
			return nil, 401, "invalid_dpop_proof: bad url"
		}
		gotJKT, dJTI, verr := dpop.Verify(proof, dpop.Options{
			HTM: r.Method, HTU: htu, AccessToken: token, MaxAge: 5 * time.Minute, Now: time.Now(),
		})
		if verr != nil {
			return nil, 401, "invalid_dpop_proof: " + verr.Error()
		}
		if !g.dpopOK.seenOnce(hashKey("dpop|"+dJTI), 10*time.Minute) {
			return nil, 401, "invalid_dpop_proof: jti replay"
		}
		if gotJKT != jkt {
			return nil, 401, "invalid_token: proof key does not match the token's bound key (cnf.jkt)"
		}
	} else if xt, ok := cnf["x5t#S256"].(string); ok {
		if scheme != "bearer" {
			return nil, 401, "invalid_token: mTLS-bound tokens use the Bearer scheme (RFC 8705)"
		}
		if certThumb == "" {
			return nil, 401, "use_tls_client_auth: present the bound client certificate over TLS"
		}
		if certThumb != xt {
			return nil, 401, "invalid_token: presented certificate differs from the token's bound certificate"
		}
	} else {
		return nil, 401, "invalid_token: unsupported cnf claim"
	}
	id := &identity{
		sub:       jwtx.ClaimString(claims, "sub"),
		clientID:  jwtx.ClaimString(claims, "client_id"),
		scopes:    strings.Fields(jwtx.ClaimString(claims, "scope")),
		jti:       jti,
		exp:       exp,
		scheme:    map[bool]string{true: "DPoP", false: "Bearer"}[scheme == "dpop"],
		certThumb: certThumb,
	}
	return id, 0, ""
}

func mtlsxThumb(r *http.Request) (string, bool) {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return "", false
	}
	return mtlsx.LeafThumbprint(r.TLS)
}
