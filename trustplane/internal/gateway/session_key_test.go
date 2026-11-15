package gateway

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"testing"
)

// GATEWAY_SESSION_KEY is documented as `openssl rand -base64 32` (standard, padded); keys
// written URL-safe and unpadded (as before) must keep working.
func TestEnsureSessionKeyAcceptsEveryBase64Form(t *testing.T) {
	key := bytes.Repeat([]byte{0xfb, 0xff, 0x3e}, 11)[:32] // bytes whose encodings contain + / - _
	for name, enc := range map[string]*base64.Encoding{
		"std padded (openssl rand -base64 32)": base64.StdEncoding,
		"std unpadded":                         base64.RawStdEncoding,
		"url padded":                           base64.URLEncoding,
		"url unpadded (previous form)":         base64.RawURLEncoding,
	} {
		got, err := ensureSessionKey(enc.EncodeToString(key)+"\n", nil)
		if err != nil || !bytes.Equal(got, key) {
			t.Fatalf("%s: got %x, %v", name, got, err)
		}
	}
}

func TestEnsureSessionKeyRefusesBadKeys(t *testing.T) {
	if _, err := ensureSessionKey("not base64 at all!", nil); err == nil {
		t.Fatal("accepted a non-base64 key")
	}
	if _, err := ensureSessionKey(base64.StdEncoding.EncodeToString(make([]byte, 16)), nil); err == nil {
		t.Fatal("accepted a 16-byte key")
	}
}

func TestEnsureSessionKeyFallsBackToTheContextSecret(t *testing.T) {
	want := sha256.Sum256([]byte("secret"))
	got, err := ensureSessionKey("", []byte("secret"))
	if err != nil || !bytes.Equal(got, want[:]) {
		t.Fatalf("got %x, %v", got, err)
	}
}
