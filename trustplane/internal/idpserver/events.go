package idpserver

// Outbound deliveries (idp-rbac-cutover-plan.md P1.5, P1.6):
//
//   - auth events to the core: POST IDP_CORE_EVENTS_URL with a signed JWT
//     (typ auth-event+jwt, jti = event id for de-duplication);
//   - back-channel logout tokens to every client with a backchannel_logout_uri
//     (OpenID Connect Back-Channel Logout 1.0, typ logout+jwt).
//
// Both are written to the `outbound` table in the same transaction as the change that
// causes them, and a worker delivers them with retries (exponential back-off, capped),
// so an outage of the receiver delays a delivery but never loses it.

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"trustplane/internal/jwtx"
	"trustplane/internal/mtlsx"
)

// Event types (AuthPluginContracts.AuthEvent.Type in the core).
const (
	EventCredentialSet     = "CREDENTIAL_SET"
	EventCredentialReset   = "CREDENTIAL_RESET"
	EventLocked            = "LOCKED"
	EventUnlocked          = "UNLOCKED"
	EventRecoveryRequested = "ASSISTED_RECOVERY_REQUESTED"
)

type execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

func enqueueOutbound(ctx context.Context, q execer, target, url string, payload []byte) error {
	_, err := q.Exec(ctx, `INSERT INTO outbound (id, target, url, payload) VALUES ($1,$2,$3,$4)`,
		"ob_"+jwtx.RandB64(16), target, url, string(payload))
	return err
}

// emitEvent queues one auth event for the core. The url is resolved at delivery time, so
// events raised before IDP_CORE_EVENTS_URL is configured are delivered once it is.
func (s *Server) emitEvent(ctx context.Context, q execer, a *Account, typ string, detail map[string]any) error {
	if detail == nil {
		detail = map[string]any{}
	}
	ev := map[string]any{
		"id": "evt_" + jwtx.RandB64(16), "provider": "trustplane", "type": typ,
		"subject": a.Subject, "uid": a.UID, "at": time.Now().UTC().Format(time.RFC3339), "detail": detail,
	}
	payload, _ := json.Marshal(ev)
	return enqueueOutbound(ctx, q, "core", "", payload)
}

// ---------------------------------------------------------------------------
// Delivery worker
// ---------------------------------------------------------------------------

type outboundRow struct {
	id, target, url, payload string
	attempts                 int
}

// backoff: 2 s, 4 s, 8 s ... capped at 10 min.
func backoff(attempts int) time.Duration {
	d := 2 * time.Second
	for i := 1; i < attempts && d < 10*time.Minute; i++ {
		d *= 2
	}
	if d > 10*time.Minute {
		d = 10 * time.Minute
	}
	return d
}

// DeliverLoop runs until ctx ends.
func (s *Server) DeliverLoop(ctx context.Context) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			for s.deliverBatch(ctx) > 0 {
			}
		}
	}
}

// deliverBatch delivers up to 20 due rows; returns how many it attempted.
func (s *Server) deliverBatch(ctx context.Context) int {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id, target, url, payload, attempts FROM outbound
		WHERE delivered_at IS NULL AND next_at <= now() ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED`)
	if err != nil {
		return 0
	}
	var batch []outboundRow
	for rows.Next() {
		var o outboundRow
		if rows.Scan(&o.id, &o.target, &o.url, &o.payload, &o.attempts) == nil {
			batch = append(batch, o)
		}
	}
	rows.Close()
	for _, o := range batch {
		err := s.deliverOne(ctx, o)
		switch {
		case err == nil:
			_, _ = tx.Exec(ctx, `UPDATE outbound SET delivered_at=now(), attempts=attempts+1, last_error=NULL WHERE id=$1`, o.id)
		case err == errNotConfigured:
			_, _ = tx.Exec(ctx, `UPDATE outbound SET next_at=now()+interval '1 minute', last_error=$2 WHERE id=$1`, o.id, err.Error())
		default:
			_, _ = tx.Exec(ctx, `UPDATE outbound SET attempts=attempts+1, next_at=now()+make_interval(secs => $2), last_error=$3 WHERE id=$1`,
				o.id, backoff(o.attempts+1).Seconds(), truncate(err.Error(), 300))
			log.Printf("[idp] outbound %s (%s) attempt %d failed: %v", o.id, o.target, o.attempts+1, err)
		}
	}
	if tx.Commit(ctx) != nil {
		return 0
	}
	return len(batch)
}

var errNotConfigured = fmt.Errorf("receiver not configured (IDP_CORE_EVENTS_URL empty)")

func (s *Server) deliverOne(ctx context.Context, o outboundRow) error {
	switch o.target {
	case "core":
		if s.cfg.CoreEventsURL == "" {
			return errNotConfigured
		}
		var ev map[string]any
		if err := json.Unmarshal([]byte(o.payload), &ev); err != nil {
			return fmt.Errorf("bad payload: %w", err)
		}
		tok, err := s.signEvent(ev)
		if err != nil {
			return err
		}
		return s.post(ctx, s.coreHTTP, s.cfg.CoreEventsURL, "application/jwt", []byte(tok))
	case "logout":
		var p struct {
			ClientID string `json:"client_id"`
			Sub      string `json:"sub"`
			UID      string `json:"uid"`
		}
		if err := json.Unmarshal([]byte(o.payload), &p); err != nil {
			return fmt.Errorf("bad payload: %w", err)
		}
		tok, err := s.logoutToken(p.ClientID, p.Sub, p.UID)
		if err != nil {
			return err
		}
		form := "logout_token=" + urlEscape(tok)
		return s.post(ctx, s.logoutHTTP, o.url, "application/x-www-form-urlencoded", []byte(form))
	default:
		return fmt.Errorf("unknown target %q", o.target)
	}
}

func (s *Server) post(ctx context.Context, cl *http.Client, url, ctype string, body []byte) error {
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", ctype)
	resp, err := cl.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("status %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

func (s *Server) signer() keyEntry { return s.keys.byAlg[jwtx.AlgES256] }

// signEvent signs an auth event for the core (verifiable against the IdP JWKS).
func (s *Server) signEvent(ev map[string]any) (string, error) {
	k := s.signer()
	now := time.Now().Unix()
	return jwtx.SignJWS(jwtx.AlgES256, "auth-event+jwt", k.kid, map[string]any{
		"iss": s.cfg.Issuer, "aud": "mipay-core", "iat": now, "exp": now + 300,
		"jti": ev["id"], "event": ev,
	}, k.signer)
}

// logoutToken builds an OIDC back-channel logout token for one client.
func (s *Server) logoutToken(clientID, sub, uid string) (string, error) {
	k := s.signer()
	now := time.Now().Unix()
	claims := map[string]any{
		"iss": s.cfg.Issuer, "aud": clientID, "iat": now, "exp": now + 120,
		"jti": jwtx.RandB64(16), "sub": sub,
		"events": map[string]any{"http://schemas.openid.net/event/backchannel-logout": map[string]any{}},
	}
	if uid != "" {
		claims["uid"] = uid
	}
	return jwtx.SignJWS(jwtx.AlgES256, "logout+jwt", k.kid, claims, k.signer)
}

// outboundClients builds the HTTP clients for deliveries:
//   - core / comms over https present the IdP's mesh certificate (it carries clientAuth);
//   - back-channel logout to the gateway verifies the gateway against the platform CA but
//     presents no certificate (the gateway's edge listener trusts only the partner CA).
func (s *Server) outboundClients() error {
	pool := x509.NewCertPool()
	if pem, err := os.ReadFile(s.cfg.InternalCA); err == nil {
		pool.AppendCertsFromPEM(pem)
	}
	s.logoutHTTP = &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool},
	}}
	mesh := &http.Transport{}
	if cfg, err := mtlsx.MeshClientConfig(s.cfg.CertFile, s.cfg.KeyFile, s.cfg.InternalCA, ""); err == nil {
		cfg.ServerName = "" // verified per request host
		mesh.TLSClientConfig = cfg
	} else {
		log.Printf("[idp] mesh client certificate unavailable (%v); https deliveries will fail", err)
	}
	s.coreHTTP = &http.Client{Timeout: 10 * time.Second, Transport: mesh}
	return nil
}
