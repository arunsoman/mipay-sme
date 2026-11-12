// fapi-client is the reference FAPI 2.0 client used by the end-to-end suite.
// It exercises the whole flow through the API gateway exactly as a partner
// client would: PAR (with client auth) -> browser login/consent -> code ->
// token (PKCE + sender-constrained) -> RBAC admin calls with DPoP/mTLS proofs.
package main

import (
	"context"
	"crypto"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"trustplane/internal/clients"
	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
	"trustplane/internal/mtlsx"
	"trustplane/internal/pb"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
)

var (
	issuerBase = envOr("TP_ISSUER", "https://idp.fapi.local:8443")
	rbacBase   = envOr("TP_RBAC_BASE", "https://rbac.fapi.local:8443")
	caFile     = envOr("TP_CA", "/pki/platform-ca.pem")
	clientsF   = envOr("TP_CLIENTS_FILE", "/config/idp/clients.yaml")
)

// Seed accounts (IDP_SEED_USERS); their uids are the RBAC subjects of fixtures.e2e.yaml.
var passOf = map[string]string{
	"rootadmin": envOr("TP_PASS_ROOTADMIN", "rootadmin-pw"), // platform_user_admin
	"uadmin2":   envOr("TP_PASS_UADMIN2", "uadmin2-pw"),     // second platform_user_admin (four-eyes)
	"alice":     envOr("TP_PASS_ALICE", "alice-pw"),         // platform_ops_reviewer
	"mlro1":     envOr("TP_PASS_MLRO1", "mlro1-pw"),         // platform_mlro
	"selma":     envOr("TP_PASS_SELMA", "selma-pw"),         // org_admin own e2e-oka
	"tomas":     envOr("TP_PASS_TOMAS", "tomas-pw"),         // org_staff own e2e-oka
	"jonas":     envOr("TP_PASS_JONAS", "jonas-pw"),         // org_admin subtree e2e-namib
	"carol":     envOr("TP_PASS_CAROL", "carol-pw"),         // no assignment
	"mira":      envOr("TP_PASS_MIRA", "mira-pw"),           // merchant, phone OTP
}

var (
	idpMesh   = envOr("TP_IDP_MESH", "https://idp:8443")
	commsMesh = envOr("TP_COMMS_MESH", "https://comms:8443/comms/v1")
	coreCert  = envOr("TP_CORE_CERT", "/pki/mipay-mesh.pem") + "|" + envOr("TP_CORE_KEY", "/pki/mipay-mesh-key.pem")
	gwMesh    = "/pki/gateway-mesh.pem|/pki/gateway-mesh-key.pem"
)

const redirectURI = "http://127.0.0.1:8090/cb"

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// ---------------------------------------------------------------------------
// HTTP plumbing: transport per client-cert, cookie-jar clients per flow
// ---------------------------------------------------------------------------

var transportCache = struct {
	mu sync.Mutex
	m  map[string]*http.Transport
}{m: map[string]*http.Transport{}}

// tlsFileFor derives "<cert>|<key>" for a cert client profile following the
// pki convention /pki/clients/<name>.pem + /pki/clients/<name>-key.pem.
func tlsFileFor(p *clients.Client) string {
	cert := p.CertPaths[0]
	base := strings.TrimSuffix(cert, ".pem")
	if strings.HasSuffix(base, "-key") {
		base = strings.TrimSuffix(base, "-key")
	}
	return cert + "|" + base + "-key.pem"
}

func transportFor(key string) *http.Transport {
	transportCache.mu.Lock()
	defer transportCache.mu.Unlock()
	if t, ok := transportCache.m[key]; ok {
		return t
	}
	var cert *tls.Certificate
	if key != "" {
		parts := strings.SplitN(key, "|", 2)
		c, err := mtlsx.LoadCertificate(parts[0], parts[1])
		if err != nil {
			panic(fmt.Sprintf("load client cert %q: %v", key, err))
		}
		cert = c
	}
	tlsCfg, err := mtlsx.ExternalClientConfig(caFile, cert)
	if err != nil {
		panic(err)
	}
	t := &http.Transport{TLSClientConfig: tlsCfg, ForceAttemptHTTP2: true}
	transportCache.m[key] = t
	return t
}

type jar map[string][]*http.Cookie

func (j jar) SetCookies(u *url.URL, cookies []*http.Cookie) {
	j[u.Host] = append(j[u.Host], cookies...)
}

func (j jar) Cookies(u *url.URL) []*http.Cookie { return j[u.Host] }

// flowClient is a browser-like client: cookie jar, no redirects.
func flowClient(certFile string) *http.Client {
	return &http.Client{
		Transport: transportFor(certFile),
		Jar:       jar{},
		CheckRedirect: func(_ *http.Request, _ /*via*/ []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Timeout: 20 * time.Second,
	}
}

// call performs one request; returns status, headers, body.
func call(cl *http.Client, method, rawurl string, hdr map[string]string, body []byte) (int, http.Header, string) {
	var rd io.Reader
	if body != nil {
		rd = strings.NewReader(string(body))
	}
	req, err := http.NewRequest(method, rawurl, rd)
	if err != nil {
		return 0, nil, "client error: " + err.Error()
	}
	for k, v := range hdr {
		if k == "Host" {
			req.Host = v // reach the gateway by its mesh name while presenting a public host
			continue
		}
		req.Header.Set(k, v)
	}
	if body != nil && req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	resp, err := cl.Do(req)
	if err != nil {
		return 0, nil, "transport error: " + err.Error()
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode, resp.Header, string(b)
}

func postForm(cl *http.Client, method, rawurl string, form url.Values, extra map[string]string, certFile string) (int, http.Header, string) {
	h := map[string]string{"Content-Type": "application/x-www-form-urlencoded"}
	for k, v := range extra {
		h[k] = v
	}
	return call(cl, method, rawurl, h, []byte(form.Encode()))
}

func merge(a, b map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range a {
		out[k] = v
	}
	for k, v := range b {
		out[k] = v
	}
	return out
}

// ---------------------------------------------------------------------------
// DPoP helpers (client side)
// ---------------------------------------------------------------------------

func proofFor(key crypto.Signer, jwk map[string]any, method, rawurl, ath string) string {
	htu, err := dpop.CanonicalHTU(rawurl)
	if err != nil {
		panic(err)
	}
	p, err := dpop.SignProof(key, jwk, method, htu, ath)
	if err != nil {
		panic(err)
	}
	return p
}

// ---------------------------------------------------------------------------
// Client assertions (private_key_jwt; aud MUST be the issuer)
// ---------------------------------------------------------------------------

func clientAssertion(p *clients.Client, aud string) string {
	kid, _ := p.JWTKeys[0]["kid"].(string)
	signer, err := jwtx.LoadPrivateKeyFile(p.JWKSPaths[0])
	if err != nil {
		panic(err)
	}
	claims := map[string]any{
		"iss": p.ID, "sub": p.ID, "aud": aud,
		"exp": time.Now().Add(5 * time.Minute).Unix(),
		"iat": time.Now().Unix(), "jti": jwtx.RandB64(16),
	}
	a, err := jwtx.SignJWS(jwtx.AlgES256, "JWT", kid, claims, signer)
	if err != nil {
		panic(err)
	}
	return a
}

func addAssertion(form url.Values, p *clients.Client, aud string) {
	form.Set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
	form.Set("client_assertion", clientAssertion(p, aud))
}

// ---------------------------------------------------------------------------
// FAPI flow
// ---------------------------------------------------------------------------

type flowResult struct {
	profile     *clients.Client
	AccessToken string
	TokenType   string
	Refresh     string
	IDToken     string
	Key         crypto.Signer
	JWK         map[string]any
	Code        string
	State       string
	ISS         string
	Verifier    string
}

// par pushes the authorization parameters (authenticated back-channel).
func par(cl *http.Client, p *clients.Client, scopes, state, challenge string) (int, string) {
	form := url.Values{
		"client_id":             {p.ID},
		"response_type":         {"code"},
		"redirect_uri":          {redirectURI},
		"scope":                 {scopes},
		"state":                 {state},
		"nonce":                 {jwtx.RandB64(16)},
		"code_challenge":        {challenge},
		"code_challenge_method": {"S256"},
	}
	if p.AuthMethod == clients.AuthPrivateKeyJWT {
		addAssertion(form, p, issuerBase)
	}
	cert := ""
	if p.AuthMethod == clients.AuthTLSClient {
		cert = tlsFileFor(p)
	}
	st, _, body := postForm(cl, "POST", issuerBase+"/par", form, nil, cert)
	return st, body
}

// runFlow performs the complete flow and returns everything captured.
func runFlow(p *clients.Client, user, pass, scopes, sender string) (*flowResult, string) {
	// tls_client_auth profiles must present their client certificate on every
	// token-endpoint call (PAR included) — the IdP registers the cert thumb.
	cl := flowClient("")
	if p.AuthMethod == clients.AuthTLSClient {
		cl = flowClient(tlsFileFor(p))
	}
	verifier := jwtx.RandB64(48)
	state := jwtx.RandB64(16)
	st, body := par(cl, p, scopes, state, jwtx.PKCES256(verifier))
	if st != 201 {
		return nil, fmt.Sprintf("PAR failed: %d %s", st, body)
	}
	var parResp struct {
		RequestURI string `json:"request_uri"`
	}
	if err := json.Unmarshal([]byte(body), &parResp); err != nil || parResp.RequestURI == "" {
		return nil, "PAR parse failed: " + body
	}
	authzURL := fmt.Sprintf("%s/authorize?client_id=%s&request_uri=%s", issuerBase,
		url.QueryEscape(p.ID), url.QueryEscape(parResp.RequestURI))
	st, _, page := call(cl, "GET", authzURL, nil, nil)
	if st != 200 {
		return nil, fmt.Sprintf("authorize failed: %d %s", st, page)
	}
	st, _, page = postForm(cl, "POST", issuerBase+"/login", url.Values{
		"request_uri": {parResp.RequestURI}, "username": {user}, "password": {pass},
	}, nil, "")
	if st != 200 || strings.Contains(page, "Sign-in failed") {
		return nil, fmt.Sprintf("login failed: %d", st)
	}
	csrf := extractInput(page, "csrf")
	if csrf == "" {
		return nil, "csrf not found on consent page"
	}
	st, hdrs, cbody := postForm(cl, "POST", issuerBase+"/consent", url.Values{
		"request_uri": {parResp.RequestURI}, "csrf": {csrf}, "decision": {"allow"},
	}, nil, "")
	if st != 302 {
		return nil, fmt.Sprintf("consent failed: %d %s", st, cbody)
	}
	loc := hdrs.Get("Location")
	u, err := url.Parse(loc)
	if err != nil {
		return nil, "bad redirect: " + loc
	}
	q := u.Query()
	code := q.Get("code")
	if code == "" {
		return nil, "no code in redirect: " + loc
	}

	// token leg (client-authenticated back-channel)
	cert := ""
	if p.AuthMethod == clients.AuthTLSClient {
		cert = tlsFileFor(p)
	}
	tcl := flowClient(cert)
	form := url.Values{
		"grant_type": {"authorization_code"}, "code": {code},
		"redirect_uri": {redirectURI}, "code_verifier": {verifier}, "client_id": {p.ID},
	}
	if p.AuthMethod != clients.AuthTLSClient { // confidential client assertion
		addAssertion(form, p, issuerBase)
	}
	extra := map[string]string{}
	var key crypto.Signer
	var jwk map[string]any
	var err2 error
	if sender == "dpop" {
		key, jwk, err2 = dpop.GenerateES256()
		if err2 != nil {
			return nil, "dpop keygen: " + err2.Error()
		}
		extra["DPoP"] = proofFor(key, jwk, "POST", issuerBase+"/token", "")
	}
	st, _, tbody := postForm(tcl, "POST", issuerBase+"/token", form, extra, cert)
	if st != 200 {
		return nil, fmt.Sprintf("token failed: %d %s", st, tbody)
	}
	var tok struct {
		AccessToken  string `json:"access_token"`
		TokenType    string `json:"token_type"`
		RefreshToken string `json:"refresh_token"`
		IDToken      string `json:"id_token"`
	}
	if err := json.Unmarshal([]byte(tbody), &tok); err != nil || tok.AccessToken == "" {
		return nil, "token parse failed: " + tbody
	}
	return &flowResult{
		profile: p, AccessToken: tok.AccessToken, TokenType: tok.TokenType,
		Refresh: tok.RefreshToken, IDToken: tok.IDToken, Key: key, JWK: jwk,
		Code: code, State: state, ISS: q.Get("iss"), Verifier: verifier,
	}, ""
}

var inputRe = regexp.MustCompile(`<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"`)

func extractInput(page, name string) string {
	for _, m := range inputRe.FindAllStringSubmatch(page, -1) {
		if m[1] == name {
			return m[2]
		}
	}
	return ""
}

// decodeJWTClaims parses claims of a JWT without verifying (test helper).
func decodeJWTClaims(tok string) (map[string]any, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("not a JWT")
	}
	pb, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return nil, err
	}
	out := map[string]any{}
	err = json.Unmarshal(pb, &out)
	return out, err
}

// ---------------------------------------------------------------------------
// Authenticated API calls with sender binding
// ---------------------------------------------------------------------------

// apiCall performs an API call with a DPoP-bound token (key may be nil for
// mTLS-bound tokens) and optional client certificate.
func apiCall(token, tokenType string, key crypto.Signer, jwk map[string]any, tlsKey string,
	method, rawurl string, body []byte) (int, http.Header, string) {

	hdr := map[string]string{}
	if tokenType == "DPoP" && key != nil {
		hdr["Authorization"] = "DPoP " + token
		ath := ""
		if token != "" {
			sum := sha256.Sum256([]byte(token))
			ath = jwtx.B64UEnc(sum[:])
		}
		hdr["DPoP"] = proofFor(key, jwk, method, rawurl, ath)
	} else {
		hdr["Authorization"] = "Bearer " + token
	}
	if len(body) > 0 {
		hdr["Content-Type"] = "application/json"
	}
	return call(flowClient(tlsKey), method, rawurl, hdr, body)
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

func main() {
	cmd := "suite"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	reg, err := clients.Load(clientsF)
	if err != nil {
		fmt.Fprintf(os.Stderr, "fapi-client: load clients: %v\n", err)
		os.Exit(2)
	}
	switch cmd {
	case "suite":
		s := &suite{reg: reg}
		s.run()
	case "role-pack":
		// Prints the compliance role pack (P2.5a) as rootadmin (platform_user_admin) sees it.
		s := &suite{reg: reg}
		fr, errStr := runFlow(s.profile("web-private-key-jwt"), "rootadmin", passOf["rootadmin"], "openid rbac:admin", "dpop")
		if errStr != "" {
			fmt.Fprintln(os.Stderr, "login:", errStr)
			os.Exit(1)
		}
		st, _, body := apiCall(fr.AccessToken, fr.TokenType, fr.Key, fr.JWK, "", "GET", rbacBase+"/rbac/admin/export/role-pack", nil)
		if st != 200 {
			fmt.Fprintf(os.Stderr, "role pack: %d %s\n", st, body)
			os.Exit(1)
		}
		fmt.Print(body)
	case "introspect-check":
		// Debug probe: sign a gw-resource-server assertion exactly like the
		// gateway's introspector and POST it to /introspect.
		key, err := jwtx.LoadPrivateKeyFile("/pki/clients/gw-introspect.pem")
		if err != nil {
			fmt.Println("load key:", err)
			os.Exit(1)
		}
		a, err := jwtx.SignJWS(jwtx.AlgES256, "JWT", "gw-introspect",
			map[string]any{
				"iss": "gw-resource-server", "sub": "gw-resource-server",
				"aud": issuerBase, "exp": time.Now().Add(300 * time.Second).Unix(),
				"iat": time.Now().Unix(), "jti": jwtx.RandB64(16),
			}, key)
		if err != nil {
			fmt.Println("sign:", err)
			os.Exit(1)
		}
		form := url.Values{
			"token": {"x"}, "client_id": {"gw-resource-server"},
			"client_assertion_type": {"urn:ietf:params:oauth:client-assertion-type:jwt-bearer"},
			"client_assertion":      {a},
		}
		st, _, body := postForm(flowClient(""), "POST", issuerBase+"/introspect", form, nil, "")
		fmt.Printf("introspect: %d %s\n", st, body)
	case "probe-rbac":
		// Debug probe: dial the RBAC decision plane exactly like the gateway
		// (mTLS, same certs) and try Check + ListPermissions.
		tlsCfg, err := mtlsx.MeshClientConfig("/pki/gateway-mesh.pem", "/pki/gateway-mesh-key.pem", "/pki/platform-ca.pem", "rbac")
		if err != nil {
			fmt.Println("tls:", err)
			os.Exit(1)
		}
		_ = tlsCfg
		conn, err := grpc.NewClient("rbac:5051", grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)))
		if err != nil {
			fmt.Println("dial:", err)
			os.Exit(1)
		}
		cc := pb.NewRbacClient(conn)
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		ch, cerr := cc.Check(ctx, &pb.CheckRequest{Subject: "rootadmin", Tenant: "", Operation: "rbac.role:read"})
		fmt.Printf("check: err=%v allowed=%v\n", cerr, ch.GetAllowed())
		pctx, cancel2 := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel2()
		lp, lerr := cc.ListPermissions(pctx, &pb.ListPermissionsRequest{Subject: "rootadmin", Tenant: ""})
		fmt.Printf("listPermissions: err=%v ops=%d first=%v\n", lerr, len(lp.GetOperations()), firstOf(lp.GetOperations()))
	default:
		fmt.Fprintf(os.Stderr, "usage: fapi-client [suite|role-pack|introspect-check|probe-rbac]\n")
		os.Exit(2)
	}
}

func firstOf(ops []string) string {
	if len(ops) > 0 {
		return ops[0]
	}
	return ""
}
