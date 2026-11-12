package gateway

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"

	"trustplane/internal/breaker"
	"trustplane/internal/mtlsx"
	"trustplane/internal/pb"
)

// rbacClient is the gateway's policy decision point client: gRPC over
// HTTP/2+mTLS with a short-lived decision cache and a server-push
// invalidation stream (Watch) so revocations take effect sub-second.
type rbacClient struct {
	cc       pb.RbacClient
	conn     *grpc.ClientConn
	brk      *breaker.Breaker
	timeout  time.Duration
	cacheTTL time.Duration

	mu    sync.Mutex
	cache map[string]rbacEntry
	perms map[string]permsEntry
}

type rbacEntry struct {
	allowed bool
	exp     time.Time
}
type permsEntry struct {
	ops []string
	exp time.Time
}

func dialRBAC(cfg RBACGrpcCfg) (*rbacClient, error) {
	tlsCfg, err := mtlsx.MeshClientConfig(cfg.ClientCert, cfg.ClientKey, cfg.CA, cfg.ServerName)
	if err != nil {
		return nil, fmt.Errorf("gateway: rbac tls: %w", err)
	}
	timeout := time.Duration(cfg.TimeoutMS) * time.Millisecond
	if timeout == 0 {
		timeout = 400 * time.Millisecond
	}
	conn, err := grpc.NewClient(cfg.Endpoint,
		grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Time:                15 * time.Second,
			Timeout:             5 * time.Second,
			PermitWithoutStream: true,
		}),
	)
	if err != nil {
		return nil, err
	}
	return &rbacClient{
		cc:       pb.NewRbacClient(conn),
		conn:     conn,
		brk:      breaker.New(5, 5*time.Second),
		timeout:  timeout,
		cacheTTL: 5 * time.Second,
		cache:    map[string]rbacEntry{},
		perms:    map[string]permsEntry{},
	}, nil
}

// Warmup opens the mTLS channel and waits until RBAC answers (cold JVM + TLS handshake can
// exceed the per-decision timeout), so the first routed request does not fail with 503.
func (c *rbacClient) Warmup(ctx context.Context, max time.Duration) {
	deadline := time.Now().Add(max)
	for time.Now().Before(deadline) && ctx.Err() == nil {
		pctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, err := c.cc.Ping(pctx, &pb.PingRequest{})
		cancel()
		if err == nil {
			log.Printf("[gateway] rbac decision plane ready")
			return
		}
		time.Sleep(time.Second)
	}
	log.Printf("[gateway] rbac decision plane not answering after %v; decisions fail closed until it does", max)
}

// Check decides subject/operation/tenant. fresh=true skips the cache
// (required for sensitive writes). Dependency failure -> error (caller 503s).
func (c *rbacClient) Check(ctx context.Context, subject, tenant, op string, fresh bool) (bool, error) {
	key := subject + "\x00" + tenant + "\x00" + op
	now := time.Now()
	if !fresh {
		c.mu.Lock()
		e, ok := c.cache[key]
		c.mu.Unlock()
		if ok && e.exp.After(now) {
			return e.allowed, nil
		}
	}
	if !c.brk.Allow() {
		return false, fmt.Errorf("rbac: breaker open")
	}
	cctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	resp, err := c.cc.Check(cctx, &pb.CheckRequest{Subject: subject, Tenant: tenant, Operation: op, Fresh: fresh})
	if err != nil {
		c.brk.Record(false)
		return false, fmt.Errorf("rbac: %w", err)
	}
	c.brk.Record(true)
	if !fresh {
		c.mu.Lock()
		if len(c.cache) > 100000 {
			c.cache = map[string]rbacEntry{}
		}
		c.cache[key] = rbacEntry{allowed: resp.Allowed, exp: now.Add(c.cacheTTL)}
		c.mu.Unlock()
	}
	return resp.Allowed, nil
}

// Perms lists everything the subject may do in the tenant (used for the
// context header so business services can enforce record/field policies).
func (c *rbacClient) Perms(ctx context.Context, subject, tenant string) ([]string, error) {
	key := subject + "\x00" + tenant
	c.mu.Lock()
	e, ok := c.perms[key]
	c.mu.Unlock()
	if ok && e.exp.After(time.Now()) {
		return e.ops, nil
	}
	if !c.brk.Allow() {
		return nil, fmt.Errorf("rbac: breaker open")
	}
	cctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	resp, err := c.cc.ListPermissions(cctx, &pb.ListPermissionsRequest{Subject: subject, Tenant: tenant})
	if err != nil {
		return nil, fmt.Errorf("rbac: %w", err)
	}
	c.mu.Lock()
	c.perms[key] = permsEntry{ops: resp.Operations, exp: time.Now().Add(c.cacheTTL)}
	c.mu.Unlock()
	return resp.Operations, nil
}

func splitNul(s string) []string { return strings.Split(s, "\x00") }

// invalidate purges cached decisions/permissions when RBAC pushes a change.
// subjects empty => whole tenant (role or tenant-level change).
func (c *rbacClient) invalidate(ev *pb.WatchEvent) {
	c.mu.Lock()
	for key := range c.cache {
		p := splitNul(key)
		if len(p) < 3 {
			continue
		}
		if c.hit(p[0], p[1], ev) {
			delete(c.cache, key)
		}
	}
	for key := range c.perms {
		p := splitNul(key)
		if len(p) < 2 {
			continue
		}
		if c.hit(p[0], p[1], ev) {
			delete(c.perms, key)
		}
	}
	c.mu.Unlock()
	log.Printf("[gateway] rbac watch: tenant=%s subjects=%d invalidated", ev.Tenant, len(ev.Subjects))
}

func (c *rbacClient) hit(subject, tenant string, ev *pb.WatchEvent) bool {
	tenantHit := ev.Tenant == "" || ev.Tenant == tenant
	subHit := len(ev.Subjects) == 0 || containsStr(ev.Subjects, subject)
	return tenantHit && subHit
}

func containsStr(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// WatchLoop keeps the invalidation stream alive with reconnects.
func (c *rbacClient) WatchLoop(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		stream, err := c.cc.Watch(ctx, &pb.WatchRequest{})
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		for {
			ev, err := stream.Recv()
			if err != nil {
				break
			}
			c.invalidate(ev)
		}
		time.Sleep(1 * time.Second)
	}
}
