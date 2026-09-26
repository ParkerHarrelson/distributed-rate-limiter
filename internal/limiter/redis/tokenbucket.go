package redis

import (
	"context"
	_ "embed"
	"strings"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

//go:embed tokenbucket.lua
var tokenBucketLua string

var tokenBucketScript = goredis.NewScript(tokenBucketLua)

// TokenBucket - EXERCISE 3. The Go side is complete; the algorithm lives in
// tokenbucket.lua.
type TokenBucket struct {
	c    Client
	opts Options
}

// NewTokenBucket returns a limiter that stores buckets in Redis via c.
func NewTokenBucket(c Client, opts Options) *TokenBucket {
	return &TokenBucket{c: c, opts: opts.withDefaults()}
}

// Name implements limiter.Limiter.
func (b *TokenBucket) Name() string { return "token_bucket" }

// Allow implements limiter.Limiter.
func (b *TokenBucket) Allow(ctx context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	d, err := runScript(ctx, b.c, tokenBucketScript, b.opts.Timeout,
		b.opts.KeyPrefix+key,
		limit.Rate,
		limit.Period.Milliseconds(),
		limit.Capacity(),
	)
	return d, notImplemented(err)
}

// notImplemented maps the stub scripts' sentinel error onto
// limiter.ErrNotImplemented so the conformance suite skips cleanly.
func notImplemented(err error) error {
	if err != nil && strings.Contains(err.Error(), "NOT_IMPLEMENTED") {
		return limiter.ErrNotImplemented
	}
	return err
}
