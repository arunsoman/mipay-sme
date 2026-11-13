package idpserver

import "testing"

func TestBootstrapPasswordRequiresPrivateValue(t *testing.T) {
	for _, value := range []string{"", "installer123", "  long-enough-password", "long-enough-password  "} {
		if _, err := bootstrapPassword(value); err == nil {
			t.Fatalf("accepted unsafe bootstrap password of length %d", len(value))
		}
	}
	if got, err := bootstrapPassword("a-private-bootstrap-secret-42"); err != nil || got == "" {
		t.Fatalf("rejected configured bootstrap password: %v", err)
	}
}
