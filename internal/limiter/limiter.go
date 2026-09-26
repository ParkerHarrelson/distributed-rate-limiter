// Package limiter defines the contract every rate-limiting algorithm in this
// project implements, whether it keeps state in local memory or in Redis.
//
// The interface is deliberately small: a limiter is asked "may this key make
// one more request right now under this limit?" and must answer atomically.
// Everything else (which key, which limit, what to do on error) is policy and
// lives outside this package.
package limiter

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// ErrNotImplemented is returned by exercise stubs that have not been written
// yet. The conformance suite recognises it and skips instead of failing so CI
// stays green while the curriculum is in progress.
var ErrNotImplemented = errors.New("limiter: not implemented")

// Limit describes how much traffic a single key may generate.
//
// Rate events are permitted per Period on a sustained basis. Burst is the
// maximum number of events permitted instantaneously; for windowed algorithms
// it is ignored (the window itself bounds the burst) and for token bucket it is
// the bucket capacity. A Burst of 0 means "same as Rate".
type Limit struct {
	Rate   int
	Period time.Duration
	Burst  int
}

// Capacity returns the effective burst size.
func (l Limit) Capacity() int {
	if l.Burst > 0 {
		return l.Burst
	}
	return l.Rate
}

// Validate reports whether the limit is usable.
func (l Limit) Validate() error {
	if l.Rate <= 0 {
		return fmt.Errorf("limit: rate must be > 0, got %d", l.Rate)
	}
	if l.Period <= 0 {
		return fmt.Errorf("limit: period must be > 0, got %s", l.Period)
	}
	if l.Burst < 0 {
		return fmt.Errorf("limit: burst must be >= 0, got %d", l.Burst)
	}
	return nil
}

// String renders a limit the way it appears in config, e.g. "100/1m (burst 20)".
func (l Limit) String() string {
	if l.Burst > 0 && l.Burst != l.Rate {
		return fmt.Sprintf("%d/%s (burst %d)", l.Rate, l.Period, l.Burst)
	}
	return fmt.Sprintf("%d/%s", l.Rate, l.Period)
}

// Decision is the outcome of a single Allow call. The fields map directly onto
// the de-facto standard X-RateLimit-* / Retry-After response headers.
type Decision struct {
	// Allowed is true if the request may proceed.
	Allowed bool
	// Limit echoes the configured capacity (X-RateLimit-Limit).
	Limit int
	// Remaining is how many more requests would be allowed right now
	// (X-RateLimit-Remaining). Zero when denied.
	Remaining int
	// RetryAfter is how long the caller should wait before the next request
	// has a chance of being allowed. Zero when allowed.
	RetryAfter time.Duration
	// ResetAfter is how long until the key's budget is completely restored
	// (X-RateLimit-Reset).
	ResetAfter time.Duration
}

// Limiter is implemented by every algorithm.
//
// Allow atomically records one request against key and reports whether it is
// permitted under limit. Implementations must be safe for concurrent use and
// must never allow more than limit.Capacity() requests within the limit's
// window regardless of how many goroutines or service instances call them.
//
// A non-nil error means the limiter could not make a decision (for example the
// backing store is unreachable). Callers decide how to fail; see the policy
// package.
type Limiter interface {
	Allow(ctx context.Context, key string, limit Limit) (Decision, error)
	// Name identifies the algorithm for metrics and logs, e.g. "token_bucket".
	Name() string
}

// Func adapts a plain function into a Limiter; handy in tests and middleware.
type Func struct {
	AllowFn func(ctx context.Context, key string, limit Limit) (Decision, error)
	Label   string
}

// Allow calls AllowFn.
func (f Func) Allow(ctx context.Context, key string, limit Limit) (Decision, error) {
	return f.AllowFn(ctx, key, limit)
}

// Name returns Label.
func (f Func) Name() string { return f.Label }
