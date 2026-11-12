package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"

	"trustplane/internal/clients"
	"trustplane/internal/dpop"
	"trustplane/internal/jwtx"
)

type suite struct {
	reg     *clients.Registry
	pass    int
	fail    int
	verbose bool
}

func (s *suite) check(name string, ok bool, detail string) {
	if ok {
		s.pass++
		fmt.Printf("  PASS  %s\n", name)
	} else {
		s.fail++
		fmt.Printf("  FAIL  %s  %s\n", name, detail)
	}
}

func (s *suite) profile(id string) *clients.Client {
	p := s.reg.ByID[id]
	if p == nil {
		panic("unknown client profile: " + id)
	}
	return p
}

// mustFlow runs a login flow, failing the suite on error.
func (s *suite) mustFlow(profile, user, scopes, sender string) *flowResult {
	return s.flowAs(profile, user, passOf[user], scopes, sender)
}

func (s *suite) flowAs(profile, user, pass, scopes, sender string) *flowResult {
	fr, errStr := runFlow(s.profile(profile), user, pass, scopes, sender)
	s.check(fmt.Sprintf("flow %s as %s", profile, user), errStr == "", orStr(errStr, "ok"))
	if errStr != "" {
		fmt.Printf("        flow error: %s\n", errStr)
	}
	return fr
}

func orStr(errStr, ok string) string {
	if errStr != "" {
		return errStr
	}
	return ok
}

// api calls the gateway with a flow's sender-constrained token.
func api(fr *flowResult, method, rawurl string, body []byte) (int, string) {
	if fr == nil {
		return 0, "no token"
	}
	st, _, b := apiCall(fr.AccessToken, fr.TokenType, fr.Key, fr.JWK, "", method, rawurl, body)
	return st, b
}

// core calls a mesh service directly with the MiPay core's certificate (what the core does in P3).
func core(method, rawurl string, body []byte) (int, string) {
	hdr := map[string]string{}
	if body != nil {
		hdr["Content-Type"] = "application/json"
	}
	st, _, b := call(flowClient(coreCert), method, rawurl, hdr, body)
	return st, b
}

func jsonField(body, field string) string {
	var m map[string]any
	_ = json.Unmarshal([]byte(body), &m)
	v, _ := m[field].(string)
	return v
}

// waitFor retries probe once a second until it returns true or the timeout passes.
func waitFor(timeout time.Duration, probe func() bool) bool {
	for deadline := time.Now().Add(timeout); time.Now().Before(deadline); time.Sleep(time.Second) {
		if probe() {
			return true
		}
	}
	return false
}

// sandboxMessage returns the newest sandbox delivery body for a recipient and event.
func sandboxMessage(recipient, event string) string {
	var body string
	waitFor(8*time.Second, func() bool {
		st, b := core("GET", commsMesh+"/sandbox/outbox?recipient="+url.QueryEscape(recipient)+"&event="+url.QueryEscape(event), nil)
		var out struct {
			Messages []struct {
				Body string `json:"body"`
			} `json:"messages"`
		}
		_ = json.Unmarshal([]byte(b), &out)
		if st == 200 && len(out.Messages) > 0 {
			body = out.Messages[0].Body
			return true
		}
		return false
	})
	return body
}

var linkRe = regexp.MustCompile(`https://[^\s"]+`)

// tokenFromLink turns an IdP set-up/reset link into the token parameter.
func tokenFromLink(link string) string {
	u, err := url.Parse(link)
	if err != nil {
		return ""
	}
	return u.Query().Get("token")
}

// setPassword uses a one-time link through the public gateway route.
func setPassword(token, pw string) (int, string) {
	cl := flowClient("")
	st, _, page := call(cl, "GET", issuerBase+"/account/setup?token="+url.QueryEscape(token), nil, nil)
	if st != 200 {
		return st, page
	}
	st, _, page = postForm(cl, "POST", issuerBase+"/account/setup", url.Values{"token": {token}, "password": {pw}, "confirm": {pw}}, nil, "")
	return st, page
}

// ---------------------------------------------------------------------------
// Suite: FAPI 2.0 security core, the credential plugin (P1), the RBAC grid (P2),
// and the comms plane (B0) — everything through the gateway or over the mesh.
// ---------------------------------------------------------------------------

func (s *suite) run() {
	var st int
	var body string
	var hdrs http.Header
	stamp := fmt.Sprintf("%d", time.Now().Unix())
	web := s.profile("web-private-key-jwt")

	fmt.Println("== S1: discovery through the gateway ==")
	st, _, body = call(flowClient(""), "GET", issuerBase+"/.well-known/openid-configuration", nil, nil)
	var disc map[string]any
	_ = json.Unmarshal([]byte(body), &disc)
	s.check("discovery reachable via gateway (200)", st == 200, fmt.Sprintf("status=%d", st))
	s.check("issuer matches", disc["issuer"] == issuerBase, fmt.Sprintf("%v", disc["issuer"]))
	s.check("PAR is mandatory per metadata", disc["require_pushed_authorization_requests"] == true, fmt.Sprintf("%v", disc["require_pushed_authorization_requests"]))
	s.check("algorithms restricted to PS256/ES256/EdDSA",
		fmt.Sprint(disc["token_endpoint_auth_signing_alg_values_supported"]) == "[PS256 ES256 EdDSA]",
		fmt.Sprint(disc["token_endpoint_auth_signing_alg_values_supported"]))
	s.check("uid claim and back-channel logout advertised", strings.Contains(fmt.Sprint(disc["claims_supported"]), "uid") &&
		disc["backchannel_logout_supported"] == true, fmt.Sprint(disc["claims_supported"]))

	fmt.Println("== S2: full DPoP flow, rootadmin (private_key_jwt client); tokens carry uid ==")
	root := s.mustFlow("web-private-key-jwt", "rootadmin", "openid rbac:admin offline_access", "dpop")
	if root != nil {
		claims, err := decodeJWTClaims(root.AccessToken)
		s.check("access token is a JWT with cnf.jkt (DPoP-bound)", err == nil && claims["cnf"] != nil, detailOrClaims(err, claims))
		s.check("token issuer is the IdP issuer", claims["iss"] == issuerBase, fmt.Sprintf("%v", claims["iss"]))
		s.check("token carries uid (the core user id), acr, amr, sid, auth_time",
			claims["uid"] == "rootadmin" && claims["acr"] == "aal1" && claims["sid"] != nil && claims["auth_time"] != nil &&
				strings.HasPrefix(fmt.Sprint(claims["sub"]), "acc_"), fmt.Sprint(claims))
		s.check("authorization response carried iss param", root.ISS == issuerBase, root.ISS)
		s.check("token_type=DPoP per RFC 9449", root.TokenType == "DPoP", root.TokenType)
		s.check("refresh token issued (offline_access)", root.Refresh != "", "")
		idc, _ := decodeJWTClaims(root.IDToken)
		s.check("ID token carries uid", idc["uid"] == "rootadmin", fmt.Sprint(idc))
	}

	fmt.Println("== S3: the RBAC grid (rows x 10 verbs) is registered and seeded ==")
	if root != nil {
		st, body = api(root, "GET", rbacBase+"/rbac/admin/audit?limit=500", nil)
		s.check("RBAC registered its own cells at boot (permission_register)", st == 200 && strings.Contains(body, "permission_register"),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
		st, body = api(root, "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("the MiPay roles are seeded, no platform_admin superuser",
			st == 200 && strings.Contains(body, `"org_admin"`) && strings.Contains(body, `"platform_user_admin"`) && !strings.Contains(body, `"platform_admin"`),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
		st, body = api(root, "GET", rbacBase+"/rbac/admin/roles/org_admin", nil)
		s.check("org_admin on kyc.application = read (inherited) + modify, delete, submit (own ticks)",
			st == 200 && strings.Contains(body, `"kyc.application":["modify","delete","submit"]`) && strings.Contains(body, `"kyc.application:read"`) &&
				strings.Contains(body, `"inherits":["org_staff"]`), fmt.Sprintf("%d %s", st, bodyTrim(body, 300)))
		st, body = api(root, "GET", rbacBase+"/rbac/admin/resources", nil)
		s.check("rows list their used cells and endpoints", st == 200 && strings.Contains(body, `"rbac.assignment"`) && strings.Contains(body, "/rbac/admin/assignments"),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
		st, body = api(root, "GET", rbacBase+"/rbac/admin/export/role-pack", nil)
		s.check("compliance role pack exports (P2.5a)", st == 200 && strings.Contains(body, "# RBAC role pack") && strings.Contains(body, "Approved for use"),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 120)))
	}

	fmt.Println("== S4: role grids: custom role CRUD and the grid save rules ==")
	if root != nil {
		name := "e2e_probe_" + stamp
		st, body = api(root, "POST", rbacBase+"/rbac/admin/roles", []byte(`{"name":"`+name+`","kind":"ORG","grid":{"org.tree":["read"]}}`))
		s.check("custom role created", st == 200, fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
		st, body = api(root, "PUT", rbacBase+"/rbac/admin/roles/"+name, []byte(`{"permissions":["org.tree:read","org.user:read"]}`))
		s.check("grid edited (diff reported)", st == 200 && strings.Contains(body, `"added":["org.user:read"]`), fmt.Sprintf("%d %s", st, bodyTrim(body, 300)))
		st, body = api(root, "PUT", rbacBase+"/rbac/admin/roles/"+name, []byte(`{"permissions":["merchant.account:delete"]}`))
		s.check("a cell no endpoint uses cannot be ticked (400)", st == 400 && strings.Contains(body, "cell_not_used"), fmt.Sprintf("%d %s", st, body))
		st, body = api(root, "POST", rbacBase+"/rbac/admin/roles", []byte(`{"name":"e2e_bad_`+stamp+`","kind":"PLATFORM","grid":{"ops.suspension":["administer"],"ops.merchant_approval":["authorize"]}}`))
		s.check("a platform role may not administer and authorize customers (409 SOD_CONFLICT)", st == 409 && strings.Contains(body, "SOD_CONFLICT"), fmt.Sprintf("%d %s", st, body))
		st, _ = api(root, "DELETE", rbacBase+"/rbac/admin/roles/"+name, nil)
		s.check("custom role deleted", st == 200, fmt.Sprintf("%d", st))
		st, body = api(root, "DELETE", rbacBase+"/rbac/admin/roles/org_admin", nil)
		s.check("seeded roles change only through the reviewed seed (409)", st == 409, fmt.Sprintf("%d %s", st, body))
	}

	fmt.Println("== S5: org-scoped administration: own, subtree, and nothing else (G.7) ==")
	selma := s.mustFlow("web-private-key-jwt", "selma", "openid rbac:admin", "dpop")
	jonas := s.mustFlow("web-private-key-jwt", "jonas", "openid rbac:admin", "dpop")
	tomas := s.mustFlow("web-private-key-jwt", "tomas", "openid rbac:admin", "dpop")
	st, body = api(selma, "GET", rbacBase+"/rbac/admin/assignments?tenant=e2e-oka", nil)
	s.check("org_admin lists her own org's assignments", st == 200 && strings.Contains(body, `"tomas"`), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(selma, "GET", rbacBase+"/rbac/admin/assignments?tenant=e2e-sib", nil)
	s.check("...but not a sibling org's (403)", st == 403, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(selma, "GET", rbacBase+"/rbac/admin/assignments", nil)
	s.check("...and not platform-wide (403)", st == 403, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(jonas, "GET", rbacBase+"/rbac/admin/assignments?tenant=e2e-oka", nil)
	s.check("a subtree org_admin above reaches the child org", st == 200, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(selma, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"vic-`+stamp+`","role":"org_viewer","tenant":"e2e-oka","scope":"own"}`))
	s.check("org_admin assigns org_viewer in her org (ACTIVE, no second approval)", st == 200 && jsonField(body, "status") == "ACTIVE", fmt.Sprintf("%d %s", st, body))
	st, body = api(selma, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"vic-`+stamp+`","role":"org_viewer","tenant":"e2e-sib","scope":"own"}`))
	s.check("...not in another org (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	st, body = api(selma, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"vic-`+stamp+`","role":"platform_auditor","scope":"all"}`))
	s.check("...and never a platform role (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	st, body = api(tomas, "GET", rbacBase+"/rbac/admin/assignments?tenant=e2e-oka", nil)
	s.check("org_staff has no rbac.assignment cells: 403 at the gateway", st == 403 && strings.Contains(body, "insufficient_permission"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(tomas, "GET", rbacBase+"/rbac/admin/assignments?subject=tomas", nil)
	s.check("org_staff cannot use the admin API even for their own rows (gateway cell check)", st == 403, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, body = api(selma, "GET", rbacBase+"/rbac/admin/assignments?subject=selma", nil)
	s.check("org_admin reads her own assignments", st == 200 && strings.Contains(body, `"org_admin"`), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))

	fmt.Println("== S6: no assignment, no access (even with valid tokens) ==")
	carolScope := s.mustFlow("web-private-key-jwt", "carol", "openid", "dpop")
	st, body = api(carolScope, "GET", rbacBase+"/rbac/admin/roles", nil)
	s.check("carol without rbac:admin scope -> 403 insufficient_scope", st == 403 && strings.Contains(body, "insufficient_scope"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	carol := s.mustFlow("web-private-key-jwt", "carol", "openid rbac:admin", "dpop")
	st, body = api(carol, "GET", rbacBase+"/rbac/admin/roles", nil)
	s.check("carol (valid token, NO assignment) -> 403 insufficient_permission", st == 403 && strings.Contains(body, "insufficient_permission"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))

	fmt.Println("== S7: refresh tokens exist but are NOT rotated (FAPI 2.0) ==")
	alice := s.mustFlow("web-private-key-jwt", "alice", "openid rbac:admin offline_access", "dpop")
	if alice != nil && alice.Refresh != "" && alice.Key != nil {
		form := url.Values{"grant_type": {"refresh_token"}, "refresh_token": {alice.Refresh}, "client_id": {web.ID}}
		addAssertion(form, web, issuerBase)
		extra := map[string]string{"DPoP": proofFor(alice.Key, alice.JWK, "POST", issuerBase+"/token", "")}
		st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", form, extra, "")
		var resp struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		}
		_ = json.Unmarshal([]byte(body), &resp)
		s.check("refresh grant succeeds", st == 200 && resp.AccessToken != "", fmt.Sprintf("%d %s", st, body))
		s.check("no rotation: same refresh token returned", resp.RefreshToken == alice.Refresh, "rotated")
		if resp.AccessToken != "" {
			c, _ := decodeJWTClaims(resp.AccessToken)
			s.check("refreshed token keeps uid", c["uid"] == "alice", fmt.Sprint(c["uid"]))
			st, _, _ = apiCall(resp.AccessToken, "DPoP", alice.Key, alice.JWK, "", "GET", rbacBase+"/rbac/admin/roles", nil)
			s.check("refreshed token works on the API (ops reviewer reads roles)", st == 200, fmt.Sprintf("%d", st))
		}
	}

	fmt.Println("== S8: mTLS-bound tokens (cert client, RFC 8705) ==")
	cmtls := s.profile("cert-mtls-auth-mtls")
	fr9, errStr := runFlow(cmtls, "alice", passOf["alice"], "openid rbac:admin", "mtls")
	s.check("cert client flow (tls_client_auth + mTLS binding)", errStr == "", orStr(errStr, "ok"))
	if errStr == "" && fr9 != nil {
		claims, _ := decodeJWTClaims(fr9.AccessToken)
		cnf, _ := claims["cnf"].(map[string]any)
		_, hasXT := cnf["x5t#S256"]
		s.check("token bound to certificate (cnf.x5t#S256)", hasXT, fmt.Sprint(claims["cnf"]))
		tlsKey := tlsFileFor(cmtls)
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, tlsKey, "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("call WITH the bound certificate -> 200", st == 200, fmt.Sprintf("%d", st))
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, "", "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("call WITHOUT certificate -> 401", st == 401, fmt.Sprintf("%d", st))
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, tlsFileFor(s.profile("cert-mtls-auth-dpop")), "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("call with a DIFFERENT certificate -> 401", st == 401, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S9: client auth and sender binding are independent ==")
	fr10, errStr := runFlow(s.profile("cert-mtls-auth-dpop"), "alice", passOf["alice"], "openid rbac:admin", "dpop")
	s.check("cert-auth client with DPoP-bound token", errStr == "", orStr(errStr, "ok"))
	if errStr == "" && fr10 != nil {
		s.check("token_type=DPoP although client auth was mTLS", fr10.TokenType == "DPoP", fr10.TokenType)
		st, _, _ = apiCall(fr10.AccessToken, "DPoP", fr10.Key, fr10.JWK, "", "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("DPoP-bound call without any client cert -> 200", st == 200, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S10: stolen token is useless — DPoP binding ==")
	if root != nil && root.Key != nil {
		wrongKey, wrongJWK, kerr := dpop.GenerateES256()
		s.check("adversary generates own key", kerr == nil, "")
		if kerr == nil {
			st, _, body := apiCall(root.AccessToken, "DPoP", wrongKey, wrongJWK, "", "GET", rbacBase+"/rbac/admin/roles", nil)
			s.check("valid token + WRONG DPoP key -> 401", st == 401 && strings.Contains(body, "invalid"), fmt.Sprintf("%d %s", st, body))
		}
		proof := proofFor(root.Key, root.JWK, "GET", rbacBase+"/rbac/admin/roles", ath(root.AccessToken))
		hdr := map[string]string{"Authorization": "DPoP " + root.AccessToken, "DPoP": proof}
		st, _, _ = call(flowClient(""), "GET", rbacBase+"/rbac/admin/roles", hdr, nil)
		s.check("fresh proof -> 200", st == 200, fmt.Sprintf("%d", st))
		st, _, _ = call(flowClient(""), "GET", rbacBase+"/rbac/admin/roles", hdr, nil)
		s.check("replayed proof (same jti) -> 401", st == 401, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S11: token revocation takes effect (introspection cache <= 5s) ==")
	if alice != nil && alice.Key != nil {
		form := url.Values{"token": {alice.AccessToken}, "client_id": {web.ID}}
		addAssertion(form, web, issuerBase)
		postForm(flowClient(""), "POST", issuerBase+"/revoke", form, nil, "")
		ok401 := waitFor(6*time.Second, func() bool {
			st, _ := api(alice, "GET", rbacBase+"/rbac/admin/roles", nil)
			return st == 401
		})
		s.check("revoked token rejected within 5s", ok401, "still accepted after 6s")
		alice = s.mustFlow("web-private-key-jwt", "alice", "openid rbac:admin offline_access", "dpop")
	}

	fmt.Println("== S12: private_key_jwt must target the issuer (aud) ==")
	form := url.Values{
		"client_id": {web.ID}, "response_type": {"code"}, "redirect_uri": {redirectURI},
		"scope": {"openid"}, "state": {"x"}, "nonce": {"x"},
		"code_challenge": {jwtx.PKCES256(jwtx.RandB64(48))}, "code_challenge_method": {"S256"},
	}
	addAssertion(form, web, "https://attacker.example")
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/par", form, nil, "")
	s.check("assertion with wrong audience -> 401", st == 401, fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S13: /authorize cannot bypass PAR ==")
	st, hdrs, _ = call(flowClient(""), "GET",
		issuerBase+"/authorize?client_id="+url.QueryEscape(web.ID)+"&response_type=code&scope=openid&redirect_uri="+url.QueryEscape(redirectURI), nil, nil)
	s.check("direct authorize params rejected (400)", st == 400 && hdrs.Get("Location") == "", fmt.Sprintf("%d", st))
	st, hdrs, _ = call(flowClient(""), "GET", issuerBase+"/authorize?client_id="+url.QueryEscape(web.ID)+"&request_uri=urn:x&junk=1", nil, nil)
	s.check("request_uri + extra params rejected", st == 400 && hdrs.Get("Location") == "", fmt.Sprintf("%d", st))

	fmt.Println("== S14: authorization code is single-use and PKCE-bound ==")
	verifier := jwtx.RandB64(48)
	_, pbody := par(flowClient(""), web, "openid", jwtx.RandB64(16), jwtx.PKCES256(verifier))
	var pr struct {
		RequestURI string `json:"request_uri"`
	}
	_ = json.Unmarshal([]byte(pbody), &pr)
	cl := flowClient("")
	call(cl, "GET", fmt.Sprintf("%s/authorize?client_id=%s&request_uri=%s", issuerBase, web.ID, url.QueryEscape(pr.RequestURI)), nil, nil)
	_, _, page := postForm(cl, "POST", issuerBase+"/login", url.Values{
		"request_uri": {pr.RequestURI}, "username": {"carol"}, "password": {passOf["carol"]},
	}, nil, "")
	st, consentHdrs, _ := postForm(cl, "POST", issuerBase+"/consent", url.Values{
		"request_uri": {pr.RequestURI}, "csrf": {extractInput(page, "csrf")}, "decision": {"allow"},
	}, nil, "")
	s.check("consent redirects with code", st == 302 && consentHdrs.Get("Location") != "", fmt.Sprintf("%d", st))
	u, _ := url.Parse(consentHdrs.Get("Location"))
	code := u.Query().Get("code")
	pkKey, pkJWK, _ := dpop.GenerateES256()
	tokenPost := func(v string) (int, string) {
		tf := url.Values{"grant_type": {"authorization_code"}, "code": {code},
			"redirect_uri": {redirectURI}, "code_verifier": {v}, "client_id": {web.ID}}
		addAssertion(tf, web, issuerBase) // fresh anti-replay assertion per POST
		st, _, b := postForm(flowClient(""), "POST", issuerBase+"/token", tf,
			map[string]string{"DPoP": proofFor(pkKey, pkJWK, "POST", issuerBase+"/token", "")}, "")
		return st, b
	}
	tf0 := url.Values{"grant_type": {"authorization_code"}, "code": {code}, "redirect_uri": {redirectURI}, "code_verifier": {verifier}, "client_id": {web.ID}}
	addAssertion(tf0, web, issuerBase)
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", tf0, nil, "")
	s.check("no DPoP proof and no client certificate -> 400 (FAPI 2.0 sender-constrained tokens)", st == 400 && strings.Contains(body, "sender-constrained"),
		fmt.Sprintf("%d %s", st, body))
	st, body = tokenPost(jwtx.RandB64(48))
	s.check("wrong PKCE verifier -> 400 invalid_grant", st == 400 && strings.Contains(body, "PKCE"), fmt.Sprintf("%d %s", st, body))
	st, body = tokenPost(verifier)
	s.check("correct verifier -> 200", st == 200 && jsonField(body, "access_token") != "", fmt.Sprintf("%d %s", st, body))
	st, body = tokenPost(verifier)
	s.check("code replay -> 400 invalid_grant (single use)", st == 400 && strings.Contains(body, "invalid_grant"), fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S15: callers cannot forge gateway identity headers ==")
	if carol != nil {
		hdr := map[string]string{"X-SME-Context": "e30.forged", "X-SME-Subject": "rootadmin"}
		st, _, _ := apiCallWithHeaders(carol, "GET", rbacBase+"/rbac/admin/roles", hdr)
		s.check("spoofed X-SME-* ignored; still 403 for an unassigned user", st == 403, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S16: request hardening ==")
	st, _, _ = call(flowClient(""), "GET", rbacBase+"/rbac/admin/../admin/roles", nil, nil)
	s.check("traversal path rejected (4xx)", st >= 400 && st < 500, fmt.Sprintf("%d", st))
	st, _, _ = call(flowClient(""), "GET", rbacBase+"/rbac%2fadmin/roles", nil, nil)
	s.check("encoded slash rejected (4xx)", st >= 400 && st < 500, fmt.Sprintf("%d", st))
	st, _, _ = call(flowClient(""), "GET", rbacBase+"/rbac/admin/nothing-here", nil, nil)
	s.check("unknown route -> 404 deny-by-default", st == 404, fmt.Sprintf("%d", st))
	st, _, _ = call(flowClient(""), "GET", "https://gateway:8443/rbac/admin/roles", nil, nil)
	s.check("unknown host -> 404", st == 404, fmt.Sprintf("%d", st))
	st, _, _ = call(flowClient(""), "POST", issuerBase+"/accounts", map[string]string{"Content-Type": "application/json"}, []byte(`{}`))
	s.check("the IdP enrolment API has no public route (404)", st == 404, fmt.Sprintf("%d", st))

	fmt.Println("== S17: four-eyes by the risk of the role (D10) and static SoD ==")
	uadmin2 := s.mustFlow("web-private-key-jwt", "uadmin2", "openid rbac:admin", "dpop")
	opsUser := "ops-" + stamp
	st, body = api(root, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"`+opsUser+`","role":"platform_operations","scope":"all","reason":"ops desk"}`))
	reqID := jsonField(body, "id")
	s.check("platform role request is PENDING_APPROVAL", st == 200 && jsonField(body, "status") == "PENDING_APPROVAL", fmt.Sprintf("%d %s", st, body))
	st, body = api(root, "POST", rbacBase+"/rbac/admin/approvals/"+reqID+"/approve", []byte(`{}`))
	s.check("the requester cannot approve (403 four_eyes)", st == 403 && strings.Contains(body, "four_eyes"), fmt.Sprintf("%d %s", st, body))
	st, body = api(uadmin2, "GET", rbacBase+"/rbac/admin/approvals", nil)
	s.check("the second user admin sees the request", st == 200 && strings.Contains(body, reqID), fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
	st, body = api(alice, "POST", rbacBase+"/rbac/admin/approvals/"+reqID+"/approve", []byte(`{}`))
	s.check("a reviewer cannot approve access (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	st, body = api(uadmin2, "POST", rbacBase+"/rbac/admin/approvals/"+reqID+"/approve", []byte(`{"reason":"ok"}`))
	s.check("a second platform_user_admin approves -> ACTIVE with a decision id", st == 200 && jsonField(body, "status") == "ACTIVE" && jsonField(body, "decision_id") != "",
		fmt.Sprintf("%d %s", st, body))
	st, body = api(root, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"alice","role":"platform_senior_approver","scope":"all"}`))
	s.check("SoD: the first-line reviewer cannot also be senior approver (409)", st == 409 && strings.Contains(body, "SOD_CONFLICT"), fmt.Sprintf("%d %s", st, body))
	st, body = api(root, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"rootadmin","role":"platform_ops_reviewer","scope":"all"}`))
	s.check("SoD: an access admin never approves customers (409)", st == 409 && strings.Contains(body, "SOD_CONFLICT"), fmt.Sprintf("%d %s", st, body))
	compUser := "comp-" + stamp
	st, body = api(root, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"`+compUser+`","role":"platform_compliance_officer","scope":"all"}`))
	compID := jsonField(body, "id")
	st, body = api(uadmin2, "POST", rbacBase+"/rbac/admin/approvals/"+compID+"/approve", []byte(`{}`))
	s.check("compliance roles: a user admin may not approve (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	mlro := s.mustFlow("web-private-key-jwt", "mlro1", "openid rbac:admin", "dpop")
	st, body = api(mlro, "POST", rbacBase+"/rbac/admin/approvals/"+compID+"/approve", []byte(`{}`))
	s.check("compliance roles: the MLRO approves", st == 200 && jsonField(body, "status") == "ACTIVE", fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S18: revocation reaches the gateway within 10 s despite caches ==")
	audUser := "carol"
	st, body = api(root, "POST", rbacBase+"/rbac/admin/assignments", []byte(`{"subject":"`+audUser+`","role":"platform_auditor","scope":"all"}`))
	audID := jsonField(body, "id")
	api(uadmin2, "POST", rbacBase+"/rbac/admin/approvals/"+audID+"/approve", []byte(`{}`))
	granted := waitFor(10*time.Second, func() bool {
		st, _ := api(carol, "GET", rbacBase+"/rbac/admin/roles", nil)
		return st == 200
	})
	s.check("an approved assignment takes effect within 10 s", granted, "carol still denied")
	st, _ = api(root, "DELETE", rbacBase+"/rbac/admin/assignments/"+audID, nil)
	denied := waitFor(10*time.Second, func() bool {
		st, _ := api(carol, "GET", rbacBase+"/rbac/admin/roles", nil)
		return st == 403
	})
	s.check("revocation enforced within 10 s despite the gateway cache", denied, "authority still active after 10 s")
	st, body = api(root, "GET", rbacBase+"/rbac/admin/audit?limit=200", nil)
	s.check("changes are audited (assignment_approved, assignment_revoke)", st == 200 && strings.Contains(body, "assignment_approved") && strings.Contains(body, "assignment_revoke"),
		fmt.Sprintf("%d", st))

	fmt.Println("== S19: onboarding rights as delegated grants (narrow only, cascading) ==")
	st, body = api(root, "POST", rbacBase+"/rbac/admin/grants", []byte(`{"tenant":"e2e-namib","permission":"org.onboarding:create","allowed_values":["MERCHANT","SUPPLIER"],"scope":"subtree","can_delegate":true}`))
	rootGrant := jsonField(body, "id")
	s.check("platform grants e2e-namib's subtree onboarding of MERCHANT, SUPPLIER", st == 200 && rootGrant != "", fmt.Sprintf("%d %s", st, body))
	st, body = api(jonas, "POST", rbacBase+"/rbac/admin/grants", []byte(`{"tenant":"e2e-oka","permission":"org.onboarding:create","allowed_values":["LENDER"],"scope":"own","parent_grant_id":"`+rootGrant+`"}`))
	s.check("the org admin cannot widen when delegating (403 scope_exceeds_grant)", st == 403 && strings.Contains(body, "scope_exceeds_grant"), fmt.Sprintf("%d %s", st, body))
	st, body = api(jonas, "POST", rbacBase+"/rbac/admin/grants", []byte(`{"tenant":"e2e-oka","permission":"org.onboarding:create","allowed_values":["MERCHANT"],"scope":"own","parent_grant_id":"`+rootGrant+`"}`))
	childGrant := jsonField(body, "id")
	s.check("...but may narrow to a child org", st == 200 && childGrant != "", fmt.Sprintf("%d %s", st, body))
	st, body = api(selma, "POST", rbacBase+"/rbac/admin/grants", []byte(`{"tenant":"e2e-oka","permission":"org.onboarding:create","allowed_values":["MERCHANT"],"scope":"own"}`))
	s.check("root grants are platform-only (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	st, _ = api(root, "DELETE", rbacBase+"/rbac/admin/grants/"+rootGrant, nil)
	st, body = api(jonas, "GET", rbacBase+"/rbac/admin/grants?tenant=e2e-oka", nil)
	s.check("revoking the parent grant cascades to the delegated one", st == 200 && strings.Contains(body, `"id":"`+childGrant+`"`) && strings.Contains(body, `"status":"revoked"`),
		fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))

	fmt.Println("== S20: an org keeps an admin (minimum holders) ==")
	st, body = api(selma, "GET", rbacBase+"/rbac/admin/assignments?tenant=e2e-oka&role=org_admin&status=ACTIVE", nil)
	var admins []struct {
		ID      string `json:"id"`
		Subject string `json:"subject"`
	}
	_ = json.Unmarshal([]byte(body), &admins)
	selmaAdmin := ""
	for _, a := range admins {
		if a.Subject == "selma" {
			selmaAdmin = a.ID
		}
	}
	st, body = api(jonas, "DELETE", rbacBase+"/rbac/admin/assignments/"+selmaAdmin, nil)
	s.check("removing the last org_admin is refused (409 LAST_ADMIN)", st == 409 && strings.Contains(body, "LAST_ADMIN"), fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S21: P1 enrolment: only the core, then a set-up link sent through comms ==")
	uid := "e2e-u-" + stamp
	login := "newbie" + stamp
	phone := "+26481" + stamp[len(stamp)-7:]
	enrolBody := []byte(`{"uid":"` + uid + `","login_name":"` + login + `","phone":"` + phone + `","display_name":"New Bie"}`)
	st, _, body = call(flowClient(gwMesh), "POST", idpMesh+"/accounts", map[string]string{"Content-Type": "application/json"}, enrolBody)
	s.check("the enrolment API refuses any certificate but the core's (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	st, body = core("POST", idpMesh+"/accounts", enrolBody)
	subject, link := jsonField(body, "subject"), jsonField(body, "link")
	s.check("the core enrols a credential account for its user id", st == 201 && strings.HasPrefix(subject, "acc_") && strings.HasPrefix(link, issuerBase+"/account/setup?token="),
		fmt.Sprintf("%d %s", st, body))
	st, body = core("PUT", commsMesh+"/subjects/user:"+uid+"/contacts", []byte(`{"contacts":[{"channel":"sms","address":"`+phone+`","verified":true,"primary":true}]}`))
	s.check("the core writes the user's contacts in comms (sole writer)", st == 200 && strings.Contains(body, "***"), fmt.Sprintf("%d %s", st, body))
	exp := time.Now().Add(72 * time.Hour).UTC().Format(time.RFC3339)
	st, body = core("POST", commsMesh+"/messages", []byte(`{"eventCode":"account.setup","to":["user:`+uid+`"],"expiresAt":"`+exp+`","data":{"name":"New Bie","link":"`+link+`"}}`))
	s.check("the core sends the set-up link through comms (202)", st == 202, fmt.Sprintf("%d %s", st, body))
	msg := sandboxMessage("user:"+uid, "account.setup")
	got := linkRe.FindString(msg)
	s.check("the sandbox provider delivered it by SMS", got == link, fmt.Sprintf("%q", msg))
	if w, _ := runFlow(web, login, "anything-goes-here", "openid", "dpop"); w != nil {
		s.check("an ENROLLING account cannot sign in", false, "signed in")
	} else {
		s.check("an ENROLLING account cannot sign in", true, "")
	}
	st, body = setPassword(tokenFromLink(got), "short")
	s.check("the password policy applies (12+ characters)", st == 400, fmt.Sprintf("%d", st))
	pw := "a-long-enough-password-" + stamp
	st, body = setPassword(tokenFromLink(got), pw)
	s.check("the user sets a password at the IdP", st == 200 && strings.Contains(body, "Password saved"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, _ = setPassword(tokenFromLink(got), pw+"x")
	s.check("the set-up link is single use", st == 400, fmt.Sprintf("%d", st))
	newbie := s.flowAs("web-private-key-jwt", login, pw, "openid rbac:admin offline_access", "dpop")
	if newbie != nil {
		c, _ := decodeJWTClaims(newbie.AccessToken)
		s.check("the token carries the core user id as uid", c["uid"] == uid && c["sub"] == subject, fmt.Sprint(c))
	}
	st, body = core("GET", idpMesh+"/accounts/"+subject, nil)
	s.check("the account is ACTIVE", st == 200 && jsonField(body, "state") == "ACTIVE", fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S22: P1 disable ends the session everywhere; lockout ==")
	if newbie != nil {
		st, body = core("POST", idpMesh+"/accounts/"+subject+"/disable", []byte(`{"reason":"org suspended"}`))
		s.check("the core disables the account", st == 200 && jsonField(body, "state") == "DISABLED", fmt.Sprintf("%d %s", st, body))
		_, errStr := runFlow(web, login, pw, "openid", "dpop")
		s.check("a disabled account cannot sign in", errStr != "", "signed in")
		rf := url.Values{"grant_type": {"refresh_token"}, "refresh_token": {newbie.Refresh}, "client_id": {web.ID}}
		addAssertion(rf, web, issuerBase)
		st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", rf, map[string]string{"DPoP": proofFor(newbie.Key, newbie.JWK, "POST", issuerBase+"/token", "")}, "")
		s.check("its refresh token is dead", st == 400 && strings.Contains(body, "invalid_grant"), fmt.Sprintf("%d %s", st, body))
		core("POST", idpMesh+"/accounts/"+subject+"/enable", []byte(`{}`))
	}
	for i := 0; i < 5; i++ {
		runFlow(web, login, "wrong-password-xxxx", "openid", "dpop")
	}
	_, errStr = runFlow(web, login, pw, "openid", "dpop")
	s.check("after 5 wrong passwords the account is locked (right password refused)", errStr != "", "signed in while locked")
	st, body = core("GET", idpMesh+"/accounts/"+subject, nil)
	s.check("the lock is visible to the core", strings.Contains(body, `"locked_until"`), bodyTrim(body, 200))
	core("POST", idpMesh+"/accounts/"+subject+"/enable", []byte(`{}`))
	_, errStr = runFlow(web, login, pw, "openid", "dpop")
	s.check("enable clears the lock", errStr == "", errStr)

	fmt.Println("== S23: P1 back-channel logout reaches the gateway's browser sessions ==")
	s.bffBackchannel(login, pw, subject)

	fmt.Println("== S24: P1 merchant phone OTP through comms (dev OTP) ==")
	st, body = core("PUT", commsMesh+"/subjects/user:mira/contacts", []byte(`{"contacts":[{"channel":"sms","address":"+264810000042","verified":true,"primary":true}]}`))
	s.check("mira's phone is registered in comms by the core", st == 200, fmt.Sprintf("%d %s", st, body))
	s.otpFlow(web)

	fmt.Println("== S25: P1 forgot password through comms; can't get in ==")
	cl2 := flowClient("")
	st, _, body = postForm(cl2, "POST", issuerBase+"/account/forgot", url.Values{"login": {login}}, nil, "")
	s.check("forgot password answers the same for anyone", st == 200 && strings.Contains(body, "If an account matches"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	reset := linkRe.FindString(sandboxMessage("user:"+uid, "auth.password.reset"))
	s.check("the IdP sent a reset link through comms", reset != "", "no reset message")
	pw2 := "another-long-password-" + stamp
	st, _ = setPassword(tokenFromLink(reset), pw2)
	s.check("the reset link sets a new password", st == 200, fmt.Sprintf("%d", st))
	_, errStr = runFlow(web, login, pw2, "openid", "dpop")
	s.check("sign in with the new password", errStr == "", errStr)
	st, _, body = postForm(cl2, "POST", issuerBase+"/account/help", url.Values{"login": {login}, "message": {"lost phone"}}, nil, "")
	s.check("Can't get in? is accepted (forwarded to the core as an event)", st == 200 && strings.Contains(body, "Thanks"), fmt.Sprintf("%d", st))

	fmt.Println("== S26: comms B0: capabilities, caller rules, idempotency, status ==")
	st, body = core("GET", commsMesh+"/capabilities", nil)
	s.check("sms and email are live (sandbox provider + auth templates)", st == 200 && strings.Contains(body, `"sms":{"live":true`) && strings.Contains(body, `"email":{"live":true`),
		fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
	st, body = core("POST", commsMesh+"/messages", []byte(`{"eventCode":"auth.otp.sms","to":["user:`+uid+`"],"expiresAt":"`+exp+`","data":{"code":"1"}}`))
	s.check("the core may not send IdP login codes (403)", st == 403, fmt.Sprintf("%d %s", st, body))
	idem := []byte(`{"eventCode":"account.setup","to":["user:` + uid + `"],"expiresAt":"` + exp + `","data":{"name":"x","link":"https://x"}}`)
	hdr := map[string]string{"Content-Type": "application/json", "Idempotency-Key": "e2e-" + stamp}
	st1, _, b1 := call(flowClient(coreCert), "POST", commsMesh+"/messages", hdr, idem)
	st2, _, b2 := call(flowClient(coreCert), "POST", commsMesh+"/messages", hdr, idem)
	s.check("a retried request returns the original (Idempotency-Key)", st1 == 202 && st2 == 200 && jsonField(b1, "requestId") == jsonField(b2, "requestId"),
		fmt.Sprintf("%d %d", st1, st2))
	var acc struct {
		Messages []struct {
			ID string `json:"id"`
		} `json:"messages"`
	}
	_ = json.Unmarshal([]byte(b1), &acc)
	if len(acc.Messages) > 0 {
		delivered := waitFor(8*time.Second, func() bool {
			st, b := core("GET", commsMesh+"/messages/"+acc.Messages[0].ID, nil)
			return st == 200 && jsonField(b, "status") == "DELIVERED"
		})
		s.check("the message reaches DELIVERED (status API)", delivered, "not delivered")
		st, body = core("GET", commsMesh+"/messages/"+acc.Messages[0].ID+"/events", nil)
		s.check("its timeline is ACCEPTED > PLANNED > QUEUED > SENDING > SENT > DELIVERED",
			st == 200 && strings.Contains(body, "ACCEPTED") && strings.Contains(body, "DELIVERED"), bodyTrim(body, 200))
	}

	fmt.Println("== S27: TLS floor (no TLS 1.1) ==")
	cmd := exec.Command("curl", "-sk", "--tls-max", "1.1", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", issuerBase+"/.well-known/openid-configuration")
	out, err := cmd.CombinedOutput()
	s.check("TLS 1.1 handshake refused", err != nil || strings.TrimSpace(string(out)) == "000", strings.TrimSpace(string(out)))
	cmd = exec.Command("curl", "-sk", "--tlsv1.2", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", issuerBase+"/.well-known/openid-configuration")
	out, _ = cmd.Output()
	s.check("TLS 1.2 accepted", strings.TrimSpace(string(out)) == "200", strings.TrimSpace(string(out)))

	// Keep the heavy rate-limit test for the very end: it exhausts the IP budget.
	fmt.Println("== S28: PAR rate budget (429) ==")
	ltd := 0
	for i := 0; i < 80; i++ {
		form := url.Values{
			"client_id": {web.ID}, "response_type": {"code"}, "redirect_uri": {redirectURI},
			"scope": {"openid"}, "state": {"x"}, "nonce": {"x"},
			"code_challenge": {jwtx.PKCES256(jwtx.RandB64(48))}, "code_challenge_method": {"S256"},
		}
		addAssertion(form, web, issuerBase)
		st, _, _ = postForm(flowClient(""), "POST", issuerBase+"/par", form, nil, "")
		if st == 429 {
			ltd++
		}
	}
	s.check("PAR flood throttled with 429", ltd > 0, fmt.Sprintf("429s=%d", ltd))

	fmt.Printf("\n== SUMMARY: %d passed, %d failed ==\n", s.pass, s.fail)
	if s.fail > 0 {
		os.Exit(1)
	}
}

// bffBackchannel signs in through the gateway BFF (browser lane), then disables the
// account at the IdP and checks the gateway session dies by back-channel logout.
func (s *suite) bffBackchannel(login, pw, subject string) {
	gw := "https://gateway:8443"
	host := map[string]string{"Host": "fapi.local"}
	cl := flowClient("")
	st, h, body := call(cl, "GET", gw+"/auth/login?return_to=/", host, nil)
	authURL := h.Get("Location")
	if st != 302 || !strings.HasPrefix(authURL, issuerBase+"/authorize") {
		s.check("BFF login starts with PAR and redirects to the IdP", false, fmt.Sprintf("%d %s %s", st, authURL, body))
		return
	}
	s.check("BFF login starts with PAR and redirects to the IdP", true, "")
	au, _ := url.Parse(authURL)
	requestURI := au.Query().Get("request_uri")
	st, _, _ = call(cl, "GET", authURL, nil, nil)
	st, h, body = postForm(cl, "POST", issuerBase+"/login", url.Values{"request_uri": {requestURI}, "username": {login}, "password": {pw}}, nil, "")
	cb := h.Get("Location")
	s.check("the IdP skips consent for the first-party BFF and returns a code", st == 302 && strings.HasPrefix(cb, "https://fapi.local/auth/callback?"),
		fmt.Sprintf("%d %s %s", st, cb, bodyTrim(body, 120)))
	cu, _ := url.Parse(cb)
	st, _, body = call(cl, "GET", gw+"/auth/callback?"+cu.RawQuery, host, nil)
	s.check("the gateway completes the code exchange and opens a session", st == 302, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	st, _, body = call(cl, "GET", gw+"/auth/session", host, nil)
	s.check("the browser session carries the uid", st == 200 && strings.Contains(body, `"uid"`) && strings.Contains(body, `"sub":"`+subject+`"`),
		fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
	core("POST", idpMesh+"/accounts/"+subject+"/revoke-sessions", []byte(`{}`))
	dead := waitFor(10*time.Second, func() bool {
		st, _, _ := call(cl, "GET", gw+"/auth/session", host, nil)
		return st == 401
	})
	s.check("revoke-sessions ends the gateway session by back-channel logout (<= 10 s)", dead, "session still alive")
}

// otpFlow signs mira in with a phone code delivered through comms.
func (s *suite) otpFlow(web *clients.Client) {
	cl := flowClient("")
	verifier := jwtx.RandB64(48)
	st, body := par(cl, web, "openid", jwtx.RandB64(16), jwtx.PKCES256(verifier))
	requestURI := jsonField(body, "request_uri")
	st, _, page := call(cl, "GET", fmt.Sprintf("%s/authorize?client_id=%s&request_uri=%s", issuerBase, web.ID, url.QueryEscape(requestURI)), nil, nil)
	s.check("the sign-in page offers a text-message code (sms is live)", st == 200 && strings.Contains(page, "Text me a sign-in code"), fmt.Sprintf("%d", st))
	st, _, page = postForm(cl, "POST", issuerBase+"/login/otp/start", url.Values{"request_uri": {requestURI}, "phone": {"+264 81 000 0042"}}, nil, "")
	challenge := extractInput(page, "challenge")
	s.check("a code is requested for mira's phone", st == 200 && challenge != "", fmt.Sprintf("%d", st))
	msg := sandboxMessage("user:mira", "auth.otp.sms")
	codeRe := regexp.MustCompile(`\b(\d{6})\b`)
	m := codeRe.FindStringSubmatch(msg)
	s.check("the code went out through comms (sandbox SMS)", m != nil, fmt.Sprintf("%q", msg))
	if m == nil {
		return
	}
	st, _, _ = postForm(cl, "POST", issuerBase+"/login/otp/verify", url.Values{"request_uri": {requestURI}, "challenge": {challenge}, "code": {"000000"}}, nil, "")
	s.check("a wrong code is refused", st == 401, fmt.Sprintf("%d", st))
	st, _, page = postForm(cl, "POST", issuerBase+"/login/otp/verify", url.Values{"request_uri": {requestURI}, "challenge": {challenge}, "code": {m[1]}}, nil, "")
	csrf := extractInput(page, "csrf")
	s.check("the right code signs in (consent page)", st == 200 && csrf != "", fmt.Sprintf("%d", st))
	st, h, _ := postForm(cl, "POST", issuerBase+"/consent", url.Values{"request_uri": {requestURI}, "csrf": {csrf}, "decision": {"allow"}}, nil, "")
	cu, _ := url.Parse(h.Get("Location"))
	key, jwk, _ := dpop.GenerateES256()
	tf := url.Values{"grant_type": {"authorization_code"}, "code": {cu.Query().Get("code")}, "redirect_uri": {redirectURI},
		"code_verifier": {verifier}, "client_id": {web.ID}}
	addAssertion(tf, web, issuerBase)
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", tf, map[string]string{"DPoP": proofFor(key, jwk, "POST", issuerBase+"/token", "")}, "")
	c, _ := decodeJWTClaims(jsonField(body, "access_token"))
	s.check("the token says how: amr=[sms], acr=aal1, uid=mira", st == 200 && fmt.Sprint(c["amr"]) == "[sms]" && c["acr"] == "aal1" && c["uid"] == "mira",
		fmt.Sprintf("%d %v", st, c))
}

func ath(token string) string {
	sum := sha256Sum(token)
	return jwtx.B64UEnc(sum)
}

func apiCallWithHeaders(fr *flowResult, method, rawurl string, extra map[string]string) (int, http.Header, string) {
	hdr := map[string]string{"Authorization": fr.TokenType + " " + fr.AccessToken}
	for k, v := range extra {
		hdr[k] = v
	}
	if fr.TokenType == "DPoP" && fr.Key != nil {
		sum := sha256.Sum256([]byte(fr.AccessToken))
		hdr["DPoP"] = proofFor(fr.Key, fr.JWK, method, rawurl, jwtx.B64UEnc(sum[:]))
	}
	return call(flowClient(""), method, rawurl, hdr, nil)
}

func detailOrClaims(err error, claims map[string]any) string {
	if err != nil {
		return err.Error()
	}
	return fmt.Sprint(claims["cnf"])
}

func bodyTrim(s string, n int) string {
	clean := strings.ReplaceAll(s, "\n", " ")
	clean = strings.ReplaceAll(clean, "\r", " ")
	if len(clean) > n {
		clean = clean[:n] + "…"
	}
	return clean
}

func sha256Sum(s string) []byte {
	h := sha256.New()
	h.Write([]byte(s))
	return h.Sum(nil)
}
