// Package jwtx implements the strict JOSE layer used across the platform.
// Per the FAPI 2.0 Security Profile, only PS256, ES256 and EdDSA (Ed25519)
// are permitted anywhere; HMAC and RS* are rejected unconditionally.
package jwtx

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"strings"
)

const (
	AlgPS256 = "PS256"
	AlgES256 = "ES256"
	AlgEdDSA = "EdDSA"
)

// AllowedJWSAlgs is the complete algorithm allow-list mandated by FAPI 2.0.
func AllowedJWSAlgs() map[string]bool {
	return map[string]bool{AlgPS256: true, AlgES256: true, AlgEdDSA: true}
}

var (
	ErrInvalidToken = errors.New("jwtx: invalid token")
	ErrBadAlg       = errors.New("jwtx: alg not allowed")
	ErrBadSig       = errors.New("jwtx: signature verification failed")
)

var b64u = base64.RawURLEncoding

func B64UEnc(b []byte) string { return b64u.EncodeToString(b) }

func B64UDec(s string) ([]byte, error) { return b64u.DecodeString(strings.TrimRight(s, "=")) }

func RandB64(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return B64UEnc(b)
}

func sha256Bytes(input []byte) []byte {
	sum := sha256.Sum256(input)
	return sum[:]
}

// SignJWSWithHeader signs compact JWS with a caller-controlled header
// (used e.g. by DPoP proofs, which embed the public key in the header).
func SignJWSWithHeader(alg string, hdr, claims map[string]any, key crypto.Signer) (string, error) {
	hdr["alg"] = alg
	if hdr["typ"] == nil {
		hdr["typ"] = "JWT"
	}
	hj, err := json.Marshal(hdr)
	if err != nil {
		return "", err
	}
	pj, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	si := B64UEnc(hj) + "." + B64UEnc(pj)
	sig, err := signRaw(alg, key, []byte(si))
	if err != nil {
		return "", err
	}
	return si + "." + B64UEnc(sig), nil
}

// SignJWS signs a token with a standard {alg, typ, kid} header.
func SignJWS(alg, typ, kid string, claims map[string]any, key crypto.Signer) (string, error) {
	hdr := map[string]any{}
	if kid != "" {
		hdr["kid"] = kid
	}
	if typ != "" {
		hdr["typ"] = typ
	}
	return SignJWSWithHeader(alg, hdr, claims, key)
}

func signRaw(alg string, key crypto.Signer, input []byte) ([]byte, error) {
	switch alg {
	case AlgPS256:
		k, ok := key.(*rsa.PrivateKey)
		if !ok {
			return nil, fmt.Errorf("jwtx: PS256 needs an RSA private key, got %T", key)
		}
		return rsa.SignPSS(rand.Reader, k, crypto.SHA256, sha256Bytes(input), &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthAuto})
	case AlgES256:
		k, ok := key.(*ecdsa.PrivateKey)
		if !ok || k.Curve != elliptic.P256() {
			return nil, fmt.Errorf("jwtx: ES256 needs a P-256 key, got %T", key)
		}
		r, s, err := ecdsa.Sign(rand.Reader, k, sha256Bytes(input))
		if err != nil {
			return nil, err
		}
		sig := make([]byte, 64)
		r.FillBytes(sig[:32])
		s.FillBytes(sig[32:])
		return sig, nil
	case AlgEdDSA:
		k, ok := key.(ed25519.PrivateKey)
		if !ok {
			if kp, ok2 := key.(*ed25519.PrivateKey); ok2 {
				k = *kp
			} else {
				return nil, fmt.Errorf("jwtx: EdDSA needs an Ed25519 key, got %T", key)
			}
		}
		return ed25519.Sign(k, input), nil
	default:
		return nil, fmt.Errorf("jwtx: unsupported alg %q", alg)
	}
}

// VerifyJWS verifies a compact JWS. keyFn resolves the public key from the
// (already unverified) header; alg must be inside `allowed`.
func VerifyJWS(tok string, allowed map[string]bool, keyFn func(hdr map[string]any) (crypto.PublicKey, error)) (map[string]any, map[string]any, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return nil, nil, ErrInvalidToken
	}
	hb, err := B64UDec(parts[0])
	if err != nil {
		return nil, nil, ErrInvalidToken
	}
	pb, err := B64UDec(parts[1])
	if err != nil {
		return nil, nil, ErrInvalidToken
	}
	sb, err := B64UDec(parts[2])
	if err != nil {
		return nil, nil, ErrInvalidToken
	}
	var hdr map[string]any
	if json.Unmarshal(hb, &hdr) != nil || hdr == nil {
		return nil, nil, ErrInvalidToken
	}
	claims := map[string]any{}
	if json.Unmarshal(pb, &claims) != nil {
		return nil, nil, ErrInvalidToken
	}
	alg, _ := hdr["alg"].(string)
	if alg == "" || !allowed[alg] {
		return nil, nil, ErrBadAlg
	}
	pub, err := keyFn(hdr)
	if err != nil {
		return nil, nil, err
	}
	si := parts[0] + "." + parts[1]
	digest := sha256Bytes([]byte(si))
	switch pk := pub.(type) {
	case *rsa.PublicKey:
		if err := rsa.VerifyPSS(pk, crypto.SHA256, digest, sb, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthAuto}); err != nil {
			return nil, nil, ErrBadSig
		}
	case *ecdsa.PublicKey:
		if len(sb) != 64 || pk.Curve != elliptic.P256() {
			return nil, nil, ErrBadSig
		}
		r := new(big.Int).SetBytes(sb[:32])
		s := new(big.Int).SetBytes(sb[32:])
		if !ecdsa.Verify(pk, digest, r, s) {
			return nil, nil, ErrBadSig
		}
	case ed25519.PublicKey:
		if !ed25519.Verify(pk, []byte(si), sb) {
			return nil, nil, ErrBadSig
		}
	default:
		return nil, nil, fmt.Errorf("jwtx: unsupported public key type %T", pub)
	}
	return hdr, claims, nil
}

func jwkStr(m map[string]any, keys ...string) bool {
	for _, k := range keys {
		if _, ok := m[k].(string); !ok {
			return false
		}
	}
	return true
}

// KeyFromJWK builds a crypto public key from a JWK map.
func KeyFromJWK(m map[string]any) (crypto.PublicKey, error) {
	kty, _ := m["kty"].(string)
	switch kty {
	case "EC":
		crv, _ := m["crv"].(string)
		if crv != "P-256" || !jwkStr(m, "x", "y") {
			return nil, errors.New("jwtx: bad EC JWK (need P-256 with x,y)")
		}
		xb, e1 := B64UDec(m["x"].(string))
		yb, e2 := B64UDec(m["y"].(string))
		if e1 != nil || e2 != nil {
			return nil, errors.New("jwtx: bad EC JWK coordinates")
		}
		x, y := new(big.Int).SetBytes(xb), new(big.Int).SetBytes(yb)
		if !elliptic.P256().IsOnCurve(x, y) {
			return nil, errors.New("jwtx: EC point not on curve")
		}
		return &ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y}, nil
	case "OKP":
		crv, _ := m["crv"].(string)
		if crv != "Ed25519" || !jwkStr(m, "x") {
			return nil, errors.New("jwtx: bad OKP JWK (need Ed25519 with x)")
		}
		xb, err := B64UDec(m["x"].(string))
		if err != nil || len(xb) != ed25519.PublicKeySize {
			return nil, errors.New("jwtx: bad Ed25519 JWK")
		}
		return ed25519.PublicKey(xb), nil
	case "RSA":
		if !jwkStr(m, "n", "e") {
			return nil, errors.New("jwtx: bad RSA JWK (need n,e)")
		}
		nb, e1 := B64UDec(m["n"].(string))
		eb, e2 := B64UDec(m["e"].(string))
		if e1 != nil || e2 != nil {
			return nil, errors.New("jwtx: bad RSA JWK numbers")
		}
		return &rsa.PublicKey{N: new(big.Int).SetBytes(nb), E: int(new(big.Int).SetBytes(eb).Int64())}, nil
	default:
		return nil, fmt.Errorf("jwtx: unsupported JWK kty %q", kty)
	}
}

// ThumbprintJWK computes the RFC 7638 SHA-256 thumbprint (base64url).
func ThumbprintJWK(m map[string]any) (string, error) {
	kty, _ := m["kty"].(string)
	var pre string
	switch kty {
	case "EC":
		if !jwkStr(m, "crv", "x", "y") {
			return "", errors.New("jwtx: bad EC JWK")
		}
		pre = fmt.Sprintf(`{"crv":%q,"kty":"EC","x":%q,"y":%q}`, m["crv"], m["x"], m["y"])
	case "OKP":
		if !jwkStr(m, "crv", "x") {
			return "", errors.New("jwtx: bad OKP JWK")
		}
		pre = fmt.Sprintf(`{"crv":%q,"kty":"OKP","x":%q}`, m["crv"], m["x"])
	case "RSA":
		if !jwkStr(m, "e", "n") {
			return "", errors.New("jwtx: bad RSA JWK")
		}
		pre = fmt.Sprintf(`{"e":%q,"kty":"RSA","n":%q}`, m["e"], m["n"])
	default:
		return "", fmt.Errorf("jwtx: unsupported JWK kty %q", kty)
	}
	return B64UEnc(sha256Bytes([]byte(pre))), nil
}

// ThumbprintCertDER = base64url(SHA-256(DER)) — the RFC 8705 x5t#S256 value.
func ThumbprintCertDER(der []byte) string { return B64UEnc(sha256Bytes(der)) }

// PKCES256 computes an S256 code challenge from the verifier.
func PKCES256(verifier string) string { return B64UEnc(sha256Bytes([]byte(verifier))) }

// ValidVerifier checks RFC 7639 S256 verifier syntax (43..128 URL-safe chars).
func ValidVerifier(v string) bool {
	if len(v) < 43 || len(v) > 128 {
		return false
	}
	for i := 0; i < len(v); i++ {
		c := v[i]
		ok := (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '.' || c == '_' || c == '~'
		if !ok {
			return false
		}
	}
	return true
}

// LoadPrivateKeyFile loads SEC1/PKCS1/PKCS8 PEM private keys
// (RSA / P-256 EC / Ed25519) as a crypto.Signer.
func LoadPrivateKeyFile(path string) (crypto.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("jwtx: no PEM block in %s", path)
	}
	switch block.Type {
	case "RSA PRIVATE KEY":
		return x509.ParsePKCS1PrivateKey(block.Bytes)
	case "EC PRIVATE KEY":
		return x509.ParseECPrivateKey(block.Bytes)
	case "PRIVATE KEY", "ENCRYPTED PRIVATE KEY":
		k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		switch kk := k.(type) {
		case *rsa.PrivateKey, *ecdsa.PrivateKey, ed25519.PrivateKey:
			return kk.(crypto.Signer), nil
		case *ed25519.PrivateKey:
			return *kk, nil
		default:
			return nil, fmt.Errorf("jwtx: unsupported PKCS8 key type %T", k)
		}
	default:
		return nil, fmt.Errorf("jwtx: unsupported PEM type %q in %s", block.Type, path)
	}
}

// PublicJWK exports a public key as JWK (with a suggested alg).
func PublicJWK(pub crypto.PublicKey) (map[string]any, error) { return pubKeyJWK(pub) }

func pubKeyJWK(pub crypto.PublicKey) (map[string]any, error) {
	switch pk := pub.(type) {
	case *rsa.PublicKey:
		return map[string]any{
			"kty": "RSA", "n": B64UEnc(pk.N.Bytes()),
			"e":   B64UEnc(new(big.Int).SetInt64(int64(pk.E)).Bytes()), "alg": AlgPS256,
		}, nil
	case *ecdsa.PublicKey:
		if pk.Curve != elliptic.P256() {
			return nil, errors.New("jwtx: only P-256 EC keys supported")
		}
		x := make([]byte, 32)
		y := make([]byte, 32)
		pk.X.FillBytes(x)
		pk.Y.FillBytes(y)
		return map[string]any{"kty": "EC", "crv": "P-256", "x": B64UEnc(x), "y": B64UEnc(y), "alg": AlgES256}, nil
	case ed25519.PublicKey:
		return map[string]any{"kty": "OKP", "crv": "Ed25519", "x": B64UEnc(pk), "alg": AlgEdDSA}, nil
	case *ecdsa.PrivateKey:
		// private keys parsed directly from PEM (client registry path)
		return pubKeyJWK(&pk.PublicKey)
	case ed25519.PrivateKey:
		pub, ok := pk.Public().(ed25519.PublicKey)
		if !ok {
			return nil, errors.New("jwtx: bad ed25519 private key")
		}
		return pubKeyJWK(pub)
	default:
		return nil, fmt.Errorf("jwtx: unsupported public key %T", pk)
	}
}

// ClaimString safely reads a string claim.
func ClaimString(claims map[string]any, key string) string {
	s, _ := claims[key].(string)
	return s
}

// ClaimInt64 reads a numeric claim (JSON numbers arrive as float64).
func ClaimInt64(claims map[string]any, key string) (int64, bool) {
	switch v := claims[key].(type) {
	case float64:
		return int64(v), true
	case int64:
		return v, true
	case json.Number:
		i, err := v.Int64()
		return i, err == nil
	default:
		return 0, false
	}
}

// ClaimStrings reads a string or []string claim.
func ClaimStrings(claims map[string]any, key string) []string {
	out := []string{}
	switch v := claims[key].(type) {
	case string:
		out = append(out, v)
	case []any:
		for _, e := range v {
			if s, ok := e.(string); ok {
				out = append(out, s)
			}
		}
	case []string:
		out = append(out, v...)
	}
	return out
}
