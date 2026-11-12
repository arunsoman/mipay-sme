package idpserver

// Credential accounts and the enrolment API (idp-rbac-cutover-plan.md P1.1, P1.3).
//
// The IdP holds credential accounts, not users: no org, role, KYC or business status.
// Only the MiPay core (mesh certificate CN = IDP_CORE_CN) may create or change them,
// always for a core user id (uid) it created inside an org (U7).

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"

	"trustplane/internal/jwtx"
)

const (
	StateEnrolling = "ENROLLING"
	StateActive    = "ACTIVE"
	StateDisabled  = "DISABLED"

	PurposeSetup    = "SETUP"    // first credential (enrolment)
	PurposeReset    = "RESET"    // self-service reset ("Forgot password?")
	PurposeRecovery = "RECOVERY" // core FULL reset (assisted recovery)
)

// Account is one credential account.
type Account struct {
	Subject          string     `json:"subject"`
	UID              string     `json:"uid"`
	LoginName        string     `json:"login_name"`
	Phone            string     `json:"phone,omitempty"`
	DisplayName      string     `json:"display_name"`
	State            string     `json:"state"`
	DisabledReason   string     `json:"disabled_reason,omitempty"`
	PasswordSet      bool       `json:"password_set"`
	FailedAttempts   int        `json:"failed_attempts"`
	LockCount        int        `json:"lock_count"`
	LockedUntil      *time.Time `json:"locked_until,omitempty"`
	PasswordChanged  *time.Time `json:"password_changed_at,omitempty"`
	TokensValidAfter time.Time  `json:"tokens_valid_after"`
	MFAProfile       string     `json:"mfa_profile,omitempty"`
	OrgRequiresMFA   bool       `json:"org_requires_mfa"`
	CreatedAt        time.Time  `json:"created_at"`

	passwordHash string
}

func (a *Account) locked(now time.Time) bool {
	return a.LockedUntil != nil && a.LockedUntil.After(now)
}

const accountCols = `subject, uid, login_name, COALESCE(phone,''), display_name, state,
	COALESCE(disabled_reason,''), COALESCE(password_hash,''), failed_attempts, lock_count, locked_until,
	password_changed_at, tokens_valid_after, COALESCE(mfa_profile,''), org_requires_mfa, created_at`

func scanAccount(row pgx.Row) (*Account, error) {
	a := &Account{}
	err := row.Scan(&a.Subject, &a.UID, &a.LoginName, &a.Phone, &a.DisplayName, &a.State,
		&a.DisabledReason, &a.passwordHash, &a.FailedAttempts, &a.LockCount, &a.LockedUntil,
		&a.PasswordChanged, &a.TokensValidAfter, &a.MFAProfile, &a.OrgRequiresMFA, &a.CreatedAt)
	if err != nil {
		return nil, err
	}
	a.PasswordSet = a.passwordHash != ""
	return a, nil
}

func (s *Server) accountBy(ctx context.Context, where string, arg any) (*Account, error) {
	return scanAccount(s.pool.QueryRow(ctx, `SELECT `+accountCols+` FROM accounts WHERE `+where, arg))
}

func (s *Server) accountBySubject(ctx context.Context, subject string) (*Account, error) {
	return s.accountBy(ctx, "subject=$1", subject)
}

// accountByLogin finds an account by login name (case-insensitive) or phone.
func (s *Server) accountByLogin(ctx context.Context, login string) (*Account, error) {
	login = strings.TrimSpace(login)
	if login == "" {
		return nil, pgx.ErrNoRows
	}
	if a, err := s.accountBy(ctx, "lower(login_name)=lower($1)", login); err == nil {
		return a, nil
	}
	if p := normalizePhone(login); p != "" {
		return s.accountByPhone(ctx, p)
	}
	return nil, pgx.ErrNoRows
}

// accountByPhone finds the one account with this phone. A phone shared by several accounts
// (one person administering several organizations) identifies none of them: the user signs
// in with their login name instead.
func (s *Server) accountByPhone(ctx context.Context, phone string) (*Account, error) {
	var n int
	if err := s.pool.QueryRow(ctx, `SELECT count(*) FROM accounts WHERE phone=$1`, phone).Scan(&n); err != nil {
		return nil, err
	}
	if n != 1 {
		return nil, pgx.ErrNoRows
	}
	return s.accountBy(ctx, "phone=$1", phone)
}

var phoneRe = regexp.MustCompile(`^\+?[0-9]{6,15}$`)

// normalizePhone keeps a leading + and digits; returns "" for anything that is not a phone number.
func normalizePhone(p string) string {
	p = strings.TrimSpace(p)
	var b strings.Builder
	for i, r := range p {
		switch {
		case r == '+' && i == 0:
			b.WriteRune(r)
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case r == ' ' || r == '-' || r == '(' || r == ')':
		default:
			return ""
		}
	}
	out := b.String()
	if !phoneRe.MatchString(out) {
		return ""
	}
	return out
}

var loginNameRe = regexp.MustCompile(`^[A-Za-z0-9._@+-]{3,64}$`)

// newSubject returns an opaque, IdP-generated subject identifier.
func newSubject() string { return "acc_" + jwtx.RandB64(18) }

// seedSubject is stable across restarts so demo tokens keep their sub.
func seedSubject(name string) string {
	sum := sha256.Sum256([]byte("seed:" + name))
	return "acc_seed_" + jwtx.B64UEnc(sum[:12])
}

func linkHash(token string) string {
	sum := sha256.Sum256([]byte("link:" + token))
	return jwtx.B64UEnc(sum[:])
}

// issueLink stores a one-time link (hashed) and returns the raw URL. The raw token is never stored.
func (s *Server) issueLink(ctx context.Context, q execer, subject, purpose string, ttl time.Duration) (string, time.Time, error) {
	token := jwtx.RandB64(32)
	exp := time.Now().Add(ttl).UTC().Truncate(time.Second)
	// a new link of the same purpose replaces any unused earlier one
	if _, err := q.Exec(ctx, `UPDATE account_links SET used_at=now() WHERE subject=$1 AND purpose=$2 AND used_at IS NULL`,
		subject, purpose); err != nil {
		return "", time.Time{}, err
	}
	if _, err := q.Exec(ctx, `INSERT INTO account_links (token_hash, subject, purpose, expires_at) VALUES ($1,$2,$3,$4)`,
		linkHash(token), subject, purpose, exp); err != nil {
		return "", time.Time{}, err
	}
	return s.cfg.Issuer + "/account/setup?token=" + urlEscape(token), exp, nil
}

// issueEnrolLink makes a lost-response retry return the same setup URL without
// storing the raw token. The token is keyed by the account subject and command id;
// deleting and recreating an account cannot revive its old link.
func (s *Server) issueEnrolLink(ctx context.Context, tx pgx.Tx, subject, idempotencyKey string, ttl time.Duration) (string, time.Time, error) {
	if idempotencyKey == "" {
		return s.issueLink(ctx, tx, subject, PurposeSetup, ttl)
	}
	if len(idempotencyKey) > 200 {
		return "", time.Time{}, errors.New("idempotency key too long")
	}
	key, err := readMFAKey(s.cfg.MFAKeyFile)
	if err != nil {
		return "", time.Time{}, err
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte("enrol:" + subject + ":" + idempotencyKey))
	token := jwtx.B64UEnc(mac.Sum(nil))
	hash := linkHash(token)
	var storedHash string
	var exp time.Time
	var used *time.Time
	err = tx.QueryRow(ctx, `SELECT token_hash,expires_at,used_at FROM account_links
		WHERE subject=$1 AND purpose='SETUP' AND idempotency_key=$2`, subject, idempotencyKey).Scan(&storedHash, &exp, &used)
	if err == nil {
		if storedHash != hash || used != nil || !exp.After(time.Now()) {
			return "", time.Time{}, errors.New("enrolment link is no longer usable")
		}
		return s.cfg.Issuer + "/account/setup?token=" + urlEscape(token), exp, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return "", time.Time{}, err
	}
	exp = time.Now().Add(ttl).UTC().Truncate(time.Second)
	if _, err := tx.Exec(ctx, `UPDATE account_links SET used_at=now() WHERE subject=$1 AND purpose='SETUP' AND used_at IS NULL`, subject); err != nil {
		return "", time.Time{}, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO account_links(token_hash,subject,purpose,idempotency_key,expires_at)
		VALUES($1,$2,'SETUP',$3,$4)`, hash, subject, idempotencyKey, exp); err != nil {
		return "", time.Time{}, err
	}
	return s.cfg.Issuer + "/account/setup?token=" + urlEscape(token), exp, nil
}

// ---------------------------------------------------------------------------
// Password policy (same rules as the core's ActivationService [H8])
// ---------------------------------------------------------------------------

func validatePassword(a *Account, password string) error {
	if len(password) < 12 {
		return errors.New("Password must be at least 12 characters.")
	}
	if len(password) > 128 {
		return errors.New("Password must be at most 128 characters.")
	}
	if strings.EqualFold(password, a.LoginName) {
		return errors.New("Password must differ from the login name.")
	}
	digits := onlyDigits(a.Phone)
	if digits != "" && strings.Contains(onlyDigits(password), digits) {
		return errors.New("Password must not contain the phone number.")
	}
	return nil
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

func hashPassword(pw string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.DefaultCost)
	return string(h), err
}

// dummyHash keeps the timing of "unknown account" equal to "wrong password".
var dummyHash, _ = bcrypt.GenerateFromPassword([]byte("dummy-password-for-timing"), bcrypt.DefaultCost)

// ---------------------------------------------------------------------------
// Session revocation (disable, revoke-sessions, credential change)
// ---------------------------------------------------------------------------

// revokeAll ends every session and token of the account: IdP sessions, refresh tokens,
// access tokens issued before now (introspection refuses them), and queues a back-channel
// logout token for every client that registered a back-channel logout URI (P1.6).
func (s *Server) revokeAll(ctx context.Context, tx pgx.Tx, a *Account, reason string) error {
	if _, err := tx.Exec(ctx, `UPDATE accounts SET tokens_valid_after=now(), updated_at=now() WHERE subject=$1`, a.Subject); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM sessions WHERE user_id=$1`, a.Subject); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM mfa_login WHERE subject=$1`, a.Subject); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE refresh_tokens SET revoked=TRUE WHERE sub=$1 AND NOT revoked`, a.Subject); err != nil {
		return err
	}
	for _, cl := range s.clients.List {
		if cl.BackchannelLogoutURI == "" {
			continue
		}
		payload, _ := json.Marshal(map[string]any{"client_id": cl.ID, "sub": a.Subject, "uid": a.UID, "reason": reason})
		if err := enqueueOutbound(ctx, tx, "logout", cl.BackchannelLogoutURI, payload); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// Enrolment API (mTLS, core only)
// ---------------------------------------------------------------------------

func normalizeCN(s string) string {
	v := strings.ToLower(strings.TrimSpace(s))
	v = strings.TrimPrefix(v, "sme-")
	v = strings.TrimSuffix(v, "-mesh")
	return v
}

// requireCore admits only the core's mesh certificate. The gateway proxies public traffic
// with its own mesh certificate, so nothing from outside can reach this API (and the
// gateway has no route for it: deny by default).
func (s *Server) requireCore(w http.ResponseWriter, r *http.Request) bool {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 ||
		normalizeCN(r.TLS.PeerCertificates[0].Subject.CommonName) != normalizeCN(s.cfg.CoreCN) {
		apiErr(w, http.StatusForbidden, "forbidden", "the enrolment API accepts only the core's mesh certificate")
		return false
	}
	return true
}

func apiErr(w http.ResponseWriter, status int, code, desc string) {
	jsonBytes(w, status, map[string]string{"error": code, "error_description": desc})
}

type enrolBody struct {
	UID         string `json:"uid"`
	LoginName   string `json:"login_name"`
	Phone       string `json:"phone"`
	DisplayName string `json:"display_name"`
	// Optional credential import (cut-over P3.10 backfill): a bcrypt hash copied from the
	// MiPay core ({bcrypt} prefix stripped). When present and well-formed, the account is
	// created/activated directly with the imported password instead of issuing a set-up link.
	// Only the core's mesh certificate reaches this branch, same as the rest of the API.
	PasswordHash string `json:"password_hash,omitempty"`
}

func readJSON(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 16*1024))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// accountsAPI routes /accounts and /accounts/{subject}[/action].
func (s *Server) accountsAPI(w http.ResponseWriter, r *http.Request) {
	if !s.requireCore(w, r) {
		return
	}
	rest := strings.Trim(strings.TrimPrefix(r.URL.Path, "/accounts"), "/")
	parts := strings.Split(rest, "/")
	switch {
	case rest == "" && r.Method == http.MethodPost:
		s.apiEnrol(w, r)
	case rest == "" && r.Method == http.MethodGet:
		s.apiFindByUID(w, r)
	case len(parts) == 1 && r.Method == http.MethodGet:
		s.apiGet(w, r, parts[0])
	case len(parts) == 1 && r.Method == http.MethodPatch:
		s.apiPatch(w, r, parts[0])
	case len(parts) == 1 && r.Method == http.MethodDelete:
		s.apiDelete(w, r, parts[0])
	case len(parts) == 2 && r.Method == http.MethodPost && parts[1] == "reset-link":
		s.apiResetLink(w, r, parts[0])
	case len(parts) == 2 && r.Method == http.MethodPost && parts[1] == "disable":
		s.apiDisable(w, r, parts[0])
	case len(parts) == 2 && r.Method == http.MethodPost && parts[1] == "enable":
		s.apiEnable(w, r, parts[0])
	case len(parts) == 2 && r.Method == http.MethodPost && parts[1] == "revoke-sessions":
		s.apiRevokeSessions(w, r, parts[0])
	case len(parts) == 2 && r.Method == http.MethodPut && parts[1] == "mfa-profile":
		s.apiMFAProfile(w, r, parts[0])
	default:
		apiErr(w, http.StatusNotFound, "not_found", "unknown accounts operation")
	}
}

func (s *Server) apiEnrol(w http.ResponseWriter, r *http.Request) {
	var b enrolBody
	if err := readJSON(r, &b); err != nil {
		apiErr(w, 400, "invalid_request", "body must be {uid, login_name, phone, display_name}")
		return
	}
	b.UID = strings.TrimSpace(b.UID)
	b.LoginName = strings.TrimSpace(b.LoginName)
	if b.UID == "" || len(b.UID) > 128 {
		apiErr(w, 400, "invalid_request", "uid required (the core user id)")
		return
	}
	if !loginNameRe.MatchString(b.LoginName) {
		apiErr(w, 400, "invalid_request", "login_name must be 3-64 characters of letters, digits and . _ @ + -")
		return
	}
	phone := ""
	if strings.TrimSpace(b.Phone) != "" {
		if phone = normalizePhone(b.Phone); phone == "" {
			apiErr(w, 400, "invalid_request", "phone is not a valid number")
			return
		}
	}
	importHash := ""
	if strings.TrimSpace(b.PasswordHash) != "" {
		if validImportHash(b.PasswordHash) != nil {
			apiErr(w, 400, "invalid_request", "password_hash is not a usable bcrypt hash ($2a/$2b, cost <= 12)")
			return
		}
		importHash = b.PasswordHash
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		apiErr(w, 500, "server_error", "storage failure")
		return
	}
	defer tx.Rollback(ctx)

	// Idempotent for the core's outbox retries: the same uid still ENROLLING gets a fresh link.
	existing, err := scanAccount(tx.QueryRow(ctx, `SELECT `+accountCols+` FROM accounts WHERE uid=$1 FOR UPDATE`, b.UID))
	status := http.StatusCreated
	subject := ""
	switch {
	case err == nil && existing.State == StateDisabled:
		apiErr(w, 409, "account_exists", "uid already has a credential account in state "+existing.State)
		return
	case err == nil && importHash != "":
		subject = existing.Subject
		status = http.StatusOK
		if existing.State == StateActive && existing.passwordHash != importHash {
			apiErr(w, 409, "hash_mismatch", "credential already imported with a different hash; refusing to overwrite")
			return
		}
		if existing.State == StateEnrolling {
			// an unused set-up link dies with the import: the credential is already set
			if _, err := tx.Exec(ctx, `UPDATE account_links SET used_at=now() WHERE subject=$1 AND purpose='SETUP' AND used_at IS NULL`, subject); err != nil {
				apiErr(w, 500, "server_error", "storage failure")
				return
			}
			if _, err := tx.Exec(ctx, `UPDATE accounts SET login_name=$2, phone=NULLIF($3,''), display_name=$4,
				state='ACTIVE', password_hash=$5, password_changed_at=now(), tokens_valid_after=now(),
				failed_attempts=0, lock_count=0, locked_until=NULL, updated_at=now() WHERE subject=$1`,
				subject, b.LoginName, phone, b.DisplayName, importHash); err != nil {
				s.uniqueOr500(w, err)
				return
			}
			// the imported credential is a set credential: the core activates the user and its
			// link row through the same CREDENTIAL_SET event it receives after a normal set-up
			if err := s.emitEvent(ctx, tx, &Account{Subject: subject, UID: b.UID}, EventCredentialSet,
				map[string]any{"method": "password", "imported": true}); err != nil {
				apiErr(w, 500, "server_error", "storage failure")
				return
			}
		}
	case err == nil && existing.State != StateEnrolling:
		apiErr(w, 409, "account_exists", "uid already has a credential account in state "+existing.State)
		return
	case err == nil:
		subject = existing.Subject
		status = http.StatusOK
		if _, err := tx.Exec(ctx, `UPDATE accounts SET login_name=$2, phone=NULLIF($3,''), display_name=$4, updated_at=now() WHERE subject=$1`,
			subject, b.LoginName, phone, b.DisplayName); err != nil {
			s.uniqueOr500(w, err)
			return
		}
	case errors.Is(err, pgx.ErrNoRows):
		subject = newSubject()
		if importHash != "" {
			if _, err := tx.Exec(ctx, `INSERT INTO accounts (subject, uid, login_name, phone, display_name, state, password_hash, password_changed_at, tokens_valid_after)
				VALUES ($1,$2,$3,NULLIF($4,''),$5,'ACTIVE',$6,now(),now())`, subject, b.UID, b.LoginName, phone, b.DisplayName, importHash); err != nil {
				s.uniqueOr500(w, err)
				return
			}
			// a credential that arrives already set activates the core user through CREDENTIAL_SET
			if err := s.emitEvent(ctx, tx, &Account{Subject: subject, UID: b.UID}, EventCredentialSet,
				map[string]any{"method": "password", "imported": true}); err != nil {
				apiErr(w, 500, "server_error", "storage failure")
				return
			}
		} else {
			if _, err := tx.Exec(ctx, `INSERT INTO accounts (subject, uid, login_name, phone, display_name, state)
				VALUES ($1,$2,$3,NULLIF($4,''),$5,'ENROLLING')`, subject, b.UID, b.LoginName, phone, b.DisplayName); err != nil {
				s.uniqueOr500(w, err)
				return
			}
		}
	default:
		apiErr(w, 500, "server_error", "storage failure")
		return
	}
	if importHash == "" {
		link, exp, err := s.issueEnrolLink(ctx, tx, subject, r.Header.Get("Idempotency-Key"), s.cfg.LinkTTL)
		if err != nil || tx.Commit(ctx) != nil {
			apiErr(w, 500, "server_error", "storage failure")
			return
		}
		jsonBytes(w, status, map[string]any{"subject": subject, "uid": b.UID, "state": StateEnrolling,
			"link": link, "expires_at": exp.Format(time.RFC3339)})
		return
	}
	if tx.Commit(ctx) != nil {
		apiErr(w, 500, "server_error", "storage failure")
		return
	}
	jsonBytes(w, status, map[string]any{"subject": subject, "uid": b.UID, "state": StateActive})
}

// validImportHash accepts only a well-formed bcrypt hash the Go login path can actually compare:
// $2a/$2b prefix, standard 60-byte encoding, and a sane cost (legacy core hashes are cost 10).
func validImportHash(hash string) error {
	if len(hash) != 60 {
		return errors.New("bcrypt hashes are 60 bytes")
	}
	if !strings.HasPrefix(hash, "$2a$") && !strings.HasPrefix(hash, "$2b$") {
		return errors.New("unsupported bcrypt prefix")
	}
	cost, err := bcrypt.Cost([]byte(hash))
	if err != nil {
		return err
	}
	if cost < 4 || cost > 12 {
		return fmt.Errorf("bcrypt cost %d out of range 4..12", cost)
	}
	return nil
}

func (s *Server) uniqueOr500(w http.ResponseWriter, err error) {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "accounts_login_name_uq"):
		apiErr(w, 409, "login_name_taken", "login_name is used by another account")
	case strings.Contains(msg, "accounts_phone_uq"):
		apiErr(w, 409, "phone_taken", "phone is used by another account")
	case strings.Contains(msg, "accounts_uid_key"):
		apiErr(w, 409, "account_exists", "uid already has a credential account")
	default:
		apiErr(w, 500, "server_error", "storage failure")
	}
}

func (s *Server) apiFindByUID(w http.ResponseWriter, r *http.Request) {
	uid := r.URL.Query().Get("uid")
	if uid == "" {
		apiErr(w, 400, "invalid_request", "uid query parameter required")
		return
	}
	a, err := s.accountBy(r.Context(), "uid=$1", uid)
	if err != nil {
		apiErr(w, 404, "not_found", "no account for uid")
		return
	}
	jsonBytes(w, 200, a)
}

func (s *Server) apiGet(w http.ResponseWriter, r *http.Request, subject string) {
	a, err := s.accountBySubject(r.Context(), subject)
	if err != nil {
		apiErr(w, 404, "not_found", "unknown subject")
		return
	}
	jsonBytes(w, 200, a)
}

func (s *Server) apiPatch(w http.ResponseWriter, r *http.Request, subject string) {
	var b struct {
		LoginName   *string `json:"login_name"`
		Phone       *string `json:"phone"`
		DisplayName *string `json:"display_name"`
	}
	if err := readJSON(r, &b); err != nil {
		apiErr(w, 400, "invalid_request", "body must be {login_name?, phone?, display_name?}")
		return
	}
	a, err := s.accountBySubject(r.Context(), subject)
	if err != nil {
		apiErr(w, 404, "not_found", "unknown subject")
		return
	}
	login, phone, name := a.LoginName, a.Phone, a.DisplayName
	if b.LoginName != nil {
		if login = strings.TrimSpace(*b.LoginName); !loginNameRe.MatchString(login) {
			apiErr(w, 400, "invalid_request", "login_name must be 3-64 characters of letters, digits and . _ @ + -")
			return
		}
	}
	if b.Phone != nil {
		phone = ""
		if strings.TrimSpace(*b.Phone) != "" {
			if phone = normalizePhone(*b.Phone); phone == "" {
				apiErr(w, 400, "invalid_request", "phone is not a valid number")
				return
			}
		}
	}
	if b.DisplayName != nil {
		name = *b.DisplayName
	}
	if _, err := s.pool.Exec(r.Context(), `UPDATE accounts SET login_name=$2, phone=NULLIF($3,''), display_name=$4, updated_at=now() WHERE subject=$1`,
		subject, login, phone, name); err != nil {
		s.uniqueOr500(w, err)
		return
	}
	s.apiGet(w, r, subject)
}

// withAccountTx loads the account FOR UPDATE and runs fn in one transaction.
func (s *Server) withAccountTx(w http.ResponseWriter, r *http.Request, subject string, fn func(ctx context.Context, tx pgx.Tx, a *Account) (int, any)) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		apiErr(w, 500, "server_error", "storage failure")
		return
	}
	defer tx.Rollback(ctx)
	a, err := scanAccount(tx.QueryRow(ctx, `SELECT `+accountCols+` FROM accounts WHERE subject=$1 FOR UPDATE`, subject))
	if err != nil {
		apiErr(w, 404, "not_found", "unknown subject")
		return
	}
	status, body := fn(ctx, tx, a)
	if status >= 400 {
		if m, ok := body.(map[string]string); ok {
			apiErr(w, status, m["error"], m["error_description"])
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		apiErr(w, 500, "server_error", "storage failure")
		return
	}
	jsonBytes(w, status, body)
}

func errBody(code, desc string) map[string]string {
	return map[string]string{"error": code, "error_description": desc}
}

// apiResetLink: LINK re-issues the set-up link of an ENROLLING account; MFA removes second
// factors; FULL removes the password too, ends every session
// and issues a reset link (2fa-design.md §9).
func (s *Server) apiResetLink(w http.ResponseWriter, r *http.Request, subject string) {
	var b struct {
		Scope string `json:"scope"`
	}
	if err := readJSON(r, &b); err != nil {
		apiErr(w, 400, "invalid_request", "body must be {scope: LINK|MFA|FULL}")
		return
	}
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		switch strings.ToUpper(b.Scope) {
		case "LINK":
			if a.State != StateEnrolling {
				return 409, errBody("not_enrolling", "LINK re-issues a set-up link only while the account is ENROLLING; use FULL")
			}
			link, exp, err := s.issueLink(ctx, tx, a.Subject, PurposeSetup, s.cfg.LinkTTL)
			if err != nil {
				return 500, errBody("server_error", "storage failure")
			}
			return 200, map[string]any{"subject": a.Subject, "scope": "LINK", "link": link, "expires_at": exp.Format(time.RFC3339)}
		case "MFA":
			deleted, err := tx.Exec(ctx, `DELETE FROM mfa_factors WHERE subject=$1`, a.Subject)
			if err != nil || s.revokeAll(ctx, tx, a, "mfa_reset") != nil {
				return 500, errBody("server_error", "storage failure")
			}
			if deleted.RowsAffected() > 0 && s.emitEvent(ctx, tx, a, EventMFARemoved, map[string]any{"method": "TOTP"}) != nil {
				return 500, errBody("server_error", "storage failure")
			}
			return 200, map[string]any{"subject": a.Subject, "scope": "MFA", "factors_removed": deleted.RowsAffected()}
		case "FULL":
			if a.State == StateDisabled {
				return 409, errBody("account_disabled", "enable the account before resetting it")
			}
			if _, err := tx.Exec(ctx, `UPDATE accounts SET password_hash=NULL, state='ENROLLING', failed_attempts=0,
				lock_count=0, locked_until=NULL, updated_at=now() WHERE subject=$1`, a.Subject); err != nil {
				return 500, errBody("server_error", "storage failure")
			}
			if err := s.revokeAll(ctx, tx, a, "credential_reset"); err != nil {
				return 500, errBody("server_error", "storage failure")
			}
			if _, err := tx.Exec(ctx, `DELETE FROM mfa_factors WHERE subject=$1`, a.Subject); err != nil {
				return 500, errBody("server_error", "storage failure")
			}
			link, exp, err := s.issueLink(ctx, tx, a.Subject, PurposeRecovery, s.cfg.LinkTTL)
			if err != nil {
				return 500, errBody("server_error", "storage failure")
			}
			return 200, map[string]any{"subject": a.Subject, "scope": "FULL", "link": link, "expires_at": exp.Format(time.RFC3339)}
		default:
			return 400, errBody("invalid_request", "scope must be LINK, MFA or FULL")
		}
	})
}

func (s *Server) apiDisable(w http.ResponseWriter, r *http.Request, subject string) {
	var b struct {
		Reason string `json:"reason"`
	}
	_ = readJSON(r, &b)
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		if _, err := tx.Exec(ctx, `UPDATE accounts SET state='DISABLED', disabled_reason=$2, updated_at=now() WHERE subject=$1`,
			a.Subject, truncate(b.Reason, 200)); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		if err := s.revokeAll(ctx, tx, a, "disabled"); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		return 200, map[string]any{"subject": a.Subject, "state": StateDisabled}
	})
}

func (s *Server) apiEnable(w http.ResponseWriter, r *http.Request, subject string) {
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		state := StateEnrolling
		if a.PasswordSet {
			state = StateActive
		}
		if _, err := tx.Exec(ctx, `UPDATE accounts SET state=$2, disabled_reason=NULL, failed_attempts=0, lock_count=0,
			locked_until=NULL, updated_at=now() WHERE subject=$1`, a.Subject, state); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		if a.locked(time.Now()) {
			if err := s.emitEvent(ctx, tx, a, EventUnlocked, map[string]any{"via": "enable"}); err != nil {
				return 500, errBody("server_error", "storage failure")
			}
		}
		return 200, map[string]any{"subject": a.Subject, "state": state}
	})
}

func (s *Server) apiRevokeSessions(w http.ResponseWriter, r *http.Request, subject string) {
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		if err := s.revokeAll(ctx, tx, a, "revoked"); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		return 200, map[string]any{"subject": a.Subject, "revoked": true}
	})
}

var mfaProfiles = map[string]bool{"PLATFORM_STAFF": true, "ORG_ADMIN": true, "ORG_MEMBER": true, "MERCHANT_OTP": true}

func (s *Server) apiMFAProfile(w http.ResponseWriter, r *http.Request, subject string) {
	var b struct {
		Profile        string `json:"profile"`
		OrgRequiresMFA bool   `json:"org_requires_mfa"`
	}
	if err := readJSON(r, &b); err != nil || !mfaProfiles[b.Profile] {
		apiErr(w, 400, "invalid_request", "body must be {profile: PLATFORM_STAFF|ORG_ADMIN|ORG_MEMBER|MERCHANT_OTP, org_requires_mfa}")
		return
	}
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		if _, err := tx.Exec(ctx, `UPDATE accounts SET mfa_profile=$2, org_requires_mfa=$3, updated_at=now() WHERE subject=$1`,
			a.Subject, b.Profile, b.OrgRequiresMFA); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		return 200, map[string]any{"subject": a.Subject, "mfa_profile": b.Profile, "org_requires_mfa": b.OrgRequiresMFA}
	})
}

// apiDelete removes the account (core "unlink"): sessions end first, then the row goes.
func (s *Server) apiDelete(w http.ResponseWriter, r *http.Request, subject string) {
	s.withAccountTx(w, r, subject, func(ctx context.Context, tx pgx.Tx, a *Account) (int, any) {
		if err := s.revokeAll(ctx, tx, a, "deleted"); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		if _, err := tx.Exec(ctx, `DELETE FROM accounts WHERE subject=$1`, a.Subject); err != nil {
			return 500, errBody("server_error", "storage failure")
		}
		return 200, map[string]any{"subject": a.Subject, "deleted": true}
	})
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

func (a *Account) String() string {
	return fmt.Sprintf("account(%s uid=%s %s)", a.Subject, a.UID, a.State)
}
