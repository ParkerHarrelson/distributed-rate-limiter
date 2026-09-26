package resilience

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/memory"
)

var errDown = errors.New("redis: connection refused")

// flaky is a scriptable primary limiter.
type flaky struct {
	mu        sync.Mutex
	failing   bool
	calls     atomic.Int64
	lastLimit limiter.Limit
}

func (f *flaky) Name() string { return "flaky" }
func (f *flaky) Allow(_ context.Context, _ string, l limiter.Limit) (limiter.Decision, error) {
	f.calls.Add(1)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lastLimit = l
	if f.failing {
		return limiter.Decision{}, errDown
	}
	return limiter.Decision{Allowed: true, Limit: l.Capacity(), Remaining: 1}, nil
}
func (f *flaky) setFailing(v bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.failing = v
}

var lim = limiter.Limit{Rate: 10, Period: time.Second}

func mustAllow(t *testing.T, r *Resilient, key string) limiter.Decision {
	t.Helper()
	d, err := r.Allow(context.Background(), key, lim)
	if errors.Is(err, limiter.ErrNotImplemented) {
		t.Skip("exercise 5 not implemented yet")
	}
	if err != nil {
		t.Fatalf("Allow returned %v; the resilience layer must never surface backend errors", err)
	}
	return d
}

func TestPrimaryUsedWhenHealthy(t *testing.T) {
	p := &flaky{}
	r := New(p, Options{Mode: Closed})
	d := mustAllow(t, r, "k")
	if !d.Allowed || p.calls.Load() != 1 {
		t.Fatalf("healthy primary should decide: allowed=%v calls=%d", d.Allowed, p.calls.Load())
	}
}

func TestFailOpenAllows(t *testing.T) {
	p := &flaky{failing: true}
	var fallbacks atomic.Int64
	r := New(p, Options{Mode: Open, OnFallback: func(Mode, bool) { fallbacks.Add(1) }})
	for i := 0; i < 3; i++ {
		if d := mustAllow(t, r, "k"); !d.Allowed {
			t.Fatalf("fail-open must allow, got %+v", d)
		}
	}
	if fallbacks.Load() != 3 {
		t.Fatalf("OnFallback called %d times, want 3", fallbacks.Load())
	}
}

func TestFailClosedDenies(t *testing.T) {
	p := &flaky{failing: true}
	r := New(p, Options{Mode: Closed})
	d := mustAllow(t, r, "k")
	if d.Allowed {
		t.Fatal("fail-closed must deny")
	}
	if d.RetryAfter <= 0 {
		t.Error("a fail-closed denial should still tell the client when to retry")
	}
}

func TestLocalFallbackUsesScaledLimit(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	local := memory.NewFixedWindow(clk)
	r := New(p, Options{Mode: Local, Fallback: local, LocalShare: 0.5, Clock: clk})

	allowed := 0
	for i := 0; i < 20; i++ {
		if mustAllow(t, r, "k").Allowed {
			allowed++
		}
	}
	// Rate 10 * share 0.5 = 5 locally.
	if allowed != 5 {
		t.Fatalf("local fallback allowed %d, want 5 (rate 10 * share 0.5)", allowed)
	}
}

func TestLocalShareNeverRoundsToZero(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	r := New(p, Options{Mode: Local, Fallback: memory.NewFixedWindow(clk), LocalShare: 0.1, Clock: clk})
	d, err := r.Allow(context.Background(), "k", limiter.Limit{Rate: 1, Period: time.Second})
	if errors.Is(err, limiter.ErrNotImplemented) {
		t.Skip("exercise 5 not implemented yet")
	}
	if err != nil || !d.Allowed {
		t.Fatalf("rate 1 * share 0.1 must still permit at least 1 request, got allowed=%v err=%v", d.Allowed, err)
	}
}

func TestBreakerStopsCallingPrimary(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	r := New(p, Options{Mode: Open, Threshold: 3, Cooldown: 10 * time.Second, Clock: clk})

	for i := 0; i < 3; i++ {
		mustAllow(t, r, "k")
	}
	if got := r.State(); got != "open" {
		t.Fatalf("after %d consecutive failures State() = %q, want open", 3, got)
	}
	before := p.calls.Load()
	for i := 0; i < 100; i++ {
		mustAllow(t, r, "k")
	}
	if p.calls.Load() != before {
		t.Fatalf("breaker is open but primary was called %d more times", p.calls.Load()-before)
	}
}

func TestBreakerRecovers(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	r := New(p, Options{Mode: Open, Threshold: 2, Cooldown: 10 * time.Second, Clock: clk})

	mustAllow(t, r, "k")
	mustAllow(t, r, "k")
	if r.State() != "open" {
		t.Fatalf("State() = %q, want open", r.State())
	}

	// Still open during cooldown even though primary has recovered.
	p.setFailing(false)
	clk.Advance(5 * time.Second)
	before := p.calls.Load()
	mustAllow(t, r, "k")
	if p.calls.Load() != before {
		t.Fatal("primary must not be called before cooldown elapses")
	}

	// After cooldown one probe reaches the primary; success closes the breaker.
	clk.Advance(6 * time.Second)
	mustAllow(t, r, "k")
	if p.calls.Load() != before+1 {
		t.Fatalf("expected exactly one probe call after cooldown, got %d", p.calls.Load()-before)
	}
	if r.State() != "closed" {
		t.Fatalf("after successful probe State() = %q, want closed", r.State())
	}
	mustAllow(t, r, "k")
	if p.calls.Load() != before+2 {
		t.Fatal("primary should be back in use after the breaker closes")
	}
}

func TestBreakerReopensOnFailedProbe(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	r := New(p, Options{Mode: Open, Threshold: 1, Cooldown: 10 * time.Second, Clock: clk})

	mustAllow(t, r, "k")
	clk.Advance(11 * time.Second)
	before := p.calls.Load()
	mustAllow(t, r, "k") // probe, fails
	if p.calls.Load() != before+1 || r.State() != "open" {
		t.Fatalf("failed probe should reopen: calls=%d state=%q", p.calls.Load()-before, r.State())
	}
	mustAllow(t, r, "k")
	if p.calls.Load() != before+1 {
		t.Fatal("after a failed probe the breaker must wait a full cooldown again")
	}
}

func TestConcurrentSafety(t *testing.T) {
	p := &flaky{failing: true}
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	r := New(p, Options{Mode: Local, Fallback: memory.NewFixedWindow(clk), Threshold: 3, Cooldown: time.Second, Clock: clk})
	mustAllow(t, r, "probe")

	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				_, _ = r.Allow(context.Background(), "k", lim)
				if i%50 == 0 {
					p.setFailing(i%100 == 0)
				}
			}
		}()
	}
	wg.Wait() // run with -race
}
