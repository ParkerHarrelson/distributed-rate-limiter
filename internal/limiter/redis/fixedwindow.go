package redis

import (
	"context"
	_ "embed"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

//go:embed fixedwindow.lua
var fixedWindowLua string

var fixedWindowScript = goredis.NewScript(fixedWindowLua)

// FixedWindow is the Redis-backed twin of memory.FixedWindow. See
// fixedwindow.lua for the algorithm.
type FixedWindow struct {
	c    Client
	opts Options
}

// NewFixedWindow returns a limiter that stores windows in Redis via c.
func NewFixedWindow(c Client, opts Options) *FixedWindow {
	return &FixedWindow{c: c, opts: opts.withDefaults()}
}

// Name implements limiter.Limiter.
func (f *FixedWindow) Name() string { return "fixed_window" }

// Allow implements limiter.Limiter.
func (f *FixedWindow) Allow(ctx context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
	return runScript(ctx, f.c, fixedWindowScript, f.opts.Timeout,
		f.opts.KeyPrefix+key,
		limit.Rate,
		limit.Period.Milliseconds(),
	)
}
