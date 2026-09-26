// Package resilience decides what happens when the distributed limiter cannot
// answer. This is EXERCISE 5 (see docs/curriculum.md).
//
// A Redis-backed limiter fails in two ways: it returns an error quickly
// (connection refused) or it returns an error slowly (timeout under a network
// partition or an overloaded node). Both must be handled without making the
// protected service worse than it would be with no rate limiter at all.
//
// Modes:
//
//	Open    allow every request while the backend is failing. Availability over
//	        protection; the usual choice for user-facing APIs.
//	Closed  deny every request while the backend is failing. Protection over
//	        availability; appropriate when the downstream is fragile.
//	Local   fall back to an in-process limiter with a scaled-down limit
//	        (Limit * LocalShare, where LocalShare ~= 1/replicas). Approximate
//	        enforcement is better than none.
//
// Circuit breaker: paying the full Redis timeout on every request during an
// outage adds latency to every call for the whole outage. After Threshold
// consecutive failures the breaker opens and requests go straight to the
// fallback for Cooldown; then one probe request is let through to test
// recovery. This is the same pattern as Hystrix / resilience4j in the Java
// world; implement it by hand here so you know what is inside the box.
//
// Design questions:
//
//   - Does a context deadline exceeded count as a "failure" for the breaker?
//   - When the breaker is half-open and the probe succeeds, what happens to
//     the requests that arrived while the probe was in flight?
//   - LocalShare scales Rate; should it also scale Burst? What about a limit
//     with Rate 1 and share 0.33?
//   - Is the resilience layer itself safe for concurrent use? (The tests will
//     find out.)
package resilience

import (
	"context"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// Mode selects the failure behaviour.
type Mode string

// Supported modes.
const (
	Open   Mode = "open"
	Closed Mode = "closed"
	Local  Mode = "local"
)

// ParseMode validates a mode string from config.
func ParseMode(s string) (Mode, bool) {
	switch Mode(s) {
	case Open, Closed, Local:
		return Mode(s), true
	}
	return "", false
}

// Options configure the resilience layer.
type Options struct {
	Mode Mode
	// Fallback is the local limiter used in Local mode. Required for Local.
	Fallback limiter.Limiter
	// LocalShare scales limits handed to Fallback. Defaults to 1.0.
	LocalShare float64
	// Threshold is how many consecutive primary failures open the breaker.
	// Defaults to 5. Zero threshold disables the breaker.
	Threshold int
	// Cooldown is how long the breaker stays open before probing. Defaults to 5s.
	Cooldown time.Duration
	// Clock drives the breaker's timers. Defaults to clock.Real{}.
	Clock clock.Clock
	// OnFallback, if set, is called every time a decision is made by the
	// fallback path instead of the primary. The server uses it for metrics.
	OnFallback func(mode Mode, allowed bool)
}

func (o Options) withDefaults() Options {
	if o.LocalShare <= 0 {
		o.LocalShare = 1
	}
	if o.Threshold == 0 {
		o.Threshold = 5
	}
	if o.Cooldown <= 0 {
		o.Cooldown = 5 * time.Second
	}
	if o.Clock == nil {
		o.Clock = clock.Real{}
	}
	return o
}

// Resilient wraps a primary limiter with failure handling and a circuit breaker.
type Resilient struct {
	primary limiter.Limiter
	opts    Options
	// Add your breaker state here.
}

// New wraps primary.
func New(primary limiter.Limiter, opts Options) *Resilient {
	return &Resilient{primary: primary, opts: opts.withDefaults()}
}

// Name implements limiter.Limiter; the wrapper is transparent for metrics.
func (r *Resilient) Name() string { return r.primary.Name() }

// Allow implements limiter.Limiter.
func (r *Resilient) Allow(ctx context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	_ = ctx
	_ = key
	_ = limit
	return limiter.Decision{}, limiter.ErrNotImplemented
}

// State reports the breaker state for tests and the /healthz endpoint:
// "closed" (healthy, calling primary), "open" (using fallback), "half_open"
// (a probe is being allowed through).
func (r *Resilient) State() string { return "closed" }
