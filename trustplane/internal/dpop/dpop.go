// Package dpop implements RFC 9449 DPoP proof validation and creation.
// Proof tokens are short-lived JWS objects signed by an ephemeral
// client-held key (ES256 or EdDSA only).
package dpop

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"trustplane/internal/jwtx"
)

const TypJWT = "dpop+jwt"

var AllowedProofAlgs = map[string]bool{jwtx.AlgES256: true, jwtx.AlgEdDSA: true}

var ErrInvalidProof = errors.New("dpop: invalid proof")

// Options describe what the server expects from a proof.
type Options struct {
	HTM         string        // HTTP method of the outer request
	HTU         string        // exact URI (canonical) of the outer request
	AccessToken string        // non-empty => "ath" claim must bind it
	MaxAge      time.Duration // max proof age (recommend <= 300s)
	Now         time.Time
}

// Verify validates a DPoP proof and returns the bound key thumbprint
// (cnf.jkt) and the proof jti (for replay detection), plus the header
// JWK so callers can log/inspect.
func Verify(proof string, opt Options) (jkt, jti string, err error) {
	if opt.Now.IsZero() {
		opt.Now = time.Now()
	}
	now := opt.Now.Unix()
	var hdr map[string]any
	hdr, claims, err := jwtx.VerifyJWS(proof, AllowedProofAlgs, func(h map[string]any) (crypto.PublicKey, error) {
		t, _ := h["typ"].(string)
		if !strings.EqualFold(t, TypJWT) {
			return nil, fmt.Errorf("%w: typ must be %s", ErrInvalidProof, TypJWT)
		}
		jm, ok := h["jwk"].(map[string]any)
		if !ok {
			return nil, fmt.Errorf("%w: missing public key (jwk) in header", ErrInvalidProof)
		}
		return jwtx.KeyFromJWK(jm)
	})
	if err != nil {
		return "", "", fmt.Errorf("%w: %v", ErrInvalidProof, err)
	}
	jm, ok := hdr["jwk"].(map[string]any)
	if !ok {
		return "", "", fmt.Errorf("%w: missing jwk", ErrInvalidProof)
	}
	if jkt, err = jwtx.ThumbprintJWK(jm); err != nil {
		return "", "", fmt.Errorf("%w: bad jwk: %v", ErrInvalidProof, err)
	}
	htm := strings.ToUpper(strings.TrimSpace(jwtx.ClaimString(claims, "htm")))
	if htm == "" || htm != strings.ToUpper(strings.TrimSpace(opt.HTM)) {
		return "", "", fmt.Errorf("%w: htm mismatch", ErrInvalidProof)
	}
	htu := jwtx.ClaimString(claims, "htu")
	if htu == "" || htu != opt.HTU {
		return "", "", fmt.Errorf("%w: htu mismatch (%q != %q)", ErrInvalidProof, htu, opt.HTU)
	}
	iat, ok := jwtx.ClaimInt64(claims, "iat")
	if !ok || iat == 0 {
		return "", "", fmt.Errorf("%w: missing iat", ErrInvalidProof)
	}
	maxAge := int64(opt.MaxAge.Seconds())
	if maxAge <= 0 {
		maxAge = 300
	}
	if iat < now-maxAge || iat > now+300 {
		return "", "", fmt.Errorf("%w: iat out of window", ErrInvalidProof)
	}
	jti = jwtx.ClaimString(claims, "jti")
	if jti == "" {
		return "", "", fmt.Errorf("%w: missing jti", ErrInvalidProof)
	}
	if opt.AccessToken != "" {
		ath := jwtx.ClaimString(claims, "ath")
		if ath == "" {
			return "", "", fmt.Errorf("%w: missing ath for resource request", ErrInvalidProof)
		}
		sum := sha256.Sum256([]byte(opt.AccessToken))
		if ath != jwtx.B64UEnc(sum[:]) {
			return "", "", fmt.Errorf("%w: ath mismatch", ErrInvalidProof)
		}
	}
	return jkt, jti, nil
}

// CanonicalHTU normalizes a URI the way RFC 9449 requires (no query/frags,
// lowercase scheme/host, default ports removed).
func CanonicalHTU(rawurl string) (string, error) {
	u, err := url.Parse(rawurl)
	if err != nil {
		return "", err
	}
	u.Fragment = ""
	u.RawQuery = ""
	u.ForceQuery = false
	if (u.Scheme == "https" && u.Port() == "443") || (u.Scheme == "http" && u.Port() == "80") {
		u.Host = u.Hostname()
	}
	u.Scheme = strings.ToLower(u.Scheme)
	u.Host = strings.ToLower(u.Host)
	return u.String(), nil
}

// FromHeader extracts the DPoP proof from request headers.
func FromHeader(h http.Header) (string, bool) {
	v := h.Get("DPoP")
	return v, v != ""
}

// GenerateES256 creates a fresh client signing key + JWK for proofs.
func GenerateES256() (crypto.Signer, map[string]any, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	jwk, err := jwtx.PublicJWK(k.Public())
	if err != nil {
		return nil, nil, err
	}
	return k, jwk, nil
}

// SignProof creates a DPoP proof JWS (client side).
func SignProof(key crypto.Signer, jwk map[string]any, htm, htu, ath string) (string, error) {
	alg := ""
	switch key.(type) {
	case *ecdsa.PrivateKey:
		alg = jwtx.AlgES256
	case ed25519.PrivateKey:
		alg = jwtx.AlgEdDSA
	default:
		return "", fmt.Errorf("dpop: unsupported key %T", key)
	}
	claims := map[string]any{
		"jti": jwtx.RandB64(16),
		"htm": strings.ToUpper(htm),
		"htu": htu,
		"iat": time.Now().Unix(),
	}
	if ath != "" {
		claims["ath"] = ath
	}
	hdr := map[string]any{"typ": TypJWT, "jwk": jwk}
	return jwtx.SignJWSWithHeader(alg, hdr, claims, key)
}
