package idpserver

// Self-service account pages (idp-rbac-cutover-plan.md P1.4, P1.9; 2fa-design.md §9):
//
//   GET/POST /account/setup?token=   set the first password (SETUP) or a new one (RESET)
//   GET/POST /account/forgot         self-service reset link through comms (only while a channel is live)
//   GET/POST /account/help           "Can't get in?": forwards an assisted-recovery request to the core
//
// Responses never reveal whether an account exists.

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

func (s *Server) clientIDOfPAR(r *http.Request, uri string) string {
	if uri == "" {
		return ""
	}
	if p, ok := s.loadPAR(r, uri); ok {
		return p.clientID
	}
	return ""
}

// forgotOffered: the reset link needs a channel that can reach the user.
func (s *Server) forgotOffered(ctx context.Context) bool {
	return s.comms.Live(ctx, channelSMS) || s.comms.Live(ctx, channelEmail)
}

// ---------------------------------------------------------------------------
// /account/setup
// ---------------------------------------------------------------------------

type linkRow struct {
	subject, purpose string
	expiresAt        time.Time
	usedAt           *time.Time
}

func (s *Server) loadLink(ctx context.Context, q pgx.Tx, token string, forUpdate bool) (*linkRow, error) {
	sql := `SELECT subject, purpose, expires_at, used_at FROM account_links WHERE token_hash=$1`
	if forUpdate {
		sql += ` FOR UPDATE`
	}
	l := &linkRow{}
	err := q.QueryRow(ctx, sql, linkHash(token)).Scan(&l.subject, &l.purpose, &l.expiresAt, &l.usedAt)
	return l, err
}

func (l *linkRow) usable() bool { return l.usedAt == nil && time.Now().Before(l.expiresAt) }

func (s *Server) accountSetup(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		token := r.URL.Query().Get("token")
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			render(w, 500, "message", pageData{Title: "Something went wrong", Error: "Please try again."})
			return
		}
		defer tx.Rollback(ctx)
		l, err := s.loadLink(ctx, tx, token, false)
		if err != nil || !l.usable() {
			render(w, 400, "message", pageData{Title: "This link has expired",
				Sub: "Set-up and reset links work once and expire. Ask your administrator for a new one, or use \"Forgot password?\" on the sign-in page."})
			return
		}
		title := "Set your password"
		if l.purpose != PurposeSetup {
			title = "Choose a new password"
		}
		render(w, 200, "setpw", pageData{Title: title, Sub: "This is the password you will use to sign in to MiPay.", Token: token})
	case http.MethodPost:
		s.accountSetupPost(w, r)
	default:
		errorPage(w, 405, "Method not allowed", "GET or POST")
	}
}

func (s *Server) accountSetupPost(w http.ResponseWriter, r *http.Request) {
	if !s.loginIP.Allow("setup:"+remoteIP(r), 20, 20) {
		render(w, 429, "message", pageData{Title: "Too many attempts", Sub: "Wait a minute and try again."})
		return
	}
	if err := r.ParseForm(); err != nil {
		render(w, 400, "message", pageData{Title: "Bad request"})
		return
	}
	token := r.PostFormValue("token")
	pw, confirm := r.PostFormValue("password"), r.PostFormValue("confirm")
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		render(w, 500, "message", pageData{Title: "Something went wrong", Error: "Please try again."})
		return
	}
	defer tx.Rollback(ctx)
	l, err := s.loadLink(ctx, tx, token, true)
	if err != nil || !l.usable() {
		render(w, 400, "message", pageData{Title: "This link has expired",
			Sub: "Set-up and reset links work once and expire. Ask for a new one."})
		return
	}
	a, err := scanAccount(tx.QueryRow(ctx, `SELECT `+accountCols+` FROM accounts WHERE subject=$1 FOR UPDATE`, l.subject))
	if err != nil || a.State == StateDisabled {
		render(w, 400, "message", pageData{Title: "This link can't be used", Sub: "The account is not available. Contact your administrator."})
		return
	}
	title := "Set your password"
	if l.purpose != PurposeSetup {
		title = "Choose a new password"
	}
	if pw != confirm {
		render(w, 400, "setpw", pageData{Title: title, Token: token, Error: "The two passwords do not match."})
		return
	}
	if err := validatePassword(a, pw); err != nil {
		render(w, 400, "setpw", pageData{Title: title, Token: token, Error: err.Error()})
		return
	}
	hash, err := hashPassword(pw)
	if err != nil {
		render(w, 500, "message", pageData{Title: "Something went wrong"})
		return
	}
	if _, err := tx.Exec(ctx, `UPDATE account_links SET used_at=now() WHERE token_hash=$1`, linkHash(token)); err != nil {
		render(w, 500, "message", pageData{Title: "Something went wrong"})
		return
	}
	if _, err := tx.Exec(ctx, `UPDATE accounts SET password_hash=$2, state='ACTIVE', failed_attempts=0, lock_count=0,
		locked_until=NULL, password_changed_at=now(), updated_at=now() WHERE subject=$1`, a.Subject, hash); err != nil {
		render(w, 500, "message", pageData{Title: "Something went wrong"})
		return
	}
	evType, detail := EventCredentialSet, map[string]any{"method": "password"}
	if l.purpose != PurposeSetup || a.PasswordChanged != nil {
		// A reset ends every other session and starts the cooling-off of 2fa-design.md §9.5
		// (enforced by the core): 72 h after an assisted recovery; 24 h after a self-service
		// reset proved only by a link (always the case while 2FA is off).
		if err := s.revokeAll(ctx, tx, a, "credential_reset"); err != nil {
			render(w, 500, "message", pageData{Title: "Something went wrong"})
			return
		}
		evType = EventCredentialReset
		cool, via := s.cfg.ResetCoolOffSMS, "self_service"
		if l.purpose == PurposeRecovery {
			cool, via = 72*time.Hour, "assisted_recovery"
		}
		detail["via"] = via
		detail["cooling_off_until"] = time.Now().Add(cool).UTC().Format(time.RFC3339)
	}
	if err := s.emitEvent(ctx, tx, a, evType, detail); err != nil || tx.Commit(ctx) != nil {
		render(w, 500, "message", pageData{Title: "Something went wrong"})
		return
	}
	render(w, 200, "message", pageData{Title: "Password saved", Notice: "You can now sign in to MiPay with your new password."})
}

// ---------------------------------------------------------------------------
// /account/forgot
// ---------------------------------------------------------------------------

func (s *Server) accountForgot(w http.ResponseWriter, r *http.Request) {
	uri := r.FormValue("request_uri")
	d := pageData{Title: "Reset your password", Sub: "We will send a reset link to the phone or email your organisation registered.",
		RequestURI: uri, ClientID: s.clientIDOfPAR(r, uri)}
	if !s.forgotOffered(r.Context()) {
		d.Sub = ""
		d.Notice = ""
		d.Error = "Password reset by message is not available yet. Use \"Can't get in?\" to ask your administrator."
		render(w, 503, "forgot", d)
		return
	}
	if r.Method == http.MethodGet {
		render(w, 200, "forgot", d)
		return
	}
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "GET or POST")
		return
	}
	d.Notice = "If an account matches, a reset link is on its way. It works once and expires in one hour."
	if !s.loginIP.Allow("forgot:"+remoteIP(r), 10, 10) {
		render(w, 429, "forgot", pageData{Title: "Too many requests", Error: "Wait a minute and try again.", RequestURI: uri, ClientID: d.ClientID})
		return
	}
	login := r.PostFormValue("login")
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	a, err := s.accountByLogin(ctx, login)
	if err != nil || a.State != StateActive {
		render(w, 200, "forgot", d) // same answer whether or not the account exists
		return
	}
	// at most 3 reset links per account per hour (abuse and SMS pumping)
	var recent int
	_ = s.pool.QueryRow(ctx, `SELECT count(*) FROM account_links WHERE subject=$1 AND purpose='RESET' AND created_at > now()-interval '1 hour'`,
		a.Subject).Scan(&recent)
	if recent >= 3 {
		render(w, 200, "forgot", d)
		return
	}
	link, exp, err := s.issueLink(ctx, s.pool, a.Subject, PurposeReset, s.cfg.ResetLinkTTL)
	if err == nil {
		_, err = s.comms.Send(ctx, CommsMessage{EventCode: EvPwdReset, To: "user:" + a.UID, Lane: LaneCritical,
			ExpiresAt: exp, Sensitivity: SensSecret, Data: map[string]string{"link": link}})
	}
	if err != nil {
		d.Notice = ""
		d.Error = "We could not send a reset link right now. Try again later, or use \"Can't get in?\"."
		render(w, 503, "forgot", d)
		return
	}
	render(w, 200, "forgot", d)
}

// ---------------------------------------------------------------------------
// /account/help ("Can't get in?")
// ---------------------------------------------------------------------------

func (s *Server) accountHelp(w http.ResponseWriter, r *http.Request) {
	uri := r.FormValue("request_uri")
	d := pageData{Title: "Can't get in?", Sub: "Tell us who you are. Your organisation's administrator or MiPay support will get back to you.",
		RequestURI: uri, ClientID: s.clientIDOfPAR(r, uri)}
	if r.Method == http.MethodGet {
		render(w, 200, "help", d)
		return
	}
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "GET or POST")
		return
	}
	if !s.loginIP.Allow("help:"+remoteIP(r), 5, 5) {
		render(w, 429, "help", pageData{Title: "Too many requests", Error: "Wait a minute and try again.", RequestURI: uri, ClientID: d.ClientID})
		return
	}
	d.Sub = ""
	d.Notice = "Thanks. If we find your account, your request goes to the people who can restore your access."
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	a, err := s.accountByLogin(ctx, r.PostFormValue("login"))
	if err == nil {
		msg := strings.TrimSpace(r.PostFormValue("message"))
		if err := s.emitEvent(ctx, s.pool, a, EventRecoveryRequested, map[string]any{
			"message": truncate(msg, 500), "ip": remoteIP(r), "state": a.State, "locked": a.locked(time.Now()),
		}); err != nil {
			d.Notice = ""
			d.Error = "We could not record your request. Try again later."
			render(w, 503, "help", d)
			return
		}
	} else if !errors.Is(err, pgx.ErrNoRows) {
		d.Notice = ""
		d.Error = "We could not record your request. Try again later."
		render(w, 503, "help", d)
		return
	}
	render(w, 200, "help", d)
}
