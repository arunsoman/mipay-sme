// Package commsplane is the comms plane, phase B0 (docs/plans/comms-design.md §12 P0):
// service skeleton in the mTLS mesh, Postgres schema, Postgres queue behind a Queue
// interface, outbox relay, idempotency, the message state machine, the sandbox provider,
// POST /messages, the status API, contacts (core-only writer), GET /capabilities and metrics.
//
// It lives in the trustplane Go module (cmd/comms) so it can reuse internal/{dbx,mtlsx,breaker}
// as comms-design §3.1 recommends; a sibling module could not import trustplane/internal.
package commsplane

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Environment  string // dev | sandbox | uat | production
	Listen       string
	HealthListen string
	DSN          string
	CertFile     string
	KeyFile      string
	InternalCA   string
	ConfigFile   string
	DataKeyFile  string // 32+ bytes of secret material (hex or raw); derives the AES and blind-index keys

	File FileConfig
}

type ProviderCfg struct {
	Kind string `yaml:"kind"` // sandbox (B0); real kinds arrive in B1
}

type PolicyCfg struct {
	Category      string        `yaml:"category"` // SECURITY | TRANSACTIONAL | MARKETING
	Lane          string        `yaml:"lane"`     // CRITICAL | TRANSACTIONAL | BULK
	Channels      []string      `yaml:"channels"` // chain: first channel the recipient can be reached on
	TTL           time.Duration `yaml:"ttl"`
	RequireExpiry bool          `yaml:"require_expiry"`
	Secret        []string      `yaml:"secret"`
}

type TemplateCfg struct {
	Event   string `yaml:"event"`
	Channel string `yaml:"channel"`
	Locale  string `yaml:"locale"`
	Subject string `yaml:"subject"`
	Body    string `yaml:"body"`
}

type FileConfig struct {
	Callers        map[string][]string    `yaml:"callers"`
	ContactWriters []string               `yaml:"contact_writers"`
	Providers      map[string]ProviderCfg `yaml:"providers"`
	EventPolicies  map[string]PolicyCfg   `yaml:"event_policies"`
	Templates      []TemplateCfg          `yaml:"templates"`
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

var (
	lanes    = map[string]bool{"CRITICAL": true, "TRANSACTIONAL": true, "BULK": true}
	channels = map[string]bool{"sms": true, "email": true, "push": true, "whatsapp": true, "in_app": true}
	varRe    = regexp.MustCompile(`\{\{\s*([A-Za-z0-9_]+)\s*\}\}`)
)

func FromEnv() (Config, error) {
	c := Config{
		Environment:  envOr("COMMS_ENVIRONMENT", "dev"),
		Listen:       envOr("COMMS_LISTEN", ":8443"),
		HealthListen: envOr("COMMS_HEALTH_LISTEN", ":8081"),
		DSN:          os.Getenv("COMMS_DB_URL"),
		CertFile:     envOr("COMMS_TLS_CERT", "/pki/comms-server.pem"),
		KeyFile:      envOr("COMMS_TLS_KEY", "/pki/comms-server-key.pem"),
		InternalCA:   envOr("COMMS_INTERNAL_CA", "/pki/platform-ca.pem"),
		ConfigFile:   envOr("COMMS_CONFIG", "/config/comms/comms.yaml"),
		DataKeyFile:  envOr("COMMS_DATA_KEY_FILE", "/pki/comms-data-key"),
	}
	if c.DSN == "" {
		return c, fmt.Errorf("comms: COMMS_DB_URL is required")
	}
	raw, err := os.ReadFile(c.ConfigFile)
	if err != nil {
		return c, fmt.Errorf("comms: config: %w", err)
	}
	if err := yaml.Unmarshal(raw, &c.File); err != nil {
		return c, fmt.Errorf("comms: parse %s: %w", c.ConfigFile, err)
	}
	return c, c.validate()
}

func (c *Config) validate() error {
	switch c.Environment {
	case "dev", "sandbox", "uat", "production":
	default:
		return fmt.Errorf("comms: COMMS_ENVIRONMENT %q must be dev|sandbox|uat|production", c.Environment)
	}
	for ch, p := range c.File.Providers {
		if !channels[ch] {
			return fmt.Errorf("comms: provider for unknown channel %q", ch)
		}
		if p.Kind != "sandbox" {
			return fmt.Errorf("comms: provider kind %q for %s is not available in B0 (sandbox only)", p.Kind, ch)
		}
		if p.Kind == "sandbox" && c.Environment == "production" {
			return fmt.Errorf("comms: the sandbox provider (%s) is refused when COMMS_ENVIRONMENT=production", ch)
		}
	}
	for code, p := range c.File.EventPolicies {
		if !lanes[p.Lane] {
			return fmt.Errorf("comms: policy %s: lane %q must be CRITICAL|TRANSACTIONAL|BULK", code, p.Lane)
		}
		if len(p.Channels) == 0 {
			return fmt.Errorf("comms: policy %s: channels required", code)
		}
		for _, ch := range p.Channels {
			if !channels[ch] {
				return fmt.Errorf("comms: policy %s: unknown channel %q", code, ch)
			}
		}
	}
	for i, t := range c.File.Templates {
		if _, ok := c.File.EventPolicies[t.Event]; !ok {
			return fmt.Errorf("comms: template #%d: no event policy for %q", i, t.Event)
		}
		if !channels[t.Channel] || strings.TrimSpace(t.Body) == "" {
			return fmt.Errorf("comms: template #%d (%s): channel and body required", i, t.Event)
		}
		if t.Locale == "" {
			c.File.Templates[i].Locale = "en"
		}
	}
	return nil
}

// IsProduction: no sandbox provider, no sandbox outbox endpoint.
func (c Config) IsProduction() bool { return c.Environment == "production" }

// templateVars lists the variables a template body/subject references.
func templateVars(parts ...string) []string {
	seen := map[string]bool{}
	var out []string
	for _, p := range parts {
		for _, m := range varRe.FindAllStringSubmatch(p, -1) {
			if !seen[m[1]] {
				seen[m[1]] = true
				out = append(out, m[1])
			}
		}
	}
	sort.Strings(out)
	return out
}

// renderTemplate substitutes {{var}} (values are plain text in B0: SMS and text email).
func renderTemplate(tpl string, data map[string]string) string {
	return varRe.ReplaceAllStringFunc(tpl, func(m string) string {
		return data[varRe.FindStringSubmatch(m)[1]]
	})
}

func hashTemplate(subject, body string) string {
	sum := sha256.Sum256([]byte(subject + "\x00" + body))
	return hex.EncodeToString(sum[:8])
}
