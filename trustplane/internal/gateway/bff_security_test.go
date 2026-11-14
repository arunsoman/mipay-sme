package gateway

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"trustplane/internal/dpop"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestBFFDiscoveryRejectsIssuerAndEndpointMismatch(t *testing.T) {
	const good = `{"issuer":"https://auth.example.test","authorization_endpoint":"https://auth.example.test/authorize","pushed_authorization_request_endpoint":"https://auth.example.test/par","token_endpoint":"https://auth.example.test/token","revocation_endpoint":"https://auth.example.test/revoke","introspection_endpoint":"https://auth.example.test/introspect","jwks_uri":"https://auth.example.test/jwks.json","require_pushed_authorization_requests":true,"code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["private_key_jwt"],"dpop_signing_alg_values_supported":["ES256"]}`
	for _, tc := range []struct {
		name, body string
		ok         bool
	}{
		{"valid", good, true},
		{"wrong issuer", strings.Replace(good, `"issuer":"https://auth.example.test"`, `"issuer":"https://evil.example"`, 1), false},
		{"wrong token host", strings.Replace(good, `"token_endpoint":"https://auth.example.test/token"`, `"token_endpoint":"https://evil.example/token"`, 1), false},
		{"no PAR", strings.Replace(good, `"require_pushed_authorization_requests":true`, `"require_pushed_authorization_requests":false`, 1), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			g := &Gateway{cfg: &Config{Server: ServerCfg{Issuer: "https://auth.example.test"}}, bffIDP: "https://idp:8443", bffIDPPublic: "https://auth.example.test"}
			g.up = map[string]*upstream{"idp": {client: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(tc.body)), Header: make(http.Header)}, nil
			})}}}
			err := g.loadBFFMetadata(context.Background())
			if (err == nil) != tc.ok {
				t.Fatalf("metadata result: %v", err)
			}
			if tc.ok && (g.bffTokenURL != "https://idp:8443/token" || g.bffTokenHTU != "https://auth.example.test/token") {
				t.Fatalf("token endpoints did not bind to discovery")
			}
		})
	}
}

func TestBFFHostAndCSRF(t *testing.T) {
	g := &Gateway{cfg: &Config{BFF: BFFCfg{Hosts: []string{"admin.example.test"}}}}
	if !g.bffHostAllowed("ADMIN.EXAMPLE.TEST") || g.bffHostAllowed("evil.example.test") || g.bffHostAllowed("admin.example.test.evil.test") {
		t.Fatal("BFF host allowlist failed")
	}
	if !csrfMatches("secret", "secret") || csrfMatches("", "secret") || csrfMatches("other", "secret") {
		t.Fatal("CSRF comparison failed")
	}
}

func TestTokenRequestRetriesDPoPNonce(t *testing.T) {
	key, _, err := dpop.GenerateES256()
	if err != nil {
		t.Fatal(err)
	}
	calls := 0
	g := &Gateway{cfg: &Config{Server: ServerCfg{Issuer: "https://auth.example.test"}},
		bffID: "gateway-bff", bffKey: key, bffKid: "test", bffTokenURL: "https://idp:8443/token", bffTokenHTU: "https://auth.example.test/token"}
	g.up = map[string]*upstream{"idp": {client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		parts := strings.Split(r.Header.Get("DPoP"), ".")
		if len(parts) != 3 {
			t.Fatal("missing DPoP proof")
		}
		raw, err := base64.RawURLEncoding.DecodeString(parts[1])
		if err != nil {
			t.Fatal(err)
		}
		var claims map[string]any
		if err := json.Unmarshal(raw, &claims); err != nil {
			t.Fatal(err)
		}
		if calls == 1 {
			if claims["nonce"] != nil {
				t.Fatal("first proof had a nonce")
			}
			return &http.Response{StatusCode: 400, Header: http.Header{"Dpop-Nonce": {"server-nonce"}}, Body: io.NopCloser(strings.NewReader(`{"error":"use_dpop_nonce"}`))}, nil
		}
		if claims["nonce"] != "server-nonce" {
			t.Fatalf("retry nonce = %v", claims["nonce"])
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"access_token":"ok"}`))}, nil
	})}}}
	status, _, err := g.postToken(context.Background(), url.Values{"grant_type": {"refresh_token"}}, key)
	if err != nil || status != 200 || calls != 2 {
		t.Fatalf("status=%d calls=%d err=%v", status, calls, err)
	}
}
