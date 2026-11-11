package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"trustplane/internal/clients"
	"trustplane/internal/dbx"
	"trustplane/internal/idpserver"
)

func main() {
	cfg, err := idpserver.FromEnv()
	if err != nil {
		log.Fatalf("idp config: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := dbx.Pool(ctx, cfg.DSN, cfg.MaxConns)
	if err != nil {
		log.Fatalf("idp db: %v", err)
	}
	if err := dbx.Migrate(ctx, pool, idpserver.DDL); err != nil {
		log.Fatalf("idp migrate: %v", err)
	}
	reg, err := clients.Load(cfg.ClientsFile)
	if err != nil {
		log.Fatalf("idp clients: %v", err)
	}
	keys, err := idpserver.LoadKeyring(cfg.SigningDir)
	if err != nil {
		log.Fatalf("idp keyring: %v", err)
	}
	srv := idpserver.NewServer(cfg, pool, reg, keys)
	if err := srv.SeedUsers(ctx); err != nil {
		log.Fatalf("idp seed users: %v", err)
	}
	go srv.SweepLoop(ctx)
	log.Printf("[idp] issuer=%s listening=%s", cfg.Issuer, cfg.Listen)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sig; cancel() }()

	if err := srv.Run(ctx); err != nil {
		log.Fatalf("idp serve: %v", err)
	}
}
