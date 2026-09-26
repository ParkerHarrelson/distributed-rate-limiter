// Package redis contains rate limiters whose state lives in a shared Redis
// instance so that every service replica enforces the same limits.
//
// Every algorithm is a single Lua script executed with EVALSHA. Redis runs
// scripts atomically on its single command-processing thread, which is what
// gives us check-and-increment without a distributed lock. The Go side is a
// thin wrapper: build the arguments, run the script, decode the reply.
//
// Scripts are embedded at compile time from the .lua files next to this file
// so they can be read and tested in isolation.
//
// Time: scripts read the clock with redis.call('TIME') rather than trusting a
// timestamp passed by the caller. Every replica therefore agrees on "now"
// regardless of its own clock, at the cost of making the script read a
// non-deterministic value (fine on Redis >= 5, which replicates script
// effects rather than the script itself).
//
// FixedWindow is fully implemented as a worked example. TokenBucket and
// SlidingCounter are curriculum exercises.
package redis

import (
	"context"
	"fmt"
	"time"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// Client is the subset of go-redis that the limiters need. Using the
// interface keeps the limiters testable against a mock and works with both a
// single node and a cluster client.
type Client interface {
	goredis.Scripter
}

// Options tune every Redis-backed limiter.
type Options struct {
	// KeyPrefix namespaces all keys, e.g. "rl:". Defaults to "rl:".
	KeyPrefix string
	// Timeout bounds each Allow call. A rate limiter that is slower than the
	// request it protects is worse than no rate limiter; keep this small.
	// Defaults to 50ms.
	Timeout time.Duration
}

func (o Options) withDefaults() Options {
	if o.KeyPrefix == "" {
		o.KeyPrefix = "rl:"
	}
	if o.Timeout <= 0 {
		o.Timeout = 50 * time.Millisecond
	}
	return o
}

// Dial connects to a single Redis node at addr (host:port) and verifies the
// connection with PING.
func Dial(ctx context.Context, addr string) (*goredis.Client, error) {
	rdb := goredis.NewClient(&goredis.Options{
		Addr:         addr,
		DialTimeout:  2 * time.Second,
		ReadTimeout:  200 * time.Millisecond,
		WriteTimeout: 200 * time.Millisecond,
		PoolSize:     64,
		MinIdleConns: 4,
		// Without this, go-redis ignores the per-call context deadline and
		// only honours Read/WriteTimeout, so a partition costs 200ms per
		// call instead of the Options.Timeout the limiter asked for.
		ContextTimeoutEnabled: true,
	})
	if err := rdb.Ping(ctx).Err(); err != nil {
		_ = rdb.Close()
		return nil, fmt.Errorf("redis: ping %s: %w", addr, err)
	}
	return rdb, nil
}

// runScript executes script against key with args and decodes the standard
// 5-element integer reply every limiter script in this package returns:
//
//	{ allowed(0|1), limit, remaining, retry_after_ms, reset_after_ms }
//
// Keeping the reply shape identical across algorithms means the Go wrappers
// are interchangeable and the Lua is where the algorithm lives.
func runScript(ctx context.Context, c Client, script *goredis.Script, timeout time.Duration, key string, args ...any) (limiter.Decision, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	res, err := script.Run(ctx, c, []string{key}, args...).Slice()
	if err != nil {
		return limiter.Decision{}, fmt.Errorf("redis: %w", err)
	}
	if len(res) != 5 {
		return limiter.Decision{}, fmt.Errorf("redis: script returned %d values, want 5", len(res))
	}
	ints := make([]int64, 5)
	for i, v := range res {
		n, ok := v.(int64)
		if !ok {
			return limiter.Decision{}, fmt.Errorf("redis: reply[%d] = %T, want int64", i, v)
		}
		ints[i] = n
	}
	return limiter.Decision{
		Allowed:    ints[0] == 1,
		Limit:      int(ints[1]),
		Remaining:  int(ints[2]),
		RetryAfter: time.Duration(ints[3]) * time.Millisecond,
		ResetAfter: time.Duration(ints[4]) * time.Millisecond,
	}, nil
}

// LazyClient returns a client that has not been verified with PING. Use it
// when Redis may not be up yet at process start; the first command that
// succeeds establishes the connection.
func LazyClient(addr string) *goredis.Client {
	return goredis.NewClient(&goredis.Options{
		Addr:                  addr,
		DialTimeout:           500 * time.Millisecond,
		ReadTimeout:           200 * time.Millisecond,
		WriteTimeout:          200 * time.Millisecond,
		PoolSize:              64,
		ContextTimeoutEnabled: true,
	})
}
