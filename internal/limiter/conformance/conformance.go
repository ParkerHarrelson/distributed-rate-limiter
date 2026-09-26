// Package conformance is a reusable test suite that every Limiter implementation
// must pass. Run it from an algorithm's _test.go file:
//
//	conformance.Run(t, conformance.Harness{
//	    Semantics: conformance.TokenBucket,
//	    Unit:      10 * time.Second,
//	    New: func(t *testing.T) (limiter.Limiter, conformance.Clock) {
//	        clk := clock.NewFake(time.Unix(1_700_000_000, 0))
//	        return memory.NewTokenBucket(clk), clk
//	    },
//	})
//
// The suite is the "grader" for the curriculum: an exercise is complete when
// its conformance tests pass. Stubs that return limiter.ErrNotImplemented are
// reported as skipped rather than failed.
package conformance

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// Semantics selects the algorithm-family-specific assertions.
type Semantics int

const (
	// FixedWindow counts requests in aligned, non-overlapping windows.
	FixedWindow Semantics = iota
	// SlidingLog keeps an exact timestamp log; never allows a boundary burst.
	SlidingLog
	// SlidingCounter approximates a sliding window from two fixed counters.
	SlidingCounter
	// TokenBucket refills continuously and permits bursts up to capacity.
	TokenBucket
)

func (s Semantics) String() string {
	switch s {
	case FixedWindow:
		return "fixed_window"
	case SlidingLog:
		return "sliding_log"
	case SlidingCounter:
		return "sliding_counter"
	case TokenBucket:
		return "token_bucket"
	}
	return fmt.Sprintf("Semantics(%d)", int(s))
}

// Clock lets the suite read and move the limiter's notion of time. A
// clock.Fake satisfies it directly; real-clock (Redis) harnesses use
// SleepClock.
type Clock interface {
	Now() time.Time
	Advance(d time.Duration)
}

// SleepClock is a Clock for limiters that read the real wall clock: Advance
// actually sleeps.
type SleepClock struct{}

// Now returns time.Now().
func (SleepClock) Now() time.Time { return time.Now() }

// Advance sleeps for d.
func (SleepClock) Advance(d time.Duration) { time.Sleep(d) }

// Harness tells the suite how to build and drive the limiter under test.
type Harness struct {
	Semantics Semantics
	// New must return a fresh, empty limiter and the clock that drives it.
	// It is called once per subtest so no state leaks between tests.
	New func(t *testing.T) (limiter.Limiter, Clock)
	// Unit is the period used for every limit in the suite. Fake clocks can use
	// anything (10s reads nicely). Real clocks should use something small like
	// 1s so the suite finishes quickly; steps are fractions of Unit.
	Unit time.Duration
	// RealClock relaxes assertions that depend on time *not* having advanced,
	// which cannot be guaranteed when sleeping.
	RealClock bool
}

func (h Harness) epsilon() time.Duration { return h.Unit / 20 }

// Run executes the full suite as subtests of t.
func Run(t *testing.T, h Harness) {
	t.Helper()
	if h.Unit <= 0 {
		h.Unit = 10 * time.Second
	}
	if h.New == nil {
		t.Fatal("conformance: Harness.New is required")
	}

	t.Run("AllowsUpToCapacity", func(t *testing.T) { testAllowsUpToCapacity(t, h) })
	t.Run("KeysAreIndependent", func(t *testing.T) { testKeysAreIndependent(t, h) })
	t.Run("ConcurrentNeverExceedsCapacity", func(t *testing.T) { testConcurrent(t, h) })
	t.Run("ResetAfterIsHonest", func(t *testing.T) { testResetAfterIsHonest(t, h) })
	t.Run("RetryAfterIsHonest", func(t *testing.T) { testRetryAfterIsHonest(t, h) })

	switch h.Semantics {
	case FixedWindow:
		t.Run("BoundaryBurstIsAllowed", func(t *testing.T) { testFixedWindowBoundaryBurst(t, h) })
	case SlidingLog:
		t.Run("NoBoundaryBurst", func(t *testing.T) { testSlidingLogNoBoundaryBurst(t, h) })
	case SlidingCounter:
		t.Run("NoBoundaryBurst", func(t *testing.T) { testSlidingCounterNoBoundaryBurst(t, h) })
		t.Run("WeightedApproximation", func(t *testing.T) { testSlidingCounterWeighted(t, h) })
	case TokenBucket:
		t.Run("RefillsContinuously", func(t *testing.T) { testTokenBucketRefill(t, h) })
		t.Run("CapacityNotExceededAfterIdle", func(t *testing.T) { testTokenBucketIdleCap(t, h) })
		t.Run("BurstSmallerThanRate", func(t *testing.T) { testTokenBucketSmallBurst(t, h) })
	}
}

// allow calls Allow and converts ErrNotImplemented into a skip.
func allow(t *testing.T, l limiter.Limiter, key string, lim limiter.Limit) limiter.Decision {
	t.Helper()
	d, err := l.Allow(context.Background(), key, lim)
	if errors.Is(err, limiter.ErrNotImplemented) {
		t.Skipf("%s: exercise not implemented yet", l.Name())
	}
	if err != nil {
		t.Fatalf("Allow(%q) returned unexpected error: %v", key, err)
	}
	return d
}

func expectAllowed(t *testing.T, d limiter.Decision, msg string, args ...any) {
	t.Helper()
	if !d.Allowed {
		t.Fatalf("expected ALLOWED but was denied: "+msg, args...)
	}
}

func expectDenied(t *testing.T, d limiter.Decision, msg string, args ...any) {
	t.Helper()
	if d.Allowed {
		t.Fatalf("expected DENIED but was allowed: "+msg, args...)
	}
}

// drain sends requests until one is denied or max is reached; returns allowed count.
func drain(t *testing.T, l limiter.Limiter, key string, lim limiter.Limit, max int) int {
	t.Helper()
	n := 0
	for i := 0; i < max; i++ {
		if !allow(t, l, key, lim).Allowed {
			return n
		}
		n++
	}
	return n
}

// ---------------------------------------------------------------------------
// Common assertions
// ---------------------------------------------------------------------------

func testAllowsUpToCapacity(t *testing.T, h Harness) {
	l, _ := h.New(t)
	lim := limiter.Limit{Rate: 5, Period: h.Unit}

	for i := 0; i < 5; i++ {
		d := allow(t, l, "alice", lim)
		expectAllowed(t, d, "request %d of 5", i+1)
		if d.Limit != 5 {
			t.Errorf("request %d: Limit = %d, want 5", i+1, d.Limit)
		}
		if want := 5 - (i + 1); d.Remaining != want {
			t.Errorf("request %d: Remaining = %d, want %d", i+1, d.Remaining, want)
		}
		if d.RetryAfter != 0 {
			t.Errorf("request %d: RetryAfter = %s on an allowed decision, want 0", i+1, d.RetryAfter)
		}
	}

	d := allow(t, l, "alice", lim)
	expectDenied(t, d, "6th request within the period")
	if d.Remaining != 0 {
		t.Errorf("denied: Remaining = %d, want 0", d.Remaining)
	}
	if d.RetryAfter <= 0 {
		t.Errorf("denied: RetryAfter = %s, want > 0", d.RetryAfter)
	}
	if d.RetryAfter > h.Unit {
		t.Errorf("denied: RetryAfter = %s exceeds the period %s", d.RetryAfter, h.Unit)
	}
	if d.ResetAfter <= 0 {
		t.Errorf("denied: ResetAfter = %s, want > 0", d.ResetAfter)
	}
}

func testKeysAreIndependent(t *testing.T, h Harness) {
	l, _ := h.New(t)
	lim := limiter.Limit{Rate: 3, Period: h.Unit}

	if n := drain(t, l, "alice", lim, 10); n != 3 {
		t.Fatalf("alice: allowed %d, want 3", n)
	}
	d := allow(t, l, "bob", lim)
	expectAllowed(t, d, "bob's first request after alice was exhausted")
	if d.Remaining != 2 {
		t.Errorf("bob: Remaining = %d, want 2", d.Remaining)
	}
}

func testConcurrent(t *testing.T, h Harness) {
	l, _ := h.New(t)
	const capacity = 50
	const goroutines = 32
	const perGoroutine = 20 // 640 attempts against a capacity of 50

	// A long period keeps refill negligible even on a real clock.
	lim := limiter.Limit{Rate: capacity, Period: 10 * h.Unit}

	// Probe once so a stub skips cleanly instead of failing from 32 goroutines.
	allow(t, l, "probe", lim)

	var allowed atomic.Int64
	var errs atomic.Int64
	var wg sync.WaitGroup
	start := make(chan struct{})
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for i := 0; i < perGoroutine; i++ {
				d, err := l.Allow(context.Background(), "shared", lim)
				if err != nil {
					errs.Add(1)
					continue
				}
				if d.Allowed {
					allowed.Add(1)
				}
			}
		}()
	}
	close(start)
	wg.Wait()

	if errs.Load() > 0 {
		t.Fatalf("%d Allow calls returned errors", errs.Load())
	}
	got := int(allowed.Load())
	if h.RealClock {
		// Token bucket may refill a token or two while the test runs.
		if got < capacity || got > capacity+2 {
			t.Fatalf("allowed %d requests concurrently, want %d (tolerance +2 on real clock)", got, capacity)
		}
		return
	}
	if got != capacity {
		t.Fatalf("allowed %d requests concurrently, want exactly %d - this is a race", got, capacity)
	}
}

func testResetAfterIsHonest(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 4, Period: h.Unit}

	if n := drain(t, l, "alice", lim, 10); n != 4 {
		t.Fatalf("initial drain allowed %d, want 4", n)
	}
	d := allow(t, l, "alice", lim)
	expectDenied(t, d, "after drain")
	if d.ResetAfter <= 0 {
		t.Fatalf("ResetAfter = %s, want > 0", d.ResetAfter)
	}

	// ResetAfter promises the *entire* budget is back. Every algorithm defines
	// it differently (window end, time to refill to Burst, time for the log
	// to empty, two window boundaries for the counter approximation) but the
	// promise is the same.
	clk.Advance(d.ResetAfter + h.epsilon())
	if n := drain(t, l, "alice", lim, 10); n != 4 {
		t.Fatalf("after ResetAfter (%s) allowed %d, want 4", d.ResetAfter, n)
	}
}

func testRetryAfterIsHonest(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 3, Period: h.Unit}

	drain(t, l, "alice", lim, 10)
	d := allow(t, l, "alice", lim)
	expectDenied(t, d, "after drain")

	clk.Advance(d.RetryAfter + h.epsilon())
	d2 := allow(t, l, "alice", lim)
	expectAllowed(t, d2, "after waiting the advertised RetryAfter of %s", d.RetryAfter)
}

// ---------------------------------------------------------------------------
// Fixed window
// ---------------------------------------------------------------------------

// The fixed window's well-known weakness: a client can send 2x the limit in
// just over an instant by straddling a window boundary. This test *documents*
// that behaviour so the comparison against sliding windows is measurable.
func testFixedWindowBoundaryBurst(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 10, Period: h.Unit}

	alignToWindowStart(clk, h)
	clk.Advance(h.Unit - 2*h.epsilon()) // just before the boundary

	if n := drain(t, l, "alice", lim, 20); n != 10 {
		t.Fatalf("just before boundary allowed %d, want 10", n)
	}
	clk.Advance(3 * h.epsilon()) // cross the boundary
	if n := drain(t, l, "alice", lim, 20); n != 10 {
		t.Fatalf("just after boundary allowed %d, want 10 (fixed window permits a 2x burst at the boundary)", n)
	}
}

// alignToWindowStart advances the clock to just past the next Unit-aligned
// boundary (between 2.5% and ~5% into the new window), so that tests which
// depend on window position are deterministic. Windows are absolute
// (index = floor(now / period)) in every algorithm in this project.
func alignToWindowStart(clk Clock, h Harness) {
	now := clk.Now()
	period := int64(h.Unit)
	next := (now.UnixNano()/period + 1) * period
	clk.Advance(time.Duration(next-now.UnixNano()) + h.epsilon()/2)
}

// ---------------------------------------------------------------------------
// Sliding log
// ---------------------------------------------------------------------------

func testSlidingLogNoBoundaryBurst(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 10, Period: h.Unit}

	// t=0: 5 requests. t=0.5: 5 more (now full).
	if n := drain(t, l, "alice", lim, 5); n != 5 {
		t.Fatalf("t=0 allowed %d, want 5", n)
	}
	clk.Advance(h.Unit / 2)
	if n := drain(t, l, "alice", lim, 5); n != 5 {
		t.Fatalf("t=0.5 allowed %d, want 5", n)
	}
	expectDenied(t, allow(t, l, "alice", lim), "t=0.5 with 10 in window")

	// t=1.0+eps: only the first 5 have aged out -> exactly 5 allowed.
	clk.Advance(h.Unit/2 + h.epsilon())
	if n := drain(t, l, "alice", lim, 20); n != 5 {
		t.Fatalf("t=1.0 allowed %d, want exactly 5 (no 2x boundary burst)", n)
	}

	// t=1.5+eps: the second batch of 5 ages out.
	clk.Advance(h.Unit / 2)
	if n := drain(t, l, "alice", lim, 20); n != 5 {
		t.Fatalf("t=1.5 allowed %d, want exactly 5", n)
	}
}

// ---------------------------------------------------------------------------
// Sliding window counter
// ---------------------------------------------------------------------------

// The counter approximation estimates the count in the trailing window as
//
//	estimate = previous_window_count * (1 - elapsed_fraction) + current_window_count
//
// where elapsed_fraction is how far into the current fixed window we are.
func testSlidingCounterNoBoundaryBurst(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 10, Period: h.Unit}

	alignToWindowStart(clk, h)
	if n := drain(t, l, "alice", lim, 20); n != 10 {
		t.Fatalf("fresh key allowed %d, want 10", n)
	}

	// Cross into the next window by a hair (we are < 10% into it).
	clk.Advance(h.Unit)
	// previous weighs > 0.9 -> estimate > 9 -> estimate + 1 > 10 -> deny.
	if n := drain(t, l, "alice", lim, 20); n != 0 {
		t.Fatalf("just after boundary allowed %d, want 0 (previous window still weighs >90%%)", n)
	}
}

func testSlidingCounterWeighted(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 10, Period: h.Unit}

	alignToWindowStart(clk, h)
	if n := drain(t, l, "alice", lim, 20); n != 10 {
		t.Fatalf("fresh key allowed %d, want 10", n)
	}

	// Move to just past the midpoint of the *next* window (50-60% in).
	clk.Advance(h.Unit + h.Unit/2)
	// estimate = 10 * (0.4..0.5) + current; allowed iff estimate + 1 <= 10 -> 5.
	if n := drain(t, l, "alice", lim, 20); n != 5 {
		t.Fatalf("at ~50%% into the next window allowed %d, want 5", n)
	}
}

// ---------------------------------------------------------------------------
// Token bucket
// ---------------------------------------------------------------------------

func testTokenBucketRefill(t *testing.T, h Harness) {
	l, clk := h.New(t)
	// 10 tokens per Unit -> one token every Unit/10.
	lim := limiter.Limit{Rate: 10, Period: h.Unit, Burst: 10}
	tick := h.Unit / 10

	if n := drain(t, l, "alice", lim, 20); n != 10 {
		t.Fatalf("initial burst allowed %d, want 10", n)
	}

	clk.Advance(tick + h.epsilon()/2)
	expectAllowed(t, allow(t, l, "alice", lim), "one tick after exhaustion should yield exactly one token")
	expectDenied(t, allow(t, l, "alice", lim), "second request within the same tick")

	if !h.RealClock {
		clk.Advance(tick / 2)
		expectDenied(t, allow(t, l, "alice", lim), "half a tick should not yield a whole token")
		clk.Advance(tick / 2)
		expectAllowed(t, allow(t, l, "alice", lim), "two half ticks accumulate to one token")
	}

	// 3 ticks -> 3 tokens, no more.
	clk.Advance(3*tick + h.epsilon()/2)
	if n := drain(t, l, "alice", lim, 20); n != 3 {
		t.Fatalf("after 3 ticks allowed %d, want 3", n)
	}
}

func testTokenBucketIdleCap(t *testing.T, h Harness) {
	l, clk := h.New(t)
	lim := limiter.Limit{Rate: 10, Period: h.Unit, Burst: 10}

	drain(t, l, "alice", lim, 20)
	clk.Advance(100 * h.Unit) // idle for a long time
	if n := drain(t, l, "alice", lim, 50); n != 10 {
		t.Fatalf("after long idle allowed %d, want 10 (bucket must cap at Burst)", n)
	}
}

func testTokenBucketSmallBurst(t *testing.T, h Harness) {
	l, clk := h.New(t)
	// Sustained 100/Unit but never more than 5 at once.
	lim := limiter.Limit{Rate: 100, Period: h.Unit, Burst: 5}

	if n := drain(t, l, "alice", lim, 50); n != 5 {
		t.Fatalf("burst allowed %d, want 5", n)
	}
	d := allow(t, l, "alice", lim)
	expectDenied(t, d, "6th request")
	if d.Limit != 5 {
		t.Errorf("Limit = %d, want 5 (Burst, not Rate)", d.Limit)
	}
	// One token every Unit/100.
	clk.Advance(h.Unit/100 + h.epsilon()/4)
	expectAllowed(t, allow(t, l, "alice", lim), "after one refill interval")
}

// ---------------------------------------------------------------------------
// Benchmarks
// ---------------------------------------------------------------------------

// Benchmark measures Allow throughput on a hot key and on many distinct keys.
func Benchmark(b *testing.B, newLimiter func() limiter.Limiter) {
	lim := limiter.Limit{Rate: 1_000_000, Period: time.Second}
	ctx := context.Background()

	b.Run("HotKey", func(b *testing.B) {
		l := newLimiter()
		b.ReportAllocs()
		b.RunParallel(func(pb *testing.PB) {
			for pb.Next() {
				_, _ = l.Allow(ctx, "hot", lim)
			}
		})
	})

	b.Run("ManyKeys", func(b *testing.B) {
		l := newLimiter()
		keys := make([]string, 1024)
		for i := range keys {
			keys[i] = fmt.Sprintf("user-%d", i)
		}
		b.ReportAllocs()
		b.RunParallel(func(pb *testing.PB) {
			i := 0
			for pb.Next() {
				_, _ = l.Allow(ctx, keys[i%len(keys)], lim)
				i++
			}
		})
	})
}
