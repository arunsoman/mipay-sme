package gateway

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server        ServerCfg           `yaml:"server"`
	Upstreams     map[string]Upstream `yaml:"upstreams"`
	RBACGrpc      RBACGrpcCfg         `yaml:"rbac_grpc"`
	Introspection IntrospectionCfg    `yaml:"introspection"`
	Limits        LimitsCfg           `yaml:"limits"`
	Routes        []Route             `yaml:"routes"`
}

type ServerCfg struct {
	Cert           string `yaml:"cert"`
	Key            string `yaml:"key"`
	ClientCA       string `yaml:"client_ca"`      // partner client certs (optional at edge)
	ContextSecret  string `yaml:"context_secret"` // HMAC key for internal context headers
	Issuer         string `yaml:"issuer"`
	MaxHeaderBytes int    `yaml:"max_header_bytes"`
}

type Upstream struct {
	BaseURL     string `yaml:"base_url"`
	ClientCert  string `yaml:"client_cert"`
	ClientKey   string `yaml:"client_key"`
	CA          string `yaml:"ca"`
	ServerName  string `yaml:"server_name"`
	ForwardDPoP bool   `yaml:"forward_dpop"` // idp needs the client's DPoP proof on /token
	// Legacy/foreign upstreams that terminate their own TLS-and-auth stack
	// (e.g. the MiPay Java backend validates the bearer tokens it issues
	// itself) may be reached over plain HTTP on the isolated mesh. Never for
	// edge traffic - edge always terminates TLS at the gateway.
	PlainHTTP bool `yaml:"plain_http"`
	// ForwardAuthorization passes the caller's Authorization header through
	// for upstreams that are their own resource server (the gateway is still
	// the routing/policy point; authN/Z is delegated to the upstream IdP).
	ForwardAuthorization bool `yaml:"forward_authorization"`
}

type RBACGrpcCfg struct {
	Endpoint   string `yaml:"endpoint"`
	ClientCert string `yaml:"client_cert"`
	ClientKey  string `yaml:"client_key"`
	CA         string `yaml:"ca"`
	ServerName string `yaml:"server_name"`
	TimeoutMS  int    `yaml:"timeout_ms"`
}

type IntrospectionCfg struct {
	Enabled      bool   `yaml:"enabled"`
	BaseURL      string `yaml:"base_url"` // e.g. https://idp:8443
	ClientID     string `yaml:"client_id"`
	AssertionKey string `yaml:"assertion_key"`
	CacheTTL     int    `yaml:"cache_ttl_seconds"`
	TimeoutMS    int    `yaml:"timeout_ms"`
}

type LimitsCfg struct {
	GlobalConcurrency   int `yaml:"global_concurrency"`
	UpstreamConcurrency int `yaml:"upstream_concurrency"`
}

type Route struct {
	Host       string   `yaml:"host"`
	Path       string   `yaml:"path"`
	Methods    []string `yaml:"methods"`
	Upstream   string   `yaml:"upstream"`
	Auth       string   `yaml:"auth"` // none | token
	Scopes     []string `yaml:"scopes"`
	Permission string   `yaml:"permission"`
	Fresh      bool     `yaml:"fresh_decision"`
	Rate       RateCfg  `yaml:"rate"`
	BodyLimit  int64    `yaml:"body_limit"`
	TimeoutMS  int      `yaml:"timeout_ms"`
}

type RateCfg struct {
	IPPerMin     int `yaml:"ip_per_min"`
	ClientPerMin int `yaml:"client_per_min"`
}

// Load reads and validates the route table. Deny-by-default is enforced at
// deployment time: a route without a policy (auth) fails to load, and any
// upstream reference must exist.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := yaml.Unmarshal(raw, &cfg); err != nil {
		return nil, fmt.Errorf("gateway: parse %s: %w", path, err)
	}
	if err := cfg.validate(); err != nil {
		return nil, fmt.Errorf("gateway: %s: %w", path, err)
	}
	return &cfg, nil
}

func (c *Config) validate() error {
	if c.Server.Issuer == "" || c.Server.ClientCA == "" || c.Server.ContextSecret == "" {
		return fmt.Errorf("server.issuer/client_ca/context_secret are required")
	}
	if c.Server.MaxHeaderBytes == 0 {
		c.Server.MaxHeaderBytes = 32 * 1024
	}
	if c.Limits.GlobalConcurrency == 0 {
		c.Limits.GlobalConcurrency = 512
	}
	if c.Limits.UpstreamConcurrency == 0 {
		c.Limits.UpstreamConcurrency = 128
	}
	if len(c.Upstreams) == 0 {
		return fmt.Errorf("no upstreams")
	}
	for i := range c.Routes {
		r := &c.Routes[i]
		if r.Host == "" || r.Path == "" || r.Upstream == "" {
			return fmt.Errorf("route #%d: host/path/upstream required", i)
		}
		if r.Auth != "none" && r.Auth != "token" {
			return fmt.Errorf("route %s%s: auth must be none|token (deny-by-default)", r.Host, r.Path)
		}
		if _, ok := c.Upstreams[r.Upstream]; !ok {
			return fmt.Errorf("route %s%s: unknown upstream %q", r.Host, r.Path, r.Upstream)
		}
		if r.Auth == "token" && len(r.Scopes) == 0 && r.Permission == "" {
			return fmt.Errorf("route %s%s: token route without scopes or permission policy", r.Host, r.Path)
		}
		if r.Permission != "" && !strings.HasPrefix(r.Permission, "rbac.") && !strings.Contains(r.Path, "{tenant}") {
			// Business-domain permissions are tenant-scoped by construction;
			// only the RBAC admin plane (rbac.*) is evaluated without a tenant.
			return fmt.Errorf("route %s%s: permission requires a {tenant} path parameter", r.Host, r.Path)
		}
		if r.BodyLimit == 0 {
			r.BodyLimit = 1 << 20
		}
		if r.TimeoutMS == 0 {
			r.TimeoutMS = 10000
		}
		if len(r.Methods) == 0 {
			return fmt.Errorf("route %s%s: methods required", r.Host, r.Path)
		}
		if r.Rate.IPPerMin == 0 {
			r.Rate.IPPerMin = 600
		}
	}
	return nil
}
