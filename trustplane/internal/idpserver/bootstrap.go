package idpserver

import (
	"context"
	"fmt"
	"log"
	"os"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
)

// The clean-slate bootstrap installer (2026-11-13 redesign: no yaml seed anywhere).
//
// A fresh IdP store holds NO accounts at all, so nobody could sign in to set up
// the first RBAC role. Instead, on startup with an EMPTY
// accounts table the IdP mints exactly one installer account (with a stable,
// predictable subject). The installer signs in, and while the RBAC clean-slate window is open
// (see rbac-service Decisions.bootstrapWindow) creates the first role with every
// permission, and that role's first assignment.
//
// Rules:
//   - The account is created ONLY while the accounts table is empty; the moment any account
//     exists (including a previously created installer), booting creates nothing. A restart
//     after installation therefore creates no user.
//   - It is a bootstrap credential: the installer should disable it once installation
//     is complete.
func BootstrapInstaller(ctx context.Context, pool *pgxpool.Pool) error {
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM accounts`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	password, err := bootstrapPassword(os.Getenv("IDP_BOOTSTRAP_PASSWORD"))
	if err != nil {
		return err
	}
	subject := seedSubject("installer") // stable: acc_seed_...
	hash, err := hashPassword(password)
	if err != nil {
		return err
	}
	tag, err := pool.Exec(ctx, `INSERT INTO accounts (subject, uid, login_name, phone, display_name, password_hash, state, password_changed_at, seeded)
		VALUES ($1,'installer','installer',NULL,'Installer',$2,'ACTIVE',now(),TRUE)
		ON CONFLICT DO NOTHING`, subject, hash)
	if err != nil {
		return err
	}
	if tag.RowsAffected() > 0 {
		log.Printf("[idp] clean-slate bootstrap: created the installer account (login_name=installer, subject=%s). "+
			"It exists only because the store was empty; rotate or disable it after installation.", subject)
	}
	return nil
}

func bootstrapPassword(value string) (string, error) {
	if len(value) < 16 || strings.TrimSpace(value) != value {
		return "", fmt.Errorf("IDP_BOOTSTRAP_PASSWORD must be set to a private value of at least 16 characters with no surrounding whitespace on a clean IdP store")
	}
	return value, nil
}
