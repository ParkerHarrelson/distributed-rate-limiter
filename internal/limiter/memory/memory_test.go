package memory

import (
	"context"
	"testing"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/conformance"
)

// start is an arbitrary fixed instant. Choosing one that is not aligned to a
// 10s boundary matters: it means tests begin mid-window, like real traffic.
var start = time.Unix(1_700_000_003, 250_000_000)

func fakeHarness(sem conformance.Semantics, build func(clock.Clock) limiter.Limiter) conformance.Harness {
	return conformance.Harness{
		Semantics: sem,
		Unit:      10 * time.Second,
		New: func(t *testing.T) (limiter.Limiter, conformance.Clock) {
			clk := clock.NewFake(start)
			return build(clk), clk
		},
	}
}

func TestFixedWindow(t *testing.T) {
	conformance.Run(t, fakeHarness(conformance.FixedWindow, func(c clock.Clock) limiter.Limiter {
		return NewFixedWindow(c)
	}))
}

func TestTokenBucket(t *testing.T) {
	conformance.Run(t, fakeHarness(conformance.TokenBucket, func(c clock.Clock) limiter.Limiter {
		return NewTokenBucket(c)
	}))
}

func TestSlidingLog(t *testing.T) {
	conformance.Run(t, fakeHarness(conformance.SlidingLog, func(c clock.Clock) limiter.Limiter {
		return NewSlidingLog(c)
	}))
}

func TestSlidingCounter(t *testing.T) {
	conformance.Run(t, fakeHarness(conformance.SlidingCounter, func(c clock.Clock) limiter.Limiter {
		return NewSlidingCounter(c)
	}))
}

func TestFixedWindow_Sweep(t *testing.T) {
	clk := clock.NewFake(start)
	fw := NewFixedWindow(clk)
	lim := limiter.Limit{Rate: 5, Period: time.Second}
	for _, k := range []string{"a", "b", "c"} {
		_, _ = fw.Allow(context.Background(), k, lim)
	}
	if fw.Len() != 3 {
		t.Fatalf("Len = %d, want 3", fw.Len())
	}
	if n := fw.Sweep(); n != 0 {
		t.Fatalf("Sweep before expiry removed %d, want 0", n)
	}
	clk.Advance(2 * time.Second)
	if n := fw.Sweep(); n != 3 {
		t.Fatalf("Sweep after expiry removed %d, want 3", n)
	}
}

func BenchmarkFixedWindow(b *testing.B) {
	conformance.Benchmark(b, func() limiter.Limiter { return NewFixedWindow(clock.Real{}) })
}

func BenchmarkTokenBucket(b *testing.B) {
	conformance.Benchmark(b, func() limiter.Limiter { return NewTokenBucket(clock.Real{}) })
}

func BenchmarkSlidingLog(b *testing.B) {
	conformance.Benchmark(b, func() limiter.Limiter { return NewSlidingLog(clock.Real{}) })
}

func BenchmarkSlidingCounter(b *testing.B) {
	conformance.Benchmark(b, func() limiter.Limiter { return NewSlidingCounter(clock.Real{}) })
}
