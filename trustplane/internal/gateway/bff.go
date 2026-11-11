package gateway

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/x509"
	"crypto/sha256"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
)

// bff is the gateway's Browser-for-Frontend (Lane A, §4): a confidential
// FAPI 2.0 client that signs users in via the IdP and holds their tokens.
type bff struct {
	store     *SessionStore
	clientID  string
	signKey   crypto.Signer // gateway-bff private key for client_assertion
	signKid   string
	idpBase   string        // https://idp:8443 (mesh)
	absLife   time.Duration // session absolute lifetime
	idleLife  time.Duration // session idle timeout
}

// loginTransaction is a short-lived login attempt (5 min).
type loginTransaction struct {
	State           string    `json:"state"`
	Nonce           string    `json:"nonce"`
	CodeVerifier    string    `json:"code_verifier"`
	ReturnTo        string    `json:"return_to"`
	Host            string    `json:"host"` // original Host for callback redirect_uri
	DPoPPrivatePEM  string    `json:"dpop_private_pem"` // session DPoP private key (PEM)
	DPoPJKT         string    `json:"dpop_jkt"`           // cnf.jkt sent to the IdP
	CreatedAt       time.Time `json:"created_at"`
}

// txStore holds in-flight login transactions (memory, 5 min TTL).
type txStore struct {
	mu map[string]*loginTransaction
}

func newTxStore() *txStore { return &txStore{mu: map[string]*loginTransaction{}} }

func (t *txStore) put(id string, tx *loginTransaction) { t.mu[id] = tx }

func (t *txStore) pop(id string) (*loginTransaction, bool) {
	tx, ok := t.mu[id]
	delete(t.mu, id)
	return tx, ok
}

var loginTx = newTxStore()

// ---------------------------------------------------------------- routes

// bff handles /auth/* directly (Lane A). These routes are "auth: none"
// (login, callback, session, logout) and are invoked from Gateway.serve
// before deny-by-default route matching.
func (g *Gateway) bff(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	switch {
	case path == "/auth/login" && r.Method == http.MethodGet:
		g.login(w, r)
	case path == "/auth/callback" && r.Method == http.MethodGet:
		g.callback(w, r)
	case path == "/auth/logout" && (r.Method == http.MethodGet || r.Method == http.MethodPost):
		g.logout(w, r)
	case path == "/auth/session" && r.Method == http.MethodGet:
		g.session(w, r)
	default:
		writeErr(w, 404, "not_found")
	}
}

// ---------------------------------------------------------------- login

// login starts a FAPI 2.0 authorization flow: PAR → 302 to IdP /authorize.
// The browser never sees tokens; the gateway holds them in a session.
func (g *Gateway) login(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	returnTo := q.Get("return_to")
	if returnTo == "" {
		returnTo = "/"
	}
	if !isSameHostRelative(returnTo) {
		returnTo = "/" // open-redirect guard
	}

	// Per-login DPoP key (§4.2): the gateway signs DPoP proofs for every API
	// call this session makes.
	dpopKey, dpopJWK, err := dpop.GenerateES256()
	if err != nil {
		writeErr(w, 500, "server_error: dpop key")
		return
	}
	dpopJKT, err := jwtx.ThumbprintJWK(dpopJWK)
	if err != nil {
		writeErr(w, 500, "server_error: dpop thumbprint")
		return
	}

	// Marshal the DPoP private key to PEM so it can survive in a transaction.
	keyDER, err := x509.MarshalECPrivateKey(dpopKey.(*ecdsa.PrivateKey))
	if err != nil {
		writeErr(w, 500, "server_error: dpop marshal")
		return
	}
	privatePEM := string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}))

	state := jwtx.RandB64(24)
	nonce := jwtx.RandB64(24)
	verifier := jwtx.RandB64(32)
	sum := sha256.Sum256([]byte(verifier)); challenge := jwtx.B64UEnc(sum[:])

	tx := &loginTransaction{
		State:           state,
		Nonce:           nonce,
		CodeVerifier:    verifier,
		ReturnTo:        returnTo,
		Host:            r.Host,
		DPoPPrivatePEM:  privatePEM,
		DPoPJKT:         dpopJKT,
		CreatedAt:       time.Now(),
	}
	loginTx.put(state, tx)

	// Transaction cookie ties the callback to this browser (login CSRF).
	http.SetCookie(w, &http.Cookie{
		Name:     "__Host-mp_tx",
		Value:    state,
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   300, // 5 min
	})

	// PAR to the IdP as the gateway-bff confidential client.
	requestURI, err := g.par(r.Context(), r.Host, state, nonce, challenge, dpopJKT)
	if err != nil {
		log.Printf("[gateway] bff login PAR failed: %v", err)
		writeErr(w, 502, "server_error: identity unavailable")
		return
	}

	authURL := g.bffIDPPublic + "/authorize?client_id=" + urlEscape(g.bffID) +
		"&request_uri=" + urlEscape(requestURI)
	http.Redirect(w, r, authURL, http.StatusFound)
}

// par pushes the authorization request to the IdP (RFC 9126).
func (g *Gateway) par(ctx context.Context, host, state, nonce, challenge, dpopJKT string) (string, error) {
	assertion, err := g.clientAssertion(ctx)
	if err != nil {
		return "", err
	}
	redirectURI := "https://" + host + "/auth/callback"
	form := url.Values{}
	form.Set("client_id", g.bffID)
	form.Set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
	form.Set("client_assertion", assertion)
	form.Set("response_type", "code")
	form.Set("redirect_uri", redirectURI)
	form.Set("scope", "openid offline_access")
	form.Set("code_challenge", challenge)
	form.Set("code_challenge_method", "S256")
	form.Set("state", state)
	form.Set("nonce", nonce)
	form.Set("dpop_jkt", dpopJKT)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.bffIDP+"/par",
		strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := g.up["idp"].client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != 201 {
		return "", fmt.Errorf("PAR status %d: %s", resp.StatusCode, string(body))
	}
	var out struct {
		RequestURI string `json:"request_uri"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", err
	}
	return out.RequestURI, nil
}

// clientAssertion signs a private_key_jwt client assertion (RFC 7523).
func (g *Gateway) clientAssertion(ctx context.Context) (string, error) {
	now := time.Now()
	claims := map[string]any{
		"iss": g.bffID, "sub": g.bffID, "aud": g.cfg.Server.Issuer,
		"iat": now.Unix(), "nbf": now.Unix(),
		"exp": now.Add(10 * time.Minute).Unix(),
		"jti": jwtx.RandB64(16),
	}
	return jwtx.SignJWS(jwtx.AlgES256, "JWT", g.bffKid, claims, g.bffKey)
}

// ---------------------------------------------------------------- callback

// callback completes the authorization flow: code → tokens → session.
func (g *Gateway) callback(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	code := q.Get("code")
	state := q.Get("state")
	iss := q.Get("iss")
	if code == "" || state == "" || iss == "" {
		writeErr(w, 400, "invalid_request: missing code/state/iss")
		return
	}

	// Verify issuer (RFC 9207 mix-up mitigation).
	if iss != g.cfg.Server.Issuer {
		writeErr(w, 400, "invalid_issuer")
		return
	}

	// Transaction cookie binds the callback to this browser.
	txCookie, err := r.Cookie("__Host-mp_tx")
	if err != nil || txCookie.Value == "" {
		writeErr(w, 400, "invalid_state: transaction cookie missing")
		return
	}
	tx, ok := loginTx.pop(txCookie.Value)
	if !ok || tx.State != state {
		writeErr(w, 400, "invalid_state: unknown or expired transaction")
		return
	}
	if time.Since(tx.CreatedAt) > 5*time.Minute {
		writeErr(w, 400, "invalid_state: transaction expired")
		return
	}
	// Clear the transaction cookie.
	http.SetCookie(w, &http.Cookie{Name: "__Host-mp_tx", Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})

	// Reconstruct the per-session DPoP private key.
	dpopKey, err := pemToECKey(tx.DPoPPrivatePEM)
	if err != nil {
		writeErr(w, 500, "server_error: dpop key")
		return
	}

	// Exchange the code at the token endpoint, DPoP-bound to the session key.
	redirectURI := "https://" + tx.Host + "/auth/callback"
	tokens, err := g.tokenExchange(r.Context(), code, tx.CodeVerifier, redirectURI, dpopKey)
	if err != nil {
		log.Printf("[gateway] bff callback token exchange failed: %v", err)
		writeErr(w, 400, "invalid_grant: token exchange failed")
		return
	}

	// Verify the ID token.
	idClaims, err := g.verifyIDToken(tokens.IDToken, tx.Nonce, g.bffID)
	if err != nil {
		writeErr(w, 400, "invalid_grant: id token "+err.Error())
		return
	}
	sub := idString(idClaims, "sub")

	// Build the session.
	ses := &Session{
		ID:             jwtx.RandB64(32),
		Sub:            sub,
		ClientID:       g.bffID,
		CSRF:           jwtx.RandB64(24),
		OrgID:          idString(idClaims, "org_id"),
		ACR:            idString(idClaims, "acr"),
		AuthTime:       idInt64(idClaims, "auth_time"),
		Issuer:         idString(idClaims, "iss"),
		Audience:       idString(idClaims, "aud"),
		AccessToken:    tokens.AccessToken,
		RefreshToken:   tokens.RefreshToken,
		IDToken:        tokens.IDToken,
		DPoPJKT:        tx.DPoPJKT,
		CreatedAt:      time.Now(),
		ExpiresAt:      time.Now().Add(g.bffAbs),
		IdleAt:         time.Now(),
	}
	if amr, ok := idClaims["amr"].([]any); ok {
		for _, a := range amr {
			if s, ok := a.(string); ok {
				ses.AMR = append(ses.AMR, s)
			}
		}
	}
	if err := g.store.Create(r.Context(), ses); err != nil {
		writeErr(w, 500, "server_error: session")
		return
	}

	// Store the DPoP private key in the session so the gateway can sign DPoP
	// proofs for this session's API calls.
	if err := g.store.SetDPoPKey(r.Context(), ses.ID, tx.DPoPPrivatePEM); err != nil {
		log.Printf("[gateway] bff callback store DPoP key failed: %v", err)
	}

	// __Host-mp_sess: the browser's only handle on this session.
	http.SetCookie(w, &http.Cookie{
		Name:     "__Host-mp_sess",
		Value:    ses.ID,
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteStrictMode,
		MaxAge:   int(g.bffAbs.Seconds()),
	})

	http.Redirect(w, r, tx.ReturnTo, http.StatusFound)
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int    `json:"expires_in"`
	RefreshToken string `json:"refresh_token"`
	IDToken      string `json:"id_token"`
	Scope        string `json:"scope"`
}

// tokenExchange calls the IdP token endpoint (RFC 6749 §5.1), DPoP-bound.
// client_assertion is sent in the request body (RFC 7523); the DPoP proof is
// in the DPoP request header.
func (g *Gateway) tokenExchange(ctx context.Context, code, verifier, redirectURI string, dpopKey crypto.Signer) (*tokenResponse, error) {
	assertion, err := g.clientAssertion(ctx)
	if err != nil {
		return nil, err
	}
	htu, err := dpop.CanonicalHTU(g.bffIDP + "/token")
	if err != nil {
		return nil, err
	}
	jwk, err2 := jwkFromSigner(dpopKey)
	if err2 != nil {
		return nil, err2
	}
	proof, err := dpop.SignProof(dpopKey, jwk, "POST", htu, "")
	if err != nil {
		return nil, err
	}

	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code)
	form.Set("code_verifier", verifier)
	form.Set("redirect_uri", redirectURI)
	form.Set("client_id", g.bffID)
	form.Set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
	form.Set("client_assertion", assertion)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.bffIDP+"/token",
		strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("DPoP", proof)

	resp, err := g.up["idp"].client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("token status %d: %s", resp.StatusCode, string(body))
	}
	var tr tokenResponse
	if err := json.Unmarshal(body, &tr); err != nil {
		return nil, err
	}
	return &tr, nil
}

// ---------------------------------------------------------------- logout

// logout ends the session: revoke the refresh token at the IdP, delete the
// session, clear cookies.
func (g *Gateway) logout(w http.ResponseWriter, r *http.Request) {
	ses := g.currentSession(r)
	if ses != nil {
		// Revoke refresh token at the IdP (best effort).
		g.revokeRefresh(r.Context(), ses.RefreshToken)
		g.store.Delete(r.Context(), ses.ID)
	}
	http.SetCookie(w, &http.Cookie{Name: "__Host-mp_sess", Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})
	http.SetCookie(w, &http.Cookie{Name: "__Host-mp_tx", Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})
	http.Redirect(w, r, g.bffIDP+"/logout", http.StatusFound)
}

func (g *Gateway) revokeRefresh(ctx context.Context, refreshToken string) {
	assertion, err := g.clientAssertion(ctx)
	if err != nil {
		return
	}
	form := url.Values{}
	form.Set("token", refreshToken)
	form.Set("token_type_hint", "refresh_token")
	form.Set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
	form.Set("client_assertion", assertion)
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, g.bffIDP+"/revoke",
		strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	g.up["idp"].client.Do(req) // nolint: errcheck (best effort)
}

// ---------------------------------------------------------------- session

// session returns the current session's identity + CSRF for the portal.
func (g *Gateway) session(w http.ResponseWriter, r *http.Request) {
	ses := g.currentSession(r)
	if ses == nil {
		writeErr(w, 401, "login_required")
		return
	}
	// Extend idle lifetime on activity.
	_ = g.store.Touch(r.Context(), ses.ID, g.bffIdle)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"sub":        ses.Sub,
		"client_id":  ses.ClientID,
		"org_id":     ses.OrgID,
		"acr":        ses.ACR,
		"amr":        ses.AMR,
		"auth_time":  ses.AuthTime,
		"csrf":       ses.CSRF,
		"expires_at": ses.ExpiresAt.Unix(),
	})
}

// currentSession loads the session from the __Host-mp_sess cookie.
func (g *Gateway) currentSession(r *http.Request) *Session {
	c, err := r.Cookie("__Host-mp_sess")
	if err != nil || c.Value == "" {
		return nil
	}
	ses, err := g.store.Get(r.Context(), c.Value)
	if err != nil {
		return nil
	}
	return ses
}

// ---------------------------------------------------------------- helpers

func isSameHostRelative(p string) bool {
	return strings.HasPrefix(p, "/") && !strings.HasPrefix(p, "//")
}

func idString(m map[string]any, k string) string {
	if v, ok := m[k].(string); ok {
		return v
	}
	return ""
}

func idInt64(m map[string]any, k string) int64 {
	switch v := m[k].(type) {
	case float64:
		return int64(v)
	case string:
		var n int64
		fmt.Sscanf(v, "%d", &n)
		return n
	}
	return 0
}

func mustMarshalJWK(jwk map[string]any) string {
	b, _ := json.Marshal(jwk)
	return string(b)
}

func jwkFromSigner(key crypto.Signer) (map[string]any, error) {
	return jwtx.PublicJWK(key.Public())
}

// pemToECKey reconstructs an *ecdsa.PrivateKey from PEM.
func pemToECKey(pemBlock string) (crypto.Signer, error) {
	block, _ := pem.Decode([]byte(pemBlock))
	if block == nil {
		return nil, fmt.Errorf("no PEM block")
	}
	key, err := x509.ParseECPrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	return key, nil
}

// urlEscape percent-encodes (RFC 3986 unreserved only).
func urlEscape(s string) string {
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

// ---------------------------------------------------------------- ID token verification

type idTokenClaims map[string]any

// verifyIDToken verifies an ID token signature and claims.
func (g *Gateway) verifyIDToken(idToken, nonce, expectedAud string) (map[string]any, error) {
	_, claims, err := g.jwks.verify(idToken)
	if err != nil {
		return nil, err
	}
	if jwtx.ClaimString(claims, "iss") != g.cfg.Server.Issuer {
		return nil, fmt.Errorf("issuer mismatch")
	}
	auds := jwtx.ClaimStrings(claims, "aud")
	found := false
	for _, a := range auds {
		if a == expectedAud {
			found = true
		}
	}
	if !found {
		return nil, fmt.Errorf("audience mismatch")
	}
	if jwtx.ClaimString(claims, "nonce") != nonce {
		return nil, fmt.Errorf("nonce mismatch")
	}
	now := time.Now().Unix()
	exp, _ := jwtx.ClaimInt64(claims, "exp")
	if exp == 0 || now >= exp {
		return nil, fmt.Errorf("expired")
	}
	return claims, nil
}
