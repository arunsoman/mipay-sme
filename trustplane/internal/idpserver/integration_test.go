package idpserver

// Integration tests for the credential plugin (idp-rbac-cutover-plan.md P1). They need a
// scratch Postgres: TEST_IDP_DSN=postgres://.../idp go test ./internal/idpserver/
// Every test starts from empty tables.

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base32"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"

	"trustplane/internal/clients"
	"trustplane/internal/dbx"
	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
)

type harness struct {
	t      *testing.T
	s      *Server
	h      http.Handler
	client *clients.Client
	ckey   crypto.Signer
}

func writeKey(t *testing.T, dir, name string, key any) {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, name), pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
}

func newHarness(t *testing.T, mut func(*Config)) *harness {
	dsn := os.Getenv("TEST_IDP_DSN")
	if dsn == "" {
		t.Skip("TEST_IDP_DSN not set (needs a scratch Postgres)")
	}
	ctx := context.Background()
	pool, err := dbx.Pool(ctx, dsn, 8)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	for _, tbl := range []string{"mfa_login", "mfa_factors", "otp_challenges", "account_links", "outbound", "sim_outbox", "sessions", "pars", "auth_codes",
		"jti_seen", "revoked_jti", "refresh_tokens", "accounts"} {
		_, _ = pool.Exec(ctx, "DROP TABLE IF EXISTS "+tbl+" CASCADE")
	}
	if err := dbx.Migrate(ctx, pool, DDL); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	ecKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	_, edKey, _ := ed25519.GenerateKey(rand.Reader)
	writeKey(t, dir, "ps256.pem", rsaKey)
	writeKey(t, dir, "es256.pem", ecKey)
	writeKey(t, dir, "eddsa.pem", edKey)
	keys, err := LoadKeyring(dir)
	if err != nil {
		t.Fatal(err)
	}
	ckey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	jwk, _ := jwtx.PublicJWK(ckey.Public())
	jwk["kid"] = "web"
	consent := true
	cl := &clients.Client{ID: "web", AuthMethod: clients.AuthPrivateKeyJWT, TokenAlg: jwtx.AlgES256,
		RedirectURIs: []string{"https://app.test/cb"}, Scopes: []string{"openid", "offline_access"},
		JWTKeys: []map[string]any{jwk}, TokenTTLSeconds: 300, RefreshTTLSeconds: 3600, RequireConsent: &consent,
		BackchannelLogoutURI: "https://gw.test/auth/backchannel-logout"}
	reg := &clients.Registry{List: []*clients.Client{cl}, ByID: map[string]*clients.Client{"web": cl}}
	cfg := Config{Issuer: "https://idp.test", Environment: "dev", CoreCN: "sme-mipay-mesh",
		ParTTL: 120 * time.Second, CodeTTL: 30 * time.Second, SessionTTL: 15 * time.Minute,
		LinkTTL: 72 * time.Hour, ResetLinkTTL: time.Hour, LockAfter: 3, LockBase: time.Minute, LockMax: time.Hour,
		OTPTTL: 5 * time.Minute, OTPMaxAttempts: 3, OTPResendGap: 60 * time.Second, OTPMaxPerHour: 5,
		ResetCoolOffSMS: 24 * time.Hour, SandboxChannels: []string{"sms", "email"}, DevOTP: "246810",
		InternalCA: filepath.Join(dir, "none.pem")}
	if mut != nil {
		mut(&cfg)
	}
	s := NewServer(cfg, pool, reg, keys)
	return &harness{t: t, s: s, h: s.Handler(), client: cl, ckey: ckey}
}

// ---- request helpers ------------------------------------------------------

func peer(cn string) *tls.ConnectionState {
	return &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Subject: pkix.Name{CommonName: cn}}}}
}

func (h *harness) do(method, target string, body io.Reader, ctype, cn string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, target, body)
	if ctype != "" {
		r.Header.Set("Content-Type", ctype)
	}
	if cn != "" {
		r.TLS = peer(cn)
	}
	for _, c := range cookies {
		r.AddCookie(c)
	}
	w := httptest.NewRecorder()
	h.h.ServeHTTP(w, r)
	return w
}

func (h *harness) api(method, path, body string) (int, map[string]any) {
	w := h.do(method, path, strings.NewReader(body), "application/json", "sme-mipay-mesh")
	out := map[string]any{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

func (h *harness) form(path string, v url.Values, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	return h.do("POST", path, strings.NewReader(v.Encode()), "application/x-www-form-urlencoded", "", cookies...)
}

func tokenOf(link string) string {
	u, _ := url.Parse(link)
	return u.Query().Get("token")
}

func (h *harness) enrol(uid, login, phone string) (string, string) {
	code, out := h.api("POST", "/accounts", `{"uid":"`+uid+`","login_name":"`+login+`","phone":"`+phone+`","display_name":"`+login+`"}`)
	if code != 201 && code != 200 {
		h.t.Fatalf("enrol %s: %d %v", uid, code, out)
	}
	return out["subject"].(string), tokenOf(out["link"].(string))
}

func (h *harness) setPassword(token, pw string) *httptest.ResponseRecorder {
	return h.form("/account/setup", url.Values{"token": {token}, "password": {pw}, "confirm": {pw}})
}

// par inserts an authorization request directly (the PAR endpoint itself is covered by the e2e suite).
func (h *harness) par() string {
	uri := "urn:ietf:params:oauth:request_uri:" + jwtx.RandB64(12)
	verifier := "v" + strings.Repeat("x", 50)
	params, _ := json.Marshal(map[string]any{"redirect_uri": "https://app.test/cb", "scope": "openid offline_access",
		"code_challenge": jwtx.PKCES256(verifier), "code_challenge_method": "S256", "state": "st", "nonce": "n1"})
	if _, err := h.s.pool.Exec(context.Background(), `INSERT INTO pars (request_uri, client_id, params, expires_at) VALUES ($1,'web',$2,now()+interval '2 minutes')`,
		uri, params); err != nil {
		h.t.Fatal(err)
	}
	return uri
}

const verifier = "v" + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

func (h *harness) login(user, pw string) (*httptest.ResponseRecorder, string) {
	uri := h.par()
	return h.form("/login", url.Values{"request_uri": {uri}, "username": {user}, "password": {pw}}), uri
}

// loginToTokens: password login -> consent -> code -> token endpoint (private_key_jwt + DPoP).
func (h *harness) loginToTokens(user, pw string) map[string]any {
	w, uri := h.login(user, pw)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Authorize application") {
		h.t.Fatalf("login %s: %d %s", user, w.Code, w.Body.String())
	}
	csrf := regexp.MustCompile(`name="csrf" value="([^"]+)"`).FindStringSubmatch(w.Body.String())[1]
	var sess *http.Cookie
	for _, c := range w.Result().Cookies() {
		if c.Name == sessionCookie {
			sess = c
		}
	}
	w = h.form("/consent", url.Values{"request_uri": {uri}, "csrf": {csrf}, "decision": {"allow"}}, sess)
	if w.Code != 302 {
		h.t.Fatalf("consent: %d %s", w.Code, w.Body.String())
	}
	loc, _ := url.Parse(w.Header().Get("Location"))
	return h.redeem(loc.Query().Get("code"))
}

func (h *harness) assertion() string {
	now := time.Now().Unix()
	a, _ := jwtx.SignJWS(jwtx.AlgES256, "JWT", "web", map[string]any{"iss": "web", "sub": "web", "aud": "https://idp.test",
		"iat": now, "exp": now + 60, "jti": jwtx.RandB64(12)}, h.ckey)
	return a
}

func (h *harness) tokenReq(form url.Values, dpopKey crypto.Signer, dpopJWK map[string]any) map[string]any {
	form.Set("client_id", "web")
	form.Set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
	form.Set("client_assertion", h.assertion())
	r := httptest.NewRequest("POST", "/token", strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	proof, _ := dpop.SignProof(dpopKey, dpopJWK, "POST", "https://idp.test/token", "")
	r.Header.Set("DPoP", proof)
	w := httptest.NewRecorder()
	h.h.ServeHTTP(w, r)
	out := map[string]any{"_status": float64(w.Code)}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return out
}

var testDPoPKey, testDPoPJWK, _ = dpop.GenerateES256()

func (h *harness) redeem(code string) map[string]any {
	return h.tokenReq(url.Values{"grant_type": {"authorization_code"}, "code": {code}, "code_verifier": {verifier},
		"redirect_uri": {"https://app.test/cb"}}, testDPoPKey, testDPoPJWK)
}

func claimsOf(t *testing.T, tok string) map[string]any {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatalf("not a JWT: %q", tok)
	}
	b, _ := jwtx.B64UDec(parts[1])
	m := map[string]any{}
	_ = json.Unmarshal(b, &m)
	return m
}

func (h *harness) outbound(target string) []map[string]any {
	rows, err := h.s.pool.Query(context.Background(), `SELECT payload FROM outbound WHERE target=$1 ORDER BY created_at`, target)
	if err != nil {
		h.t.Fatal(err)
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var p string
		_ = rows.Scan(&p)
		m := map[string]any{}
		_ = json.Unmarshal([]byte(p), &m)
		out = append(out, m)
	}
	return out
}

func (h *harness) eventTypes() []string {
	var out []string
	for _, e := range h.outbound("core") {
		out = append(out, e["type"].(string))
	}
	return out
}

// ---- tests ----------------------------------------------------------------

// Credential import (cut-over P3.10): the core's IdentityBackfill enrols existing users with
// their bcrypt hash copied over, so the account is ACTIVE straight away instead of ENROLLING.
func TestAccountsHashImport(t *testing.T) {
	h := newHarness(t, nil)
	importHash, err := bcrypt.GenerateFromPassword([]byte("a-good-long-password"), 10)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	hash := string(importHash)

	body := `"uid":"u-imp","login_name":"imelda","phone":"+264811234000","display_name":"Imelda","password_hash":"` + hash + `"`
	// malformed hashes never reach the store
	if code, _ := h.api("POST", "/accounts", `{"uid":"u-imp2","login_name":"imelda2","password_hash":"hunter2"}`); code != 400 {
		t.Fatalf("non-bcrypt import hash accepted: %d", code)
	}
	if code, _ := h.api("POST", "/accounts", `{"uid":"u-imp2","login_name":"imelda2","password_hash":"$2y$10$"}`); code != 400 {
		t.Fatalf("truncated import hash accepted: %d", code)
	}
	code, out := h.api("POST", "/accounts", `{`+body+`}`)
	if code != 201 || out["state"] != "ACTIVE" || out["subject"] == "" || out["link"] != nil {
		t.Fatalf("import enrol: %d %v", code, out)
	}
	sub := out["subject"].(string)
	// a set credential is announced to the core like any other credential-set
	if ev := h.eventTypes(); len(ev) != 1 || ev[0] != EventCredentialSet {
		t.Fatalf("import credential-set event: %v", ev)
	}
	// the imported password signs in, and the core is told the link is not needed
	if w, _ := h.login("imelda", "a-good-long-password"); w.Code != 200 {
		t.Fatalf("login with imported hash: %d %s", w.Code, w.Body.String())
	}
	// replay of the same import is idempotent (the backfill is safe to re-run)
	code, out2 := h.api("POST", "/accounts", `{`+body+`}`)
	if code != 200 || out2["subject"] != sub || out2["state"] != "ACTIVE" || out2["link"] != nil {
		t.Fatalf("import replay: %d %v", code, out2)
	}
	// a different hash for a live account is refused, never silently replaced
	other, _ := bcrypt.GenerateFromPassword([]byte("another-good-password"), 10)
	if code, _ := h.api("POST", "/accounts", `{"uid":"u-imp","login_name":"imelda","password_hash":"` + string(other) + `"}`); code != 409 || out["state"] != "ACTIVE" {
		t.Fatalf("hash mismatch not refused: %d %v", code, out)
	}
	// an account left ENROLLING is finished by the import and the pending link dies
	_, tok := h.enrol("u-late", "late-user", "+264811234111")
	code, out3 := h.api("POST", "/accounts", `{"uid":"u-late","login_name":"late-user","password_hash":"` + hash + `"}`)
	if code != 200 || out3["state"] != "ACTIVE" || out3["link"] != nil {
		t.Fatalf("import over ENROLLING: %d %v", code, out3)
	}
	if w, _ := h.login("late-user", "a-good-long-password"); w.Code != 200 {
		t.Fatalf("activated-by-import login: %d", w.Code)
	}
	// the superseded set-up link no longer works
	if w := h.setPassword(tok, "yet-another-password"); w.Code == 200 {
		t.Fatalf("superseded enrol link still usable")
	}
	// without the hash the old link-flow is unchanged
	code, out4 := h.api("POST", "/accounts", `{"uid":"u-link","login_name":"link-user","phone":"","display_name":"LU"}`)
	if code != 201 || out4["state"] != "ENROLLING" || out4["link"] == nil {
		t.Fatalf("plain enrol changed: %d %v", code, out4)
	}
}

func TestEnrolmentAPIAcceptsOnlyTheCore(t *testing.T) {
	h := newHarness(t, nil)
	body := `{"uid":"u-1","login_name":"selma","phone":"","display_name":"Selma"}`
	if w := h.do("POST", "/accounts", strings.NewReader(body), "application/json", ""); w.Code != 403 {
		t.Fatalf("no client certificate: want 403, got %d", w.Code)
	}
	if w := h.do("POST", "/accounts", strings.NewReader(body), "application/json", "sme-gateway-mesh"); w.Code != 403 {
		t.Fatalf("gateway certificate: want 403, got %d", w.Code)
	}
	code, out := h.api("POST", "/accounts", body)
	if code != 201 || out["state"] != "ENROLLING" || !strings.HasPrefix(out["link"].(string), "https://idp.test/account/setup?token=") {
		t.Fatalf("core enrol: %d %v", code, out)
	}
	// idempotent while ENROLLING (outbox retries): same subject, a new link, the old one dead
	code2, out2 := h.api("POST", "/accounts", body)
	if code2 != 200 || out2["subject"] != out["subject"] {
		t.Fatalf("re-enrol: %d %v", code2, out2)
	}
	if w := h.setPassword(tokenOf(out["link"].(string)), "a-good-long-password"); w.Code != 400 {
		t.Fatalf("superseded link must not work: %d", w.Code)
	}
	if code, _ := h.api("POST", "/accounts", `{"uid":"u-2","login_name":"SELMA","phone":"","display_name":"x"}`); code != 409 {
		t.Fatalf("login name is unique case-insensitively: %d", code)
	}
	if code, _ := h.api("POST", "/accounts", `{"uid":"","login_name":"x1y","phone":"","display_name":"x"}`); code != 400 {
		t.Fatalf("uid required: %d", code)
	}
	if code, out := h.api("GET", "/accounts?uid=u-1", ""); code != 200 || out["login_name"] != "selma" {
		t.Fatalf("find by uid: %d %v", code, out)
	}
}

func TestEnrolmentRetryKeepsDeliveredLink(t *testing.T) {
	keyFile := filepath.Join(t.TempDir(), "mfa-key")
	if err := os.WriteFile(keyFile, []byte(strings.Repeat("ab", 32)), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t, func(c *Config) { c.MFAKeyFile = keyFile })
	body := `{"uid":"u-retry","login_name":"retry-user","phone":"","display_name":"Retry User"}`
	send := func(key string) (int, map[string]any) {
		r := httptest.NewRequest("POST", "/accounts", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", key)
		r.TLS = peer("sme-mipay-mesh")
		w := httptest.NewRecorder()
		h.h.ServeHTTP(w, r)
		var out map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		return w.Code, out
	}
	firstStatus, first := send("enrol:u-retry")
	retryStatus, retry := send("enrol:u-retry")
	if firstStatus != 201 || retryStatus != 200 || first["link"] != retry["link"] {
		t.Fatalf("lost-response retry changed setup link: first=%d %v retry=%d %v", firstStatus, first, retryStatus, retry)
	}
	var count int
	if err := h.s.pool.QueryRow(context.Background(), `SELECT count(*) FROM account_links WHERE subject=$1 AND used_at IS NULL`, first["subject"]).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("want one usable setup link after retry, got %d", count)
	}
	changedStatus, changed := send("explicit-new-command")
	if changedStatus != 200 || changed["link"] == first["link"] {
		t.Fatalf("a new command must issue a new setup link: %d %v", changedStatus, changed)
	}
	if w := h.setPassword(tokenOf(first["link"].(string)), "a-good-long-password"); w.Code != 400 {
		t.Fatalf("explicitly replaced link must be refused: %d", w.Code)
	}
}

func TestSetupLinkPasswordPolicyAndCredentialSet(t *testing.T) {
	h := newHarness(t, nil)
	_, tok := h.enrol("u-10", "tomas", "+264811234567")
	if w, _ := h.login("tomas", "anything-at-all"); w.Code != 401 {
		t.Fatalf("ENROLLING account must not sign in: %d", w.Code)
	}
	if w := h.do("GET", "/account/setup?token="+tok, nil, "", ""); w.Code != 200 || !strings.Contains(w.Body.String(), "Set your password") {
		t.Fatalf("setup page: %d", w.Code)
	}
	for pw, why := range map[string]string{"short": "length", "TOMAS": "login name", "xx264811234567xx": "phone"} {
		if w := h.setPassword(tok, pw); w.Code != 400 {
			t.Fatalf("policy (%s) not enforced: %d", why, w.Code)
		}
	}
	if w := h.form("/account/setup", url.Values{"token": {tok}, "password": {"a-good-long-password"}, "confirm": {"other-password-123"}}); w.Code != 400 {
		t.Fatalf("mismatch accepted: %d", w.Code)
	}
	if w := h.setPassword(tok, "a-good-long-password"); w.Code != 200 {
		t.Fatalf("setup: %d %s", w.Code, w.Body.String())
	}
	if w := h.setPassword(tok, "another-good-password"); w.Code != 400 {
		t.Fatalf("a set-up link is single use: %d", w.Code)
	}
	if ev := h.eventTypes(); len(ev) != 1 || ev[0] != EventCredentialSet {
		t.Fatalf("events: %v", ev)
	}
	tokens := h.loginToTokens("tomas", "a-good-long-password")
	at := claimsOf(t, tokens["access_token"].(string))
	if at["uid"] != "u-10" || at["acr"] != "aal1" || at["sid"] == "" || at["auth_time"] == nil {
		t.Fatalf("access token claims: %v", at)
	}
	idt := claimsOf(t, tokens["id_token"].(string))
	if idt["uid"] != "u-10" || idt["nonce"] != "n1" {
		t.Fatalf("id token claims: %v", idt)
	}
	// sign-in by phone works too
	if w, _ := h.login("+264 81 123 4567", "a-good-long-password"); w.Code != 200 {
		t.Fatalf("login by phone: %d", w.Code)
	}
}

func TestLockoutAndEnableUnlocks(t *testing.T) {
	h := newHarness(t, nil) // LockAfter=3
	sub, tok := h.enrol("u-20", "jonas", "")
	h.setPassword(tok, "a-good-long-password")
	for i := 0; i < 3; i++ {
		if w, _ := h.login("jonas", "wrong-password-123"); w.Code != 401 {
			t.Fatalf("wrong password %d: %d", i, w.Code)
		}
	}
	if w, _ := h.login("jonas", "a-good-long-password"); w.Code != 401 {
		t.Fatalf("locked account signed in: %d", w.Code)
	}
	if ev := h.eventTypes(); ev[len(ev)-1] != EventLocked {
		t.Fatalf("LOCKED not emitted: %v", ev)
	}
	if code, out := h.api("POST", "/accounts/"+sub+"/enable", `{}`); code != 200 || out["state"] != "ACTIVE" {
		t.Fatalf("enable: %d %v", code, out)
	}
	if ev := h.eventTypes(); ev[len(ev)-1] != EventUnlocked {
		t.Fatalf("UNLOCKED not emitted: %v", ev)
	}
	if w, _ := h.login("jonas", "a-good-long-password"); w.Code != 200 {
		t.Fatalf("unlocked login: %d", w.Code)
	}
}

func TestDisableEndsEverythingAndQueuesBackchannelLogout(t *testing.T) {
	h := newHarness(t, nil)
	sub, tok := h.enrol("u-30", "ana", "")
	h.setPassword(tok, "a-good-long-password")
	tokens := h.loginToTokens("ana", "a-good-long-password")
	time.Sleep(1100 * time.Millisecond) // revocation compares at second precision
	if code, _ := h.api("POST", "/accounts/"+sub+"/disable", `{"reason":"org suspended"}`); code != 200 {
		t.Fatalf("disable: %d", code)
	}
	if w, _ := h.login("ana", "a-good-long-password"); w.Code != 401 {
		t.Fatalf("disabled account signed in: %d", w.Code)
	}
	ref := h.tokenReq(url.Values{"grant_type": {"refresh_token"}, "refresh_token": {tokens["refresh_token"].(string)}}, testDPoPKey, testDPoPJWK)
	if ref["_status"].(float64) != 400 {
		t.Fatalf("refresh after disable: %v", ref)
	}
	logout := h.outbound("logout")
	if len(logout) != 1 || logout[0]["sub"] != sub || logout[0]["client_id"] != "web" {
		t.Fatalf("back-channel logout not queued: %v", logout)
	}
	// introspection says inactive for the old access token
	form := url.Values{"token": {tokens["access_token"].(string)}, "client_id": {"web"},
		"client_assertion_type": {"urn:ietf:params:oauth:client-assertion-type:jwt-bearer"}, "client_assertion": {h.assertion()}}
	w := h.do("POST", "/introspect", strings.NewReader(form.Encode()), "application/x-www-form-urlencoded", "")
	if !strings.Contains(w.Body.String(), `"active":false`) {
		t.Fatalf("introspection after disable: %s", w.Body.String())
	}
	// enable restores sign-in
	h.api("POST", "/accounts/"+sub+"/enable", `{}`)
	if w, _ := h.login("ana", "a-good-long-password"); w.Code != 200 {
		t.Fatalf("enabled account cannot sign in: %d", w.Code)
	}
}

func TestFullResetIsAssistedRecovery(t *testing.T) {
	h := newHarness(t, nil)
	sub, tok := h.enrol("u-40", "priya", "")
	h.setPassword(tok, "a-good-long-password")
	if code, _ := h.api("POST", "/accounts/"+sub+"/reset-link", `{"scope":"LINK"}`); code != 409 {
		t.Fatalf("LINK on an ACTIVE account: %d", code)
	}
	if code, out := h.api("POST", "/accounts/"+sub+"/reset-link", `{"scope":"MFA"}`); code != 200 || out["factors_removed"].(float64) != 0 {
		t.Fatalf("MFA reset: %d %v", code, out)
	}
	code, out := h.api("POST", "/accounts/"+sub+"/reset-link", `{"scope":"FULL"}`)
	if code != 200 {
		t.Fatalf("FULL: %d %v", code, out)
	}
	if w, _ := h.login("priya", "a-good-long-password"); w.Code != 401 {
		t.Fatalf("old password survives FULL reset: %d", w.Code)
	}
	if w := h.setPassword(tokenOf(out["link"].(string)), "a-brand-new-password"); w.Code != 200 {
		t.Fatalf("recovery link: %d", w.Code)
	}
	evs := h.outbound("core")
	last := evs[len(evs)-1]
	detail := last["detail"].(map[string]any)
	if last["type"] != EventCredentialReset || detail["via"] != "assisted_recovery" {
		t.Fatalf("reset event: %v", last)
	}
	until, _ := time.Parse(time.RFC3339, detail["cooling_off_until"].(string))
	if d := time.Until(until); d < 71*time.Hour || d > 73*time.Hour {
		t.Fatalf("assisted recovery cooling-off should be 72 h, got %v", d)
	}
	if w, _ := h.login("priya", "a-brand-new-password"); w.Code != 200 {
		t.Fatalf("new password: %d", w.Code)
	}
	if code, _ := h.api("DELETE", "/accounts/"+sub, ""); code != 200 {
		t.Fatalf("delete: %d", code)
	}
	if code, _ := h.api("GET", "/accounts/"+sub, ""); code != 404 {
		t.Fatalf("deleted account still there: %d", code)
	}
}

func TestEnforcedMFATOTPWithoutComms(t *testing.T) {
	keyFile := filepath.Join(t.TempDir(), "mfa-key")
	if err := os.WriteFile(keyFile, []byte(hex.EncodeToString([]byte(strings.Repeat("k", 32)))), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t, func(c *Config) { c.MFAMode = "enforce"; c.MFAKeyFile = keyFile })
	h.s.SetComms(noComms{})
	_, link := h.enrol("u-mfa", "mfa-user", "")
	if w := h.setPassword(link, "correct-password-123"); w.Code != 200 {
		t.Fatalf("password setup: %d", w.Code)
	}
	w, uri := h.login("mfa-user", "correct-password-123")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Set up your authenticator") {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	if len(w.Result().Cookies()) != 0 {
		t.Fatal("session created before second factor")
	}
	challenge := regexp.MustCompile(`name="challenge" value="([^"]+)"`).FindStringSubmatch(w.Body.String())[1]
	encoded := regexp.MustCompile(`<code>([A-Z2-7]+)</code>`).FindStringSubmatch(w.Body.String())[1]
	secret, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	wrong := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {"000000"}})
	if wrong.Code != 401 {
		t.Fatalf("wrong code: %d", wrong.Code)
	}
	code := totpAt(secret, time.Now().Unix()/30)
	verified := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {code}})
	if verified.Code != 200 || !strings.Contains(verified.Body.String(), "Authorize application") {
		t.Fatalf("verified: %d %s", verified.Code, verified.Body.String())
	}
	if len(verified.Result().Cookies()) == 0 {
		t.Fatal("no session after second factor")
	}
	if replay := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {code}}); replay.Code != 401 {
		t.Fatalf("replayed challenge: %d", replay.Code)
	}
	second, uri2 := h.login("mfa-user", "correct-password-123")
	if second.Code != 200 || strings.Contains(second.Body.String(), "Set up your authenticator") {
		t.Fatalf("existing factor: %d %s", second.Code, second.Body.String())
	}
	challenge2 := regexp.MustCompile(`name="challenge" value="([^"]+)"`).FindStringSubmatch(second.Body.String())[1]
	if replay := h.form("/login/mfa/verify", url.Values{"request_uri": {uri2}, "challenge": {challenge2}, "code": {code}}); replay.Code != 401 {
		t.Fatalf("replayed TOTP step: %d", replay.Code)
	}
}

func TestEnforcedMFASMSAfterPassword(t *testing.T) {
	keyFile := filepath.Join(t.TempDir(), "mfa-key")
	if err := os.WriteFile(keyFile, []byte(hex.EncodeToString([]byte(strings.Repeat("k", 32)))), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t, func(c *Config) { c.MFAMode = "enforce"; c.MFAKeyFile = keyFile })
	h.s.SetComms(&sandboxComms{pool: h.s.pool, live: map[string]bool{"sms": true}})
	_, link := h.enrol("u-sms-mfa", "sms-mfa-user", "+264810000055")
	if w := h.setPassword(link, "correct-password-123"); w.Code != 200 {
		t.Fatalf("password setup: %d", w.Code)
	}
	w, uri := h.login("sms-mfa-user", "correct-password-123")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Verify your identity") {
		t.Fatalf("login: %d %s", w.Code, w.Body.String())
	}
	if len(w.Result().Cookies()) != 0 {
		t.Fatal("session issued before SMS verification")
	}
	challenge := regexp.MustCompile(`name="challenge" value="([^"]+)"`).FindStringSubmatch(w.Body.String())[1]
	var data []byte
	if err := h.s.pool.QueryRow(context.Background(), `SELECT data FROM sim_outbox WHERE event_code='auth.otp.sms' ORDER BY created_at DESC LIMIT 1`).Scan(&data); err != nil {
		t.Fatal(err)
	}
	var msg map[string]string
	if err := json.Unmarshal(data, &msg); err != nil {
		t.Fatal(err)
	}
	if wrong := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {"000000"}}); wrong.Code != 401 {
		t.Fatalf("wrong SMS: %d", wrong.Code)
	}
	verified := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {msg["code"]}})
	if verified.Code != 200 || len(verified.Result().Cookies()) == 0 {
		t.Fatalf("verified SMS: %d %s", verified.Code, verified.Body.String())
	}
	var amr string
	if err := h.s.pool.QueryRow(context.Background(), `SELECT amr FROM sessions WHERE user_id=(SELECT subject FROM accounts WHERE uid='u-sms-mfa')`).Scan(&amr); err != nil {
		t.Fatal(err)
	}
	if assuranceFor(amr) != "aal2" {
		t.Fatalf("amr=%s", amr)
	}
}

func TestEnforcedMFASMSCanSwitchToAuthenticator(t *testing.T) {
	keyFile := filepath.Join(t.TempDir(), "mfa-key")
	if err := os.WriteFile(keyFile, []byte(hex.EncodeToString([]byte(strings.Repeat("k", 32)))), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newHarness(t, func(c *Config) { c.MFAMode = "enforce"; c.MFAKeyFile = keyFile })
	h.s.SetComms(&sandboxComms{pool: h.s.pool, live: map[string]bool{"sms": true}})
	_, link := h.enrol("u-switch", "switch-user", "+264810000056")
	if w := h.setPassword(link, "correct-password-123"); w.Code != 200 {
		t.Fatalf("password setup: %d", w.Code)
	}
	w, uri := h.login("switch-user", "correct-password-123")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Set up an authenticator instead") {
		t.Fatalf("SMS page: %d %s", w.Code, w.Body.String())
	}
	challenge := regexp.MustCompile(`name="challenge" value="([^"]+)"`).FindStringSubmatch(w.Body.String())[1]
	switched := h.form("/login/mfa/switch", url.Values{"request_uri": {uri}, "challenge": {challenge}})
	if switched.Code != 200 || !strings.Contains(switched.Body.String(), "Set up your authenticator") {
		t.Fatalf("switch: %d %s", switched.Code, switched.Body.String())
	}
	secretText := regexp.MustCompile(`<code>([A-Z2-7]+)</code>`).FindStringSubmatch(switched.Body.String())[1]
	secret, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(secretText)
	if err != nil {
		t.Fatal(err)
	}
	verified := h.form("/login/mfa/verify", url.Values{"request_uri": {uri}, "challenge": {challenge}, "code": {totpAt(secret, time.Now().Unix()/30)}})
	if verified.Code != 200 || len(verified.Result().Cookies()) == 0 {
		t.Fatalf("verified: %d %s", verified.Code, verified.Body.String())
	}
}

func TestPhoneOTPForMerchants(t *testing.T) {
	h := newHarness(t, nil) // dev OTP 246810, OTPMaxAttempts=3
	sub, tok := h.enrol("u-50", "mira", "+264810000050")
	h.setPassword(tok, "a-good-long-password")
	start := func() (string, string) {
		uri := h.par()
		w := h.form("/login/otp/start", url.Values{"request_uri": {uri}, "phone": {"+264 81 000 0050"}})
		m := regexp.MustCompile(`name="challenge" value="([^"]+)"`).FindStringSubmatch(w.Body.String())
		if w.Code != 200 || m == nil {
			t.Fatalf("otp start: %d %s", w.Code, w.Body.String())
		}
		return uri, m[1]
	}
	// without the MERCHANT_OTP profile no code is sent (same page, nothing in the outbox)
	start()
	var n int
	_ = h.s.pool.QueryRow(context.Background(), `SELECT count(*) FROM sim_outbox`).Scan(&n)
	if n != 0 {
		t.Fatalf("code sent to a non-merchant account")
	}
	if code, _ := h.api("PUT", "/accounts/"+sub+"/mfa-profile", `{"profile":"MERCHANT_OTP","org_requires_mfa":false}`); code != 200 {
		t.Fatalf("mfa profile: %d", code)
	}
	uri, ch := start()
	var body string
	_ = h.s.pool.QueryRow(context.Background(), `SELECT body FROM sim_outbox WHERE recipient='user:u-50' AND event_code='auth.otp.sms'`).Scan(&body)
	if !strings.Contains(body, "246810") {
		t.Fatalf("OTP not sent through comms: %q", body)
	}
	if w := h.form("/login/otp/start", url.Values{"request_uri": {h.par()}, "phone": {"+264810000050"}}); w.Code != 429 {
		t.Fatalf("resend within 60 s must be refused: %d", w.Code)
	}
	if w := h.form("/login/otp/verify", url.Values{"request_uri": {uri}, "challenge": {ch}, "code": {"000000"}}); w.Code != 401 {
		t.Fatalf("wrong code: %d", w.Code)
	}
	w := h.form("/login/otp/verify", url.Values{"request_uri": {uri}, "challenge": {ch}, "code": {"246810"}})
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Authorize application") {
		t.Fatalf("right code: %d %s", w.Code, w.Body.String())
	}
	var amr string
	_ = h.s.pool.QueryRow(context.Background(), `SELECT amr FROM sessions WHERE user_id=$1`, sub).Scan(&amr)
	if amr != "sms" {
		t.Fatalf("session amr = %q, want sms", amr)
	}
	if w := h.form("/login/otp/verify", url.Values{"request_uri": {uri}, "challenge": {ch}, "code": {"246810"}}); w.Code != 401 {
		t.Fatalf("a code is single use: %d", w.Code)
	}
}

func TestForgotPasswordAndHelp(t *testing.T) {
	h := newHarness(t, nil)
	_, tok := h.enrol("u-60", "kofi", "+264810000060")
	h.setPassword(tok, "a-good-long-password")
	w := h.form("/account/forgot", url.Values{"login": {"kofi"}})
	if w.Code != 200 {
		t.Fatalf("forgot: %d", w.Code)
	}
	unknown := h.form("/account/forgot", url.Values{"login": {"nobody"}})
	if unknown.Code != 200 || unknown.Body.String() != w.Body.String() {
		t.Fatalf("forgot must not reveal whether an account exists")
	}
	var body string
	_ = h.s.pool.QueryRow(context.Background(), `SELECT body FROM sim_outbox WHERE recipient='user:u-60' AND event_code='auth.password.reset'`).Scan(&body)
	link := regexp.MustCompile(`https://\S+`).FindString(body)
	if link == "" {
		t.Fatalf("reset link not sent: %q", body)
	}
	if w := h.setPassword(tokenOf(link), "a-reset-password-1"); w.Code != 200 {
		t.Fatalf("reset: %d", w.Code)
	}
	evs := h.outbound("core")
	if d := evs[len(evs)-1]["detail"].(map[string]any); d["via"] != "self_service" {
		t.Fatalf("self-service reset event: %v", evs[len(evs)-1])
	}
	if w := h.form("/account/help", url.Values{"login": {"kofi"}, "message": {"new phone"}}); w.Code != 200 {
		t.Fatalf("help: %d", w.Code)
	}
	if ev := h.eventTypes(); ev[len(ev)-1] != EventRecoveryRequested {
		t.Fatalf("help not forwarded: %v", ev)
	}
}

func TestForgotHiddenWithoutAChannel(t *testing.T) {
	h := newHarness(t, func(c *Config) { c.SandboxChannels = nil; c.DevOTP = "" })
	uri := h.par()
	w := h.do("GET", "/authorize?client_id=web&request_uri="+url.QueryEscape(uri), nil, "", "")
	if strings.Contains(w.Body.String(), "Forgot password?") || strings.Contains(w.Body.String(), "Text me a sign-in code") {
		t.Fatalf("features that need comms must be hidden while no channel is live")
	}
	if w := h.form("/account/forgot", url.Values{"login": {"x"}}); w.Code != 503 {
		t.Fatalf("forgot without a channel: %d", w.Code)
	}
}

func TestDeliveryToCoreAndGateway(t *testing.T) {
	var gotEvent, gotLogout string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if r.URL.Path == "/internal/auth/events" {
			gotEvent = string(b)
		} else {
			gotLogout = string(b)
		}
		w.WriteHeader(204)
	}))
	defer srv.Close()
	h := newHarness(t, func(c *Config) { c.CoreEventsURL = srv.URL + "/internal/auth/events" })
	h.client.BackchannelLogoutURI = srv.URL + "/auth/backchannel-logout"
	h.s.coreHTTP, h.s.logoutHTTP = srv.Client(), srv.Client()
	sub, tok := h.enrol("u-70", "lena", "")
	h.setPassword(tok, "a-good-long-password")
	h.api("POST", "/accounts/"+sub+"/revoke-sessions", `{}`)
	for i := 0; i < 3 && h.s.deliverBatch(context.Background()) > 0; i++ {
	}
	_, claims, err := jwtx.VerifyJWS(gotEvent, jwtx.AllowedJWSAlgs(), h.s.keyringKeyFn)
	if err != nil {
		t.Fatalf("event is not a valid IdP-signed JWT: %v (%q)", err, gotEvent)
	}
	ev := claims["event"].(map[string]any)
	if ev["type"] != EventCredentialSet || ev["uid"] != "u-70" || claims["jti"] != ev["id"] || claims["aud"] != "mipay-core" {
		t.Fatalf("event claims: %v", claims)
	}
	vals, _ := url.ParseQuery(gotLogout)
	hdr, lc, err := jwtx.VerifyJWS(vals.Get("logout_token"), jwtx.AllowedJWSAlgs(), h.s.keyringKeyFn)
	if err != nil || hdr["typ"] != "logout+jwt" || lc["sub"] != sub || lc["aud"] != "web" || lc["events"] == nil {
		t.Fatalf("logout token: %v %v %v", err, hdr, lc)
	}
	var pending int
	_ = h.s.pool.QueryRow(context.Background(), `SELECT count(*) FROM outbound WHERE delivered_at IS NULL`).Scan(&pending)
	if pending != 0 {
		t.Fatalf("%d deliveries still pending", pending)
	}
}

func TestUndeliveredEventsWaitForTheCore(t *testing.T) {
	h := newHarness(t, nil) // no CoreEventsURL
	_, tok := h.enrol("u-80", "omar", "")
	h.setPassword(tok, "a-good-long-password")
	h.s.deliverBatch(context.Background())
	var pending int
	var lastErr string
	_ = h.s.pool.QueryRow(context.Background(), `SELECT count(*), max(last_error) FROM outbound WHERE delivered_at IS NULL`).Scan(&pending, &lastErr)
	if pending != 1 || !strings.Contains(lastErr, "not configured") {
		t.Fatalf("event must stay queued until the core is configured: %d %q", pending, lastErr)
	}
}

func TestSeedAccounts(t *testing.T) {
	h := newHarness(t, func(c *Config) {
		c.SeedUsers = "alice:alice-pw:openid|mira:mira-password:openid:m-1:+264810000099:MERCHANT_OTP"
	})
	if err := h.s.SeedUsers(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := h.s.SeedUsers(context.Background()); err != nil { // idempotent
		t.Fatal(err)
	}
	a, err := h.s.accountByLogin(context.Background(), "mira")
	if err != nil || a.UID != "m-1" || a.Phone != "+264810000099" || a.MFAProfile != "MERCHANT_OTP" || a.State != StateActive {
		t.Fatalf("seed: %v %v", a, err)
	}
	if b, _ := h.s.accountByLogin(context.Background(), "alice"); b.UID != "alice" || b.Subject != seedSubject("alice") {
		t.Fatalf("seed default uid/subject: %v", b)
	}
}

// Behind the gateway the TLS peer is the gateway's mesh certificate; it must never become
// the token's binding (FAPI 2.0: no DPoP proof and no forwarded client certificate -> 400).
func TestGatewayCertificateIsNotASenderBinding(t *testing.T) {
	h := newHarness(t, nil)
	_, tok := h.enrol("u-90", "gwtest", "")
	h.setPassword(tok, "a-good-long-password")
	w, uri := h.login("gwtest", "a-good-long-password")
	csrf := regexp.MustCompile(`name="csrf" value="([^"]+)"`).FindStringSubmatch(w.Body.String())[1]
	var sess *http.Cookie
	for _, c := range w.Result().Cookies() {
		if c.Name == sessionCookie {
			sess = c
		}
	}
	w = h.form("/consent", url.Values{"request_uri": {uri}, "csrf": {csrf}, "decision": {"allow"}}, sess)
	loc, _ := url.Parse(w.Header().Get("Location"))
	form := url.Values{"grant_type": {"authorization_code"}, "code": {loc.Query().Get("code")}, "code_verifier": {verifier},
		"redirect_uri": {"https://app.test/cb"}, "client_id": {"web"},
		"client_assertion_type": {"urn:ietf:params:oauth:client-assertion-type:jwt-bearer"}, "client_assertion": {h.assertion()}}
	r := httptest.NewRequest("POST", "/token", strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Subject: pkix.Name{CommonName: "sme-gateway-mesh"}, Raw: []byte("gw")}}}
	rec := httptest.NewRecorder()
	h.h.ServeHTTP(rec, r)
	if rec.Code != 400 || !strings.Contains(rec.Body.String(), "sender-constrained") {
		t.Fatalf("token bound to the gateway's certificate: %d %s", rec.Code, rec.Body.String())
	}
}
