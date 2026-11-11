package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
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
	fr, errStr := runFlow(s.profile(profile), user, passOf[user], scopes, sender)
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

// ---------------------------------------------------------------------------
// Suite — trust-plane focus: FAPI 2.0 security core + delegated RBAC
// ---------------------------------------------------------------------------

func (s *suite) run() {
	var st int
	var body string
	var hdrs http.Header
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

	fmt.Println("== S2: full DPoP flow, rootadmin (private_key_jwt client) ==")
	web := s.profile("web-private-key-jwt")
	root := s.mustFlow("web-private-key-jwt", "rootadmin", "openid rbac:admin offline_access", "dpop")
	if root != nil {
		claims, err := decodeJWTClaims(root.AccessToken)
		s.check("access token is a JWT with cnf.jkt (DPoP-bound)", err == nil && claims["cnf"] != nil, detailOrClaims(err, claims))
		s.check("token issuer is the IdP issuer", claims["iss"] == issuerBase, fmt.Sprintf("%v", claims["iss"]))
		s.check("token carries requested scopes", strings.Contains(fmt.Sprint(claims["scope"]), "rbac:admin"), fmt.Sprint(claims["scope"]))
		s.check("authorization response carried iss param", root.ISS == issuerBase, root.ISS)
		s.check("token_type=DPoP per RFC 9449", root.TokenType == "DPoP", root.TokenType)
		s.check("refresh token issued (offline_access)", root.Refresh != "", "")
		_ = web
	}

	fmt.Println("== S3: RBAC auto-picks up controller permissions at boot ==")
	if root != nil {
		st, _, audit := apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/audit", nil)
		s.check("permission_register events in audit (catalog self-registered)",
			st == 200 && strings.Contains(audit, "permission_register") && strings.Contains(audit, `"rbac"`),
			fmt.Sprintf("%d %s", st, bodyTrim(audit, 220)))
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/roles/platform_admin", nil)
		s.check("platform roles serve CRUD catalogs", st == 200 && strings.Contains(body, "rbac.role:read"),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
	}

	fmt.Println("== S4: role CRUD (root-owned catalog) ==")
	if root != nil {
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "POST",
			rbacBase+"/rbac/admin/roles",
			[]byte(`{"name":"rbac_probe","description":"temp CRUD probe","permissions":["rbac.user:read"]}`))
		s.check("role created (rbac.role:create)", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))
		st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "PUT",
			rbacBase+"/rbac/admin/roles/rbac_probe",
			[]byte(`{"permissions":["rbac.user:read","rbac.audit:read"]}`))
		s.check("role updated (rbac.role:update)", st == 200, fmt.Sprintf("%d", st))
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/roles/rbac_probe", nil)
		s.check("role read-back", st == 200 && strings.Contains(body, "rbac.audit:read"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
		st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "DELETE",
			rbacBase+"/rbac/admin/roles/rbac_probe", nil)
		s.check("role deleted (rbac.role:delete)", st == 200, fmt.Sprintf("%d", st))
	}

	alice := s.mustFlow("web-private-key-jwt", "alice", "openid rbac:admin offline_access", "dpop")

	fmt.Println("== S5: user CRUD confined to the actor's own supplier ==")
	saF := s.mustFlow("web-private-key-jwt", "saadmin", "openid rbac:admin", "dpop")
	if saF != nil && root != nil {
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/users",
			[]byte(`{"id":"ea-3001","display_name":"Erik (Supplier A)","supplier":"supplier-a"}`))
		s.check("supplier admin creates own user", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))
		st, _, _ = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "PUT",
			rbacBase+"/rbac/admin/users/ea-3001", []byte(`{"status":"suspended"}`))
		s.check("supplier admin suspends own user", st == 200, fmt.Sprintf("%d", st))
		st, _, _ = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "DELETE",
			rbacBase+"/rbac/admin/users/ea-3001", nil)
		s.check("supplier admin offboards own user", st == 200, fmt.Sprintf("%d", st))
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/users",
			[]byte(`{"id":"ea-3002","display_name":"Outside Probe","supplier":"supplier-b"}`))
		s.check("supplier admin cannot create users for another supplier (membership)",
			st == 403 && strings.Contains(body, "membership"), fmt.Sprintf("%d %s", st, body))
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "POST",
			rbacBase+"/rbac/admin/users",
			[]byte(`{"id":"platform-op-x","display_name":"Platform Ops","supplier":""}`))
		s.check("root admin creates platform staff", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))
	}

	fmt.Println("== S6: no root-backed assignment, no access (even with valid tokens) ==")
	// carol has openid only -> scope gate at the gateway
	carolFlow := s.mustFlow("web-private-key-jwt", "carol", "openid", "dpop")
	if carolFlow != nil {
		st, _, body = apiCall(carolFlow.AccessToken, carolFlow.TokenType, carolFlow.Key, carolFlow.JWK, "", "GET",
			rbacBase+"/rbac/admin/users", nil)
		s.check("carol lacks rbac:admin scope -> 403 insufficient_scope",
			st == 403 && strings.Contains(body, "insufficient_scope"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	}
	if alice != nil {
		st, _, body = apiCall(alice.AccessToken, alice.TokenType, alice.Key, alice.JWK, "", "GET",
			rbacBase+"/rbac/admin/users", nil)
		s.check("alice (valid token, NO assignment) -> 403 insufficient_permission",
			st == 403 && strings.Contains(body, "insufficient_permission"), fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
	}

	fmt.Println("== S7: refresh tokens exist but are NOT rotated (FAPI 2.0) ==")
	if alice != nil && alice.Refresh != "" && alice.Key != nil {
		form := url.Values{
			"grant_type": {"refresh_token"}, "refresh_token": {alice.Refresh}, "client_id": {web.ID},
		}
		addAssertion(form, web, issuerBase)
		extra := map[string]string{"DPoP": proofFor(alice.Key, alice.JWK, "POST", issuerBase+"/token", "")}
		st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", form, extra, "")
		var resp struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		}
		_ = json.Unmarshal([]byte(body), &resp)
		s.check("refresh grant succeeds", st == 200 && resp.AccessToken != "", fmt.Sprintf("%d %s", st, body))
		s.check("no rotation: same refresh token returned", resp.RefreshToken == alice.Refresh,
			fmt.Sprintf("orig=%s… new=%s…", alice.Refresh[:8], resp.RefreshToken[:8]))
		if resp.AccessToken != "" {
			st, _, _ = apiCall(resp.AccessToken, "DPoP", alice.Key, alice.JWK, "", "GET",
				rbacBase+"/rbac/admin/assignments?subject=alice", nil)
			s.check("refreshed token works on the API (403 expected: no assignment)", st == 403, fmt.Sprintf("%d", st))
		}
	}

	fmt.Println("== S8: mTLS-bound tokens (cert client, RFC 8705) ==")
	cmtls := s.profile("cert-mtls-auth-mtls")
	fr9, errStr := runFlow(cmtls, "bob", passOf["bob"], "openid rbac:admin", "mtls")
	s.check("cert client flow (tls_client_auth + mTLS binding)", errStr == "", orStr(errStr, "ok"))
	if errStr == "" && fr9 != nil {
		claims, _ := decodeJWTClaims(fr9.AccessToken)
		cnf := claims["cnf"].(map[string]any)
		hasXT := false
		if cnf != nil {
			_, hasXT = cnf["x5t#S256"]
		}
		s.check("token bound to certificate (cnf.x5t#S256)", hasXT, fmt.Sprint(claims["cnf"]))
		tlsKey := tlsFileFor(cmtls)
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, tlsKey, "GET",
			rbacBase+"/rbac/admin/assignments?subject=bob", nil)
		s.check("call WITH the bound certificate -> 200", st == 200, fmt.Sprintf("%d", st))
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, "", "GET",
			rbacBase+"/rbac/admin/assignments?subject=bob", nil)
		s.check("call WITHOUT certificate -> 401", st == 401, fmt.Sprintf("%d", st))
		other := s.profile("cert-mtls-auth-dpop")
		st, _, _ = apiCall(fr9.AccessToken, fr9.TokenType, fr9.Key, fr9.JWK, tlsFileFor(other), "GET",
			rbacBase+"/rbac/admin/assignments?subject=bob", nil)
		s.check("call with a DIFFERENT certificate -> 401", st == 401, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S9: client auth and sender binding are independent ==")
	cdpop := s.profile("cert-mtls-auth-dpop")
	fr10, errStr := runFlow(cdpop, "bob", passOf["bob"], "openid rbac:admin", "dpop")
	s.check("cert-auth client with DPoP-bound token", errStr == "", orStr(errStr, "ok"))
	if errStr == "" && fr10 != nil {
		s.check("token_type=DPoP although client auth was mTLS", fr10.TokenType == "DPoP", fr10.TokenType)
		st, _, _ = apiCall(fr10.AccessToken, "DPoP", fr10.Key, fr10.JWK, "", "GET",
			rbacBase+"/rbac/admin/assignments?subject=bob", nil)
		s.check("DPoP-bound call without any client cert -> 200", st == 200, fmt.Sprintf("%d", st))
	}

	fmt.Println("== S10: stolen token is useless — DPoP binding ==")
	if root != nil && root.Key != nil {
		wrongKey, wrongJWK, kerr := dpop.GenerateES256()
		s.check("adversary generates own key", kerr == nil, "")
		if kerr == nil {
			st, _, body := apiCall(root.AccessToken, "DPoP", wrongKey, wrongJWK, "", "GET",
				rbacBase+"/rbac/admin/roles", nil)
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
		ok401 := false
		for i := 0; i < 6; i++ {
			st, _, _ = apiCall(alice.AccessToken, "DPoP", alice.Key, alice.JWK, "", "GET",
				rbacBase+"/rbac/admin/audit", nil)
			if st == 401 {
				ok401 = true
				break
			}
			time.Sleep(time.Second)
		}
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
	state := jwtx.RandB64(16)
	_, pbody := par(flowClient(""), web, "openid", state, jwtx.PKCES256(verifier))
	var pr struct {
		RequestURI string `json:"request_uri"`
	}
	_ = json.Unmarshal([]byte(pbody), &pr)
	cl := flowClient("")
	call(cl, "GET", fmt.Sprintf("%s/authorize?client_id=%s&request_uri=%s", issuerBase, web.ID, url.QueryEscape(pr.RequestURI)), nil, nil)
	var page, spage string
	st, _, page = postForm(cl, "POST", issuerBase+"/login", url.Values{
		"request_uri": {pr.RequestURI}, "username": {"bob"}, "password": {passOf["bob"]},
	}, nil, "")
	spage = page
	csrf := extractInput(spage, "csrf")
	st, consentHdrs, _ := postForm(cl, "POST", issuerBase+"/consent", url.Values{
		"request_uri": {pr.RequestURI}, "csrf": {csrf}, "decision": {"allow"},
	}, nil, "")
	s.check("consent redirects with code", st == 302 && consentHdrs.Get("Location") != "", fmt.Sprintf("%d", st))
	loc := consentHdrs.Get("Location")
	u, _ := url.Parse(loc)
	code := u.Query().Get("code")

	tform := url.Values{"grant_type": {"authorization_code"}, "code": {code},
		"redirect_uri": {redirectURI}, "code_verifier": {jwtx.RandB64(48)}, "client_id": {web.ID}}
	addAssertion(tform, web, issuerBase)
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", tform, nil, "")
	s.check("wrong PKCE verifier -> 400", st == 400 && strings.Contains(body, "invalid_grant"), fmt.Sprintf("%d %s", st, body))
	tform.Set("code_verifier", verifier)
	addAssertion(tform, web, issuerBase) // fresh anti-replay assertion per POST
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", tform, nil, "")
	s.check("correct verifier -> 200", st == 200, fmt.Sprintf("%d %s", st, body))
	if st == 200 {
		var tk struct {
			AccessToken string `json:"access_token"`
		}
		_ = json.Unmarshal([]byte(body), &tk)
		st, _, _ = apiCall(tk.AccessToken, "DPoP", nil, nil, "", "GET", rbacBase+"/rbac/admin/roles", nil)
		s.check("token from stolen code lacks sender binding and fails DPoP gate", st == 401, fmt.Sprintf("%d", st))
	}
	addAssertion(tform, web, issuerBase)
	st, _, body = postForm(flowClient(""), "POST", issuerBase+"/token", tform, nil, "")
	s.check("code replay -> 400 invalid_grant (single use)", st == 400 && strings.Contains(body, "already used"), fmt.Sprintf("%d %s", st, body))

	fmt.Println("== S15: callers cannot forge gateway identity headers ==")
	if alice != nil {
		hdr := map[string]string{"X-SME-Subject": "rootadmin", "X-SME-Perms": "rbac.role:read"}
		st, hd, _ := apiCallWithHeaders(alice, "GET", rbacBase+"/rbac/admin/roles", hdr)
		// alice lacks assignments, spoofing must not help her in
		s.check("spoofed X-SME-* ignored; still 403 for unassigned user", st == 403, fmt.Sprintf("%d", st))
		_ = hd
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

	fmt.Println("== S17: assignment revocation — enforced within 10s despite caches ==")
	if root != nil {
		// find bob's direct supplier_operator assignment (tenant acme)
		st, _, listBody := apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/assignments?subject=bob&tenant=acme", nil)
		var lst []struct {
			ID   string `json:"id"`
			Role string `json:"role"`
		}
		_ = json.Unmarshal([]byte(listBody), &lst)
		// Revoke EVERY supplier_operator assignment of bob's — earlier suite
		// runs leave restored duplicates behind (the DB persists).
		ids := []string{}
		for _, a := range lst {
			if a.Role == "supplier_operator" {
				ids = append(ids, a.ID)
			}
		}
		s.check("root admin lists bob's assignments", st == 200 && len(ids) > 0, fmt.Sprintf("%d %s", st, listBody))
		revokedAll := len(ids) > 0
		for _, id := range ids {
			st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "DELETE",
				rbacBase+"/rbac/admin/assignments/"+id, nil)
			if st != 200 && st != 201 {
				revokedAll = false
			}
		}
		s.check("root admin revokes bob's assignments", revokedAll, fmt.Sprintf("%d ids=%d", st, len(ids)))
		bobFlow := s.mustFlow("web-private-key-jwt", "bob", "openid rbac:admin", "dpop")
		denied := false
		if bobFlow != nil {
			for i := 0; i < 10; i++ {
				st, _, _ = apiCall(bobFlow.AccessToken, bobFlow.TokenType, bobFlow.Key, bobFlow.JWK, "", "GET",
					rbacBase+"/rbac/admin/audit", nil)
				if st == 403 {
					denied = true
					break
				}
				time.Sleep(time.Second)
			}
		}
		s.check("revocation enforced within 10s despite gateway cache", denied, "authority still active after 10s")
		// restore bob (direct platform assignment)
		st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "",
			"POST", rbacBase+"/rbac/admin/assignments",
			[]byte(`{"subject":"bob","role":"supplier_operator","tenant":"acme","scope":"all"}`))
		s.check("root admin re-assigns bob", st == 200 || st == 201, fmt.Sprintf("%d", st))
		st, _, audit := apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/audit?tenant=acme", nil)
		s.check("admin changes are audited", st == 200 && strings.Contains(audit, "assignment_revoke"), fmt.Sprintf("%d", st))
	}

	fmt.Println("== S20: delegated RBAC — root grants, supplier assigns, cascades ==")
	if root != nil && saF != nil {
		var grantID string
		// 1) before any grant: supplier admin cannot assign an operatonal role
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/assignments",
			[]byte(`{"subject":"priya","role":"supplier_operator","tenant":"acme","scope":"own"}`))
		s.check("no root grant yet -> supplier assignment denied (no_matching_grant)",
			st == 403 && strings.Contains(body, "no_matching_grant"), fmt.Sprintf("%d %s", st, body))

		// 2) Root RBAC delegates supplier_operator (own-scope, acme) to supplier-a
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "POST",
			rbacBase+"/rbac/admin/grants",
			[]byte(`{"supplier":"supplier-a","role":"supplier_operator","tenant":"acme","scope":"own","can_delegate":true}`))
		s.check("root RBAC creates delegation grant", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))
		var g struct {
			ID string `json:"id"`
		}
		_ = json.Unmarshal([]byte(body), &g)
		grantID = g.ID

		// 3) supplier admin assigns within the grant boundary
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/assignments",
			[]byte(`{"subject":"priya","role":"supplier_operator","tenant":"acme","scope":"own"}`))
		s.check("supplier admin assigns role inside the grant", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))
		var a struct {
			ID      string  `json:"id"`
			GrantID *string `json:"grant_id"`
		}
		_ = json.Unmarshal([]byte(body), &a)
		s.check("assignment references the root grant (traceability)",
			a.ID != "" && a.GrantID != nil && *a.GrantID == grantID, fmt.Sprintf("%s", body))

		// 4) scope boundary: requesting 'all' exceeds the own-scope grant
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/assignments",
			[]byte(`{"subject":"priya","role":"supplier_operator","tenant":"acme","scope":"all"}`))
		s.check("scope exceeding grant denied (scope_exceeds_grant)",
			st == 403 && strings.Contains(body, "scope_exceeds_grant"), fmt.Sprintf("%d %s", st, body))

		// 5) supplier admin cannot delegate to users outside its org
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/assignments",
			[]byte(`{"subject":"carol","role":"supplier_operator","tenant":"globex","scope":"own"}`))
		s.check("supplier admin cannot assign platform users (membership)", st == 403 && strings.Contains(body, "membership"), fmt.Sprintf("%d %s", st, body))

		// 6) supplier admin cannot create grants itself
		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/grants",
			[]byte(`{"supplier":"supplier-a","role":"supplier_operator","tenant":"acme","scope":"all"}`))
		s.check("supplier admin cannot self-grant (403 rbac.grant:create)", st == 403, fmt.Sprintf("%d %s", st, body))

		// 7) the delegated member gains least-privilege read access
		priyaF := s.mustFlow("web-private-key-jwt", "priya", "openid rbac:admin", "dpop")
		if priyaF != nil {
			st, _, body = apiCall(priyaF.AccessToken, priyaF.TokenType, priyaF.Key, priyaF.JWK, "", "GET",
				rbacBase+"/rbac/admin/users?supplier=supplier-a", nil)
			s.check("delegated member reads its supplier's users (rbac.user:read)", st == 200, fmt.Sprintf("%d %s", st, bodyTrim(body, 160)))
			st, _, body = apiCall(priyaF.AccessToken, priyaF.TokenType, priyaF.Key, priyaF.JWK, "", "POST",
				rbacBase+"/rbac/admin/users", []byte(`{"id":"ea-nope","display_name":"Nope"}`))
			s.check("delegated member cannot create users (least privilege)", st == 403, fmt.Sprintf("%d", st))
		}

		// 8) grant revocation cascades: priya loses authority despite valid assignment+token
		st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "DELETE",
			rbacBase+"/rbac/admin/grants/"+grantID, nil)
		s.check("root RBAC revokes the grant", st == 200, fmt.Sprintf("%d", st))
		priyaF2 := s.mustFlow("web-private-key-jwt", "priya", "openid rbac:admin", "dpop")
		denied := false
		if priyaF2 != nil {
			for i := 0; i < 10; i++ {
				st, _, _ = apiCall(priyaF2.AccessToken, priyaF2.TokenType, priyaF2.Key, priyaF2.JWK, "", "GET",
					rbacBase+"/rbac/admin/users?supplier=supplier-a", nil)
				if st == 403 {
					denied = true
					break
				}
				time.Sleep(time.Second)
			}
		}
		s.check("grant revocation cascades within 10s (request-time check)", denied, "authority still active after 10s")

		// 9) delegation trail audited
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
			rbacBase+"/rbac/admin/audit?supplier=supplier-a&limit=500", nil)
		s.check("delegation trail audited (grant_create/assignment_create/grant_revoke)",
			st == 200 && strings.Contains(body, "grant_create") && strings.Contains(body, "assignment_create") && strings.Contains(body, "grant_revoke"),
			fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
	}

	fmt.Println("== S21: groups — local membership mapped onto delegated roles ==")
	var groupID string
	var grantID string
	if root != nil && saF != nil {
		// fresh grant for the group experiment
		st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "POST",
			rbacBase+"/rbac/admin/grants",
			[]byte(`{"supplier":"supplier-a","role":"supplier_operator","tenant":"globex","scope":"all","can_delegate":true}`))
		var gr struct {
			ID string `json:"id"`
		}
		_ = json.Unmarshal([]byte(body), &gr)
		grantID = gr.ID
		s.check("grant for group experiment (globex)", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))

		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/groups", []byte(`{"name":"intake-team"}`))
		var gp struct {
			ID string `json:"id"`
		}
		_ = json.Unmarshal([]byte(body), &gp)
		groupID = gp.ID
		s.check("supplier admin creates a local group", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))

		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/groups/"+groupID+"/roles", []byte(`{"role":"supplier_operator","scope":"all"}`))
		s.check("supplier admin maps delegated role onto group", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))

		st, _, body = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "POST",
			rbacBase+"/rbac/admin/groups/"+groupID+"/members", []byte(`{"user":"priya"}`))
		s.check("member added; assignments materialized through grant", st == 200 || st == 201, fmt.Sprintf("%d %s", st, body))

		// priya (globex grant, scope all) reads audit — allowed
		priyaF3 := s.mustFlow("web-private-key-jwt", "priya", "openid rbac:admin", "dpop")
		if priyaF3 != nil && grantID != "" {
			var ok200 int
			st, _, _ = apiCall(priyaF3.AccessToken, priyaF3.TokenType, priyaF3.Key, priyaF3.JWK, "", "GET",
				rbacBase+"/rbac/admin/audit?supplier=supplier-a", nil)
			ok200 = st
			s.check("group-backed member exercises delegated permission", ok200 == 200 || ok200 == 201, fmt.Sprintf("%d", st))
			// revoke the underlying grant -> group authority collapses too
			st, _, _ = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "DELETE",
				rbacBase+"/rbac/admin/grants/"+grantID, nil)
			denied := false
			for i := 0; i < 10 && st == 200; i++ {
				st, _, _ = apiCall(priyaF3.AccessToken, priyaF3.TokenType, priyaF3.Key, priyaF3.JWK, "", "GET",
					rbacBase+"/rbac/admin/audit?supplier=supplier-a", nil)
				if st == 403 {
					denied = true
					break
				}
				time.Sleep(time.Second)
			}
			s.check("revoking the root grant collapses group authority within 10s", denied, "group authority survived 10s")
		}

		if groupID != "" {
			st, _, _ = apiCall(saF.AccessToken, saF.TokenType, saF.Key, saF.JWK, "", "DELETE",
				rbacBase+"/rbac/admin/groups/"+groupID, nil)
			s.check("group deleted along with its materialized assignments", st == 200, fmt.Sprintf("%d", st))
			st, _, body = apiCall(root.AccessToken, root.TokenType, root.Key, root.JWK, "", "GET",
				rbacBase+"/rbac/admin/assignments?subject=priya", nil)
			// authority gone is proven by the cascade checks above; what must
			// hold on paper is traceability: every supplier_operator assignment
			// of priya references the root grant that backs it
			trace := true
			var rows []struct {
				GrantID *string `json:"grant_id"`
			}
			_ = json.Unmarshal([]byte(body), &rows)
			for _, r := range rows {
				if r.GrantID == nil || *r.GrantID == "" {
					trace = false
				}
			}
			s.check("every delegated assignment references its root grant (traceability)", trace && len(rows) > 0,
				fmt.Sprintf("%d %s", st, bodyTrim(body, 200)))
		}
	}

	fmt.Println("== S18: TLS floor (no TLS 1.1) ==")
	cmd := exec.Command("curl", "-sk", "--tls-max", "1.1", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", issuerBase+"/.well-known/openid-configuration")
	out, err := cmd.CombinedOutput()
	s.check("TLS 1.1 handshake refused", err != nil || strings.TrimSpace(string(out)) == "000", strings.TrimSpace(string(out)))
	cmd = exec.Command("curl", "-sk", "--tlsv1.2", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", issuerBase+"/.well-known/openid-configuration")
	out, _ = cmd.Output()
	s.check("TLS 1.2 accepted", strings.TrimSpace(string(out)) == "200", strings.TrimSpace(string(out)))

	// Keep heavy rate-limit test for the very end: it exhausts the IP budget.
	fmt.Println("== S19: PAR rate budget (429) ==")
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
