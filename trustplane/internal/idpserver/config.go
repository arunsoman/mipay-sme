package idpserver

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
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

	// ---- credential plugin (idp-rbac-cutover-plan.md P1) ----

	// CoreCN is the mesh certificate CN of the only caller allowed to use the
	// enrolment API (the MiPay core, client "mipay-core"). Normalised like the
	// RBAC registry: "sme-mipay-mesh" -> "mipay".
	CoreCN string
	// CoreEventsURL receives signed auth events (POST /internal/auth/events).
	// Empty: events stay queued until it is configured.
	CoreEventsURL string
	// CommsURL is the comms plane base URL (https://comms:8443/comms/v1).
	// Empty: the sandbox CommsClient (sim_outbox) in dev/sandbox, none elsewhere.
	CommsURL string
	// SandboxChannels are the channels the sandbox CommsClient reports as live.
	SandboxChannels []string
	// DevOTP is the fixed phone-OTP code in dev/sandbox (empty: random codes).
	DevOTP string

	LinkTTL         time.Duration // set-up and reset links (72 h)
	ResetLinkTTL    time.Duration // self-service reset links
	LockAfter       int           // consecutive failures before a temporary lock
	LockBase        time.Duration // first lock duration, doubled per further lock
	LockMax         time.Duration
	OTPTTL          time.Duration
	OTPMaxAttempts  int
	OTPResendGap    time.Duration
	OTPMaxPerHour   int
	ResetCoolOffSMS time.Duration // cooling-off after a reset proved only by SMS (2fa-design §9.5)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
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

		CoreCN:        envOr("IDP_CORE_CN", "sme-mipay-mesh"),
		CoreEventsURL: os.Getenv("IDP_CORE_EVENTS_URL"),
		CommsURL:      os.Getenv("IDP_COMMS_URL"),
		DevOTP:        os.Getenv("IDP_DEV_OTP"),

		LinkTTL:         envDuration("IDP_LINK_TTL", 72*time.Hour),
		ResetLinkTTL:    envDuration("IDP_RESET_LINK_TTL", 1*time.Hour),
		LockAfter:       envInt("IDP_LOCK_AFTER", 5),
		LockBase:        envDuration("IDP_LOCK_BASE", 15*time.Minute),
		LockMax:         envDuration("IDP_LOCK_MAX", 24*time.Hour),
		OTPTTL:          5 * time.Minute,
		OTPMaxAttempts:  5,
		OTPResendGap:    60 * time.Second,
		OTPMaxPerHour:   5,
		ResetCoolOffSMS: 24 * time.Hour,
	}
	c.Environment = envOr("IDP_ENVIRONMENT", "dev")
	sandboxDefault := ""
	if devEnvironments[c.Environment] {
		sandboxDefault = "sms,email"
	}
	for _, ch := range strings.Split(envOr("IDP_SANDBOX_CHANNELS", sandboxDefault), ",") {
		if ch = strings.TrimSpace(ch); ch != "" {
			c.SandboxChannels = append(c.SandboxChannels, ch)
		}
	}
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

// IsDev reports whether demo conveniences (seed users, fixed OTP, sandbox comms) are allowed.
func (c Config) IsDev() bool { return devEnvironments[c.Environment] }

// validateEnvironment enforces cut-over P0.3 (idp-rbac-cutover-plan.md): outside dev and
// sandbox the IdP refuses to start with demo seed users, and its issuer must be a public
// https URL (FAPI clients check aud = issuer, so it must be the name they can reach).
// P1 adds: no fixed dev OTP and no sandbox comms channels outside dev/sandbox.
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
	if c.DevOTP != "" {
		return fmt.Errorf("idp: IDP_DEV_OTP is not allowed when IDP_ENVIRONMENT=%s (fixed OTP only in dev/sandbox)", c.Environment)
	}
	if len(c.SandboxChannels) > 0 {
		return fmt.Errorf("idp: IDP_SANDBOX_CHANNELS is not allowed when IDP_ENVIRONMENT=%s (sandbox comms only in dev/sandbox)", c.Environment)
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

// seedUser is one demo credential account (dev/sandbox only).
type seedUser struct {
	Name, Password, UID string
}

// parseSeedUsers reads "name:pw:scopes[:uid[:phone[:profile]]]|..." (the scopes field is ignored since P1:
// accounts carry no authority; the uid defaults to the name).
func parseSeedUsers(raw string) []seedUser {
	var out []seedUser
	for _, part := range strings.Split(raw, "|") {
		fields := strings.Split(strings.TrimSpace(part), ":")
		if len(fields) < 3 {
			continue
		}
		name, pw := fields[0], fields[1]
		if name == "" || pw == "" {
			continue
		}
		uid := name
		if len(fields) >= 4 && strings.TrimSpace(fields[3]) != "" {
			uid = strings.TrimSpace(fields[3])
		}
		out = append(out, seedUser{Name: name, Password: pw, UID: uid})
	}
	return out
}
