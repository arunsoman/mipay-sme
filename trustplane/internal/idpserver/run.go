package idpserver

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"time"

	"trustplane/internal/mtlsx"
)

// Run starts the mTLS TLS listener (gateway mesh only) and a plain HTTP
// health endpoint. Blocks until ctx is cancelled.
func (s *Server) Run(ctx context.Context) error {
	tlsCfg, err := mtlsx.ServerConfig(s.cfg.CertFile, s.cfg.KeyFile, s.cfg.InternalCA, true)
	if err != nil {
		return fmt.Errorf("idp: tls: %w", err)
	}
	srv := &http.Server{
		Handler:           s.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    32 * 1024,
		TLSConfig:         tlsCfg,
	}
	ln, err := net.Listen("tcp", s.cfg.Listen)
	if err != nil {
		return err
	}
	health := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { s.Health(w, nil) })}
	hln, err := net.Listen("tcp", s.cfg.HealthListen)
	if err != nil {
		return err
	}
	go func() { _ = health.Serve(hln) }()

	errCh := make(chan error, 1)
	go func() { errCh <- srv.Serve(tls.NewListener(ln, tlsCfg)) }()

	select {
	case <-ctx.Done():
		shCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shCtx)
		_ = health.Close()
		return nil
	case err := <-errCh:
		return err
	}
}
