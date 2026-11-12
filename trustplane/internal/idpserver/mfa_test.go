package idpserver

import (
	"testing"
	"time"
)

func TestTOTPAndReplayProtection(t *testing.T) {
	// RFC 6238 SHA-1 vector at T=59 (the 8-digit value ends in 287082).
	secret := []byte("12345678901234567890")
	now := time.Unix(59, 0)
	code := totpAt(secret, 1)
	if code != "287082" {
		t.Fatalf("code=%s", code)
	}
	if got := acceptedTOTPStep(secret, code, now, -1); got != 1 {
		t.Fatalf("step=%d", got)
	}
	if got := acceptedTOTPStep(secret, code, now, 1); got != -1 {
		t.Fatalf("replay step=%d", got)
	}
	if got := acceptedTOTPStep(secret, "000000", now, -1); got != -1 {
		t.Fatalf("wrong code step=%d", got)
	}
}

func TestMFASecretEncryptionAndAssurance(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	sealed, err := sealMFA(key, []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := openMFA(key, sealed)
	if err != nil || string(plain) != "secret" {
		t.Fatalf("open=%s error=%v", plain, err)
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := openMFA(key, sealed); err == nil {
		t.Fatal("modified ciphertext accepted")
	}
	if assuranceFor("pwd") != "aal1" || assuranceFor("pwd totp") != "aal2" || assuranceFor("sms totp") != "aal2" || assuranceFor("pwd sms-mfa") != "aal2" {
		t.Fatal("assurance mapping")
	}
}
