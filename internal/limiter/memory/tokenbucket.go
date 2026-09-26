package memory

import (
	"context"
	"sync"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// TokenBucket - EXERCISE 1 (see docs/curriculum.md).
//
// Mental model: every key owns a bucket that holds at most limit.Capacity()
// tokens. Tokens drip in continuously at limit.Rate per limit.Period. A request
// takes one token if one is available and is denied otherwise.
//
// Unlike the windowed algorithms, a token bucket separates two concerns that
// fixed windows conflate: the sustained rate (Rate/Period) and the maximum
// instantaneous burst (Burst). That is why it is the algorithm behind most
// production API rate limiters (and Go's own golang.org/x/time/rate).
//
// Design questions to answer before writing code:
//
//   - You should NOT run a goroutine per bucket that adds tokens on a timer.
//     How do you compute how many tokens a bucket holds *right now* given only
//     what you stored on the previous request?
//   - Tokens refill fractionally (half a token after half an interval). Do you
//     store a float64, or an integer in some smaller unit? What are the
//     failure modes of each? (Hint: think about very long idle periods and
//     about equality comparisons.)
//   - What is the exact RetryAfter when a request is denied with t tokens in
//     the bucket, 0 <= t < 1?
//   - What must be true about the state you store so that a brand-new key
//     starts with a *full* bucket rather than an empty one?
//
// The conformance suite in tokenbucket_test.go is the acceptance test.
type TokenBucket struct {
	clk clock.Clock

	mu      sync.Mutex
	buckets map[string]*tokenBucketState
}

// tokenBucketState is what the bucket remembers between requests. Add whatever
// fields you decide you need.
type tokenBucketState struct{}

// NewTokenBucket returns an empty limiter driven by clk.
func NewTokenBucket(clk clock.Clock) *TokenBucket {
	return &TokenBucket{clk: clk, buckets: make(map[string]*tokenBucketState)}
}

// Name implements limiter.Limiter.
func (b *TokenBucket) Name() string { return "token_bucket" }

// Allow implements limiter.Limiter.
func (b *TokenBucket) Allow(_ context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	_ = b.clk
	_ = key
	_ = limit
	return limiter.Decision{}, limiter.ErrNotImplemented
}

// Sweep removes buckets that have been idle long enough to be full again (a
// full bucket is indistinguishable from a missing one, so it is safe to drop).
func (b *TokenBucket) Sweep() int {
	return 0
}
