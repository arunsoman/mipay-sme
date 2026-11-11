package gateway

import (
	"net/http/httptest"
	"testing"
)

func TestRemoteIPKeyTrustedProxy(t *testing.T) {
	if err := setTrustedProxies([]string{"172.16.0.0/12", "127.0.0.1/32"}); err != nil {
		t.Fatal(err)
	}
	defer setTrustedProxies(nil)

	r := httptest.NewRequest("GET", "/api/x", nil)
	r.RemoteAddr = "172.18.0.1:5555" // docker bridge = the host nginx
	r.Header.Set("X-Real-IP", "41.182.9.10")
	if got := remoteIPKey(r); got != "41.182.9.10" {
		t.Fatalf("trusted proxy: got %q, want the X-Real-IP", got)
	}

	r = httptest.NewRequest("GET", "/api/x", nil)
	r.RemoteAddr = "8.8.8.8:4444" // not a proxy: its header must be ignored
	r.Header.Set("X-Real-IP", "1.2.3.4")
	if got := remoteIPKey(r); got != "8.8.8.8" {
		t.Fatalf("untrusted peer: got %q, want the peer", got)
	}

	r = httptest.NewRequest("GET", "/api/x", nil)
	r.RemoteAddr = "172.18.0.1:5555"
	r.Header.Set("X-Real-IP", "not-an-ip")
	if got := remoteIPKey(r); got != "172.18.0.1" {
		t.Fatalf("garbage header: got %q, want the peer", got)
	}

	if err := setTrustedProxies([]string{"nonsense"}); err == nil {
		t.Fatal("bad CIDR should be rejected at start-up")
	}
}
