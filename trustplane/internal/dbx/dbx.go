// Package dbx holds Postgres helpers shared by all services.
package dbx

import (
	"context"
	"fmt"
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
