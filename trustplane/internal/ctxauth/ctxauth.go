// Package ctxauth signs and verifies the gateway's "context" header that
// carries the authenticated identity to internal services over the mTLS
// mesh. Services reject requests whose context header is missing, tampered
// with, or stale — and strip any inbound copies so callers cannot spoof it.
package ctxauth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

const (
	// Header carries "<payload>.<hmac>" (both base64url).
	Header = "X-SME-Context"
	// CertHeader carries the RFC 8705 thumbprint of the external client
	// certificate seen at gateway TLS termination (used by the IdP for
	// tls_client_auth and mTLS token binding).
	CertHeader = "X-SME-Client-Cert-Sha256"
	// RequestID echoes a trace id to upstreams.
	RequestID = "X-SME-Request-Id"
)

var ErrBadContext = errors.New("ctxauth: invalid gateway context")

type Context struct {
	Issuer     string   `json:"iss,omitempty"`
	Sub        string   `json:"sub"`
	UID        string   `json:"uid,omitempty"` // core user id (RBAC subject); idp-rbac-cutover-plan.md P1.7
	SID        string   `json:"sid,omitempty"`
	ACR        string   `json:"acr,omitempty"`
	AMR        []string `json:"amr,omitempty"`
	AuthTime   int64    `json:"auth_time,omitempty"`
	ClientID   string   `json:"client_id,omitempty"`
	TokenID    string   `json:"token_jti,omitempty"`
	CertThumb  string   `json:"cert_thumb,omitempty"`
	Scopes     []string `json:"scopes,omitempty"`
	Perms      []string `json:"perms,omitempty"`
	Tenant     string   `json:"tenant,omitempty"`
	SupplierID string   `json:"supplier_id,omitempty"` // supplier org ("" => platform staff)
	TS         int64    `json:"ts"`
}

func (c Context) HasPerm(op string) bool {
	for _, p := range c.Perms {
		if p == op {
			return true
		}
	}
	return false
}

func (c Context) HasScope(s string) bool {
	for _, p := range c.Scopes {
		if p == s {
			return true
		}
	}
	return false
}

var b64 = base64.RawURLEncoding

// Sign serializes and HMACs the context header value.
func Sign(c Context, secret []byte) string {
	payload, _ := json.Marshal(c)
	p := b64.EncodeToString(payload)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(p))
	return p + "." + b64.EncodeToString(mac.Sum(nil))
}

// Verify validates the context header of an inbound internal request.
func Verify(h http.Header, secret []byte, maxSkew time.Duration) (Context, error) {
	v := h.Get(Header)
	if v == "" {
		return Context{}, ErrBadContext
	}
	parts := strings.Split(v, ".")
	if len(parts) != 2 {
		return Context{}, ErrBadContext
	}
	payload, err := b64.DecodeString(parts[0])
	if err != nil {
		return Context{}, ErrBadContext
	}
	mac, err := b64.DecodeString(parts[1])
	if err != nil {
		return Context{}, ErrBadContext
	}
	w := hmac.New(sha256.New, secret)
	w.Write([]byte(parts[0]))
	if !hmac.Equal(w.Sum(nil), mac) {
		return Context{}, ErrBadContext
	}
	var c Context
	if json.Unmarshal(payload, &c) != nil {
		return Context{}, ErrBadContext
	}
	if delta := time.Since(time.Unix(c.TS, 0)); delta > maxSkew || delta < -maxSkew {
		return Context{}, errors.New("ctxauth: stale context")
	}
	return c, nil
}

// StripSpoofed removes any inbound X-SME-* headers so that external callers
// cannot forge identity or certificate evidence. Call this before any
// forwarding decision is made.
func StripSpoofed(h http.Header) {
	for name := range h {
		if strings.HasPrefix(http.CanonicalHeaderKey(name), "X-Sme-") {
			h.Del(name)
		}
	}
}
