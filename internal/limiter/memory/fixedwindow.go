// Package memory contains single-process rate limiters that keep their state in
// Go maps guarded by a mutex. They are the simplest possible implementations
// and exist for three reasons:
//
//  1. To learn each algorithm's data structure without Redis in the way.
//  2. To serve as the local fallback when Redis is unreachable.
//  3. To give the Redis implementations something to be benchmarked against.
//
// FixedWindow is fully implemented as a worked example. TokenBucket,
// SlidingLog and SlidingCounter are curriculum exercises (see docs/curriculum.md).
package memory

import (
	"context"
	"sync"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// FixedWindow divides time into aligned windows of limit.Period and allows at
// most limit.Rate requests per window. It is the cheapest algorithm to run
// (one integer per key) and the least accurate: a client can send 2x the limit
// in a short span by straddling a window boundary.
//
// Window alignment is absolute, i.e. window index = floor(now / period), so
// every process and every key agrees on where the boundaries fall. That is
// what makes the same idea trivially portable to Redis later.
//
// Concurrency: a single mutex guards the whole map. That is a deliberate
// starting point, not a recommendation; the curriculum asks you to measure it
// and try lock striping.
type FixedWindow struct {
	clk clock.Clock

	mu      sync.Mutex
	windows map[string]*fixedWindowState
}

type fixedWindowState struct {
	index     int64     // floor(now / period) the count belongs to
	count     int       // requests seen in that window
	expiresAt time.Time // end of the window; used by Sweep to evict idle keys
}

// NewFixedWindow returns an empty limiter driven by clk.
func NewFixedWindow(clk clock.Clock) *FixedWindow {
	return &FixedWindow{clk: clk, windows: make(map[string]*fixedWindowState)}
}

// Name implements limiter.Limiter.
func (f *FixedWindow) Name() string { return "fixed_window" }

// Allow implements limiter.Limiter. Burst is ignored: the window itself is the
// only bound on burstiness.
func (f *FixedWindow) Allow(_ context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	now := f.clk.Now()
	period := int64(limit.Period)
	index := now.UnixNano() / period
	windowEnd := time.Unix(0, (index+1)*period)
	reset := windowEnd.Sub(now)

	f.mu.Lock()
	defer f.mu.Unlock()

	st, ok := f.windows[key]
	if !ok || st.index != index {
		// First request in a new window: start counting from zero.
		st = &fixedWindowState{index: index, expiresAt: windowEnd}
		f.windows[key] = st
	}

	if st.count >= limit.Rate {
		return limiter.Decision{
			Allowed:    false,
			Limit:      limit.Rate,
			Remaining:  0,
			RetryAfter: reset,
			ResetAfter: reset,
		}, nil
	}

	st.count++
	return limiter.Decision{
		Allowed:    true,
		Limit:      limit.Rate,
		Remaining:  limit.Rate - st.count,
		ResetAfter: reset,
	}, nil
}

// Sweep removes state for keys whose window has already ended. Without it the
// map grows forever as new keys appear. Returns the number of entries removed.
func (f *FixedWindow) Sweep() int {
	now := f.clk.Now()
	f.mu.Lock()
	defer f.mu.Unlock()
	removed := 0
	for k, st := range f.windows {
		if !st.expiresAt.After(now) {
			delete(f.windows, k)
			removed++
		}
	}
	return removed
}

// Len reports how many keys currently hold state. Useful for tests and metrics.
func (f *FixedWindow) Len() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.windows)
}

// RunJanitor calls Sweep every interval until ctx is cancelled. Start it as a
// goroutine from wherever the limiter is constructed.
func RunJanitor(ctx context.Context, interval time.Duration, sweep func() int) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			sweep()
		}
	}
}
