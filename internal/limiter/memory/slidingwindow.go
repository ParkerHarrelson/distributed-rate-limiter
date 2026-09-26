package memory

import (
	"context"
	"sync"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// SlidingLog - EXERCISE 2a (see docs/curriculum.md).
//
// The exact sliding window: remember the timestamp of every allowed request in
// the trailing Period. A request is allowed iff fewer than limit.Rate
// timestamps are newer than now - Period.
//
// It is perfectly accurate and has no boundary-burst problem. Its cost is
// memory: O(Rate) timestamps per key. At 10,000 requests/minute per user that
// is 80 KB per user per minute of activity - which is exactly why the counter
// approximation below exists.
//
// Design questions:
//
//   - Which data structure lets you (a) append the newest timestamp and
//     (b) discard everything older than a cutoff, both cheaply? A plain slice
//     works; what is its worst case? Would a ring buffer be better and why?
//   - Do you record denied requests in the log? What changes if you do?
//     (This is a real design fork; some products do, some do not.)
//   - When denied, RetryAfter is *not* "until the window resets". What is it?
type SlidingLog struct {
	clk clock.Clock

	mu   sync.Mutex
	logs map[string]*slidingLogState
}

type slidingLogState struct{}

// NewSlidingLog returns an empty limiter driven by clk.
func NewSlidingLog(clk clock.Clock) *SlidingLog {
	return &SlidingLog{clk: clk, logs: make(map[string]*slidingLogState)}
}

// Name implements limiter.Limiter.
func (s *SlidingLog) Name() string { return "sliding_log" }

// Allow implements limiter.Limiter.
func (s *SlidingLog) Allow(_ context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	_ = s.clk
	_ = key
	_ = limit
	return limiter.Decision{}, limiter.ErrNotImplemented
}

// Sweep removes keys whose log is entirely older than their period.
func (s *SlidingLog) Sweep() int { return 0 }

// SlidingCounter - EXERCISE 2b (see docs/curriculum.md).
//
// The approximation popularised by Cloudflare: keep only two fixed-window
// counters per key, the current window's and the previous window's, and
// estimate how many requests fall in the trailing Period by assuming the
// previous window's requests were spread evenly:
//
//	elapsed  = fraction of the current window that has passed, in [0, 1)
//	estimate = previous * (1 - elapsed) + current
//	allowed  = estimate + 1 <= Rate
//
// It costs two integers per key (like fixed window) and smooths the boundary
// burst almost entirely (like sliding log). Cloudflare measured it as
// mis-classifying ~0.003% of requests against real traffic.
//
// Design questions:
//
//   - What happens to "previous" and "current" when a key is next seen two or
//     more windows later? Make sure you do not weight stale data.
//   - Derive RetryAfter. When denied, how long until the estimate drops enough
//     to admit one request? Note there are two cases: the previous window is
//     still contributing, or the current window alone is already over the
//     limit (then you must wait until *after* the next boundary, plus some).
//     The RetryAfterIsHonest test will catch a lazy answer.
type SlidingCounter struct {
	clk clock.Clock

	mu       sync.Mutex
	counters map[string]*slidingCounterState
}

type slidingCounterState struct{}

// NewSlidingCounter returns an empty limiter driven by clk.
func NewSlidingCounter(clk clock.Clock) *SlidingCounter {
	return &SlidingCounter{clk: clk, counters: make(map[string]*slidingCounterState)}
}

// Name implements limiter.Limiter.
func (s *SlidingCounter) Name() string { return "sliding_counter" }

// Allow implements limiter.Limiter.
func (s *SlidingCounter) Allow(_ context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	_ = s.clk
	_ = key
	_ = limit
	return limiter.Decision{}, limiter.ErrNotImplemented
}

// Sweep removes keys not seen for two full periods.
func (s *SlidingCounter) Sweep() int { return 0 }
