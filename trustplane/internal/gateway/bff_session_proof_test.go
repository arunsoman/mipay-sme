package gateway

import (
	"testing"
	"time"

	"trustplane/internal/dpop"
)

func TestSessionDPoPProofBindsAccessToken(t *testing.T) {
	key, jwk, err := dpop.GenerateES256()
	if err != nil {
		t.Fatal(err)
	}
	const token = "access-token-for-session"
	const htu = "https://admin.afriqpay.com/api/me"
	proof, err := sessionDPoPProof(key, jwk, "GET", htu, token)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := dpop.Verify(proof, dpop.Options{
		HTM: "GET", HTU: htu, AccessToken: token,
		MaxAge: 5 * time.Minute, Now: time.Now(),
	}); err != nil {
		t.Fatalf("session proof must bind the access token: %v", err)
	}
	if _, _, err := dpop.Verify(proof, dpop.Options{
		HTM: "GET", HTU: htu, AccessToken: "different-token",
		MaxAge: 5 * time.Minute, Now: time.Now(),
	}); err == nil {
		t.Fatal("session proof accepted a different access token")
	}
}
