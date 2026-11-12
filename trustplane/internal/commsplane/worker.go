package commsplane

// Workers (comms-design.md §3, §4, §4.1):
//
//   relay      outbox -> queue (stage plan)
//   planner    resolve recipient -> contact, choose the first reachable channel of the
//              event's chain, pick the provider, pick the published template; ACCEPTED ->
//              PLANNED -> QUEUED, or SUPPRESSED (no contact) / EXPIRED
//   dispatcher one pool per lane: expiry check, render in memory, SENDING -> SENT ->
//              DELIVERED (sandbox), RETRYING with back-off + full jitter, FAILED -> DLQ
//
// Secret values exist only sealed in the queue and in memory while rendering; the payload
// is wiped on every terminal state.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/rand/v2"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

type execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

// Provider sends one rendered message on one channel.
type Provider interface {
	Kind() string
	Send(ctx context.Context, m Outgoing) (providerMsgID string, delivered bool, err error)
}

type Outgoing struct {
	MessageID, EventCode, Recipient, Channel, Address, Subject, Body string
}

// PermanentError is not retried (invalid number, blocked content ...).
type PermanentError struct{ Reason string }

func (e PermanentError) Error() string { return e.Reason }

type sandboxProvider struct {
	pool    *pgxpool.Pool
	channel string
}

func (sandboxProvider) Kind() string { return "sandbox" }

func (p sandboxProvider) Send(ctx context.Context, m Outgoing) (string, bool, error) {
	id := newID("sim")
	_, err := p.pool.Exec(ctx, `INSERT INTO sim_outbox (id, message_id, event_code, recipient_ref, channel, address, subject, body)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, id, m.MessageID, m.EventCode, m.Recipient, m.Channel, m.Address, m.Subject, m.Body)
	return id, err == nil, err
}

// ---------------------------------------------------------------------------

var laneWorkers = map[string]int{"CRITICAL": 4, "TRANSACTIONAL": 2, "BULK": 1}
var laneMaxAttempts = map[string]int{"CRITICAL": 3, "TRANSACTIONAL": 6, "BULK": 10}

// kick wakes the workers after an accept so CRITICAL work does not wait for the poll.
func (s *Server) kick() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *Server) StartWorkers(ctx context.Context) {
	go s.loop(ctx, "relay", 250*time.Millisecond, func(ctx context.Context) (int, error) { return s.relayOnce(ctx) })
	for _, lane := range []string{"CRITICAL", "TRANSACTIONAL", "BULK"} {
		lane := lane
		go s.loop(ctx, "plan-"+lane, 250*time.Millisecond, func(ctx context.Context) (int, error) { return s.planOnce(ctx, lane) })
		for i := 0; i < laneWorkers[lane]; i++ {
			go s.loop(ctx, "dispatch-"+lane, 250*time.Millisecond, func(ctx context.Context) (int, error) { return s.dispatchOnce(ctx, lane) })
		}
	}
	go s.loop(ctx, "sweep", 10*time.Minute, s.sweepOnce)
}

func (s *Server) loop(ctx context.Context, name string, every time.Duration, fn func(context.Context) (int, error)) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		n, err := fn(ctx)
		if err != nil && ctx.Err() == nil {
			log.Printf("[comms] %s: %v", name, err)
		}
		if n > 0 {
			continue // drain
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		case <-s.wake:
		}
	}
}

// setStatus moves a message along the state machine and records the event.
func (s *Server) setStatus(ctx context.Context, q execer, msgID, event, channel, status, reason string, detail map[string]any) error {
	if detail == nil {
		detail = map[string]any{}
	}
	if reason != "" {
		detail["reason"] = reason
	}
	dj, _ := json.Marshal(detail)
	if _, err := q.Exec(ctx, `UPDATE messages SET status=$2, status_reason=NULLIF($3,''), updated_at=now() WHERE id=$1`, msgID, status, reason); err != nil {
		return err
	}
	if _, err := q.Exec(ctx, `INSERT INTO message_events (message_id, status, detail) VALUES ($1,$2,$3)`, msgID, status, string(dj)); err != nil {
		return err
	}
	s.metrics.status(event, channel, status)
	return nil
}

type msgRow struct {
	id, event, recipient, lane, status, locale string
	channel                                    *string
	expires                                    *time.Time
}

func (s *Server) loadMsg(ctx context.Context, id string) (*msgRow, error) {
	m := &msgRow{}
	err := s.pool.QueryRow(ctx, `SELECT id, event_code, recipient_ref, lane, status, locale, channel, expires_at FROM messages WHERE id=$1`, id).
		Scan(&m.id, &m.event, &m.recipient, &m.lane, &m.status, &m.locale, &m.channel, &m.expires)
	return m, err
}

func (m *msgRow) expired() bool { return m.expires != nil && !time.Now().Before(*m.expires) }

// terminal ends a message: status, payload wiped, job removed (or parked in the DLQ).
func (s *Server) terminal(ctx context.Context, j Job, m *msgRow, channel, status, reason string, dlq bool) error {
	if err := s.setStatus(ctx, s.pool, m.id, m.event, channel, status, reason, nil); err != nil {
		return err
	}
	if dlq {
		return s.queue.DeadLetter(ctx, j, reason)
	}
	return s.queue.Ack(ctx, j)
}

// ---------------------------------------------------------------------------
// Planner
// ---------------------------------------------------------------------------

func (s *Server) planOnce(ctx context.Context, lane string) (int, error) {
	jobs, err := s.queue.Lease(ctx, "plan", lane, 20, 30*time.Second)
	if err != nil || len(jobs) == 0 {
		return 0, err
	}
	for _, j := range jobs {
		if err := s.plan(ctx, j); err != nil {
			log.Printf("[comms] plan %s: %v", j.MessageID, err)
			_ = s.queue.Nack(ctx, j, backoff(j.Attempts))
		}
	}
	return len(jobs), nil
}

func (s *Server) plan(ctx context.Context, j Job) error {
	m, err := s.loadMsg(ctx, j.MessageID)
	if err != nil {
		return err
	}
	if m.status != "ACCEPTED" {
		return s.queue.Ack(ctx, j) // duplicate job from a relay retry: already planned
	}
	if m.expired() {
		return s.terminal(ctx, j, m, "", "EXPIRED", "expired before planning", false)
	}
	pol := s.cfg.File.EventPolicies[m.event]
	var p payload
	if err := s.seal.openJSON(j.Payload, &p); err != nil {
		return s.terminal(ctx, j, m, "", "FAILED", "payload unreadable", true)
	}
	// first channel of the chain with a contact, a healthy provider and a published template
	for _, ch := range pol.Channels {
		prov, ok := s.providers[ch]
		if !ok || !s.breakers[ch].Allow() {
			continue
		}
		addr, masked, err := s.contactFor(ctx, m.recipient, ch)
		if err != nil {
			return err
		}
		if addr == "" {
			continue
		}
		var tplID int64
		err = s.pool.QueryRow(ctx, `SELECT id FROM template_versions WHERE tenant_id IS NULL AND event_code=$1 AND channel=$2
			AND status='PUBLISHED' AND locale IN ($3,'en') ORDER BY (locale=$3) DESC, version DESC LIMIT 1`, m.event, ch, m.locale).Scan(&tplID)
		if errors.Is(err, pgx.ErrNoRows) {
			continue
		}
		if err != nil {
			return err
		}
		p.Address, p.TplID = addr, tplID
		sealed, err := s.seal.sealJSON(p)
		if err != nil {
			return err
		}
		if _, err := s.pool.Exec(ctx, `UPDATE messages SET channel=$2, address_masked=$3, template_version_id=$4, provider_id=$5 WHERE id=$1`,
			m.id, ch, masked, tplID, prov.Kind()+":"+ch); err != nil {
			return err
		}
		if err := s.setStatus(ctx, s.pool, m.id, m.event, ch, "PLANNED", "", map[string]any{"channel": ch, "provider": prov.Kind()}); err != nil {
			return err
		}
		if err := s.queue.Advance(ctx, j, "dispatch", sealed); err != nil {
			return err
		}
		s.kick()
		return s.setStatus(ctx, s.pool, m.id, m.event, ch, "QUEUED", "", nil)
	}
	return s.terminal(ctx, j, m, "", "SUPPRESSED", "no reachable contact for "+fmt.Sprint(pol.Channels), false)
}

func (s *Server) contactFor(ctx context.Context, ref, ch string) (string, string, error) {
	var enc []byte
	var masked string
	err := s.pool.QueryRow(ctx, `SELECT address_enc, address_masked FROM contacts WHERE tenant_id='platform' AND subject_ref=$1 AND channel=$2
		ORDER BY is_primary DESC, verified_at DESC NULLS LAST, updated_at DESC LIMIT 1`, ref, ch).Scan(&enc, &masked)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", nil
	}
	if err != nil {
		return "", "", err
	}
	plain, err := s.seal.open(enc)
	if err != nil {
		return "", "", err
	}
	return string(plain), masked, nil
}

// ---------------------------------------------------------------------------
// Dispatcher
// ---------------------------------------------------------------------------

// backoff: exponential with full jitter, 1 s base, capped at 10 min (§4).
func backoff(attempt int) time.Duration {
	ceil := time.Second
	for i := 1; i < attempt && ceil < 10*time.Minute; i++ {
		ceil *= 2
	}
	if ceil > 10*time.Minute {
		ceil = 10 * time.Minute
	}
	return time.Duration(rand.Int64N(int64(ceil))) + 100*time.Millisecond
}

func (s *Server) dispatchOnce(ctx context.Context, lane string) (int, error) {
	jobs, err := s.queue.Lease(ctx, "dispatch", lane, 5, 30*time.Second)
	if err != nil || len(jobs) == 0 {
		return 0, err
	}
	for _, j := range jobs {
		if err := s.dispatch(ctx, j); err != nil {
			log.Printf("[comms] dispatch %s: %v", j.MessageID, err)
			_ = s.queue.Nack(ctx, j, backoff(j.Attempts))
		}
	}
	return len(jobs), nil
}

func (s *Server) dispatch(ctx context.Context, j Job) error {
	m, err := s.loadMsg(ctx, j.MessageID)
	if err != nil {
		return err
	}
	ch := ""
	if m.channel != nil {
		ch = *m.channel
	}
	switch m.status {
	case "QUEUED", "RETRYING", "SENDING":
	default:
		return s.queue.Ack(ctx, j) // already terminal
	}
	if m.expired() {
		return s.terminal(ctx, j, m, ch, "EXPIRED", "expired before sending: never sent late", false)
	}
	var p payload
	if err := s.seal.openJSON(j.Payload, &p); err != nil {
		return s.terminal(ctx, j, m, ch, "FAILED", "payload unreadable", true)
	}
	var subject, body string
	if err := s.pool.QueryRow(ctx, `SELECT subject, body FROM template_versions WHERE id=$1`, p.TplID).Scan(&subject, &body); err != nil {
		return s.terminal(ctx, j, m, ch, "FAILED", "template missing", true)
	}
	prov, ok := s.providers[ch]
	if !ok || !s.breakers[ch].Allow() {
		return s.retryOrFail(ctx, j, m, ch, "provider unavailable (breaker open)")
	}
	if err := s.setStatus(ctx, s.pool, m.id, m.event, ch, "SENDING", "", map[string]any{"attempt": j.Attempts}); err != nil {
		return err
	}
	if _, err := s.pool.Exec(ctx, `INSERT INTO attempts (message_id, n, provider_id) VALUES ($1,$2,$3) ON CONFLICT (message_id, n) DO NOTHING`,
		m.id, j.Attempts, prov.Kind()+":"+ch); err != nil {
		return err
	}
	out := Outgoing{MessageID: m.id, EventCode: m.event, Recipient: m.recipient, Channel: ch, Address: p.Address,
		Subject: renderTemplate(subject, p.Data), Body: renderTemplate(body, p.Data)}
	pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	pid, delivered, sendErr := prov.Send(pctx, out)
	cancel()
	s.breakers[ch].Record(sendErr == nil)
	if sendErr != nil {
		class := "TRANSIENT"
		var perm PermanentError
		if errors.As(sendErr, &perm) {
			class = "PERMANENT"
		}
		_, _ = s.pool.Exec(ctx, `UPDATE attempts SET finished_at=now(), result='ERROR', error_class=$3 WHERE message_id=$1 AND n=$2`, m.id, j.Attempts, class)
		if class == "PERMANENT" {
			return s.terminal(ctx, j, m, ch, "FAILED", sendErr.Error(), false)
		}
		return s.retryOrFail(ctx, j, m, ch, sendErr.Error())
	}
	_, _ = s.pool.Exec(ctx, `UPDATE attempts SET finished_at=now(), result='SENT', provider_msg_id=$3 WHERE message_id=$1 AND n=$2`, m.id, j.Attempts, pid)
	_, _ = s.pool.Exec(ctx, `UPDATE messages SET provider_msg_id=$2 WHERE id=$1`, m.id, pid)
	if err := s.setStatus(ctx, s.pool, m.id, m.event, ch, "SENT", "", map[string]any{"providerMsgId": pid}); err != nil {
		return err
	}
	if delivered {
		if err := s.setStatus(ctx, s.pool, m.id, m.event, ch, "DELIVERED", "", map[string]any{"source": prov.Kind()}); err != nil {
			return err
		}
	}
	return s.queue.Ack(ctx, j) // payload (and with it every secret) is gone with the job
}

func (s *Server) retryOrFail(ctx context.Context, j Job, m *msgRow, ch, reason string) error {
	if j.Attempts >= laneMaxAttempts[m.lane] {
		return s.terminal(ctx, j, m, ch, "FAILED", "max attempts: "+reason, true)
	}
	if err := s.setStatus(ctx, s.pool, m.id, m.event, ch, "RETRYING", reason, map[string]any{"attempt": j.Attempts}); err != nil {
		return err
	}
	return s.queue.Nack(ctx, j, backoff(j.Attempts))
}

// sweepOnce removes old sandbox output and published outbox rows.
func (s *Server) sweepOnce(ctx context.Context) (int, error) {
	for _, q := range []string{
		`DELETE FROM outbox WHERE published_at < now() - interval '7 days'`,
		`DELETE FROM sim_outbox WHERE created_at < now() - interval '30 days'`,
	} {
		if _, err := s.pool.Exec(ctx, q); err != nil {
			return 0, err
		}
	}
	return 0, nil
}
