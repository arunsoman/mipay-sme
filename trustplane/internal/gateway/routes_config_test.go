package gateway

import "testing"

// The shipped route table must load (deny-by-default validation) and must not route
// anything to the IdP enrolment API, which only the core reaches over the mesh.
func TestShippedRouteTableLoads(t *testing.T) {
	cfg, err := Load("../../config/gateway/routes.yaml")
	if err != nil {
		t.Fatalf("routes.yaml: %v", err)
	}
	for _, r := range cfg.Routes {
		if r.Upstream == "idp" && (r.Path == "/accounts" || len(r.Path) > 9 && r.Path[:10] == "/accounts/") {
			t.Fatalf("the enrolment API must have no public route: %s", r.Path)
		}
		if r.Upstream == "rbac" && r.Auth != "token" {
			t.Fatalf("rbac route without token auth: %s", r.Path)
		}
	}
}
