package idpserver

import (
	"context"
	"crypto"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"trustplane/internal/clients"
	"trustplane/internal/ctxauth"
	"trustplane/internal/dbx"
	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
	"trustplane/internal/ratelimit"
)

// ---------------------------------------------------------------------------
// DDL (idempotent, applied at boot)
// ---------------------------------------------------------------------------

var DDL = []string{
	// P1: the demo `users` table is replaced by credential accounts (no scopes, no authority).
	`DROP TABLE IF EXISTS users`,
	`CREATE TABLE IF NOT EXISTS accounts(
		subject TEXT PRIMARY KEY,
		uid TEXT NOT NULL UNIQUE,
		login_name TEXT NOT NULL,
		phone TEXT,
		display_name TEXT NOT NULL DEFAULT '',
		password_hash TEXT,
		state TEXT NOT NULL CHECK (state IN ('ENROLLING','ACTIVE','DISABLED')),
		disabled_reason TEXT,
		failed_attempts INTEGER NOT NULL DEFAULT 0,
		lock_count INTEGER NOT NULL DEFAULT 0,
		locked_until TIMESTAMPTZ,
		password_changed_at TIMESTAMPTZ,
		tokens_valid_after TIMESTAMPTZ NOT NULL DEFAULT 'epoch',
		mfa_profile TEXT,
		org_requires_mfa BOOLEAN NOT NULL DEFAULT FALSE,
		seeded BOOLEAN NOT NULL DEFAULT FALSE,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE UNIQUE INDEX IF NOT EXISTS accounts_login_name_uq ON accounts (lower(login_name))`,
	`CREATE UNIQUE INDEX IF NOT EXISTS accounts_phone_uq ON accounts (phone) WHERE phone IS NOT NULL`,
	`CREATE TABLE IF NOT EXISTS account_links(
		token_hash TEXT PRIMARY KEY,
		subject TEXT NOT NULL REFERENCES accounts(subject) ON DELETE CASCADE,
		purpose TEXT NOT NULL CHECK (purpose IN ('SETUP','RESET','RECOVERY')),
		idempotency_key TEXT,
		expires_at TIMESTAMPTZ NOT NULL,
		used_at TIMESTAMPTZ,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`ALTER TABLE account_links ADD COLUMN IF NOT EXISTS idempotency_key TEXT`,
	`CREATE UNIQUE INDEX IF NOT EXISTS account_links_idem_uq ON account_links(subject,purpose,idempotency_key) WHERE idempotency_key IS NOT NULL`,
	`CREATE INDEX IF NOT EXISTS account_links_subject_idx ON account_links (subject, purpose)`,
	`CREATE TABLE IF NOT EXISTS otp_challenges(
		id TEXT PRIMARY KEY,
		subject TEXT NOT NULL REFERENCES accounts(subject) ON DELETE CASCADE,
		request_uri TEXT NOT NULL,
		code_hash TEXT NOT NULL,
		attempts INTEGER NOT NULL DEFAULT 0,
		expires_at TIMESTAMPTZ NOT NULL,
		used_at TIMESTAMPTZ,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE INDEX IF NOT EXISTS otp_challenges_subject_idx ON otp_challenges (subject, created_at)`,
	`CREATE TABLE IF NOT EXISTS mfa_factors(
		subject TEXT PRIMARY KEY REFERENCES accounts(subject) ON DELETE CASCADE,
		kind TEXT NOT NULL CHECK (kind='TOTP'),
		secret BYTEA NOT NULL,
		last_step BIGINT NOT NULL DEFAULT -1,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE TABLE IF NOT EXISTS mfa_login(
		id TEXT PRIMARY KEY,
		subject TEXT NOT NULL REFERENCES accounts(subject) ON DELETE CASCADE,
		request_uri TEXT NOT NULL,
		first_method TEXT NOT NULL,
		method TEXT NOT NULL CHECK (method IN ('TOTP','TOTP_ENROLL','SMS')),
		secret BYTEA,
		code_hash TEXT,
		attempts INTEGER NOT NULL DEFAULT 0,
		expires_at TIMESTAMPTZ NOT NULL,
		used_at TIMESTAMPTZ,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE INDEX IF NOT EXISTS mfa_login_subject_recent_idx ON mfa_login(subject,created_at DESC)`,
	`CREATE TABLE IF NOT EXISTS outbound(
		id TEXT PRIMARY KEY,
		target TEXT NOT NULL CHECK (target IN ('core','logout')),
		url TEXT NOT NULL DEFAULT '',
		payload TEXT NOT NULL,
		attempts INTEGER NOT NULL DEFAULT 0,
		next_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		delivered_at TIMESTAMPTZ,
		last_error TEXT,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE INDEX IF NOT EXISTS outbound_due_idx ON outbound (next_at) WHERE delivered_at IS NULL`,
	`CREATE TABLE IF NOT EXISTS sim_outbox(
		id TEXT PRIMARY KEY,
		event_code TEXT NOT NULL,
		recipient TEXT NOT NULL,
		lane TEXT NOT NULL,
		body TEXT NOT NULL,
		data JSONB NOT NULL DEFAULT '{}'::jsonb,
		expires_at TIMESTAMPTZ,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now())`,
	`CREATE TABLE IF NOT EXISTS sessions(
		session_id TEXT PRIMARY KEY,
		user_id TEXT NOT NULL,
		csrf TEXT NOT NULL,
		expires_at TIMESTAMPTZ NOT NULL)`,
	`ALTER TABLE sessions ADD COLUMN IF NOT EXISTS amr TEXT NOT NULL DEFAULT 'pwd'`,
	`ALTER TABLE sessions ADD COLUMN IF NOT EXISTS auth_time TIMESTAMPTZ NOT NULL DEFAULT now()`,
	`CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions (user_id)`,
	`CREATE TABLE IF NOT EXISTS pars(
		request_uri TEXT PRIMARY KEY,
		client_id TEXT NOT NULL,
		params JSONB NOT NULL,
		expires_at TIMESTAMPTZ NOT NULL)`,
	`CREATE TABLE IF NOT EXISTS auth_codes(
		code_hash TEXT PRIMARY KEY,
		client_id TEXT NOT NULL,
		redirect_uri TEXT NOT NULL,
		sub TEXT NOT NULL,
		scope TEXT NOT NULL,
		nonce TEXT NOT NULL,
		code_challenge TEXT NOT NULL,
		session_id TEXT NOT NULL,
		used BOOLEAN NOT NULL DEFAULT FALSE,
		expires_at TIMESTAMPTZ NOT NULL)`,
	`ALTER TABLE auth_codes ADD COLUMN IF NOT EXISTS amr TEXT NOT NULL DEFAULT 'pwd'`,
	`ALTER TABLE auth_codes ADD COLUMN IF NOT EXISTS auth_time BIGINT NOT NULL DEFAULT 0`,
	`CREATE TABLE IF NOT EXISTS jti_seen(
		jti TEXT PRIMARY KEY,
		expires_at TIMESTAMPTZ NOT NULL)`,
	`CREATE TABLE IF NOT EXISTS revoked_jti(
		jti TEXT PRIMARY KEY,
		expires_at TIMESTAMPTZ NOT NULL)`,
	`CREATE TABLE IF NOT EXISTS refresh_tokens(
		token_hash TEXT PRIMARY KEY,
		token       TEXT NOT NULL,
		client_id   TEXT NOT NULL,
		sub         TEXT NOT NULL,
		scope       TEXT NOT NULL,
		cnf         JSONB NOT NULL,
		expires_at  TIMESTAMPTZ NOT NULL,
		revoked     BOOLEAN NOT NULL DEFAULT FALSE)`,
	`ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS sid TEXT NOT NULL DEFAULT ''`,
	`ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS amr TEXT NOT NULL DEFAULT 'pwd'`,
	`ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS auth_time BIGINT NOT NULL DEFAULT 0`,
	`CREATE INDEX IF NOT EXISTS refresh_tokens_sub_idx ON refresh_tokens (sub)`,
}

// ---------------------------------------------------------------------------
// Keyring
// ---------------------------------------------------------------------------

type keyEntry struct {
	signer crypto.Signer
	kid    string
}

type keyring struct {
	byAlg map[string]keyEntry
	jwks  []map[string]any
}

func LoadKeyring(dir string) (*keyring, error) {
	k := &keyring{byAlg: map[string]keyEntry{}}
	for alg, file := range map[string]string{
		jwtx.AlgPS256: "ps256.pem",
		jwtx.AlgES256: "es256.pem",
		jwtx.AlgEdDSA: "eddsa.pem",
	} {
		signer, err := jwtx.LoadPrivateKeyFile(dir + "/" + file)
		if err != nil {
			return nil, fmt.Errorf("idp: signing key %s: %w", file, err)
		}
		kid := strings.TrimSuffix(file, ".pem")
		k.byAlg[alg] = keyEntry{signer: signer, kid: kid}
		jwk, err := jwtx.PublicJWK(signer.Public())
		if err != nil {
			return nil, err
		}
		jwk["kid"] = kid
		jwk["use"] = "sig"
		jwk["alg"] = alg
		k.jwks = append(k.jwks, jwk)
	}
	return k, nil
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

type Server struct {
	cfg     Config
	pool    *pgxpool.Pool
	clients *clients.Registry
	keys    *keyring
	loginIP *ratelimit.Limiter
	comms   CommsClient

	coreHTTP   *http.Client // mesh mTLS (core events, comms plane)
	logoutHTTP *http.Client // back-channel logout to clients (server-verified TLS only)
}

func NewServer(cfg Config, pool *pgxpool.Pool, reg *clients.Registry, keys *keyring) *Server {
	s := &Server{cfg: cfg, pool: pool, clients: reg, keys: keys, loginIP: ratelimit.New()}
	_ = s.outboundClients()
	s.comms = newCommsClient(cfg, pool, s.coreHTTP)
	return s
}

// SetComms replaces the comms client (tests).
func (s *Server) SetComms(c CommsClient) { s.comms = c }

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.Health)
	mux.HandleFunc("/.well-known/openid-configuration", s.discovery)
	mux.HandleFunc("/jwks.json", s.jwks)
	mux.HandleFunc("/par", s.par)
	mux.HandleFunc("/authorize", s.authorize)
	mux.HandleFunc("/login", s.login)
	mux.HandleFunc("/consent", s.consent)
	mux.HandleFunc("/token", s.token)
	mux.HandleFunc("/introspect", s.introspect)
	mux.HandleFunc("/revoke", s.revoke)
	mux.HandleFunc("/logout", s.logout)
	// P1: credential plugin
	mux.HandleFunc("/login/otp/start", s.otpStart)
	mux.HandleFunc("/login/otp/verify", s.otpVerify)
	mux.HandleFunc("/login/mfa/verify", s.mfaVerify)
	mux.HandleFunc("/login/mfa/switch", s.mfaSwitchToAuthenticator)
	mux.HandleFunc("/mfa/readiness", s.mfaReadiness)
	mux.HandleFunc("/account/setup", s.accountSetup)
	mux.HandleFunc("/account/forgot", s.accountForgot)
	mux.HandleFunc("/account/help", s.accountHelp)
	mux.HandleFunc("/accounts", s.accountsAPI)
	mux.HandleFunc("/accounts/", s.accountsAPI)
	return mux
}

func (s *Server) Health(w http.ResponseWriter, _ *http.Request) {
	if err := dbx.Healthy(s.pool); err != nil {
		http.Error(w, "db down", http.StatusServiceUnavailable)
		return
	}
	w.Write([]byte("ok"))
}

// ---------------------------------------------------------------------------
// Response helpers
// ---------------------------------------------------------------------------

func jsonBytes(w http.ResponseWriter, status int, v any) {
	b, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write(b)
}

func oauthErr(w http.ResponseWriter, status int, code, desc string) {
	auth := ""
	switch code {
	case "invalid_client":
		auth = `Basic realm="fapi-idp"`
	}
	if auth != "" {
		w.Header().Set("WWW-Authenticate", auth)
	}
	jsonBytes(w, status, map[string]string{"error": code, "error_description": desc})
}

func errorPage(w http.ResponseWriter, status int, title, msg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	fmt.Fprintf(w, `<!doctype html><html><head><title>%s</title></head>
<body style="font-family:sans-serif;max-width:32rem;margin:4rem auto">
<h2>%s</h2><p>%s</p><p><a href="/">Back</a></p></body></html>`,
		html.EscapeString(title), html.EscapeString(title), html.EscapeString(msg))
}

func redirectQuery(base string, params map[string]string) string {
	sep := "?"
	if strings.Contains(base, "?") {
		sep = "&"
	}
	var parts []string
	for k, v := range params {
		parts = append(parts, k+"="+v) // values are URL-encoded by caller when needed
	}
	return base + sep + strings.Join(parts, "&")
}

func urlEscape(s string) string {
	// percent-encode everything outside the RFC 3986 unreserved set
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

func codeSum(code string) string {
	sum := sha256.Sum256([]byte(code))
	return jwtx.B64UEnc(sum[:])
}

// ---------------------------------------------------------------------------
// Peer certificate evidence (gateway forwards it over the mTLS mesh)
// ---------------------------------------------------------------------------

func peerCertThumb(r *http.Request) string {
	if v := r.Header.Get(ctxauth.CertHeader); v != "" {
		return v
	}
	// Behind the gateway the direct TLS peer is the GATEWAY's mesh certificate, never the
	// client's: only the client certificate the gateway forwards (header above) counts.
	// A direct mesh caller's own certificate counts only when it is not the gateway.
	if r.TLS != nil && len(r.TLS.PeerCertificates) > 0 &&
		normalizeCN(r.TLS.PeerCertificates[0].Subject.CommonName) != "gateway" {
		return jwtx.ThumbprintCertDER(r.TLS.PeerCertificates[0].Raw)
	}
	return ""
}

// ---------------------------------------------------------------------------
// Client authentication (RFC 7523 private_key_jwt / RFC 8705 tls_client_auth)
// ---------------------------------------------------------------------------

type clientAuthn struct {
	method    string
	certThumb string
}

func (s *Server) clientAuth(r *http.Request) (*clients.Client, clientAuthn, error) {
	_ = r.ParseForm()
	clientID := r.FormValue("client_id")
	assertion := r.FormValue("client_assertion")
	assertionType := r.FormValue("client_assertion_type")
	thumb := peerCertThumb(r)

	var cl *clients.Client
	authn := clientAuthn{}

	switch {
	case assertion != "" || assertionType != "":
		if assertionType != "urn:ietf:params:oauth:client-assertion-type:jwt-bearer" {
			return nil, authn, fmt.Errorf("unsupported client_assertion_type")
		}
		if clientID == "" || assertion == "" {
			return nil, authn, fmt.Errorf("client_id and client_assertion required")
		}
		cl = s.clients.ByID[clientID]
		if cl == nil {
			return nil, authn, fmt.Errorf("unknown client")
		}
		if cl.AuthMethod != clients.AuthPrivateKeyJWT {
			return nil, authn, fmt.Errorf("client is not registered for private_key_jwt")
		}
		if err := s.verifyClientAssertion(cl, assertion); err != nil {
			return nil, authn, fmt.Errorf("client assertion rejected: %v", err)
		}
		authn.method = clients.AuthPrivateKeyJWT
	case thumb != "":
		if clientID == "" {
			return nil, authn, fmt.Errorf("client_id required")
		}
		cl = s.clients.ByID[clientID]
		if cl == nil {
			return nil, authn, fmt.Errorf("unknown client")
		}
		if cl.AuthMethod != clients.AuthTLSClient {
			return nil, authn, fmt.Errorf("client is not registered for tls_client_auth")
		}
		ok := false
		for _, t := range cl.CertThumbs {
			if t == thumb {
				ok = true
				break
			}
		}
		if !ok {
			return nil, authn, fmt.Errorf("client certificate thumbprint not registered")
		}
		authn.method = clients.AuthTLSClient
		authn.certThumb = thumb
	default:
		return nil, authn, fmt.Errorf("confidential client authentication required (private_key_jwt or mTLS)")
	}
	return cl, authn, nil
}

func (s *Server) verifyClientAssertion(cl *clients.Client, assertion string) error {
	_, claims, err := jwtx.VerifyJWS(assertion, jwtx.AllowedJWSAlgs(), func(hdr map[string]any) (crypto.PublicKey, error) {
		kid, _ := hdr["kid"].(string)
		if typ, _ := hdr["typ"].(string); typ != "" && typ != "JWT" {
			return nil, fmt.Errorf("assertion typ must be JWT")
		}
		for _, jwk := range cl.JWTKeys {
			if jwk["kid"] == kid {
				return jwtx.KeyFromJWK(jwk)
			}
		}
		return nil, errors.New("unknown kid")
	})
	if err != nil {
		return err
	}
	now := time.Now()
	if iss := jwtx.ClaimString(claims, "iss"); iss != cl.ID {
		return fmt.Errorf("iss must equal client_id")
	}
	if sub := jwtx.ClaimString(claims, "sub"); sub != cl.ID {
		return fmt.Errorf("sub must equal client_id")
	}
	auds := jwtx.ClaimStrings(claims, "aud")
	if len(auds) != 1 || auds[0] != s.cfg.Issuer {
		// FAPI 2.0: the assertion audience MUST be the issuer identifier.
		return fmt.Errorf("aud must be exactly the issuer identifier")
	}
	exp, _ := jwtx.ClaimInt64(claims, "exp")
	iat, _ := jwtx.ClaimInt64(claims, "iat")
	if exp == 0 || exp <= now.Unix() || exp-now.Unix() > 600 {
		return fmt.Errorf("exp out of window")
	}
	if iat == 0 || iat > now.Unix()+60 || now.Unix()-iat > 600 {
		return fmt.Errorf("iat out of window")
	}
	jti := jwtx.ClaimString(claims, "jti")
	if jti == "" {
		return fmt.Errorf("jti required")
	}
	if !s.reserveJTI(jti, 10*time.Minute) {
		return fmt.Errorf("jti replay detected")
	}
	return nil
}

// reserveJTI stores a jti for replay detection; returns false on duplicate.
func (s *Server) reserveJTI(jti string, ttl time.Duration) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	tag, err := s.pool.Exec(ctx,
		`INSERT INTO jti_seen (jti, expires_at) VALUES ($1,$2) ON CONFLICT (jti) DO NOTHING`,
		jti, time.Now().Add(ttl))
	return err == nil && tag.RowsAffected() == 1
}

// ---------------------------------------------------------------------------
// Discovery + JWKS
// ---------------------------------------------------------------------------

func (s *Server) discovery(w http.ResponseWriter, _ *http.Request) {
	iss := s.cfg.Issuer
	meta := map[string]any{
		"issuer":                                           iss,
		"authorization_endpoint":                           iss + "/authorize",
		"token_endpoint":                                   iss + "/token",
		"pushed_authorization_request_endpoint":            iss + "/par",
		"introspection_endpoint":                           iss + "/introspect",
		"revocation_endpoint":                              iss + "/revoke",
		"jwks_uri":                                         iss + "/jwks.json",
		"response_types_supported":                         []string{"code"},
		"grant_types_supported":                            []string{"authorization_code", "refresh_token"},
		"scopes_supported":                                 []string{"openid", "rbac:admin", "offline_access"},
		"code_challenge_methods_supported":                 []string{"S256"},
		"token_endpoint_auth_methods_supported":            []string{"private_key_jwt", "tls_client_auth"},
		"token_endpoint_auth_signing_alg_values_supported": []string{"PS256", "ES256", "EdDSA"},
		"id_token_signing_alg_values_supported":            []string{"PS256", "ES256", "EdDSA"},
		"tls_client_certificate_bound_access_tokens":       true,
		"dpop_signing_alg_values_supported":                []string{"ES256", "EdDSA"},
		"require_pushed_authorization_requests":            true,
		"request_uri_parameter_supported":                  true,
		"request_parameter_supported":                      false,
		"subject_types_supported":                          []string{"public"},
		"claims_supported":                                 []string{"sub", "uid", "acr", "amr", "auth_time", "sid", "name"},
		"acr_values_supported":                             []string{"aal1", "aal2"},
		"mfa_mode":                                         s.cfg.MFAMode,
		"step_up_acr":                                      stepUpACR(s.cfg.MFAMode),
		"backchannel_logout_supported":                     true,
		"backchannel_logout_session_supported":             false,
	}
	jsonBytes(w, http.StatusOK, meta)
}

func (s *Server) jwks(w http.ResponseWriter, _ *http.Request) {
	jsonBytes(w, http.StatusOK, map[string]any{"keys": s.keys.jwks})
}

// ---------------------------------------------------------------------------
// PAR (RFC 9126) — the only accepted entry to authorization
// ---------------------------------------------------------------------------

func (s *Server) par(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		oauthErr(w, 405, "invalid_request", "POST only")
		return
	}
	if err := r.ParseForm(); err != nil {
		oauthErr(w, 400, "invalid_request", "bad form")
		return
	}
	if ct := r.Header.Get("Content-Type"); ct != "" && !strings.HasPrefix(ct, "application/x-www-form-urlencoded") {
		oauthErr(w, 400, "invalid_request", "PAR body must be form-encoded")
		return
	}
	cl, authn, err := s.clientAuth(r)
	if err != nil {
		oauthErr(w, 401, "invalid_client", err.Error())
		return
	}
	formClientID := r.FormValue("client_id")
	if formClientID != cl.ID {
		oauthErr(w, 401, "invalid_client", "client_id mismatch")
		return
	}
	if r.FormValue("request") != "" {
		oauthErr(w, 400, "request_not_supported", "signed JAR request objects are out of scope (FAPI 2.0 Message Signing)")
		return
	}
	if rt := r.FormValue("response_type"); rt != "code" {
		oauthErr(w, 400, "unsupported_response_type", "FAPI 2.0 requires response_type=code")
		return
	}
	if r.FormValue("code_challenge") == "" {
		oauthErr(w, 400, "invalid_request", "PKCE code_challenge required")
		return
	}
	if m := r.FormValue("code_challenge_method"); m != "S256" {
		oauthErr(w, 400, "invalid_request", "only PKCE S256 is permitted")
		return
	}
	redirect := r.FormValue("redirect_uri")
	okURI := false
	for _, u := range cl.RedirectURIs {
		if u == redirect {
			okURI = true
			break
		}
	}
	if !okURI {
		oauthErr(w, 400, "invalid_request", "redirect_uri not registered")
		return
	}
	scopes := strings.Fields(r.FormValue("scope"))
	if len(scopes) == 0 {
		oauthErr(w, 400, "invalid_request", "scope required")
		return
	}
	allowed := map[string]bool{}
	for _, sc := range cl.Scopes {
		allowed[sc] = true
	}
	for _, sc := range scopes {
		if !allowed[sc] {
			oauthErr(w, 400, "invalid_scope", "scope not allowed for client: "+sc)
			return
		}
	}
	params := map[string]any{
		"response_type":          "code",
		"redirect_uri":           redirect,
		"scope":                  strings.Join(scopes, " "),
		"code_challenge":         r.FormValue("code_challenge"),
		"code_challenge_method":  "S256",
		"state":                  r.FormValue("state"),
		"nonce":                  r.FormValue("nonce"),
		"client_auth_method":     authn.method,
		"client_cert_thumbprint": authn.certThumb,
	}
	pj, _ := json.Marshal(params)
	requestURI := "urn:ietf:params:oauth:request_uri:" + jwtx.RandB64(24)
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	_, err = s.pool.Exec(ctx, `INSERT INTO pars (request_uri, client_id, params, expires_at) VALUES ($1,$2,$3,$4)`,
		requestURI, cl.ID, pj, time.Now().Add(s.cfg.ParTTL))
	if err != nil {
		oauthErr(w, 500, "server_error", "storage failure")
		return
	}
	jsonBytes(w, http.StatusCreated, map[string]any{"request_uri": requestURI, "expires_in": int(s.cfg.ParTTL.Seconds())})
}

// ---------------------------------------------------------------------------
// Authorize — browser entrypoint; PAR is mandatory
// ---------------------------------------------------------------------------

type parRow struct {
	requestURI string
	clientID   string
	params     map[string]any
	expiresAt  time.Time
}

func (s *Server) loadPAR(r *http.Request, uri string) (*parRow, bool) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var clientID string
	var paramsJSON []byte
	var exp time.Time
	err := s.pool.QueryRow(ctx, `SELECT client_id, params, expires_at FROM pars WHERE request_uri=$1 AND expires_at > now()`, uri).
		Scan(&clientID, &paramsJSON, &exp)
	if err != nil {
		return nil, false
	}
	p := map[string]any{}
	_ = json.Unmarshal(paramsJSON, &p)
	return &parRow{requestURI: uri, clientID: clientID, params: p, expiresAt: exp}, true
}

func (s *Server) deletePAR(r *http.Request, uri string) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	_, _ = s.pool.Exec(ctx, `DELETE FROM pars WHERE request_uri=$1`, uri)
}

func parStr(p map[string]any, key string) string {
	v, _ := p[key].(string)
	return v
}

func (s *Server) authorize(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		errorPage(w, 405, "Method not allowed", "Use GET")
		return
	}
	q := r.URL.Query()
	// PAR bypass is rejected outright: only client_id + request_uri accepted.
	for k := range q {
		switch k {
		case "client_id", "request_uri":
		default:
			errorPage(w, 400, "Invalid authorization request",
				"FAPI 2.0 requires all authorization parameters to be pushed via /par. Unexpected parameter: "+k)
			return
		}
	}
	uri := q.Get("request_uri")
	clientID := q.Get("client_id")
	if uri == "" || clientID == "" {
		errorPage(w, 400, "Invalid authorization request", "request_uri (from /par) and client_id are required")
		return
	}
	p, ok := s.loadPAR(r, uri)
	if !ok || p.clientID != clientID {
		errorPage(w, 400, "Invalid or expired request_uri", "Please restart the authorization flow via /par.")
		return
	}
	if ss, ok := s.currentSession(r); ok {
		s.afterLogin(w, r, p, ss)
		return
	}
	s.renderLogin(w, r, p, http.StatusOK, "")
}

// ---------------------------------------------------------------------------
// Token endpoint
// ---------------------------------------------------------------------------

func (s *Server) token(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		oauthErr(w, 405, "invalid_request", "POST only")
		return
	}
	if err := r.ParseForm(); err != nil {
		oauthErr(w, 400, "invalid_request", "bad form")
		return
	}
	cl, authn, err := s.clientAuth(r)
	if err != nil {
		oauthErr(w, 401, "invalid_client", err.Error())
		return
	}
	switch r.FormValue("grant_type") {
	case "authorization_code":
		s.grantAuthCode(w, r, cl, authn)
	case "refresh_token":
		s.grantRefresh(w, r, cl, authn)
	default:
		oauthErr(w, 400, "unsupported_grant_type", "only authorization_code and refresh_token")
	}
}

// senderBinding decides the proof-of-possession binding for this token
// request. Client authentication and sender-constraining are deliberately
// independent controls.
func (s *Server) senderBinding(r *http.Request, authn clientAuthn) (cnf map[string]any, binding string, err error) {
	if proof, ok := dpop.FromHeader(r.Header); ok {
		jkt, jti, derr := dpop.Verify(proof, dpop.Options{
			HTM: "POST", HTU: s.cfg.Issuer + "/token",
			MaxAge: 5 * time.Minute, Now: time.Now(),
		})
		if derr != nil {
			return nil, "", derr
		}
		if !s.reserveJTI("dpop:"+jti, 10*time.Minute) {
			return nil, "", errors.New("dpop: jti replay")
		}
		return map[string]any{"jkt": jkt}, "DPoP", nil
	}
	if thumb := peerCertThumb(r); thumb != "" {
		return map[string]any{"x5t#S256": thumb}, "Bearer", nil
	}
	// FAPI 2.0: all access tokens must be sender-constrained.
	return nil, "", errors.New("FAPI 2.0 requires sender-constrained tokens: present a DPoP proof or client certificate (binding is independent of client authentication)")
}

// tokenSubject is the account behind a code or refresh token, re-checked at every issue:
// a disabled, deleted or reset account gets no new tokens (P1.2, P1.6).
func (s *Server) tokenSubject(ctx context.Context, sub string, issuedAfter time.Time) (*Account, error) {
	a, err := s.accountBySubject(ctx, sub)
	if err != nil {
		return nil, errors.New("account not found")
	}
	if a.State != StateActive {
		return nil, errors.New("account is not active")
	}
	// auth_time and iat have second precision; compare at that precision
	if !issuedAfter.IsZero() && issuedAfter.Before(a.TokensValidAfter.Truncate(time.Second)) {
		return nil, errors.New("sessions of this account were revoked")
	}
	return a, nil
}

// userClaims are the identity claims of access and ID tokens (P1.7): sub is the account
// subject, uid the core user id; acr/amr/auth_time describe the login; sid the IdP session.
func userClaims(a *Account, amr string, authTime int64, sid string) map[string]any {
	return map[string]any{
		"sub": a.Subject, "uid": a.UID, "acr": assuranceFor(amr), "amr": strings.Split(amr, " "),
		"auth_time": authTime, "sid": sid,
	}
}

func (s *Server) grantAuthCode(w http.ResponseWriter, r *http.Request, cl *clients.Client, authn clientAuthn) {
	code := r.FormValue("code")
	verifier := r.FormValue("code_verifier")
	redirect := r.FormValue("redirect_uri")
	if code == "" || verifier == "" || redirect == "" {
		oauthErr(w, 400, "invalid_request", "code, code_verifier and redirect_uri required")
		return
	}
	cnf, binding, err := s.senderBinding(r, authn)
	if err != nil {
		oauthErr(w, 400, "invalid_dpop_proof", err.Error())
		return
	}
	if !jwtx.ValidVerifier(verifier) {
		oauthErr(w, 400, "invalid_grant", "malformed PKCE verifier")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var clientID, redirectStored, scope, nonce, challenge, sid, amr string
	var authTime int64
	var exp time.Time
	err = s.pool.QueryRow(ctx, `SELECT client_id, redirect_uri, scope, nonce, code_challenge, session_id, amr, auth_time, expires_at
		FROM auth_codes WHERE code_hash=$1 AND used=FALSE`, codeSum(code)).
		Scan(&clientID, &redirectStored, &scope, &nonce, &challenge, &sid, &amr, &authTime, &exp)
	if err != nil {
		oauthErr(w, 400, "invalid_grant", "unknown or already used code")
		return
	}
	if s.cfg.MFAMode == "enforce" && assuranceFor(amr) != "aal2" {
		oauthErr(w, 400, "invalid_grant", "second factor required")
		return
	}
	if clientID != cl.ID {
		oauthErr(w, 400, "invalid_grant", "code was issued to another client")
		return
	}
	if time.Now().After(exp) {
		oauthErr(w, 400, "invalid_grant", "authorization code expired (single use, <= 60s lifetime)")
		return
	}
	if redirectStored != redirect {
		oauthErr(w, 400, "invalid_grant", "redirect_uri mismatch")
		return
	}
	if jwtx.PKCES256(verifier) != challenge {
		oauthErr(w, 400, "invalid_grant", "PKCE verification failed")
		return
	}
	var sub string
	err = s.pool.QueryRow(ctx, `UPDATE auth_codes SET used=TRUE WHERE code_hash=$1 AND used=FALSE RETURNING sub`,
		codeSum(code)).Scan(&sub)
	if err != nil {
		oauthErr(w, 400, "invalid_grant", "code already redeemed (single use)")
		return
	}
	acct, err := s.tokenSubject(ctx, sub, time.Unix(authTime, 0))
	if err != nil {
		oauthErr(w, 400, "invalid_grant", err.Error())
		return
	}
	ttl := ttlFor(cl)
	now := time.Now()
	accessClaims := userClaims(acct, amr, authTime, sid)
	for k, v := range map[string]any{
		"iss": s.cfg.Issuer, "aud": s.cfg.Issuer, "client_id": cl.ID, "scope": scope,
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(time.Duration(ttl) * time.Second).Unix(),
		"jti": jwtx.RandB64(16), "cnf": cnf,
	} {
		accessClaims[k] = v
	}
	entry := s.keys.byAlg[cl.TokenAlg]
	at, err := jwtx.SignJWS(cl.TokenAlg, "at+jwt", entry.kid, accessClaims, entry.signer)
	if err != nil {
		oauthErr(w, 500, "server_error", "signing failure")
		return
	}
	resp := map[string]any{"access_token": at, "token_type": binding, "expires_in": ttl, "scope": scope}
	// refresh token: no routine rotation (FAPI 2.0 Final); bound to the same key.
	if hasScope(scope, "offline_access") && cl.RefreshTTLSeconds > 0 {
		rt := jwtx.RandB64(32)
		rtj, _ := json.Marshal(cnf)
		_, err = s.pool.Exec(ctx, `INSERT INTO refresh_tokens (token_hash, token, client_id, sub, scope, cnf, expires_at, sid, amr, auth_time)
			VALUES ($1,'',$2,$3,$4,$5::jsonb,$6,$7,$8,$9)`,
			refreshHash(rt), cl.ID, sub, scope, string(rtj),
			now.Add(time.Duration(cl.RefreshTTLSeconds)*time.Second), sid, amr, authTime)
		if err != nil {
			oauthErr(w, 500, "server_error", "token storage failure")
			return
		}
		resp["refresh_token"] = rt
	}
	if hasScope(scope, "openid") {
		idClaims := userClaims(acct, amr, authTime, sid)
		for k, v := range map[string]any{
			"iss": s.cfg.Issuer, "aud": cl.ID, "azp": cl.ID,
			"iat": now.Unix(), "exp": now.Add(300 * time.Second).Unix(), "name": acct.DisplayName,
		} {
			idClaims[k] = v
		}
		if nonce != "" {
			idClaims["nonce"] = nonce
		}
		ids, err := jwtx.SignJWS(cl.TokenAlg, "id+jwt", entry.kid, idClaims, entry.signer)
		if err == nil {
			resp["id_token"] = ids
		}
	}
	jsonBytes(w, http.StatusOK, resp)
}

func (s *Server) grantRefresh(w http.ResponseWriter, r *http.Request, cl *clients.Client, authn clientAuthn) {
	rt := r.FormValue("refresh_token")
	if rt == "" {
		oauthErr(w, 400, "invalid_request", "refresh_token required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var sub, scope, sid, amr string
	var authTime int64
	var cnfJSON []byte
	var exp time.Time
	var revoked bool
	err := s.pool.QueryRow(ctx, `SELECT sub, scope, cnf, expires_at, revoked, sid, amr, auth_time FROM refresh_tokens
		WHERE token_hash=$1 AND client_id=$2`, refreshHash(rt), cl.ID).
		Scan(&sub, &scope, &cnfJSON, &exp, &revoked, &sid, &amr, &authTime)
	if err != nil {
		oauthErr(w, 400, "invalid_grant", "unknown refresh token")
		return
	}
	if revoked || time.Now().After(exp) {
		oauthErr(w, 400, "invalid_grant", "refresh token expired or revoked")
		return
	}
	if s.cfg.MFAMode == "enforce" && assuranceFor(amr) != "aal2" {
		oauthErr(w, 400, "invalid_grant", "second factor required")
		return
	}
	acct, err := s.tokenSubject(ctx, sub, time.Unix(authTime, 0))
	if err != nil {
		oauthErr(w, 400, "invalid_grant", err.Error())
		return
	}
	stored := map[string]any{}
	_ = json.Unmarshal(cnfJSON, &stored)
	// The refreshed token must be bound to the SAME key (no rotation).
	if jkt, ok := stored["jkt"].(string); ok {
		proof, has := dpop.FromHeader(r.Header)
		if !has {
			oauthErr(w, 400, "invalid_dpop_proof", "DPoP proof required for refresh")
			return
		}
		newJKT, jti, derr := dpop.Verify(proof, dpop.Options{HTM: "POST", HTU: s.cfg.Issuer + "/token", MaxAge: 5 * time.Minute, Now: time.Now()})
		if derr != nil || newJKT != jkt {
			oauthErr(w, 401, "invalid_dpop_proof", "proof key differs from the bound key")
			return
		}
		if !s.reserveJTI("dpop:"+jti, 10*time.Minute) {
			oauthErr(w, 400, "invalid_dpop_proof", "dpop: jti replay")
			return
		}
	} else if xt, ok := stored["x5t#S256"].(string); ok {
		if peerCertThumb(r) != xt {
			oauthErr(w, 401, "invalid_client", "client certificate differs from the bound certificate")
			return
		}
	}
	entry := s.keys.byAlg[cl.TokenAlg]
	now := time.Now()
	claims := userClaims(acct, amr, authTime, sid)
	for k, v := range map[string]any{
		"iss": s.cfg.Issuer, "aud": s.cfg.Issuer, "client_id": cl.ID, "scope": scope,
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(time.Duration(ttlFor(cl)) * time.Second).Unix(),
		"jti": jwtx.RandB64(16), "cnf": stored,
	} {
		claims[k] = v
	}
	at, err := jwtx.SignJWS(cl.TokenAlg, "at+jwt", entry.kid, claims, entry.signer)
	if err != nil {
		oauthErr(w, 500, "server_error", "signing failure")
		return
	}
	// Deliberately NOT rotating: same refresh token continues to be valid
	// until its fixed expiry (FAPI 2.0 Security Profile Final).
	jsonBytes(w, http.StatusOK, map[string]any{
		"access_token": at, "token_type": tokenTypeFor(stored), "expires_in": ttlFor(cl),
		"refresh_token": rt, "scope": scope,
	})
}

func ttlFor(cl *clients.Client) int {
	if cl.TokenTTLSeconds > 0 {
		return cl.TokenTTLSeconds
	}
	return 300
}

func tokenTypeFor(cnf map[string]any) string {
	if _, ok := cnf["jkt"]; ok {
		return "DPoP"
	}
	return "Bearer"
}

func hasScope(scope, want string) bool {
	for _, s := range strings.Fields(scope) {
		if s == want {
			return true
		}
	}
	return false
}

// refreshHash: refresh tokens are stored only as a SHA-256 hash.
func refreshHash(t string) string {
	sum := sha256.Sum256([]byte("rt:" + t))
	return jwtx.B64UEnc(sum[:])
}

// ---------------------------------------------------------------------------
// Introspection (RFC 7662) — gateway-facing, client-authenticated
// ---------------------------------------------------------------------------

func (s *Server) introspect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		oauthErr(w, 405, "invalid_request", "POST only")
		return
	}
	if _, _, err := s.clientAuth(r); err != nil {
		oauthErr(w, 401, "invalid_client", err.Error())
		return
	}
	token := r.FormValue("token")
	if token == "" {
		oauthErr(w, 400, "invalid_request", "token required")
		return
	}
	inactive := map[string]any{"active": false}
	// Access tokens are JWTs; refresh tokens are opaque.
	_, claims, err := jwtx.VerifyJWS(token, jwtx.AllowedJWSAlgs(), s.keyringKeyFn)
	if err == nil {
		if s.cfg.MFAMode == "enforce" && jwtx.ClaimString(claims, "acr") != "aal2" {
			jsonBytes(w, 200, inactive)
			return
		}
		jti := jwtx.ClaimString(claims, "jti")
		exp, _ := jwtx.ClaimInt64(claims, "exp")
		if jti == "" || time.Now().Unix() >= exp {
			jsonBytes(w, 200, inactive)
			return
		}
		if s.jtiRevoked(jti) {
			jsonBytes(w, 200, inactive)
			return
		}
		iat, _ := jwtx.ClaimInt64(claims, "iat")
		sub := jwtx.ClaimString(claims, "sub")
		if acct, err := s.tokenSubject(r.Context(), sub, time.Unix(iat, 0)); err != nil || acct.UID != jwtx.ClaimString(claims, "uid") {
			jsonBytes(w, 200, inactive) // disabled, deleted or revoked since issue
			return
		}
		resp := map[string]any{
			"active": true, "jti": jti,
			"scope":     jwtx.ClaimString(claims, "scope"),
			"sub":       sub,
			"uid":       jwtx.ClaimString(claims, "uid"),
			"client_id": jwtx.ClaimString(claims, "client_id"),
			"iss":       jwtx.ClaimString(claims, "iss"),
			"iat":       claims["iat"], "exp": claims["exp"],
			"cnf": claims["cnf"],
			"acr": claims["acr"], "amr": claims["amr"], "auth_time": claims["auth_time"], "sid": claims["sid"],
		}
		jsonBytes(w, 200, resp)
		return
	}
	// opaque refresh token?
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var scope, sub string
	var expTime time.Time
	var revoked bool
	err = s.pool.QueryRow(ctx, `SELECT scope, sub, expires_at, revoked FROM refresh_tokens
		WHERE token_hash=$1`, refreshHash(token)).Scan(&scope, &sub, &expTime, &revoked)
	if err != nil || revoked || time.Now().After(expTime) {
		jsonBytes(w, 200, inactive)
		return
	}
	jsonBytes(w, 200, map[string]any{
		"active": true, "token_type_hint": "refresh_token", "scope": scope, "sub": sub,
		"exp": expTime.Unix(),
	})
}

func (s *Server) jtiRevoked(jti string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var one int
	return s.pool.QueryRow(ctx, `SELECT 1 FROM revoked_jti WHERE jti=$1`, jti).Scan(&one) == nil
}

func (s *Server) keyringKeyFn(hdr map[string]any) (crypto.PublicKey, error) {
	kid, _ := hdr["kid"].(string)
	for alg, e := range s.keys.byAlg {
		_ = alg
		if e.kid == kid {
			return e.signer.Public(), nil
		}
	}
	return nil, errors.New("unknown kid")
}

// ---------------------------------------------------------------------------
// Revocation (RFC 7009)
// ---------------------------------------------------------------------------

func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		oauthErr(w, 405, "invalid_request", "POST only")
		return
	}
	if _, _, err := s.clientAuth(r); err != nil {
		oauthErr(w, 401, "invalid_client", err.Error())
		return
	}
	token := r.FormValue("token")
	if token == "" {
		w.WriteHeader(http.StatusOK)
		return
	}
	if _, claims, err := jwtx.VerifyJWS(token, jwtx.AllowedJWSAlgs(), s.keyringKeyFn); err == nil {
		jti := jwtx.ClaimString(claims, "jti")
		exp, _ := jwtx.ClaimInt64(claims, "exp")
		if jti != "" {
			expT := time.Unix(exp, 0)
			if time.Until(expT) <= 0 {
				expT = time.Now().Add(time.Minute)
			}
			ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
			defer cancel()
			_, _ = s.pool.Exec(ctx, `INSERT INTO revoked_jti (jti, expires_at) VALUES ($1,$2)
				ON CONFLICT (jti) DO NOTHING`, jti, expT)
		}
	} else {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		_, _ = s.pool.Exec(ctx, `UPDATE refresh_tokens SET revoked=TRUE WHERE token_hash=$1`, refreshHash(token))
	}
	w.WriteHeader(http.StatusOK)
}

// ---------------------------------------------------------------------------
// Logout
// ---------------------------------------------------------------------------

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil && c.Value != "" {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		_, _ = s.pool.Exec(ctx, `DELETE FROM sessions WHERE session_id=$1`, c.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})
	http.Redirect(w, r, "/logged-out", http.StatusFound)
	if r.URL.Query().Get("page") == "1" { // pragma: keep handler simple
		// no-op
	}
	w.Write([]byte(""))
}

// ---------------------------------------------------------------------------
// Seeding + sweeper
// ---------------------------------------------------------------------------

// SeedUsers creates demo credential accounts (dev/sandbox only; FromEnv refuses them
// elsewhere). Seeded accounts are ACTIVE with a stable subject; re-seeding resets the password.
// Field 5 (optional) is a phone, field 6 an MFA profile, e.g. "mira:pw:openid:mira:+26481000001:MERCHANT_OTP".
func (s *Server) SeedUsers(ctx context.Context) error {
	for _, raw := range strings.Split(s.cfg.SeedUsers, "|") {
		f := strings.Split(strings.TrimSpace(raw), ":")
		us := parseSeedUsers(raw)
		if len(us) == 0 {
			continue
		}
		u := us[0]
		phone, profile := "", ""
		if len(f) >= 5 {
			phone = normalizePhone(f[4])
		}
		if len(f) >= 6 && mfaProfiles[f[5]] {
			profile = f[5]
		}
		hash, err := bcrypt.GenerateFromPassword([]byte(u.Password), bcrypt.DefaultCost-2)
		if err != nil {
			return err
		}
		if _, err := s.pool.Exec(ctx, `INSERT INTO accounts (subject, uid, login_name, phone, display_name, password_hash, state,
				password_changed_at, mfa_profile, seeded)
			VALUES ($1,$2,$3,NULLIF($4,''),$3,$5,'ACTIVE',now(),NULLIF($6,''),TRUE)
			ON CONFLICT (uid) DO UPDATE SET password_hash=EXCLUDED.password_hash, phone=EXCLUDED.phone,
				mfa_profile=EXCLUDED.mfa_profile, state='ACTIVE', failed_attempts=0, lock_count=0, locked_until=NULL`,
			seedSubject(u.Name), u.UID, u.Name, phone, string(hash), profile); err != nil {
			return fmt.Errorf("seed %s: %w", u.Name, err)
		}
	}
	return nil
}

func (s *Server) SweepLoop(ctx context.Context) {
	t := time.NewTicker(2 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			for _, stmt := range []string{
				`DELETE FROM pars WHERE expires_at < now() - interval '10 minutes'`,
				`DELETE FROM auth_codes WHERE expires_at < now() - interval '10 minutes'`,
				`DELETE FROM sessions WHERE expires_at < now() - interval '1 hour'`,
				`DELETE FROM jti_seen WHERE expires_at < now() - interval '1 hour'`,
				`DELETE FROM revoked_jti WHERE expires_at < now() - interval '1 hour'`,
				`DELETE FROM refresh_tokens WHERE expires_at < now() - interval '1 day'`,
				`DELETE FROM otp_challenges WHERE expires_at < now() - interval '1 day'`,
				`DELETE FROM mfa_login WHERE expires_at < now() - interval '1 day'`,
				`DELETE FROM account_links WHERE expires_at < now() - interval '30 days'`,
				`DELETE FROM outbound WHERE delivered_at < now() - interval '30 days'`,
				`DELETE FROM sim_outbox WHERE created_at < now() - interval '30 days'`,
			} {
				_, _ = s.pool.Exec(ctx, stmt)
			}
		}
	}
}

func remoteIP(r *http.Request) string {
	host := r.RemoteAddr
	if i := strings.LastIndex(host, ":"); i > 0 {
		host = host[:i]
	}
	return host
}
