// Package mtlsx builds TLS configurations for the edge listener and the
// internal service mesh (both pinned to TLS 1.2+ as required by FAPI 2.0).
package mtlsx

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"time"

	"trustplane/internal/jwtx"
)

// LeafThumbprint returns the RFC 8705 x5t#S256 thumbprint of the peer
// certificate presented in cs ("" when none).
func LeafThumbprint(cs *tls.ConnectionState) (string, bool) {
	if cs == nil || len(cs.PeerCertificates) == 0 {
		return "", false
	}
	return jwtx.ThumbprintCertDER(cs.PeerCertificates[0].Raw), true
}

// ServerConfig builds a server-side TLS config.
// caFile == ""  -> no client certificates requested.
// caFile != "" && require -> enforce client certs; otherwise verify-if-given.
func ServerConfig(certFile, keyFile, caFile string, require bool) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("mtlsx: load server keypair: %w", err)
	}
	cfg := &tls.Config{
		MinVersion:   tls.VersionTLS12,
		Certificates: []tls.Certificate{cert},
		CipherSuites: []uint16{
			tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
			tls.TLS_AES_128_GCM_SHA256,
			tls.TLS_AES_256_GCM_SHA384,
			tls.TLS_CHACHA20_POLY1305_SHA256,
		},
	}
	if caFile != "" {
		pemBytes, err := os.ReadFile(caFile)
		if err != nil {
			return nil, fmt.Errorf("mtlsx: read CA: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pemBytes) {
			return nil, errors.New("mtlsx: bad CA pem")
		}
		cfg.ClientCAs = pool
		if require {
			cfg.ClientAuth = tls.RequireAndVerifyClientCert
		} else {
			cfg.ClientAuth = tls.VerifyClientCertIfGiven
		}
	}
	return cfg, nil
}

// MeshClientConfig builds a client-side TLS config for the internal mesh
// (presents a mesh client cert and verifies the upstream against the
// internal CA).
func MeshClientConfig(certFile, keyFile, caFile, serverName string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("mtlsx: load mesh keypair: %w", err)
	}
	pemBytes, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("mtlsx: read CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemBytes) {
		return nil, errors.New("mtlsx: bad CA pem")
	}
	return &tls.Config{
		MinVersion:         tls.VersionTLS12,
		Certificates:       []tls.Certificate{cert},
		RootCAs:            pool,
		ServerName:         serverName,
		ClientSessionCache: tls.NewLRUClientSessionCache(64),
	}, nil
}

// ExternalClientConfig builds a client TLS config for partner-style clients
// that optionally present a client certificate and verify the server with
// the platform CA.
func ExternalClientConfig(caFile string, cert *tls.Certificate) (*tls.Config, error) {
	pemBytes, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("mtlsx: read CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemBytes) {
		return nil, errors.New("mtlsx: bad CA pem")
	}
	cfg := &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool}
	if cert != nil {
		cfg.Certificates = []tls.Certificate{*cert}
	}
	return cfg, nil
}

// LoadCertificate assembles a tls.Certificate from separate cert/key PEMs.
func LoadCertificate(certFile, keyFile string) (*tls.Certificate, error) {
	certPEM, err := os.ReadFile(certFile)
	if err != nil {
		return nil, err
	}
	key, err := jwtx.LoadPrivateKeyFile(keyFile)
	if err != nil {
		return nil, err
	}
	var der []byte
	rest := certPEM
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type == "CERTIFICATE" {
			der = block.Bytes
			break
		}
	}
	if der == nil {
		return nil, errors.New("mtlsx: no CERTIFICATE in file")
	}
	return &tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, nil
}

// SelfSignedCA is a small dev helper used by tests.
func SelfSignedCA(key *ecdsa.PrivateKey) ([]byte, error) {
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test-ca"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		IsCA:         true,
		KeyUsage:     x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), nil
}
