package idpserver

import (
	"fmt"
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
	if c.Issuer == "" {
		return c, fmt.Errorf("idp: IDP_ISSUER is required")
	}
	if c.DSN == "" {
		return c, fmt.Errorf("idp: DB_URL is required")
	}
	return c, nil
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
