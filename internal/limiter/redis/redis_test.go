package redis

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/conformance"
)

// These are integration tests. They need a real Redis and are skipped unless
// REDIS_ADDR is set, e.g.
//
//	make redis            # starts one in Docker
//	REDIS_ADDR=localhost:6379 go test ./internal/limiter/redis/
//
// Each subtest gets a unique key prefix so tests never see each other's state,
// and a real clock, so the suite uses a 1s Unit and actually sleeps.

func testClient(t *testing.T) *goredis.Client {
	t.Helper()
	addr := os.Getenv("REDIS_ADDR")
	if addr == "" {
		t.Skip("REDIS_ADDR not set; skipping Redis integration tests")
	}
	rdb, err := Dial(context.Background(), addr)
	if err != nil {
		t.Fatalf("dial redis: %v", err)
	}
	t.Cleanup(func() { _ = rdb.Close() })
	return rdb
}

var testCounter int

func realHarness(sem conformance.Semantics, build func(Client, Options) limiter.Limiter) conformance.Harness {
	return conformance.Harness{
		Semantics: sem,
		Unit:      time.Second,
		RealClock: true,
		New: func(t *testing.T) (limiter.Limiter, conformance.Clock) {
			rdb := testClient(t)
			testCounter++
			opts := Options{KeyPrefix: fmt.Sprintf("test:%d:%d:", time.Now().UnixNano(), testCounter)}
			return build(rdb, opts), conformance.SleepClock{}
		},
	}
}

func TestFixedWindow(t *testing.T) {
	conformance.Run(t, realHarness(conformance.FixedWindow, func(c Client, o Options) limiter.Limiter {
		return NewFixedWindow(c, o)
	}))
}

func TestTokenBucket(t *testing.T) {
	conformance.Run(t, realHarness(conformance.TokenBucket, func(c Client, o Options) limiter.Limiter {
		return NewTokenBucket(c, o)
	}))
}

func TestSlidingCounter(t *testing.T) {
	conformance.Run(t, realHarness(conformance.SlidingCounter, func(c Client, o Options) limiter.Limiter {
		return NewSlidingCounter(c, o)
	}))
}

// TestFixedWindow_SetsTTL checks the housekeeping that the conformance suite
// cannot see: keys must expire on their own.
func TestFixedWindow_SetsTTL(t *testing.T) {
	rdb := testClient(t)
	prefix := fmt.Sprintf("test:ttl:%d:", time.Now().UnixNano())
	fw := NewFixedWindow(rdb, Options{KeyPrefix: prefix})
	lim := limiter.Limit{Rate: 5, Period: 10 * time.Second}

	if _, err := fw.Allow(context.Background(), "alice", lim); err != nil {
		t.Fatal(err)
	}
	ttl, err := rdb.PTTL(context.Background(), prefix+"alice").Result()
	if err != nil {
		t.Fatal(err)
	}
	if ttl <= 0 || ttl > 10*time.Second {
		t.Fatalf("PTTL = %s, want (0, 10s]", ttl)
	}
}

// TestFixedWindow_Timeout verifies a slow/unreachable Redis surfaces as an
// error rather than hanging the request.
func TestFixedWindow_Timeout(t *testing.T) {
	testClient(t) // skip if no redis configured at all
	// Connect to a black-hole address: TEST-NET-1 is guaranteed unroutable.
	dead := goredis.NewClient(&goredis.Options{Addr: "192.0.2.1:6379", DialTimeout: 100 * time.Millisecond})
	defer dead.Close()
	fw := NewFixedWindow(dead, Options{Timeout: 50 * time.Millisecond})

	start := time.Now()
	_, err := fw.Allow(context.Background(), "alice", limiter.Limit{Rate: 1, Period: time.Second})
	if err == nil {
		t.Fatal("expected an error from an unreachable Redis")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("Allow took %s against a dead Redis; the timeout is not being honoured", elapsed)
	}
}
