package commsplane

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"trustplane/internal/breaker"
	"trustplane/internal/dbx"
	"trustplane/internal/jwtx"
	"trustplane/internal/mtlsx"
)

type Server struct {
	cfg       Config
	pool      *pgxpool.Pool
	queue     Queue
	seal      *sealer
	providers map[string]Provider
	breakers  map[string]*breaker.Breaker
	metrics   *metrics

	wake chan struct{}

	capsMu sync.Mutex
	caps   map[string]channelCap
	capsAt time.Time
}

// New loads the data key and builds the server.
func New(cfg Config, pool *pgxpool.Pool) (*Server, error) {
	seal, err := loadSealer(cfg.DataKeyFile)
	if err != nil {
		return nil, err
	}
	return NewServer(cfg, pool, seal), nil
}

func NewServer(cfg Config, pool *pgxpool.Pool, seal *sealer) *Server {
	s := &Server{cfg: cfg, pool: pool, queue: pgQueue{pool}, seal: seal,
		providers: map[string]Provider{}, breakers: map[string]*breaker.Breaker{}, metrics: newMetrics(),
		wake: make(chan struct{}, 1)}
	for ch, p := range cfg.File.Providers {
		if p.Kind == "sandbox" {
			s.providers[ch] = sandboxProvider{pool: pool, channel: ch}
			s.breakers[ch] = breaker.New(5, 30*time.Second)
		}
	}
	return s
}

// Setup applies the DDL and imports the platform-default templates as PUBLISHED v1.
// A published version is immutable: a changed default becomes a new version.
func (s *Server) Setup(ctx context.Context) error {
	if err := dbx.Migrate(ctx, s.pool, DDL); err != nil {
		return err
	}
	for _, t := range s.cfg.File.Templates {
		h := hashTemplate(t.Subject, t.Body)
		var latest int
		var latestHash string
		err := s.pool.QueryRow(ctx, `SELECT version, content_hash FROM template_versions
			WHERE tenant_id IS NULL AND event_code=$1 AND channel=$2 AND locale=$3 AND status='PUBLISHED'
			ORDER BY version DESC LIMIT 1`, t.Event, t.Channel, t.Locale).Scan(&latest, &latestHash)
		if err == nil && latestHash == h {
			continue
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return err
		}
		_, _ = tx.Exec(ctx, `UPDATE template_versions SET status='ARCHIVED' WHERE tenant_id IS NULL AND event_code=$1
			AND channel=$2 AND locale=$3 AND status='PUBLISHED'`, t.Event, t.Channel, t.Locale)
		if _, err := tx.Exec(ctx, `INSERT INTO template_versions (tenant_id, event_code, channel, locale, version, status, subject, body, variables, content_hash)
			VALUES (NULL,$1,$2,$3,$4,'PUBLISHED',$5,$6,$7,$8)`,
			t.Event, t.Channel, t.Locale, latest+1, t.Subject, t.Body, templateVars(t.Subject, t.Body), h); err != nil {
			tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
		log.Printf("[comms] template %s/%s/%s published as v%d", t.Event, t.Channel, t.Locale, latest+1)
	}
	return nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /comms/v1/messages", s.postMessages)
	mux.HandleFunc("GET /comms/v1/messages/{id}", s.getMessage)
	mux.HandleFunc("GET /comms/v1/messages/{id}/events", s.getMessageEvents)
	mux.HandleFunc("GET /comms/v1/requests/{id}", s.getRequest)
	mux.HandleFunc("PUT /comms/v1/subjects/{ref}/contacts", s.putContacts)
	mux.HandleFunc("GET /comms/v1/subjects/{ref}/contacts", s.getContacts)
	mux.HandleFunc("GET /comms/v1/capabilities", s.getCapabilities)
	mux.HandleFunc("GET /comms/v1/sandbox/outbox", s.getSandboxOutbox)
	mux.HandleFunc("GET /metrics", s.getMetrics)
	return mux
}

func (s *Server) Health(w http.ResponseWriter, _ *http.Request) {
	if err := dbx.Healthy(s.pool); err != nil {
		http.Error(w, "db down", http.StatusServiceUnavailable)
		return
	}
	w.Write([]byte("ok"))
}

// Run serves the mesh API (mTLS required) and a plain health endpoint, and runs the
// relay, planner and dispatchers until ctx ends.
func (s *Server) Run(ctx context.Context) error {
	tlsCfg, err := mtlsx.ServerConfig(s.cfg.CertFile, s.cfg.KeyFile, s.cfg.InternalCA, true)
	if err != nil {
		return fmt.Errorf("comms: tls: %w", err)
	}
	srv := &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second,
		WriteTimeout: 30 * time.Second, IdleTimeout: 120 * time.Second, MaxHeaderBytes: 32 * 1024, TLSConfig: tlsCfg}
	ln, err := net.Listen("tcp", s.cfg.Listen)
	if err != nil {
		return err
	}
	health := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { s.Health(w, r) }),
		ReadHeaderTimeout: 5 * time.Second}
	hln, err := net.Listen("tcp", s.cfg.HealthListen)
	if err != nil {
		return err
	}
	go func() { _ = health.Serve(hln) }()
	s.StartWorkers(ctx)
	errCh := make(chan error, 1)
	go func() { errCh <- srv.Serve(tls.NewListener(ln, tlsCfg)) }()
	select {
	case <-ctx.Done():
		sh, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sh)
		_ = health.Close()
		return nil
	case err := <-errCh:
		return err
	}
}

// ---------------------------------------------------------------------------
// Caller identity: the mesh certificate CN (normalised)
// ---------------------------------------------------------------------------

func normalizeCN(s string) string {
	v := strings.ToLower(strings.TrimSpace(s))
	v = strings.TrimPrefix(v, "sme-")
	v = strings.TrimSuffix(v, "-mesh")
	return v
}

func caller(r *http.Request) string {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return ""
	}
	return normalizeCN(r.TLS.PeerCertificates[0].Subject.CommonName)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func apiErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

func (s *Server) mayUse(callerID, event string) bool {
	for _, prefix := range s.cfg.File.Callers[callerID] {
		if strings.HasPrefix(event, prefix) {
			return true
		}
	}
	return false
}

func (s *Server) isContactWriter(callerID string) bool {
	for _, c := range s.cfg.File.ContactWriters {
		if normalizeCN(c) == callerID {
			return true
		}
	}
	return false
}

func newID(prefix string) string { return prefix + "_" + jwtx.RandB64(15) }

// ---------------------------------------------------------------------------
// POST /comms/v1/messages
// ---------------------------------------------------------------------------

var recipientRe = regexp.MustCompile(`^user:[A-Za-z0-9._:@-]{1,128}$`)

type sendBody struct {
	EventCode   string            `json:"eventCode"`
	To          []string          `json:"to"`
	Data        map[string]string `json:"data"`
	Lane        string            `json:"lane"`
	ExpiresAt   *time.Time        `json:"expiresAt"`
	Sensitivity string            `json:"sensitivity"`
	Locale      string            `json:"locale"`
}

// payload is what travels (sealed) through outbox and queue.
type payload struct {
	Data    map[string]string `json:"data"`
	Address string            `json:"address,omitempty"` // set by the planner
	TplID   int64             `json:"tpl,omitempty"`
}

func (s *Server) requiredVars(event string) []string {
	p := s.cfg.File.EventPolicies[event]
	inChain := map[string]bool{}
	for _, ch := range p.Channels {
		inChain[ch] = true
	}
	var parts []string
	for _, t := range s.cfg.File.Templates {
		if t.Event == event && inChain[t.Channel] {
			parts = append(parts, t.Subject, t.Body)
		}
	}
	return templateVars(parts...)
}

func (s *Server) postMessages(w http.ResponseWriter, r *http.Request) {
	who := caller(r)
	var b sendBody
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&b); err != nil {
		apiErr(w, 400, "REJECTED", "body must be {eventCode, to[], data{}, lane?, expiresAt?, sensitivity?, locale?}")
		return
	}
	pol, ok := s.cfg.File.EventPolicies[b.EventCode]
	if !ok {
		apiErr(w, 422, "REJECTED", "unknown eventCode "+b.EventCode)
		return
	}
	if !s.mayUse(who, b.EventCode) {
		apiErr(w, 403, "FORBIDDEN", fmt.Sprintf("caller %q may not send %s", who, b.EventCode))
		return
	}
	if len(b.To) == 0 || len(b.To) > 100 {
		apiErr(w, 422, "REJECTED", "to must hold 1 to 100 recipients")
		return
	}
	for _, to := range b.To {
		if !recipientRe.MatchString(to) {
			apiErr(w, 422, "REJECTED", "recipients are user:<id> in B0: "+to)
			return
		}
	}
	if b.Lane != "" && b.Lane != pol.Lane {
		apiErr(w, 422, "REJECTED", fmt.Sprintf("%s travels in lane %s", b.EventCode, pol.Lane))
		return
	}
	var missing []string
	for _, v := range s.requiredVars(b.EventCode) {
		if strings.TrimSpace(b.Data[v]) == "" {
			missing = append(missing, v)
		}
	}
	if len(missing) > 0 {
		apiErr(w, 422, "REJECTED", "missing template variables: "+strings.Join(missing, ", "))
		return
	}
	now := time.Now()
	var expires *time.Time
	switch {
	case b.ExpiresAt != nil:
		expires = b.ExpiresAt
	case pol.TTL > 0:
		e := now.Add(pol.TTL)
		expires = &e
	}
	if pol.RequireExpiry && b.ExpiresAt == nil {
		apiErr(w, 422, "REJECTED", b.EventCode+" requires expiresAt")
		return
	}
	if expires != nil && !expires.After(now) {
		apiErr(w, 422, "REJECTED", "expiresAt is in the past")
		return
	}
	sens := "NORMAL"
	if strings.EqualFold(b.Sensitivity, "SECRET") || len(pol.Secret) > 0 {
		sens = "SECRET"
	}
	locale := b.Locale
	if locale == "" {
		locale = "en"
	}
	idem := r.Header.Get("Idempotency-Key")
	if len(idem) > 200 {
		apiErr(w, 400, "REJECTED", "Idempotency-Key too long")
		return
	}
	names := make([]string, 0, len(b.Data))
	for k := range b.Data {
		names = append(names, k)
	}
	sort.Strings(names)
	sealed, err := s.seal.sealJSON(payload{Data: b.Data})
	if err != nil {
		apiErr(w, 500, "INTERNAL", "seal failure")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer tx.Rollback(ctx)
	reqID := newID("req")
	var idemArg any
	if idem != "" {
		idemArg = idem
	}
	tag, err := tx.Exec(ctx, `INSERT INTO requests (id, tenant_id, client_id, idempotency_key, event_code, lane, recipients)
		VALUES ($1,'platform',$2,$3,$4,$5,$6) ON CONFLICT DO NOTHING`, reqID, who, idemArg, b.EventCode, pol.Lane, len(b.To))
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	if tag.RowsAffected() == 0 {
		// Duplicate API call within the idempotency window: return the original request.
		tx.Rollback(ctx)
		var orig string
		if err := s.pool.QueryRow(ctx, `SELECT id FROM requests WHERE tenant_id='platform' AND client_id=$1 AND idempotency_key=$2
			AND created_at > now()-interval '24 hours'`, who, idem).Scan(&orig); err != nil {
			apiErr(w, 409, "CONFLICT", "Idempotency-Key reused after its 24 h window")
			return
		}
		s.writeRequest(w, ctx, orig, http.StatusOK)
		return
	}
	for i, to := range b.To {
		msgID := newID("msg")
		if _, err := tx.Exec(ctx, `INSERT INTO messages (id, request_id, tenant_id, client_id, event_code, recipient_ref, lane,
			category, sensitivity, locale, data_names, status, expires_at, seq)
			VALUES ($1,$2,'platform',$3,$4,$5,$6,$7,$8,$9,$10,'ACCEPTED',$11,$12)`,
			msgID, reqID, who, b.EventCode, to, pol.Lane, pol.Category, sens, locale, names, expires, i); err != nil {
			apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
			return
		}
		if _, err := tx.Exec(ctx, `INSERT INTO message_events (message_id, status, detail) VALUES ($1,'ACCEPTED',$2)`,
			msgID, fmt.Sprintf(`{"client":%q}`, who)); err != nil {
			apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
			return
		}
		if _, err := tx.Exec(ctx, `INSERT INTO outbox (message_id, lane, tenant_id, payload) VALUES ($1,$2,'platform',$3)`,
			msgID, pol.Lane, sealed); err != nil {
			apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	for range b.To {
		s.metrics.status(b.EventCode, "", "ACCEPTED")
	}
	s.kick()
	s.writeRequest(w, ctx, reqID, http.StatusAccepted)
}

func (s *Server) writeRequest(w http.ResponseWriter, ctx context.Context, reqID string, status int) {
	rows, err := s.pool.Query(ctx, `SELECT id, recipient_ref, status FROM messages WHERE request_id=$1 ORDER BY seq`, reqID)
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer rows.Close()
	var msgs []map[string]string
	for rows.Next() {
		var id, to, st string
		if rows.Scan(&id, &to, &st) == nil {
			msgs = append(msgs, map[string]string{"id": id, "to": to, "status": st})
		}
	}
	writeJSON(w, status, map[string]any{"requestId": reqID, "messages": msgs})
}

// ---------------------------------------------------------------------------
// Status API (a caller sees only its own messages)
// ---------------------------------------------------------------------------

func (s *Server) getRequest(w http.ResponseWriter, r *http.Request) {
	var client string
	if err := s.pool.QueryRow(r.Context(), `SELECT client_id FROM requests WHERE id=$1`, r.PathValue("id")).Scan(&client); err != nil || client != caller(r) {
		apiErr(w, 404, "NOT_FOUND", "unknown request")
		return
	}
	s.writeRequest(w, r.Context(), r.PathValue("id"), 200)
}

func (s *Server) getMessage(w http.ResponseWriter, r *http.Request) {
	var m struct {
		ID, Request, Client, Event, To, Lane, Status string
		Channel, Addr, Reason                        *string
		Expires                                      *time.Time
		Created, Updated                             time.Time
	}
	err := s.pool.QueryRow(r.Context(), `SELECT id, request_id, client_id, event_code, recipient_ref, lane, status, channel,
		address_masked, status_reason, expires_at, created_at, updated_at FROM messages WHERE id=$1`, r.PathValue("id")).
		Scan(&m.ID, &m.Request, &m.Client, &m.Event, &m.To, &m.Lane, &m.Status, &m.Channel, &m.Addr, &m.Reason, &m.Expires, &m.Created, &m.Updated)
	if err != nil || m.Client != caller(r) {
		apiErr(w, 404, "NOT_FOUND", "unknown message")
		return
	}
	writeJSON(w, 200, map[string]any{"id": m.ID, "requestId": m.Request, "eventCode": m.Event, "to": m.To, "lane": m.Lane,
		"status": m.Status, "reason": m.Reason, "channel": m.Channel, "address": m.Addr, "expiresAt": m.Expires,
		"createdAt": m.Created, "updatedAt": m.Updated})
}

func (s *Server) getMessageEvents(w http.ResponseWriter, r *http.Request) {
	var client string
	if err := s.pool.QueryRow(r.Context(), `SELECT client_id FROM messages WHERE id=$1`, r.PathValue("id")).Scan(&client); err != nil || client != caller(r) {
		apiErr(w, 404, "NOT_FOUND", "unknown message")
		return
	}
	rows, err := s.pool.Query(r.Context(), `SELECT at, status, detail FROM message_events WHERE message_id=$1 ORDER BY id`, r.PathValue("id"))
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var at time.Time
		var st string
		var detail map[string]any
		if rows.Scan(&at, &st, &detail) == nil {
			out = append(out, map[string]any{"at": at, "status": st, "detail": detail})
		}
	}
	writeJSON(w, 200, map[string]any{"messageId": r.PathValue("id"), "events": out})
}

// ---------------------------------------------------------------------------
// Contacts (PUT replaces the subject's contacts; only the core may write)
// ---------------------------------------------------------------------------

var (
	emailRe   = regexp.MustCompile(`^[^@\s]{1,64}@[^@\s]{1,255}\.[^@\s]{2,}$`)
	subjectRe = regexp.MustCompile(`^user:[A-Za-z0-9._:@-]{1,128}$`)
)

func normalizeAddress(channel, addr string) (string, bool) {
	addr = strings.TrimSpace(addr)
	switch channel {
	case "sms", "whatsapp":
		var b strings.Builder
		for i, r := range addr {
			switch {
			case r == '+' && i == 0:
				b.WriteRune(r)
			case r >= '0' && r <= '9':
				b.WriteRune(r)
			case r == ' ' || r == '-' || r == '(' || r == ')':
			default:
				return "", false
			}
		}
		out := b.String()
		return out, len(onlyDigits(out)) >= 6 && len(onlyDigits(out)) <= 15
	case "email":
		addr = strings.ToLower(addr)
		return addr, emailRe.MatchString(addr)
	}
	return "", false
}

func onlyDigits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func (s *Server) putContacts(w http.ResponseWriter, r *http.Request) {
	who := caller(r)
	if !s.isContactWriter(who) {
		apiErr(w, 403, "FORBIDDEN", "only the core writes contact details")
		return
	}
	ref := r.PathValue("ref")
	if !subjectRe.MatchString(ref) {
		apiErr(w, 422, "REJECTED", "subject must be user:<id>")
		return
	}
	var b struct {
		Contacts []struct {
			Channel  string `json:"channel"`
			Address  string `json:"address"`
			Verified bool   `json:"verified"`
			Primary  bool   `json:"primary"`
		} `json:"contacts"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&b); err != nil || len(b.Contacts) > 10 {
		apiErr(w, 400, "REJECTED", "body must be {contacts:[{channel, address, verified, primary}]} (at most 10)")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `DELETE FROM contacts WHERE tenant_id='platform' AND subject_ref=$1`, ref); err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	for _, c := range b.Contacts {
		if c.Channel != "sms" && c.Channel != "email" {
			apiErr(w, 422, "REJECTED", "contact channel must be sms or email in B0")
			return
		}
		addr, ok := normalizeAddress(c.Channel, c.Address)
		if !ok {
			apiErr(w, 422, "REJECTED", "invalid "+c.Channel+" address")
			return
		}
		enc, err := s.seal.seal([]byte(addr))
		if err != nil {
			apiErr(w, 500, "INTERNAL", "seal failure")
			return
		}
		var verified any
		if c.Verified {
			verified = time.Now()
		}
		if _, err := tx.Exec(ctx, `INSERT INTO contacts (tenant_id, subject_ref, channel, address_enc, address_hash, address_masked,
			verified_at, is_primary, updated_by) VALUES ('platform',$1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT DO NOTHING`,
			ref, c.Channel, enc, s.seal.blindIndex(c.Channel, addr), mask(c.Channel, addr), verified, c.Primary, who); err != nil {
			apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	s.getContacts(w, r)
}

func (s *Server) getContacts(w http.ResponseWriter, r *http.Request) {
	if !s.isContactWriter(caller(r)) {
		apiErr(w, 403, "FORBIDDEN", "only the core reads contact details")
		return
	}
	rows, err := s.pool.Query(r.Context(), `SELECT channel, address_masked, verified_at IS NOT NULL, is_primary FROM contacts
		WHERE tenant_id='platform' AND subject_ref=$1 ORDER BY channel, is_primary DESC`, r.PathValue("ref"))
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var ch, m string
		var v, p bool
		if rows.Scan(&ch, &m, &v, &p) == nil {
			out = append(out, map[string]any{"channel": ch, "address": m, "verified": v, "primary": p})
		}
	}
	writeJSON(w, 200, map[string]any{"subject": r.PathValue("ref"), "contacts": out})
}

// ---------------------------------------------------------------------------
// GET /comms/v1/capabilities (comms-design §8): a channel is live when a provider is
// configured and healthy (breaker closed) and every auth.* event that may use the channel
// has a published template for it. The IdP offers a factor only while its channel is live.
// ---------------------------------------------------------------------------

type channelCap struct {
	Live     bool     `json:"live"`
	Provider string   `json:"provider,omitempty"`
	Missing  []string `json:"missingTemplates,omitempty"`
}

func (s *Server) capabilities(ctx context.Context) map[string]channelCap {
	s.capsMu.Lock()
	defer s.capsMu.Unlock()
	if s.caps != nil && time.Since(s.capsAt) < 10*time.Second {
		return s.caps
	}
	published := map[string]bool{}
	rows, err := s.pool.Query(ctx, `SELECT event_code, channel FROM template_versions WHERE tenant_id IS NULL AND status='PUBLISHED'`)
	if err == nil {
		for rows.Next() {
			var ev, ch string
			if rows.Scan(&ev, &ch) == nil {
				published[ev+"|"+ch] = true
			}
		}
		rows.Close()
	}
	out := map[string]channelCap{}
	for _, ch := range []string{"sms", "email", "push", "whatsapp", "in_app"} {
		c := channelCap{}
		p, ok := s.providers[ch]
		if ok {
			c.Provider = p.Kind()
		}
		for ev, pol := range s.cfg.File.EventPolicies {
			if !strings.HasPrefix(ev, "auth.") {
				continue
			}
			for _, pc := range pol.Channels {
				if pc == ch && !published[ev+"|"+ch] {
					c.Missing = append(c.Missing, ev)
				}
			}
		}
		sort.Strings(c.Missing)
		c.Live = ok && err == nil && s.breakers[ch].Allow() && len(c.Missing) == 0
		out[ch] = c
	}
	s.caps, s.capsAt = out, time.Now()
	return out
}

func (s *Server) getCapabilities(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"environment": s.cfg.Environment, "channels": s.capabilities(r.Context())})
}

// GET /comms/v1/sandbox/outbox?recipient=user:x[&event=][&limit=]: what the sandbox
// provider "delivered" (UAT reads OTPs and links here). Not served in production.
func (s *Server) getSandboxOutbox(w http.ResponseWriter, r *http.Request) {
	if s.cfg.IsProduction() {
		apiErr(w, 404, "NOT_FOUND", "no sandbox in production")
		return
	}
	q := r.URL.Query()
	rows, err := s.pool.Query(r.Context(), `SELECT id, message_id, event_code, recipient_ref, channel, address, subject, body, created_at
		FROM sim_outbox WHERE ($1='' OR recipient_ref=$1) AND ($2='' OR event_code=$2) ORDER BY created_at DESC LIMIT 50`,
		q.Get("recipient"), q.Get("event"))
	if err != nil {
		apiErr(w, 503, "UNAVAILABLE", "storage unavailable")
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, msg, ev, to, ch, addr, subj, body string
		var at time.Time
		if rows.Scan(&id, &msg, &ev, &to, &ch, &addr, &subj, &body, &at) == nil {
			out = append(out, map[string]any{"id": id, "messageId": msg, "eventCode": ev, "to": to, "channel": ch,
				"address": addr, "subject": subj, "body": body, "createdAt": at})
		}
	}
	writeJSON(w, 200, map[string]any{"messages": out})
}
