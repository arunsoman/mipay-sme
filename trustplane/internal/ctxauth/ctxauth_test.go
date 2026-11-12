package ctxauth

import (
	"net/http"
	"reflect"
	"testing"
	"time"
)

func TestP3ContextClaimsAndSpoofedHeaders(t *testing.T) {
	key := []byte("gateway-context-test-key")
	in := Context{Issuer: "https://idp.test", Sub: "sub_1", UID: "u_1", SID: "sid_1",
		ACR: "aal2", AMR: []string{"pwd", "totp"}, AuthTime: 1700000000,
		ClientID: "bff", TS: time.Now().Unix()}
	h := http.Header{}
	h.Set(Header, Sign(in, key))
	h.Set("X-SME-Forged", "attacker")
	out, err := Verify(h, key, 30*time.Second)
	if err != nil || !reflect.DeepEqual(out, in) {
		t.Fatalf("P3 context round trip: %v %#v", err, out)
	}
	StripSpoofed(h)
	if h.Get(Header) != "" || h.Get("X-SME-Forged") != "" {
		t.Fatal("external SME headers survived spoof stripping")
	}
	h.Set(Header, Sign(in, []byte("wrong-key")))
	if _, err := Verify(h, key, 30*time.Second); err == nil {
		t.Fatal("forged context accepted")
	}
}
