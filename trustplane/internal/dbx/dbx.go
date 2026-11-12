// Package dbx holds Postgres helpers shared by all services.
package dbx

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Pool creates a connection pool with sane defaults.
func Pool(ctx context.Context, dsn string, maxConns int32) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("dbx: bad DSN: %w", err)
	}
	if maxConns > 0 {
		cfg.MaxConns = maxConns
	}
	cfg.MinConns = 1
	cfg.MaxConnIdleTime = 10 * time.Minute
	return pgxpool.NewWithConfig(ctx, cfg)
}

// Migrate applies idempotent DDL statements at boot.
func Migrate(ctx context.Context, pool *pgxpool.Pool, ddl []string) error {
	for _, s := range ddl {
		if _, err := pool.Exec(ctx, s); err != nil {
			head := s
			if len(head) > 60 {
				head = head[:60]
			}
			return fmt.Errorf("dbx: migrate %q...: %w", head, err)
		}
	}
	return nil
}

// Healthy pings the database with a short deadline (for /healthz).
func Healthy(pool *pgxpool.Pool) error {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	return pool.Ping(ctx)
}

// EnsureDatabase creates the DSN's database when it does not exist yet (services added
// after the Postgres volume was initialised, e.g. comms). It connects to the maintenance
// database "postgres" with the same credentials.
func EnsureDatabase(ctx context.Context, dsn string) error {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return fmt.Errorf("dbx: bad DSN: %w", err)
	}
	name := cfg.ConnConfig.Database
	if name == "" || name == "postgres" {
		return nil
	}
	cfg.ConnConfig.Database = "postgres"
	cfg.MaxConns = 1
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return err
	}
	defer pool.Close()
	var exists bool
	if err := pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname=$1)`, name).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return nil
	}
	_, err = pool.Exec(ctx, `CREATE DATABASE "`+strings.ReplaceAll(name, `"`, `""`)+`"`)
	if err != nil && !strings.Contains(err.Error(), "already exists") {
		return err
	}
	return nil
}
