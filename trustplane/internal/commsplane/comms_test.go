package commsplane

// B0 exit tests (comms-design.md §12): accept -> outbox -> queue -> plan -> dispatch through
// the sandbox, idempotency, expiry, suppression, caller rules, secrets never stored, retries,
// capabilities. Needs a scratch Postgres: TEST_COMMS_DSN=postgres://.../comms

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"trustplane/internal/dbx"
)

func testCfg() Config {
	c := Config{Environment: "dev", File: FileConfig{
		Callers:        map[string][]string{"idp": {"auth."}, "mipay": {"account.", "auth.security."}},
		ContactWriters: []string{"mipay"},
		Providers:      map[string]ProviderCfg{"sms": {Kind: "sandbox"}, "email": {Kind: "sandbox"}},
		EventPolicies: map[string]PolicyCfg{
			"auth.otp.sms":        {Category: "SECURITY", Lane: "CRITICAL", Channels: []string{"sms"}, TTL: 5 * time.Minute, RequireExpiry: true, Secret: []string{"code"}},
			"auth.password.reset": {Category: "SECURITY", Lane: "CRITICAL", Channels: []string{"sms", "email"}, TTL: time.Hour, Secret: []string{"link"}},
			"account.setup":       {Category: "SECURITY", Lane: "CRITICAL", Channels: []string{"email", "sms"}, TTL: 72 * time.Hour, Secret: []string{"link"}},
		},
		Templates: []TemplateCfg{
			{Event: "auth.otp.sms", Channel: "sms", Body: "Code {{code}}"},
			{Event: "auth.password.reset", Channel: "sms", Body: "Reset {{link}}"},
			{Event: "auth.password.reset", Channel: "email", Subject: "Reset", Body: "Reset {{link}}"},
			{Event: "account.setup", Channel: "email", Subject: "Welcome", Body: "Hi {{name}} {{link}}"},
			{Event: "account.setup", Channel: "sms", Body: "Hi {{name}} {{link}}"},
		},
	}}
	_ = c.validate()
	return c
}

type ch struct {
	t *testing.T
	s *Server
	h http.Handler
}

func newComms(t *testing.T, mut func(*Config)) *ch {
	dsn := os.Getenv("TEST_COMMS_DSN")
	if dsn == "" {
		t.Skip("TEST_COMMS_DSN not set (needs a scratch Postgres)")
	}
	ctx := context.Background()
	pool, err := dbx.Pool(ctx, dsn, 8)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	for _, tbl := range []string{"sim_outbox", "contacts", "template_versions", "jobs", "outbox", "attempts", "message_events", "messages", "requests", "tenants"} {
		_, _ = pool.Exec(ctx, "DROP TABLE IF EXISTS "+tbl+" CASCADE")
	}
	cfg := testCfg()
	if mut != nil {
		mut(&cfg)
	}
	seal, _ := newSealer([]byte(strings.Repeat("k", 32)))
	s := NewServer(cfg, pool, seal)
	if err := s.Setup(ctx); err != nil {
		t.Fatal(err)
	}
	return &ch{t: t, s: s, h: s.Handler()}
}

func (c *ch) call(method, path, body, cn string, hdr ...string) (int, map[string]any) {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	for i := 0; i+1 < len(hdr); i += 2 {
		r.Header.Set(hdr[i], hdr[i+1])
	}
	if cn != "" {
		r.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{{Subject: pkix.Name{CommonName: cn}}}}
	}
	w := httptest.NewRecorder()
	c.h.ServeHTTP(w, r)
	out := map[string]any{}
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

// drain runs relay, planner and dispatcher until nothing is left.
func (c *ch) drain() {
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		n, _ := c.s.relayOnce(ctx)
		for _, lane := range []string{"CRITICAL", "TRANSACTIONAL", "BULK"} {
			p, _ := c.s.planOnce(ctx, lane)
			d, _ := c.s.dispatchOnce(ctx, lane)
			n += p + d
		}
		if n == 0 {
			return
		}
	}
}

func (c *ch) contacts(ref, body string) {
	if code, out := c.call("PUT", "/comms/v1/subjects/"+ref+"/contacts", body, "sme-mipay-mesh"); code != 200 {
		c.t.Fatalf("contacts: %d %v", code, out)
	}
}

func (c *ch) status(id string) string {
	var st string
	_ = c.s.pool.QueryRow(context.Background(), `SELECT status FROM messages WHERE id=$1`, id).Scan(&st)
	return st
}

func firstMsg(out map[string]any) string {
	return out["messages"].([]any)[0].(map[string]any)["id"].(string)
}

func exp(d time.Duration) string { return time.Now().Add(d).UTC().Format(time.RFC3339) }

func TestSendThroughSandboxEndToEnd(t *testing.T) {
	c := newComms(t, nil)
	c.contacts("user:u1", `{"contacts":[{"channel":"sms","address":"+264 81 123 4567","verified":true,"primary":true}]}`)
	code, out := c.call("POST", "/comms/v1/messages",
		`{"eventCode":"auth.otp.sms","to":["user:u1"],"lane":"CRITICAL","expiresAt":"`+exp(5*time.Minute)+`","data":{"code":"123456"}}`,
		"sme-idp", "Idempotency-Key", "otp-1")
	if code != 202 {
		t.Fatalf("accept: %d %v", code, out)
	}
	id := firstMsg(out)
	c.drain()
	if st := c.status(id); st != "DELIVERED" {
		t.Fatalf("status %s", st)
	}
	var body, addr string
	_ = c.s.pool.QueryRow(context.Background(), `SELECT body, address FROM sim_outbox WHERE message_id=$1`, id).Scan(&body, &addr)
	if body != "Code 123456" || addr != "+264811234567" {
		t.Fatalf("sandbox output %q %q", body, addr)
	}
	// the secret value is nowhere in messages, events, jobs or outbox
	var leaks int
	_ = c.s.pool.QueryRow(context.Background(), `SELECT
		(SELECT count(*) FROM messages WHERE row_to_json(messages)::text LIKE '%123456%') +
		(SELECT count(*) FROM message_events WHERE detail::text LIKE '%123456%') +
		(SELECT count(*) FROM jobs) + (SELECT count(*) FROM outbox WHERE length(payload) > 0)`).Scan(&leaks)
	if leaks != 0 {
		t.Fatalf("secret or payload left behind (%d)", leaks)
	}
	// timeline
	code, ev := c.call("GET", "/comms/v1/messages/"+id+"/events", "", "sme-idp")
	var seq []string
	for _, e := range ev["events"].([]any) {
		seq = append(seq, e.(map[string]any)["status"].(string))
	}
	if code != 200 || strings.Join(seq, ">") != "ACCEPTED>PLANNED>QUEUED>SENDING>SENT>DELIVERED" {
		t.Fatalf("timeline %d %v", code, seq)
	}
	// another caller cannot read it
	if code, _ := c.call("GET", "/comms/v1/messages/"+id, "", "sme-mipay-mesh"); code != 404 {
		t.Fatalf("cross-caller read: %d", code)
	}
	// idempotent retry returns the original request
	code, again := c.call("POST", "/comms/v1/messages",
		`{"eventCode":"auth.otp.sms","to":["user:u1"],"expiresAt":"`+exp(5*time.Minute)+`","data":{"code":"999999"}}`,
		"sme-idp", "Idempotency-Key", "otp-1")
	if code != 200 || again["requestId"] != out["requestId"] {
		t.Fatalf("idempotency: %d %v", code, again)
	}
	var n int
	_ = c.s.pool.QueryRow(context.Background(), `SELECT count(*) FROM sim_outbox`).Scan(&n)
	if n != 1 {
		t.Fatalf("duplicate send: %d", n)
	}
}

func TestChannelChainAndSuppression(t *testing.T) {
	c := newComms(t, nil)
	c.contacts("user:u2", `{"contacts":[{"channel":"email","address":"Kofi@Example.com","verified":true}]}`)
	_, out := c.call("POST", "/comms/v1/messages", `{"eventCode":"auth.password.reset","to":["user:u2","user:nobody"],"expiresAt":"`+exp(time.Hour)+`","data":{"link":"https://idp/x"}}`, "sme-idp")
	msgs := out["messages"].([]any)
	c.drain()
	var chn, addr string
	_ = c.s.pool.QueryRow(context.Background(), `SELECT channel, address FROM sim_outbox WHERE recipient_ref='user:u2'`).Scan(&chn, &addr)
	if chn != "email" || addr != "kofi@example.com" {
		t.Fatalf("fallback along the chain to email: %q %q", chn, addr)
	}
	if st := c.status(msgs[1].(map[string]any)["id"].(string)); st != "SUPPRESSED" {
		t.Fatalf("no contact -> SUPPRESSED, got %s", st)
	}
}

func TestValidationAndCallerRules(t *testing.T) {
	c := newComms(t, nil)
	cases := []struct {
		body, cn string
		want     int
	}{
		{`{"eventCode":"auth.otp.sms","to":["user:u"],"data":{"code":"1"}}`, "sme-idp", 422},                                          // OTP needs expiresAt
		{`{"eventCode":"auth.otp.sms","to":["user:u"],"expiresAt":"` + exp(time.Minute) + `","data":{}}`, "sme-idp", 422},             // missing variable
		{`{"eventCode":"auth.otp.sms","to":["+2648100"],"expiresAt":"` + exp(time.Minute) + `","data":{"code":"1"}}`, "sme-idp", 422}, // raw address
		{`{"eventCode":"auth.otp.sms","to":["user:u"],"expiresAt":"` + exp(time.Minute) + `","data":{"code":"1"}}`, "sme-mipay-mesh", 403},
		{`{"eventCode":"account.setup","to":["user:u"],"expiresAt":"` + exp(time.Minute) + `","data":{"name":"a","link":"b"}}`, "sme-idp", 403},
		{`{"eventCode":"nope","to":["user:u"],"data":{}}`, "sme-idp", 422},
		{`{"eventCode":"auth.otp.sms","to":["user:u"],"lane":"BULK","expiresAt":"` + exp(time.Minute) + `","data":{"code":"1"}}`, "sme-idp", 422},
		{`{"eventCode":"auth.otp.sms","to":["user:u"],"expiresAt":"2000-01-01T00:00:00Z","data":{"code":"1"}}`, "sme-idp", 422},
	}
	for i, tc := range cases {
		if code, out := c.call("POST", "/comms/v1/messages", tc.body, tc.cn); code != tc.want {
			t.Errorf("case %d: want %d got %d %v", i, tc.want, code, out)
		}
	}
	if code, _ := c.call("PUT", "/comms/v1/subjects/user:u/contacts", `{"contacts":[]}`, "sme-idp"); code != 403 {
		t.Fatalf("only the core writes contacts: %d", code)
	}
	if code, _ := c.call("PUT", "/comms/v1/subjects/user:u/contacts", `{"contacts":[{"channel":"sms","address":"call me"}]}`, "sme-mipay-mesh"); code != 422 {
		t.Fatalf("bad address accepted: %d", code)
	}
}

func TestExpiredMessagesAreNeverSentLate(t *testing.T) {
	c := newComms(t, nil)
	c.contacts("user:u3", `{"contacts":[{"channel":"sms","address":"+264811111111"}]}`)
	_, out := c.call("POST", "/comms/v1/messages", `{"eventCode":"auth.otp.sms","to":["user:u3"],"expiresAt":"`+exp(2*time.Second)+`","data":{"code":"1"}}`, "sme-idp")
	time.Sleep(2100 * time.Millisecond)
	c.drain()
	if st := c.status(firstMsg(out)); st != "EXPIRED" {
		t.Fatalf("want EXPIRED, got %s", st)
	}
}

type flaky struct {
	fails int
	perm  bool
}

func (f *flaky) Kind() string { return "flaky" }
func (f *flaky) Send(context.Context, Outgoing) (string, bool, error) {
	if f.perm {
		return "", false, PermanentError{"invalid number"}
	}
	if f.fails > 0 {
		f.fails--
		return "", false, errors.New("timeout")
	}
	return "p-1", false, nil
}

func TestRetriesAndFailures(t *testing.T) {
	c := newComms(t, nil)
	c.contacts("user:u4", `{"contacts":[{"channel":"sms","address":"+264812222222"}]}`)
	prov := &flaky{fails: 1}
	c.s.providers["sms"] = prov
	_, out := c.call("POST", "/comms/v1/messages", `{"eventCode":"auth.otp.sms","to":["user:u4"],"expiresAt":"`+exp(time.Minute)+`","data":{"code":"1"}}`, "sme-idp")
	id := firstMsg(out)
	c.drain()
	if st := c.status(id); st != "RETRYING" {
		t.Fatalf("after a transient error: %s", st)
	}
	_, _ = c.s.pool.Exec(context.Background(), `UPDATE jobs SET available_at=now()`)
	c.drain()
	if st := c.status(id); st != "SENT" {
		t.Fatalf("after the retry: %s", st)
	}
	prov.perm = true
	_, out = c.call("POST", "/comms/v1/messages", `{"eventCode":"auth.otp.sms","to":["user:u4"],"expiresAt":"`+exp(time.Minute)+`","data":{"code":"1"}}`, "sme-idp")
	c.drain()
	if st := c.status(firstMsg(out)); st != "FAILED" {
		t.Fatalf("permanent error: %s", st)
	}
	// transient errors past the lane's attempts -> FAILED + DLQ with the payload wiped
	prov.perm, prov.fails = false, 100
	_, out = c.call("POST", "/comms/v1/messages", `{"eventCode":"auth.otp.sms","to":["user:u4"],"expiresAt":"`+exp(time.Minute)+`","data":{"code":"1"}}`, "sme-idp")
	for i := 0; i < 5; i++ {
		c.drain()
		_, _ = c.s.pool.Exec(context.Background(), `UPDATE jobs SET available_at=now() WHERE NOT dead`)
	}
	var dead int
	var plen int
	_ = c.s.pool.QueryRow(context.Background(), `SELECT count(*), COALESCE(max(length(payload)),0) FROM jobs WHERE dead`).Scan(&dead, &plen)
	if st := c.status(firstMsg(out)); st != "FAILED" || dead != 1 || plen != 0 {
		t.Fatalf("DLQ: status=%s dead=%d payload=%d", st, dead, plen)
	}
}

func TestCapabilities(t *testing.T) {
	c := newComms(t, nil)
	_, out := c.call("GET", "/comms/v1/capabilities", "", "sme-idp")
	chs := out["channels"].(map[string]any)
	if !chs["sms"].(map[string]any)["live"].(bool) || !chs["email"].(map[string]any)["live"].(bool) {
		t.Fatalf("sandbox sms/email should be live: %v", chs)
	}
	if chs["push"].(map[string]any)["live"].(bool) {
		t.Fatalf("push has no provider")
	}
	// a channel without the auth templates is not live
	c2 := newComms(t, func(cfg *Config) {
		var keep []TemplateCfg
		for _, tpl := range cfg.File.Templates {
			if !(tpl.Event == "auth.otp.sms") {
				keep = append(keep, tpl)
			}
		}
		cfg.File.Templates = keep
	})
	_, out = c2.call("GET", "/comms/v1/capabilities", "", "sme-idp")
	sms := out["channels"].(map[string]any)["sms"].(map[string]any)
	if sms["live"].(bool) || !strings.Contains(strings.Join(toStrings(sms["missingTemplates"]), ","), "auth.otp.sms") {
		t.Fatalf("sms without the OTP template must not be live: %v", sms)
	}
}

func toStrings(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

func TestProductionRefusesSandbox(t *testing.T) {
	cfg := testCfg()
	cfg.Environment = "production"
	if err := cfg.validate(); err == nil {
		t.Fatalf("sandbox provider accepted in production")
	}
}

func TestTemplateImportIsVersioned(t *testing.T) {
	c := newComms(t, nil)
	c.s.cfg.File.Templates[0].Body = "New code {{code}}"
	if err := c.s.Setup(context.Background()); err != nil {
		t.Fatal(err)
	}
	var v int
	var archived int
	_ = c.s.pool.QueryRow(context.Background(), `SELECT max(version) FILTER (WHERE status='PUBLISHED'), count(*) FILTER (WHERE status='ARCHIVED')
		FROM template_versions WHERE event_code='auth.otp.sms'`).Scan(&v, &archived)
	if v != 2 || archived != 1 {
		t.Fatalf("a changed default must become v2 and archive v1: v=%d archived=%d", v, archived)
	}
}
