package idpserver

import "testing"

func TestMFAModeFailsClosed(t *testing.T) {
	for _, tc := range []struct {
		mode   string
		wantOK bool
	}{{"off", true}, {"shadow", true}, {"enforce", true}, {"on", false}, {"", false}} {
		if err := (Config{MFAMode: tc.mode}).validateMFAMode(); (err == nil) != tc.wantOK {
			t.Errorf("mode %q: err=%v, want success=%v", tc.mode, err, tc.wantOK)
		}
	}
}

func TestValidateEnvironment(t *testing.T) {
	const seed = "alice:alice-pw:openid"
	cases := []struct {
		name               string
		env, issuer, seeds string
		ok                 bool
	}{
		{"dev keeps demo users and a .local issuer", "dev", "https://idp.fapi.local:8443", seed, true},
		{"sandbox keeps demo users", "sandbox", "https://idp.fapi.local:8443", seed, true},
		{"production, public issuer, no seeds", "production", "https://auth.afriqpay.com", "", true},
		{"uat, public issuer with port", "uat", "https://auth.uat.afriqpay.com:8443", "", true},
		{"production refuses seed users", "production", "https://auth.afriqpay.com", seed, false},
		{"uat refuses seed users", "uat", "https://auth.afriqpay.com", seed, false},
		{"production refuses a .local issuer", "production", "https://idp.fapi.local:8443", "", false},
		{"production refuses localhost", "production", "https://localhost:8443", "", false},
		{"production refuses an IP issuer", "production", "https://10.0.0.5", "", false},
		{"production refuses http", "production", "http://auth.afriqpay.com", "", false},
		{"production refuses a path", "production", "https://auth.afriqpay.com/idp", "", false},
		{"unknown environment", "prod", "https://auth.afriqpay.com", "", false},
	}
	for _, c := range cases {
		err := Config{Environment: c.env, Issuer: c.issuer, SeedUsers: c.seeds}.validateEnvironment()
		if (err == nil) != c.ok {
			t.Errorf("%s: err=%v, want ok=%v", c.name, err, c.ok)
		}
	}
}
