// Command comms runs the comms plane (docs/plans/comms-design.md, phase B0).
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"trustplane/internal/commsplane"
	"trustplane/internal/dbx"
)

func main() {
	cfg, err := commsplane.FromEnv()
	if err != nil {
		log.Fatalf("comms config: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// Postgres may still be starting: retry the database for up to a minute before giving up.
	var err2 error
	for attempt := 1; attempt <= 30; attempt++ {
		if err2 = dbx.EnsureDatabase(ctx, cfg.DSN); err2 == nil {
			break
		}
		log.Printf("comms db not ready (attempt %d): %v", attempt, err2)
		time.Sleep(2 * time.Second)
	}
	if err2 != nil {
		log.Fatalf("comms db create: %v", err2)
	}
	pool, err := dbx.Pool(ctx, cfg.DSN, 8)
	if err != nil {
		log.Fatalf("comms db: %v", err)
	}
	srv, err := commsplane.New(cfg, pool)
	if err != nil {
		log.Fatalf("comms: %v", err)
	}
	if err := srv.Setup(ctx); err != nil {
		log.Fatalf("comms setup: %v", err)
	}
	log.Printf("[comms] environment=%s listening=%s", cfg.Environment, cfg.Listen)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sig; cancel() }()
	if err := srv.Run(ctx); err != nil {
		log.Fatalf("comms serve: %v", err)
	}
}
