package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"trustplane/internal/gateway"
)

func main() {
	cfg := os.Getenv("GATEWAY_CONFIG")
	if cfg == "" {
		cfg = "/config/gateway/routes.yaml"
	}
	listen := os.Getenv("GATEWAY_LISTEN")
	if listen == "" {
		listen = ":8443"
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sig; cancel() }()
	log.Printf("[gateway] starting, config=%s listen=%s", cfg, listen)
	if err := gateway.Run(ctx, cfg, listen); err != nil {
		log.Fatalf("gateway: %v", err)
	}
}
