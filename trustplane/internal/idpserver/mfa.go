package idpserver

// Enforced login MFA. A password or merchant OTP creates only a short-lived,
// single-use challenge; the IdP session and OAuth code are created after a
// different factor has been verified. TOTP can be enrolled on first login.

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"trustplane/internal/jwtx"
)

const mfaTTL = 5 * time.Minute

func assuranceFor(amr string) string {
	if strings.Contains(" "+amr+" ", " totp ") || strings.Contains(" "+amr+" ", " sms-mfa ") {
		return "aal2"
	}
	return "aal1"
}

func stepUpACR(mode string) string {
	if mode == "enforce" {
		return "aal2"
	}
	return "aal1-fresh"
}

func (s *Server) mfaReadiness(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		apiErr(w, 405, "method_not_allowed", "GET only")
		return
	}
	if !s.requireCore(w, r) {
		return
	}
	rows, err := s.pool.Query(r.Context(), `SELECT COALESCE(NULLIF(a.mfa_profile,''),'ORG_MEMBER'),count(*),
		count(f.subject) FROM accounts a LEFT JOIN mfa_factors f ON f.subject=a.subject GROUP BY 1 ORDER BY 1`)
	if err != nil {
		apiErr(w, 503, "unavailable", "readiness unavailable")
		return
	}
	defer rows.Close()
	profiles := map[string]any{}
	sms := s.comms.Live(r.Context(), channelSMS)
	for rows.Next() {
		var profile string
		var accounts, enrolled int
		if rows.Scan(&profile, &accounts, &enrolled) != nil {
			apiErr(w, 503, "unavailable", "readiness unavailable")
			return
		}
		methods := []string{"TOTP"}
		if sms && profile != "PLATFORM_STAFF" && profile != "MERCHANT_OTP" {
			methods = append(methods, "SMS")
		}
		profiles[profile] = map[string]any{"accounts": accounts, "totp_enrolled": enrolled, "usable_methods": methods}
	}
	jsonBytes(w, 200, map[string]any{"mode": s.cfg.MFAMode, "step_up_acr": stepUpACR(s.cfg.MFAMode), "profiles": profiles})
}

func readMFAKey(path string) ([]byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	key, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(key) != 32 {
		return nil, errors.New("IDP_MFA_KEY_FILE must contain 32 hex-encoded bytes")
	}
	return key, nil
}

func sealMFA(key, plain []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	g, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, g.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return g.Seal(nonce, nonce, plain, nil), nil
}

func openMFA(key, sealed []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	g, err := cipher.NewGCM(block)
	if err != nil || len(sealed) < g.NonceSize() {
		return nil, errors.New("invalid MFA secret")
	}
	return g.Open(nil, sealed[:g.NonceSize()], sealed[g.NonceSize():], nil)
}

func totpAt(secret []byte, step int64) string {
	var counter [8]byte
	binary.BigEndian.PutUint64(counter[:], uint64(step))
	mac := hmac.New(sha1.New, secret)
	mac.Write(counter[:])
	digest := mac.Sum(nil)
	offset := int(digest[len(digest)-1] & 0x0f)
	n := binary.BigEndian.Uint32(digest[offset:offset+4]) & 0x7fffffff
	return fmt.Sprintf("%06d", n%1000000)
}

func acceptedTOTPStep(secret []byte, code string, now time.Time, last int64) int64 {
	if len(code) != 6 {
		return -1
	}
	step := now.Unix() / 30
	for delta := int64(-1); delta <= 1; delta++ {
		candidate := step + delta
		if candidate <= last {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(totpAt(secret, candidate)), []byte(code)) == 1 {
			return candidate
		}
	}
	return -1
}

func (s *Server) beginMFA(w http.ResponseWriter, r *http.Request, p *parRow, a *Account, first string) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	key, err := readMFAKey(s.cfg.MFAKeyFile)
	if err != nil {
		errorPage(w, 503, "Security setup unavailable", "Please try later.")
		return
	}
	id := "mfa_" + jwtx.RandB64(24)
	method := "TOTP"
	var secret []byte
	err = s.pool.QueryRow(ctx, `SELECT secret FROM mfa_factors WHERE subject=$1`, a.Subject).Scan(&secret)
	switch {
	case err == nil:
		// Existing authenticator factor takes precedence over SMS.
	case errors.Is(err, pgx.ErrNoRows) && first == "pwd" && a.MFAProfile != "PLATFORM_STAFF" && a.Phone != "" && s.comms.Live(ctx, channelSMS):
		method = "SMS"
		err = nil
	case errors.Is(err, pgx.ErrNoRows):
		method = "TOTP_ENROLL"
		plain := make([]byte, 20)
		if _, err = rand.Read(plain); err == nil {
			secret, err = sealMFA(key, plain)
		}
	default:
		errorPage(w, 503, "Storage failure", "Please try later.")
		return
	}
	if err != nil {
		errorPage(w, 503, "Security setup unavailable", "Please try later.")
		return
	}
	var code, hash string
	if method == "SMS" {
		var recent int
		if err := s.pool.QueryRow(ctx, `SELECT count(*) FROM mfa_login WHERE subject=$1 AND method='SMS'
			AND created_at > now()-interval '10 minutes'`, a.Subject).Scan(&recent); err != nil || recent >= 3 {
			errorPage(w, 429, "Too many security codes", "Try again later or use an authenticator app.")
			return
		}
		code = randomDigits(6)
		hash = otpHash(id, code)
	}
	_, err = s.pool.Exec(ctx, `INSERT INTO mfa_login(id,subject,request_uri,first_method,method,secret,code_hash,expires_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, id, a.Subject, p.requestURI, first, method, secret, hash, time.Now().Add(mfaTTL))
	if err != nil {
		errorPage(w, 503, "Storage failure", "Please try later.")
		return
	}
	if method == "SMS" {
		_, err = s.comms.Send(ctx, CommsMessage{EventCode: EvOTP, To: "user:" + a.UID, Lane: LaneCritical,
			ExpiresAt: time.Now().Add(mfaTTL), Sensitivity: SensSecret, Data: map[string]string{"code": code},
			IdempotencyKey: id})
		if err != nil {
			_, _ = s.pool.Exec(ctx, `DELETE FROM mfa_login WHERE id=$1`, id)
			errorPage(w, 503, "Security code unavailable", "Try again later or use an authenticator app.")
			return
		}
	}
	page := pageData{Title: "Verify your identity", RequestURI: p.requestURI, Challenge: id, CanSwitch: method == "SMS"}
	if method == "TOTP_ENROLL" {
		plain, err := openMFA(key, secret)
		if err != nil {
			errorPage(w, 503, "Security setup unavailable", "Please try later.")
			return
		}
		page.Title = "Set up your authenticator"
		page.Secret = base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(plain)
	}
	render(w, 200, "mfa", page)
}

func (s *Server) mfaSwitchToAuthenticator(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "Invalid form.")
		return
	}
	p, ok := s.loadPAR(r, r.PostFormValue("request_uri"))
	if !ok {
		errorPage(w, 400, "Expired request", "Start sign-in again.")
		return
	}
	key, err := readMFAKey(s.cfg.MFAKeyFile)
	if err != nil {
		errorPage(w, 503, "Security setup unavailable", "Try later.")
		return
	}
	secret := make([]byte, 20)
	if _, err = rand.Read(secret); err != nil {
		errorPage(w, 503, "Security setup unavailable", "Try later.")
		return
	}
	sealed, err := sealMFA(key, secret)
	if err != nil {
		errorPage(w, 503, "Security setup unavailable", "Try later.")
		return
	}
	id := r.PostFormValue("challenge")
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tag, err := s.pool.Exec(ctx, `UPDATE mfa_login SET method='TOTP_ENROLL',secret=$2,code_hash=NULL
		WHERE id=$1 AND request_uri=$3 AND method='SMS' AND first_method='pwd'
		AND used_at IS NULL AND expires_at>now()`, id, sealed, p.requestURI)
	if err != nil || tag.RowsAffected() != 1 {
		errorPage(w, 400, "Expired request", "Start sign-in again.")
		return
	}
	render(w, 200, "mfa", pageData{Title: "Set up your authenticator", RequestURI: p.requestURI, Challenge: id,
		Secret: base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(secret)})
}

func (s *Server) mfaVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errorPage(w, 405, "Method not allowed", "POST only")
		return
	}
	if !s.loginIP.Allow("mfa:"+remoteIP(r), 10, 10) {
		errorPage(w, 429, "Too many attempts", "Try again later.")
		return
	}
	if err := r.ParseForm(); err != nil {
		errorPage(w, 400, "Bad request", "Invalid form.")
		return
	}
	p, ok := s.loadPAR(r, r.PostFormValue("request_uri"))
	if !ok {
		errorPage(w, 400, "Expired request", "Start sign-in again.")
		return
	}
	id, code := r.PostFormValue("challenge"), strings.TrimSpace(r.PostFormValue("code"))
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		errorPage(w, 503, "Storage failure", "Try again later.")
		return
	}
	defer tx.Rollback(ctx)
	var subject, uri, first, method string
	var secret []byte
	var hash *string
	var attempts int
	var expiry time.Time
	var used *time.Time
	err = tx.QueryRow(ctx, `SELECT subject,request_uri,first_method,method,secret,code_hash,attempts,expires_at,used_at
		FROM mfa_login WHERE id=$1 FOR UPDATE`, id).Scan(&subject, &uri, &first, &method, &secret, &hash, &attempts, &expiry, &used)
	if err != nil || uri != p.requestURI || used != nil || time.Now().After(expiry) || attempts >= 5 {
		render(w, 401, "mfa", pageData{Title: "Verify your identity", RequestURI: p.requestURI, Challenge: id, Error: "Code invalid or expired. Start sign-in again."})
		return
	}
	a, err := s.accountBySubject(ctx, subject)
	if err != nil || a.State != StateActive || a.locked(time.Now()) {
		render(w, 401, "mfa", pageData{Title: "Verify your identity", RequestURI: p.requestURI, Challenge: id, Error: "Sign-in unavailable."})
		return
	}
	valid, step := false, int64(-1)
	key, err := readMFAKey(s.cfg.MFAKeyFile)
	if err != nil {
		errorPage(w, 503, "Security setup unavailable", "Please try later.")
		return
	}
	switch method {
	case "SMS":
		valid = first == "pwd" && hash != nil && subtle.ConstantTimeCompare([]byte(otpHash(id, code)), []byte(*hash)) == 1
	case "TOTP", "TOTP_ENROLL":
		if method == "TOTP" {
			var last int64
			err = tx.QueryRow(ctx, `SELECT secret,last_step FROM mfa_factors WHERE subject=$1 FOR UPDATE`, subject).Scan(&secret, &last)
			if err == nil {
				plain, e := openMFA(key, secret)
				if e == nil {
					step = acceptedTOTPStep(plain, code, time.Now(), last)
				}
			}
		} else {
			plain, e := openMFA(key, secret)
			if e == nil {
				step = acceptedTOTPStep(plain, code, time.Now(), -1)
			}
		}
		valid = step >= 0
	}
	if !valid {
		_, _ = tx.Exec(ctx, `UPDATE mfa_login SET attempts=attempts+1 WHERE id=$1`, id)
		_ = tx.Commit(ctx)
		render(w, 401, "mfa", pageData{Title: "Verify your identity", RequestURI: p.requestURI, Challenge: id, Error: "Incorrect code. Try again."})
		return
	}
	if method == "TOTP_ENROLL" {
		var tag pgconn.CommandTag
		tag, err = tx.Exec(ctx, `INSERT INTO mfa_factors(subject,kind,secret,last_step) VALUES($1,'TOTP',$2,$3)
			ON CONFLICT(subject) DO NOTHING`, subject, secret, step)
		if err == nil && tag.RowsAffected() != 1 {
			err = errors.New("factor was enrolled by another challenge")
		}
	} else if method == "TOTP" {
		_, err = tx.Exec(ctx, `UPDATE mfa_factors SET last_step=$2 WHERE subject=$1`, subject, step)
	}
	if err != nil {
		errorPage(w, 503, "Storage failure", "Try again later.")
		return
	}
	if method == "TOTP_ENROLL" {
		if err := s.emitEvent(ctx, tx, a, EventMFAEnrolled, map[string]any{"method": "TOTP"}); err != nil {
			errorPage(w, 503, "Storage failure", "Try again later.")
			return
		}
	}
	_, err = tx.Exec(ctx, `UPDATE mfa_login SET used_at=now() WHERE id=$1`, id)
	if err != nil || tx.Commit(ctx) != nil {
		errorPage(w, 503, "Storage failure", "Try again later.")
		return
	}
	amr := first
	if method == "SMS" {
		amr += " sms-mfa"
	} else {
		amr += " totp"
	}
	ss, err := s.createSession(w, r, subject, amr)
	if err != nil {
		errorPage(w, 503, "Storage failure", "Try again later.")
		return
	}
	s.afterLogin(w, r, p, ss)
}
