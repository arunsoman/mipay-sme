package idpserver

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLogoutRendersConfirmation(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/logout", nil)
	w := httptest.NewRecorder()
	(&Server{}).logout(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("logout status = %d, want 200", w.Code)
	}
	if !strings.Contains(w.Body.String(), "Signed out") {
		t.Fatal("logout confirmation missing")
	}
	if got := w.Header().Get("Location"); got != "" {
		t.Fatalf("logout redirected to %q", got)
	}
	if !strings.Contains(w.Header().Get("Set-Cookie"), sessionCookie+"=") {
		t.Fatal("IdP session cookie was not cleared")
	}
}
