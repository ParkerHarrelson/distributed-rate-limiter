package redis

import (
	"context"
	_ "embed"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

//go:embed slidingwindow.lua
var slidingCounterLua string

var slidingCounterScript = goredis.NewScript(slidingCounterLua)

// SlidingCounter - EXERCISE 4. The Go side is complete; the algorithm lives in
// slidingwindow.lua.
type SlidingCounter struct {
	c    Client
	opts Options
}

// NewSlidingCounter returns a limiter that stores counters in Redis via c.
func NewSlidingCounter(c Client, opts Options) *SlidingCounter {
	return &SlidingCounter{c: c, opts: opts.withDefaults()}
}

// Name implements limiter.Limiter.
func (s *SlidingCounter) Name() string { return "sliding_counter" }

// Allow implements limiter.Limiter.
func (s *SlidingCounter) Allow(ctx context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	d, err := runScript(ctx, s.c, slidingCounterScript, s.opts.Timeout,
		s.opts.KeyPrefix+key,
		limit.Rate,
		limit.Period.Milliseconds(),
	)
	return d, notImplemented(err)
}
