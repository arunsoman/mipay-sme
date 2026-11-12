package idpserver

// Sign-in (idp-rbac-cutover-plan.md P1.2, P1.8): password by login name or phone, and
// phone OTP for merchants. Accounts that are DISABLED, still ENROLLING or locked are
// refused with the same message as a wrong password. Lockout is per account (on top of
// the per-IP limit): after IDP_LOCK_AFTER consecutive failures the account is locked for
// IDP_LOCK_BASE, doubling for each further lock up to IDP_LOCK_MAX.

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"log"
	"math/big"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"

	"trustplane/internal/jwtx"
)

const (
	sessionCookie = "idp_sess"
	loginFailMsg  = "Sign-in failed. Check your details and try again, or use \"Can't get in?\"."
)

// ---------------------------------------------------------------------------
// Sessions (the IdP's own SSO session; sid in tokens)
// ---------------------------------------------------------------------------

type idpSession struct {
	id, subject, csrf, amr string
	authTime               time.Time
}

func (s *Server) currentSession(r *http.Request) (*idpSession, bool) {
	c, err := r.Cookie(sessionCookie)
	if err != nil || c.Value == "" {
		return nil, false
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	ss := &idpSession{id: c.Value}
	err = s.pool.QueryRow(ctx, `SELECT s.user_id, s.csrf, s.amr, s.auth_time FROM sessions s
		JOIN accounts a ON a.subject=s.user_id AND a.state='ACTIVE'
		WHERE s.session_id=$1 AND s.expires_at > now()`, c.Value).
		Scan(&ss.subject, &ss.csrf, &ss.amr, &ss.authTime)
	if err != nil {
		return nil, false
	}
	if s.cfg.MFAMode == "enforce" && assuranceFor(ss.amr) != "aal2" {
		return nil, false
	}
	return ss, true
}

func (s *Server) createSession(w http.ResponseWriter, r *http.Request, subject, amr string) (*idpSession, error) {
	ss := &idpSession{id: jwtx.RandB64(32), subject: subject, csrf: jwtx.RandB64(32), amr: amr, authTime: time.Now().UTC().Truncate(time.Second)}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := s.pool.Exec(ctx, `INSERT INTO sessions (session_id, user_id, csrf, amr, auth_time, expires_at) VALUES ($1,$2,$3,$4,$5,$6)`,
		ss.id, subject, ss.csrf, amr, ss.authTime, time.Now().Add(s.cfg.SessionTTL)); err != nil {
		return nil, err
	}
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: ss.id, Path: "/", HttpOnly: true, Secure: true,
		SameSite: http.SameSiteLaxMode, MaxAge: int(s.cfg.SessionTTL.Seconds()),
	})
	return ss, nil
}

// ---------------------------------------------------------------------------
// Login page
// ---------------------------------------------------------------------------

func (s *Server) otpOffered(ctx context.Context) bool {
	return s.comms.Live(ctx, channelSMS) || (s.cfg.IsDev() && s.cfg.DevOTP != "")
}

func (s *Server) renderLogin(w http.ResponseWriter, r *http.Request, p *parRow, status int, errMsg string) {
	render(w, status, "login", pageData{
		Title: "Sign in to MiPay", Sub: "Use the login name or phone number your organisation registered.",
		Error: errMsg, RequestURI: p.requestURI, ClientID: p.clientID,
		OTP: s.otpOffered(r.Context()), Forgot: s.forgotOffered(r.Context()),
	})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if !s.loginIP.Allow("login:"+remoteIP(r), 30, 30) {
		errorPage(w, 429, "Too many attempts", "Slow down and try again in a minute.")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "invalid form")
		return
	}
	p, ok := s.loadPAR(r, r.PostFormValue("request_uri"))
	if !ok {
		errorPage(w, 400, "Expired request", "authorization request expired, restart the flow")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	a, err := s.accountByLogin(ctx, r.PostFormValue("username"))
	if !s.passwordOK(ctx, a, err == nil, r.PostFormValue("password")) {
		s.renderLogin(w, r, p, http.StatusUnauthorized, loginFailMsg)
		return
	}
	if s.cfg.MFAMode == "enforce" {
		s.beginMFA(w, r, p, a, "pwd")
		return
	}
	if s.cfg.MFAMode == "shadow" {
		log.Printf("[mfa-shadow] subject=%s profile=%s would_require_second_factor=true", a.Subject, a.MFAProfile)
	}
	ss, err := s.createSession(w, r, a.Subject, "pwd")
	if err != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	s.afterLogin(w, r, p, ss)
}

// passwordOK checks the password with constant work for unknown, locked, disabled and
// enrolling accounts, and updates the lockout counters.
func (s *Server) passwordOK(ctx context.Context, a *Account, found bool, pw string) bool {
	if !found || a.locked(time.Now()) || a.State != StateActive || !a.PasswordSet {
		_ = bcrypt.CompareHashAndPassword(dummyHash, []byte(pw))
		if found && a.State == StateActive && a.PasswordSet {
			// a locked account keeps counting; it cannot unlock itself by guessing
			s.recordFailure(ctx, a)
		}
		return false
	}
	if bcrypt.CompareHashAndPassword([]byte(a.passwordHash), []byte(pw)) != nil {
		s.recordFailure(ctx, a)
		return false
	}
	s.recordSuccess(ctx, a)
	return true
}

// lockDuration doubles from LockBase per earlier lock, capped at LockMax.
func (c Config) lockDuration(lockCount int) time.Duration {
	d := c.LockBase
	for i := 0; i < lockCount && d < c.LockMax; i++ {
		d *= 2
	}
	if d > c.LockMax {
		d = c.LockMax
	}
	return d
}

func (s *Server) recordFailure(ctx context.Context, a *Account) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return
	}
	defer tx.Rollback(ctx)
	var failed, locks int
	if err := tx.QueryRow(ctx, `UPDATE accounts SET failed_attempts=failed_attempts+1, updated_at=now()
		WHERE subject=$1 RETURNING failed_attempts, lock_count`, a.Subject).Scan(&failed, &locks); err != nil {
		return
	}
	if failed >= s.cfg.LockAfter {
		until := time.Now().Add(s.cfg.lockDuration(locks)).UTC().Truncate(time.Second)
		if _, err := tx.Exec(ctx, `UPDATE accounts SET failed_attempts=0, lock_count=lock_count+1, locked_until=$2 WHERE subject=$1`,
			a.Subject, until); err != nil {
			return
		}
		if err := s.emitEvent(ctx, tx, a, EventLocked, map[string]any{"until": until.Format(time.RFC3339), "lock_count": locks + 1}); err != nil {
			return
		}
	}
	_ = tx.Commit(ctx)
}

func (s *Server) recordSuccess(ctx context.Context, a *Account) {
	if a.FailedAttempts == 0 && a.LockCount == 0 && a.LockedUntil == nil {
		return
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE accounts SET failed_attempts=0, lock_count=0, locked_until=NULL, updated_at=now() WHERE subject=$1`, a.Subject); err != nil {
		return
	}
	if a.LockCount > 0 {
		if err := s.emitEvent(ctx, tx, a, EventUnlocked, map[string]any{"via": "login"}); err != nil {
			return
		}
	}
	_ = tx.Commit(ctx)
}

// ---------------------------------------------------------------------------
// After authentication: consent (when the client requires it) or straight to the code
// ---------------------------------------------------------------------------

func (s *Server) afterLogin(w http.ResponseWriter, r *http.Request, p *parRow, ss *idpSession) {
	cl := s.clients.ByID[p.clientID]
	if cl != nil && cl.RequireConsent != nil && !*cl.RequireConsent {
		s.issueCode(w, r, p, ss)
		return
	}
	s.renderConsent(w, p, ss.csrf)
}

func (s *Server) renderConsent(w http.ResponseWriter, p *parRow, csrf string) {
	name := p.clientID
	if cl := s.clients.ByID[p.clientID]; cl != nil && cl.DisplayName != "" {
		name = cl.DisplayName
	}
	var scopes []scopeView
	for _, sc := range strings.Fields(parStr(p.params, "scope")) {
		scopes = append(scopes, scopeView{Name: sc, Desc: scopeDesc(sc)})
	}
	render(w, 200, "consent", pageData{Title: "Authorize application", ClientName: name, Scopes: scopes,
		RequestURI: p.requestURI, CSRF: csrf})
}

func scopeDesc(s string) string {
	switch s {
	case "openid":
		return "See your user identity"
	case "offline_access":
		return "Keep access while you are offline (refresh token)"
	case "rbac:admin":
		return "Manage access assignments"
	default:
		return "Access requested"
	}
}

func (s *Server) consent(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "invalid form")
		return
	}
	ss, ok := s.currentSession(r)
	if !ok {
		errorPage(w, 401, "Session required", "Login first.")
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.PostFormValue("csrf")), []byte(ss.csrf)) != 1 {
		errorPage(w, 403, "CSRF", "form token mismatch")
		return
	}
	uri := r.PostFormValue("request_uri")
	p, pok := s.loadPAR(r, uri)
	if !pok {
		errorPage(w, 400, "Expired request", "authorization request expired")
		return
	}
	if r.PostFormValue("decision") != "allow" {
		s.deletePAR(r, uri)
		http.Redirect(w, r, redirectQuery(parStr(p.params, "redirect_uri"), map[string]string{
			"error": "access_denied", "iss": urlEscape(s.cfg.Issuer),
		}), http.StatusFound)
		return
	}
	s.issueCode(w, r, p, ss)
}

// issueCode consumes the PAR (single use) and redirects with a one-time code.
func (s *Server) issueCode(w http.ResponseWriter, r *http.Request, p *parRow, ss *idpSession) {
	s.deletePAR(r, p.requestURI)
	code := jwtx.RandB64(32)
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	_, err := s.pool.Exec(ctx, `INSERT INTO auth_codes
		(code_hash, client_id, redirect_uri, sub, scope, nonce, code_challenge, session_id, amr, auth_time, expires_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
		codeSum(code), p.clientID, parStr(p.params, "redirect_uri"), ss.subject, parStr(p.params, "scope"),
		parStr(p.params, "nonce"), parStr(p.params, "code_challenge"), ss.id, ss.amr, ss.authTime.Unix(),
		time.Now().Add(s.cfg.CodeTTL))
	if err != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	params := map[string]string{"code": urlEscape(code), "iss": urlEscape(s.cfg.Issuer)}
	if st := parStr(p.params, "state"); st != "" {
		params["state"] = urlEscape(st)
	}
	http.Redirect(w, r, redirectQuery(parStr(p.params, "redirect_uri"), params), http.StatusFound)
}

// ---------------------------------------------------------------------------
// Merchant phone OTP (P1.8, D8): AAL1, only for accounts the core gave the MERCHANT_OTP
// profile, only while SMS is live (or the dev OTP in dev/sandbox).
// ---------------------------------------------------------------------------

func otpHash(challenge, code string) string {
	sum := sha256.Sum256([]byte("otp:" + challenge + ":" + code))
	return jwtx.B64UEnc(sum[:])
}

func randomDigits(n int) string {
	var b strings.Builder
	for i := 0; i < n; i++ {
		v, _ := rand.Int(rand.Reader, big.NewInt(10))
		b.WriteByte(byte('0' + v.Int64()))
	}
	return b.String()
}

func (s *Server) otpStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "invalid form")
		return
	}
	p, ok := s.loadPAR(r, r.PostFormValue("request_uri"))
	if !ok {
		errorPage(w, 400, "Expired request", "authorization request expired, restart the flow")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	if !s.otpOffered(ctx) {
		s.renderLogin(w, r, p, http.StatusServiceUnavailable, "Sign-in codes by text message are not available right now. Use your password.")
		return
	}
	if !s.loginIP.Allow("otp-start:"+remoteIP(r), 10, 10) {
		s.renderLogin(w, r, p, http.StatusTooManyRequests, "Too many requests. Wait a minute and try again.")
		return
	}
	phone := normalizePhone(r.PostFormValue("phone"))
	codePage := pageData{Title: "Enter your code", Sub: "If this number is registered for code sign-in, we sent it a 6-digit code. It expires in 5 minutes.",
		RequestURI: p.requestURI, ClientID: p.clientID}
	var a *Account
	var err error
	if phone != "" {
		a, err = s.accountBy(ctx, "phone=$1", phone)
	}
	if phone == "" || err != nil || a.State != StateActive || a.locked(time.Now()) || a.MFAProfile != "MERCHANT_OTP" {
		// same page as a real send: phone numbers are not enumerable here
		codePage.Challenge = "otp_" + jwtx.RandB64(16)
		render(w, 200, "otp", codePage)
		return
	}
	var lastAt *time.Time
	var hour int
	_ = s.pool.QueryRow(ctx, `SELECT max(created_at), count(*) FILTER (WHERE created_at > now()-interval '1 hour')
		FROM otp_challenges WHERE subject=$1`, a.Subject).Scan(&lastAt, &hour)
	if lastAt != nil && time.Since(*lastAt) < s.cfg.OTPResendGap {
		s.renderLogin(w, r, p, http.StatusTooManyRequests, "A code was sent less than a minute ago. Wait a moment before asking for another.")
		return
	}
	if hour >= s.cfg.OTPMaxPerHour {
		s.renderLogin(w, r, p, http.StatusTooManyRequests, "Too many codes were requested for this number. Try again later or use your password.")
		return
	}
	code := randomDigits(6)
	if s.cfg.IsDev() && s.cfg.DevOTP != "" {
		code = s.cfg.DevOTP
	}
	challenge := "otp_" + jwtx.RandB64(16)
	exp := time.Now().Add(s.cfg.OTPTTL)
	if _, err := s.pool.Exec(ctx, `INSERT INTO otp_challenges (id, subject, request_uri, code_hash, expires_at) VALUES ($1,$2,$3,$4,$5)`,
		challenge, a.Subject, p.requestURI, otpHash(challenge, code), exp); err != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	if _, err := s.comms.Send(ctx, CommsMessage{EventCode: EvOTP, To: "user:" + a.UID, Lane: LaneCritical, ExpiresAt: exp,
		Sensitivity: SensSecret, Data: map[string]string{"code": code}, IdempotencyKey: challenge}); err != nil {
		_, _ = s.pool.Exec(ctx, `DELETE FROM otp_challenges WHERE id=$1`, challenge)
		s.renderLogin(w, r, p, http.StatusServiceUnavailable, "We could not send a code right now. Use your password, or try again later.")
		return
	}
	codePage.Challenge = challenge
	render(w, 200, "otp", codePage)
}

func (s *Server) otpVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if !s.loginIP.Allow("otp-verify:"+remoteIP(r), 30, 30) {
		errorPage(w, 429, "Too many attempts", "Slow down and try again in a minute.")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "invalid form")
		return
	}
	p, ok := s.loadPAR(r, r.PostFormValue("request_uri"))
	if !ok {
		errorPage(w, 400, "Expired request", "authorization request expired, restart the flow")
		return
	}
	challenge, code := r.PostFormValue("challenge"), strings.TrimSpace(r.PostFormValue("code"))
	retry := pageData{Title: "Enter your code", RequestURI: p.requestURI, ClientID: p.clientID, Challenge: challenge,
		Error: "That code is not valid. Check it and try again, or ask for a new one."}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	defer tx.Rollback(ctx)
	var subject, hash, reqURI string
	var attempts int
	var exp time.Time
	var used *time.Time
	err = tx.QueryRow(ctx, `SELECT subject, code_hash, request_uri, attempts, expires_at, used_at FROM otp_challenges WHERE id=$1 FOR UPDATE`,
		challenge).Scan(&subject, &hash, &reqURI, &attempts, &exp, &used)
	if err != nil || used != nil || time.Now().After(exp) || attempts >= s.cfg.OTPMaxAttempts || reqURI != p.requestURI {
		_ = bcrypt.CompareHashAndPassword(dummyHash, []byte(code)) // equal timing
		render(w, http.StatusUnauthorized, "otp", retry)
		return
	}
	a, err := s.accountBySubject(ctx, subject)
	if err != nil {
		render(w, http.StatusUnauthorized, "otp", retry)
		return
	}
	if subtle.ConstantTimeCompare([]byte(otpHash(challenge, code)), []byte(hash)) != 1 {
		dead := attempts+1 >= s.cfg.OTPMaxAttempts
		_, _ = tx.Exec(ctx, `UPDATE otp_challenges SET attempts=attempts+1, used_at=CASE WHEN $2 THEN now() ELSE used_at END WHERE id=$1`, challenge, dead)
		_ = tx.Commit(ctx)
		s.recordFailure(ctx, a) // the account's lockout applies to codes too
		if dead {
			retry.Error = "Too many wrong codes. Ask for a new code or use your password."
		}
		render(w, http.StatusUnauthorized, "otp", retry)
		return
	}
	if _, err := tx.Exec(ctx, `UPDATE otp_challenges SET used_at=now() WHERE id=$1`, challenge); err != nil || tx.Commit(ctx) != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	if a.State != StateActive || a.locked(time.Now()) {
		render(w, http.StatusUnauthorized, "otp", retry)
		return
	}
	s.recordSuccess(ctx, a)
	if s.cfg.MFAMode == "enforce" {
		s.beginMFA(w, r, p, a, "sms")
		return
	}
	if s.cfg.MFAMode == "shadow" {
		log.Printf("[mfa-shadow] subject=%s profile=%s would_require_second_factor=true", a.Subject, a.MFAProfile)
	}
	ss, err := s.createSession(w, r, a.Subject, "sms")
	if err != nil {
		errorPage(w, 500, "Storage failure", "try again")
		return
	}
	s.afterLogin(w, r, p, ss)
}
