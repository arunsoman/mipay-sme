// Package breaker implements a minimal fail-closed circuit breaker used in
// front of remote authorization dependencies (introspection, RBAC).
package breaker

import (
	"sync"
	"time"
)

type Breaker struct {
	mu        sync.Mutex
	fails     int
	threshold int
	openFor   time.Duration
	openUntil time.Time
}

func New(threshold int, openFor time.Duration) *Breaker {
	return &Breaker{threshold: threshold, openFor: openFor}
}

// Allow reports whether a call may proceed right now.
func (b *Breaker) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return time.Now().After(b.openUntil)
}

// Record notes the outcome of a call.
func (b *Breaker) Record(ok bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if ok {
		b.fails = 0
		return
	}
	b.fails++
	if b.fails >= b.threshold {
		b.openUntil = time.Now().Add(b.openFor)
		b.fails = 0
	}
}
