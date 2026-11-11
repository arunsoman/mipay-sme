// Package ratelimit implements a tiny sharded-free token-bucket limiter
// (in-memory; for multi-gateway deployments back this with Redis).
package ratelimit

import (
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

type Limiter struct {
	mu  sync.Mutex
	b   map[string]*bucket
	ops int
}

func New() *Limiter { return &Limiter{b: map[string]*bucket{}} }

// Allow consumes one token from the bucket identified by key.
// perMinute refill rate, burst capacity. Non-positive values disable limits.
func (l *Limiter) Allow(key string, perMinute float64, burst float64) bool {
	if perMinute <= 0 || burst <= 0 {
		return true
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	bt, ok := l.b[key]
	if !ok {
		l.b[key] = &bucket{tokens: burst, last: now}
		bt = l.b[key]
	}
	rate := perMinute / 60.0
	bt.tokens += now.Sub(bt.last).Seconds() * rate
	if bt.tokens > burst {
		bt.tokens = burst
	}
	bt.last = now
	if bt.tokens < 1 {
		l.ops++
		if l.ops%2048 == 0 {
			l.sweepLocked(now)
		}
		return false
	}
	bt.tokens--
	l.ops++
	if l.ops%2048 == 0 {
		l.sweepLocked(now)
	}
	return true
}

func (l *Limiter) sweepLocked(now time.Time) {
	for k, v := range l.b {
		if now.Sub(v.last) > 15*time.Minute {
			delete(l.b, k)
		}
	}
}
