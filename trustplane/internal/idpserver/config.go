package idpserver

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
	"time"
)

// Config is sourced from env vars (see docker-compose).
type Config struct {
	Issuer       string
	Listen       string
	HealthListen string
	DSN          string
	MaxConns     int32

	CertFile    string
	KeyFile     string
	InternalCA  string // mesh CA for requiring gateway client certs
	ClientsFile string
	SigningDir  string
	SeedUsers   string
	// Environment: dev | sandbox | uat | production (IDP_ENVIRONMENT, default dev).
	Environment string

	ParTTL     time.Duration
	CodeTTL    time.Duration
	AccessTTL  time.Duration
	SessionTTL time.Duration
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func FromEnv() (Config, error) {
	c := Config{
		Issuer:       os.Getenv("IDP_ISSUER"),
		Listen:       envOr("IDP_LISTEN", ":8443"),
		HealthListen: envOr("IDP_HEALTH_LISTEN", ":8081"),
		DSN:          envOr("DB_URL", ""),
		MaxConns:     16,
		CertFile:     envOr("IDP_TLS_CERT", "/pki/idp-server.pem"),
		KeyFile:      envOr("IDP_TLS_KEY", "/pki/idp-server-key.pem"),
		InternalCA:   envOr("IDP_INTERNAL_CA", "/pki/platform-ca.pem"),
		ClientsFile:  envOr("IDP_CLIENTS_FILE", "/config/idp/clients.yaml"),
		SigningDir:   envOr("IDP_SIGNING_DIR", "/pki/signing"),
		SeedUsers:    envOr("IDP_SEED_USERS", ""),
		ParTTL:       120 * time.Second, // FAPI: <= 600s
		CodeTTL:      30 * time.Second,  // FAPI: <= 60s, single use
		AccessTTL:    300 * time.Second,
		SessionTTL:   15 * time.Minute,
	}
	c.Environment = envOr("IDP_ENVIRONMENT", "dev")
	if c.Issuer == "" {
		return c, fmt.Errorf("idp: IDP_ISSUER is required")
	}
	if c.DSN == "" {
		return c, fmt.Errorf("idp: DB_URL is required")
	}
	if err := c.validateEnvironment(); err != nil {
		return c, err
	}
	return c, nil
}

// devEnvironments may carry demo seed users and an internal (*.local) issuer.
var devEnvironments = map[string]bool{"dev": true, "sandbox": true}

// validateEnvironment enforces cut-over P0.3 (idp-rbac-cutover-plan.md): outside dev and
// sandbox the IdP refuses to start with demo seed users, and its issuer must be a public
// https URL (FAPI clients check aud = issuer, so it must be the name they can reach).
func (c Config) validateEnvironment() error {
	switch c.Environment {
	case "dev", "sandbox", "uat", "production":
	default:
		return fmt.Errorf("idp: IDP_ENVIRONMENT %q must be dev|sandbox|uat|production", c.Environment)
	}
	if devEnvironments[c.Environment] {
		return nil
	}
	if strings.TrimSpace(c.SeedUsers) != "" {
		return fmt.Errorf("idp: IDP_SEED_USERS is not allowed when IDP_ENVIRONMENT=%s (demo users only in dev/sandbox)", c.Environment)
	}
	u, err := url.Parse(c.Issuer)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return fmt.Errorf("idp: IDP_ISSUER %q must be a bare https origin when IDP_ENVIRONMENT=%s", c.Issuer, c.Environment)
	}
	if host := strings.ToLower(u.Hostname()); strings.HasSuffix(host, ".local") || host == "localhost" || net.ParseIP(host) != nil {
		return fmt.Errorf("idp: IDP_ISSUER %q must be a public host name when IDP_ENVIRONMENT=%s", c.Issuer, c.Environment)
	}
	return nil
}

func parseSeedUsers(raw string) [][3]string {
	var out [][3]string
	for _, part := range strings.Split(raw, "|") {
		fields := strings.SplitN(strings.TrimSpace(part), ":", 3)
		if len(fields) != 3 {
			continue
		}
		name, pw, scopes := fields[0], fields[1], fields[2]
		if name == "" || pw == "" {
			continue
		}
		out = append(out, [3]string{name, pw, scopes})
	}
	return out
}
