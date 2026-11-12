// Package clients loads the confidential-client registry shared between the
// IdP (verification) and the e2e client (acting as the OAuth client).
package clients

import (
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"

	"trustplane/internal/jwtx"
)

const (
	AuthPrivateKeyJWT = "private_key_jwt"
	AuthTLSClient     = "tls_client_auth"
)

type Client struct {
	ID           string   `yaml:"id"`
	DisplayName  string   `yaml:"display_name"`
	AuthMethod   string   `yaml:"auth_method"`
	TokenAlg     string   `yaml:"token_alg"`
	RedirectURIs []string `yaml:"redirect_uris"`
	Scopes       []string `yaml:"scopes"`
	JWKSFiles    []string `yaml:"jwks_files"`
	CertFiles    []string `yaml:"cert_files"`
	// resolved at load time
	JWTKeys    []map[string]any `yaml:"-"`
	CertThumbs []string         `yaml:"-"`
	JWKSPaths  []string         `yaml:"-"`
	CertPaths  []string         `yaml:"-"`

	TokenTTLSeconds   int   `yaml:"token_ttl_seconds"`
	RefreshTTLSeconds int   `yaml:"refresh_ttl_seconds"`
	RequireConsent    *bool `yaml:"require_consent"`
	// BackchannelLogoutURI receives OIDC back-channel logout tokens when an account is
	// disabled, reset or has its sessions revoked (idp-rbac-cutover-plan.md P1.6).
	BackchannelLogoutURI string `yaml:"backchannel_logout_uri"`
}

type Registry struct {
	List []*Client
	ByID map[string]*Client
}

// resolvePath joins relative entries with the registry dir; entries that
// are already absolute (e.g. inside the shared PKI volume) pass through.
func resolvePath(dir, f string) string {
	if strings.HasPrefix(f, "/") {
		return f
	}
	return filepath.Join(dir, f)
}

// Load parses the registry file and resolves every referenced key/cert file
// (paths relative to the file's directory).
func Load(path string) (*Registry, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var file struct {
		Clients []*Client `yaml:"clients"`
	}
	if err := yaml.Unmarshal(raw, &file); err != nil {
		return nil, fmt.Errorf("clients: parse %s: %w", path, err)
	}
	if len(file.Clients) == 0 {
		return nil, errors.New("clients: registry empty")
	}
	dir := filepath.Dir(path)
	reg := &Registry{ByID: map[string]*Client{}}
	for _, c := range file.Clients {
		if c.ID == "" || c.AuthMethod == "" {
			return nil, fmt.Errorf("clients: %s: missing id/auth_method", path)
		}
		if c.TokenAlg == "" {
			c.TokenAlg = jwtx.AlgPS256
		}
		switch c.AuthMethod {
		case AuthPrivateKeyJWT:
			if len(c.JWKSFiles) == 0 {
				return nil, fmt.Errorf("clients: %s: private_key_jwt without jwks_files", c.ID)
			}
			for _, f := range c.JWKSFiles {
				p := resolvePath(dir, f)
				c.JWKSPaths = append(c.JWKSPaths, p)
				signer, err := jwtx.LoadPrivateKeyFile(p)
				if err != nil {
					return nil, fmt.Errorf("clients: %s: %w", c.ID, err)
				}
				jwk, err := jwtx.PublicJWK(signer)
				if err != nil {
					return nil, err
				}
				jwk["kid"] = strings.TrimSuffix(filepath.Base(f), ".pem")
				c.JWTKeys = append(c.JWTKeys, jwk)
			}
		case AuthTLSClient:
			if len(c.CertFiles) == 0 {
				return nil, fmt.Errorf("clients: %s: tls_client_auth without cert_files", c.ID)
			}
			for _, f := range c.CertFiles {
				p := resolvePath(dir, f)
				c.CertPaths = append(c.CertPaths, p)
				der, err := readCert(p)
				if err != nil {
					return nil, fmt.Errorf("clients: %s: %w", c.ID, err)
				}
				c.CertThumbs = append(c.CertThumbs, jwtx.ThumbprintCertDER(der))
			}
		default:
			return nil, fmt.Errorf("clients: %s: unknown auth_method %q", c.ID, c.AuthMethod)
		}
		reg.List = append(reg.List, c)
		reg.ByID[c.ID] = c
	}
	return reg, nil
}

func readCert(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	rest := data
	for {
		block, remaining := pem.Decode(rest)
		if block == nil {
			break
		}
		rest = remaining
		if block.Type == "CERTIFICATE" {
			cert, err := x509.ParseCertificate(block.Bytes)
			if err != nil {
				return nil, err
			}
			return cert.Raw, nil
		}
	}
	return nil, errors.New("clients: no certificate in pem")
}
