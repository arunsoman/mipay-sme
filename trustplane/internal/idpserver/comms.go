package idpserver

// CommsClient (idp-rbac-cutover-plan.md P1.4a, 2fa-design.md D10): the IdP sends the
// messages that happen inside a login (phone OTP, self-service reset links, security
// notices) through the comms plane, addressed only by the core user id (user:<uid>). The
// IdP never holds an email address or a delivery phone of its own: the core writes contacts.
//
// Implementations:
//   - httpComms: the comms plane (POST {IDP_COMMS_URL}/messages, GET /capabilities, mTLS);
//   - sandboxComms: dev/sandbox only, writes to sim_outbox (the only place a raw code is visible);
//   - noComms: uat/production before the comms plane is configured; nothing is live, so the
//     features that need a channel (OTP login, Forgot password) are hidden.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"trustplane/internal/jwtx"
)

// Lanes and sensitivity (comms-design.md §3.3, §9).
const (
	LaneCritical  = "CRITICAL"
	SensSecret    = "SECRET"
	EvOTP         = "auth.otp.sms"
	EvPwdReset    = "auth.password.reset"
	EvSecNotice   = "auth.security.notice"
	channelSMS    = "sms"
	channelEmail  = "email"
	capsCacheTime = 60 * time.Second
)

type CommsMessage struct {
	EventCode      string
	To             string // "user:<uid>"
	Lane           string
	ExpiresAt      time.Time
	Sensitivity    string
	Data           map[string]string
	IdempotencyKey string
}

type CommsClient interface {
	Send(ctx context.Context, m CommsMessage) (string, error)
	// Live reports whether a channel ("sms", "email", "push", ...) can deliver right now.
	Live(ctx context.Context, channel string) bool
	Kind() string
}

func newCommsClient(cfg Config, pool *pgxpool.Pool, hc *http.Client) CommsClient {
	switch {
	case cfg.CommsURL != "":
		return &httpComms{base: strings.TrimSuffix(cfg.CommsURL, "/"), hc: hc}
	case cfg.IsDev():
		live := map[string]bool{}
		for _, c := range cfg.SandboxChannels {
			live[c] = true
		}
		return &sandboxComms{pool: pool, live: live}
	default:
		return noComms{}
	}
}

// ---------------------------------------------------------------------------

type noComms struct{}

func (noComms) Send(context.Context, CommsMessage) (string, error) {
	return "", errors.New("comms plane not configured (IDP_COMMS_URL)")
}
func (noComms) Live(context.Context, string) bool { return false }
func (noComms) Kind() string                      { return "none" }

// ---------------------------------------------------------------------------

type sandboxComms struct {
	pool *pgxpool.Pool
	live map[string]bool
}

func (c *sandboxComms) Kind() string { return "sandbox" }

func (c *sandboxComms) Live(_ context.Context, ch string) bool { return c.live[ch] }

func (c *sandboxComms) Send(ctx context.Context, m CommsMessage) (string, error) {
	if !time.Now().Before(m.ExpiresAt) && !m.ExpiresAt.IsZero() {
		return "", errors.New("message already expired")
	}
	id := "sim_" + jwtx.RandB64(12)
	data, _ := json.Marshal(m.Data)
	_, err := c.pool.Exec(ctx, `INSERT INTO sim_outbox (id, event_code, recipient, lane, body, data, expires_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7)`, id, m.EventCode, m.To, m.Lane, renderSandbox(m), string(data), nullTime(m.ExpiresAt))
	return id, err
}

func nullTime(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t
}

// renderSandbox gives UAT testers a readable message (the real templates live in comms).
func renderSandbox(m CommsMessage) string {
	switch m.EventCode {
	case EvOTP:
		return fmt.Sprintf("Your MiPay sign-in code is %s. It expires in 5 minutes. Never share it.", m.Data["code"])
	case EvPwdReset:
		return "Reset your MiPay password: " + m.Data["link"]
	default:
		keys := make([]string, 0, len(m.Data))
		for k := range m.Data {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		var parts []string
		for _, k := range keys {
			parts = append(parts, k+"="+m.Data[k])
		}
		return m.EventCode + ": " + strings.Join(parts, " ")
	}
}

// ---------------------------------------------------------------------------

type httpComms struct {
	base string
	hc   *http.Client

	mu      sync.Mutex
	caps    map[string]bool
	capsAt  time.Time
	capsErr bool
}

func (c *httpComms) Kind() string { return "comms" }

func (c *httpComms) Send(ctx context.Context, m CommsMessage) (string, error) {
	body := map[string]any{
		"eventCode": m.EventCode, "to": []string{m.To}, "lane": m.Lane,
		"sensitivity": m.Sensitivity, "data": m.Data,
	}
	if !m.ExpiresAt.IsZero() {
		body["expiresAt"] = m.ExpiresAt.UTC().Format(time.RFC3339)
	}
	b, _ := json.Marshal(body)
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodPost, c.base+"/messages", bytes.NewReader(b))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	key := m.IdempotencyKey
	if key == "" {
		key = jwtx.RandB64(16)
	}
	req.Header.Set("Idempotency-Key", key)
	resp, err := c.hc.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusAccepted && resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("comms: status %d: %s", resp.StatusCode, strings.TrimSpace(string(rb)))
	}
	var out struct {
		RequestID string `json:"requestId"`
	}
	_ = json.Unmarshal(rb, &out)
	return out.RequestID, nil
}

// Live reads GET /capabilities, cached for 60 s; an unreachable comms plane means nothing is live.
func (c *httpComms) Live(ctx context.Context, ch string) bool {
	c.mu.Lock()
	fresh := time.Since(c.capsAt) < capsCacheTime
	caps := c.caps
	c.mu.Unlock()
	if !fresh {
		caps = c.fetchCaps(ctx)
		c.mu.Lock()
		c.caps, c.capsAt = caps, time.Now()
		c.mu.Unlock()
	}
	return caps[ch]
}

func (c *httpComms) fetchCaps(ctx context.Context) map[string]bool {
	out := map[string]bool{}
	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, c.base+"/capabilities", nil)
	if err != nil {
		return out
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return out
	}
	defer resp.Body.Close()
	var body struct {
		Channels map[string]struct {
			Live bool `json:"live"`
		} `json:"channels"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(io.LimitReader(resp.Body, 1<<16)).Decode(&body) != nil {
		return out
	}
	for ch, v := range body.Channels {
		out[ch] = v.Live
	}
	return out
}
