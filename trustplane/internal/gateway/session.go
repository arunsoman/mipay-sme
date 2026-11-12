// Package gateway implements the trustplane API gateway: deny-by-default
// route table, FAPI 2.0 token validation (Lane B) and the browser BFF
// (Lane A, §4 of docs/plans/fapi2-design.md).
//
// The BFF acts as a confidential FAPI client on behalf of the four portals.
// Browser calls arrive with a __Host-mp_sess cookie; the gateway holds the
// access token, refresh token and per-session DPoP key in gw_sessions and
// forwards API calls as DPoP-bound requests. Tokens are encrypted at rest.

package gateway

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ---------------------------------------------------------------------------
// Session model (§4.2)
// ---------------------------------------------------------------------------

// Session is an in-memory view of a browser session. Sensitive fields are
// encrypted at rest in gw_sessions.
type Session struct {
	ID            string    `json:"-"` // session_id, never returned to the browser
	Sub           string    `json:"sub"`
	UID           string    `json:"uid"` // core user id from the ID token (RBAC subject)
	ClientID      string    `json:"client_id"`
	CSRF          string    `json:"csrf"`
	OrgID         string    `json:"org_id,omitempty"` // org the user is acting in
	ACR           string    `json:"acr"`
	AMR           []string  `json:"amr,omitempty"`
	AuthTime      int64     `json:"auth_time,omitempty"`
	Issuer        string    `json:"iss,omitempty"`
	Audience      string    `json:"aud,omitempty"`

	// Token material (encrypted at rest).
	AccessToken  string `json:"-"`
	RefreshToken string `json:"-"`
	IDToken      string `json:"-"` // raw ID token JWT

	// Per-session DPoP key (§4.2): the gateway signs DPoP proofs for every
	// API call this session makes. cnf.jkt of the tokens equals the
	// thumbprint of this key.
	DPoPJKT         string `json:"-"`
	DPoPPrivateJWK  string `json:"-"` // JWK of the session's ES256 DPoP private key

	CreatedAt time.Time `json:"-"`
	ExpiresAt time.Time `json:"-"`  // absolute lifetime
	IdleAt    time.Time `json:"-"`  // last activity
	Revoked   bool      `json:"-"`
}

// SessionStore persists and loads sessions.
type SessionStore struct {
	pool *pgxpool.Pool
	enc  cipher.AEAD // AES-GCM for token material
}

// NewSessionStore opens the session table (idempotent DDL) and prepares the
// encryption AEAD from a 32-byte key (base64 in GATEWAY_SESSION_KEY, or
// derived from the gateway context secret).
func NewSessionStore(pool *pgxpool.Pool, encKey []byte) (*SessionStore, error) {
	block, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, fmt.Errorf("session: aes key: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("session: gcm: %w", err)
	}
	s := &SessionStore{pool: pool, enc: aead}

	// Idempotent DDL (same pattern as the IdP).
	ddl := []string{
		`CREATE TABLE IF NOT EXISTS gw_sessions (
			session_id   TEXT PRIMARY KEY,
			sub          TEXT NOT NULL,
			client_id    TEXT NOT NULL,
			csrf         TEXT NOT NULL,
			org_id       TEXT,
			acr          TEXT,
			amr          TEXT,
			auth_time    BIGINT,
			issuer       TEXT,
			audience     TEXT,
			access_token_enc   TEXT NOT NULL,
			refresh_token_enc  TEXT NOT NULL,
			id_token_enc   TEXT NOT NULL,
			dpop_jkt     TEXT NOT NULL,
			dpop_private_enc   TEXT NOT NULL,
			created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
			expires_at   TIMESTAMPTZ NOT NULL,
			idle_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
			revoked      BOOLEAN NOT NULL DEFAULT FALSE
		)`,
		`CREATE INDEX IF NOT EXISTS gw_sessions_sub_idx ON gw_sessions(sub)`,
		`ALTER TABLE gw_sessions ADD COLUMN IF NOT EXISTS uid TEXT NOT NULL DEFAULT ''`,
		`CREATE INDEX IF NOT EXISTS gw_sessions_expires_idx ON gw_sessions(expires_at)`,
	}
	ctx, cancel := newCtx(5 * time.Second)
	defer cancel()
	for _, stmt := range ddl {
		if _, err := pool.Exec(ctx, stmt); err != nil {
			return nil, fmt.Errorf("session: ddl: %w", err)
		}
	}
	log.Printf("[gateway] session store ready")
	return s, nil
}

// encrypt / decrypt with nonce prepended to ciphertext.
func (s *SessionStore) encrypt(plaintext string) (string, error) {
	nonce := make([]byte, 12)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	ct := s.enc.Seal(nonce, nonce, []byte(plaintext), nil)
	return base64.RawURLEncoding.EncodeToString(ct), nil
}

func (s *SessionStore) decrypt(ctB64 string) (string, error) {
	ct, err := base64.RawURLEncoding.DecodeString(ctB64)
	if err != nil {
		return "", err
	}
	if len(ct) < 12 {
		return "", errors.New("ciphertext too short")
	}
	nonce, data := ct[:12], ct[12:]
	pt, err := s.enc.Open(nil, nonce, data, nil)
	if err != nil {
		return "", err
	}
	return string(pt), nil
}

// Create stores a new session.
func (s *SessionStore) Create(ctx context.Context, ses *Session) error {
	atEnc, err := s.encrypt(ses.AccessToken)
	if err != nil {
		return err
	}
	rtEnc, err := s.encrypt(ses.RefreshToken)
	if err != nil {
		return err
	}
	idEnc, err := s.encrypt(ses.IDToken)
	if err != nil {
		return err
	}
	dpEnc, err := s.encrypt(ses.DPoPPrivateJWK)
	if err != nil {
		return err
	}
	amrJSON, _ := json.Marshal(ses.AMR)
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	_, err = s.pool.Exec(ctx, `
		INSERT INTO gw_sessions
			(session_id, sub, client_id, csrf, org_id, acr, amr, auth_time,
			 issuer, audience, access_token_enc, refresh_token_enc, id_token_enc,
			 dpop_jkt, dpop_private_enc, expires_at, idle_at, uid)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18)`,
		ses.ID, ses.Sub, ses.ClientID, ses.CSRF, ses.OrgID, ses.ACR, string(amrJSON),
		ses.AuthTime, ses.Issuer, ses.Audience, atEnc, rtEnc, idEnc,
		ses.DPoPJKT, dpEnc, ses.ExpiresAt, ses.IdleAt, ses.UID)
	return err
}

// Get loads a session by id, returning nil if missing/revoked/expired.
func (s *SessionStore) Get(ctx context.Context, id string) (*Session, error) {
	row := sessionRow{}
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	err := s.pool.QueryRow(ctx, `
		SELECT session_id, sub, client_id, csrf, org_id, acr, amr, auth_time,
			issuer, audience, access_token_enc, refresh_token_enc, id_token_enc,
			dpop_jkt, dpop_private_enc, created_at, expires_at, idle_at, revoked, uid
		FROM gw_sessions WHERE session_id=$1`, id).
		Scan(&row.SessionID, &row.Sub, &row.ClientID, &row.CSRF, &row.OrgID,
			&row.ACR, &row.AMR, &row.AuthTime, &row.Issuer, &row.Audience,
			&row.AccessToken, &row.RefreshToken, &row.IDToken,
			&row.DPoPJKT, &row.DPoPPrivate, &row.CreatedAt, &row.ExpiresAt,
			&row.IdleAt, &row.Revoked, &row.UID)
	if err != nil {
		return nil, err
	}
	if row.Revoked || time.Now().After(row.ExpiresAt) {
		return nil, errors.New("session revoked or expired")
	}
	ses := &Session{
		ID: row.SessionID, Sub: row.Sub, UID: row.UID, ClientID: row.ClientID, CSRF: row.CSRF,
		OrgID: row.OrgID, ACR: row.ACR, AuthTime: row.AuthTime,
		Issuer: row.Issuer, Audience: row.Audience,
		DPoPJKT: row.DPoPJKT, CreatedAt: row.CreatedAt,
		ExpiresAt: row.ExpiresAt, IdleAt: row.IdleAt, Revoked: row.Revoked,
	}
	if row.AMR != "" {
		_ = json.Unmarshal([]byte(row.AMR), &ses.AMR)
	}
	if row.AccessToken != "" {
		ses.AccessToken, err = s.decrypt(row.AccessToken)
		if err != nil {
			return nil, err
		}
	}
	if row.RefreshToken != "" {
		ses.RefreshToken, err = s.decrypt(row.RefreshToken)
		if err != nil {
			return nil, err
		}
	}
	if row.IDToken != "" {
		ses.IDToken, err = s.decrypt(row.IDToken)
		if err != nil {
			return nil, err
		}
	}
	if row.DPoPPrivate != "" {
		ses.DPoPPrivateJWK, err = s.decrypt(row.DPoPPrivate)
		if err != nil {
			return nil, err
		}
	}
	return ses, nil
}

// Touch updates idle_at and extends the absolute expiry while the session is
// still within its window. Returns an error if the session is gone.
func (s *SessionStore) Touch(ctx context.Context, id string, idle time.Duration) error {
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	_, err := s.pool.Exec(ctx, `
		UPDATE gw_sessions
		SET idle_at = now(), expires_at = now() + $2
		WHERE session_id=$1 AND NOT revoked AND expires_at > now()`, id, idle)
	return err
}

// Delete removes a session (logout).
func (s *SessionStore) Delete(ctx context.Context, id string) error {
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	_, err := s.pool.Exec(ctx, `DELETE FROM gw_sessions WHERE session_id=$1`, id)
	return err
}

// RevokeBySub removes all sessions for a user (back-channel logout, disable).
// Returns how many live sessions were revoked.
func (s *SessionStore) RevokeBySub(ctx context.Context, sub string) (int64, error) {
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	tag, err := s.pool.Exec(ctx, `UPDATE gw_sessions SET revoked=TRUE WHERE sub=$1 AND NOT revoked`, sub)
	return tag.RowsAffected(), err
}

// sessionRow mirrors gw_sessions columns.
type sessionRow struct {
	SessionID     string
	Sub           string
	UID           string
	ClientID      string
	CSRF          string
	OrgID         string
	ACR           string
	AMR           string
	AuthTime      int64
	Issuer        string
	Audience      string
	AccessToken   string
	RefreshToken  string
	IDToken       string
	DPoPJKT       string
	DPoPPrivate   string
	CreatedAt     time.Time
	ExpiresAt     time.Time
	IdleAt        time.Time
	Revoked       bool
}

// ensureSessionKey derives a 32-byte encryption key: base64(GATEWAY_SESSION_KEY)
// if set, otherwise SHA-256(context_secret).
func ensureSessionKey(sessionKeyB64 string, contextSecret []byte) ([]byte, error) {
	if sessionKeyB64 != "" {
		b, err := base64.RawURLEncoding.DecodeString(sessionKeyB64)
		if err != nil {
			return nil, err
		}
		if len(b) != 32 {
			return nil, errors.New("GATEWAY_SESSION_KEY must decode to 32 bytes")
		}
		return b, nil
	}
	sum := sha256.Sum256(contextSecret)
	return sum[:], nil
}

// newCtx returns a short-lived context for session DB calls.
func newCtx(d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), d)
}

// SetDPoPKey stores the per-session DPoP private key (PEM) so the gateway can
// sign DPoP proofs for this session's API calls.
func (s *SessionStore) SetDPoPKey(ctx context.Context, sessionID, privatePEM string) error {
	enc, err := s.encrypt(privatePEM)
	if err != nil {
		return err
	}
	ctx, cancel := newCtx(3 * time.Second)
	defer cancel()
	_, err = s.pool.Exec(ctx, `UPDATE gw_sessions SET dpop_private_enc=$2 WHERE session_id=$1`,
		sessionID, enc)
	return err
}
