// Command server is one replica of the rate-limited API. Run several of them
// against one Redis and they enforce the same limits collectively.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/config"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/httpapi"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/memory"
	rlredis "github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/redis"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/metrics"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/policy"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/resilience"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(logger); err != nil {
		logger.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.FromEnv()
	if err != nil {
		return err
	}
	pol, file, err := policy.LoadFile(cfg.LimitsFile)
	if err != nil {
		return err
	}
	algorithm := firstNonEmpty(cfg.Algorithm, file.Algorithm, "fixed_window")
	failMode := firstNonEmpty(cfg.FailMode, file.FailMode)
	logger = logger.With("instance", cfg.InstanceID)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// A skewed clock only affects the memory backend; that asymmetry is the
	// lesson (Redis TIME gives every replica the same clock).
	var clk clock.Clock = clock.Real{}
	if cfg.ClockSkew != 0 {
		clk = skewed{offset: cfg.ClockSkew}
		logger.Warn("running with skewed clock", "skew", cfg.ClockSkew)
	}

	primary, err := buildLimiter(ctx, cfg, algorithm, clk, logger)
	if err != nil {
		return err
	}

	m := metrics.New()
	var lim limiter.Limiter = primary
	var breakerState func() string

	if failMode != "" {
		mode, ok := resilience.ParseMode(failMode)
		if !ok {
			return fmt.Errorf("unknown fail mode %q (want open|closed|local)", failMode)
		}
		local := buildMemoryLimiter(ctx, algorithm, clk)
		res := resilience.New(primary, resilience.Options{
			Mode:       mode,
			Fallback:   local,
			LocalShare: cfg.LocalShare,
			Clock:      clk,
			OnFallback: func(mode resilience.Mode, allowed bool) {
				result := "denied"
				if allowed {
					result = "allowed"
				}
				m.FallbackDecisions.WithLabelValues(string(mode), result).Inc()
			},
		})
		lim = res
		breakerState = res.State
	}

	m.Info.WithLabelValues(cfg.InstanceID, algorithm, cfg.Backend, failMode).Set(1)

	mw := &httpapi.Middleware{
		Limiter:    lim,
		Policy:     pol,
		Metrics:    m,
		Logger:     logger,
		InstanceID: cfg.InstanceID,
		Backend:    cfg.Backend,
	}
	handler := httpapi.NewRouter(httpapi.RouterOptions{
		Middleware: mw,
		Metrics:    m,
		Policy:     pol,
		Health: func() httpapi.Health {
			h := httpapi.Health{Status: "ok", InstanceID: cfg.InstanceID, Algorithm: algorithm, Backend: cfg.Backend, FailMode: failMode}
			if breakerState != nil {
				h.Breaker = breakerState()
			}
			return h
		},
	})

	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	logger.Info("listening",
		"port", cfg.Port, "backend", cfg.Backend, "algorithm", algorithm,
		"fail_mode", failMode, "rules", len(pol.Rules()), "redis", cfg.RedisAddr)
	for _, r := range pol.Rules() {
		logger.Info("rule", "name", r.Name, "scope", r.Scope, "limit", r.Limit.String(),
			"endpoints", r.Endpoints, "users", r.Users)
	}

	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("shut down cleanly")
	return nil
}

// buildLimiter picks the backend and algorithm.
func buildLimiter(ctx context.Context, cfg config.Config, algorithm string, clk clock.Clock, logger *slog.Logger) (limiter.Limiter, error) {
	if cfg.Backend == "memory" {
		return buildMemoryLimiter(ctx, algorithm, clk), nil
	}
	rdb, err := rlredis.Dial(ctx, cfg.RedisAddr)
	if err != nil {
		// Start anyway: the resilience layer (exercise 5) is what should
		// handle an unavailable Redis, and the demo wants to show what
		// happens without it.
		logger.Warn("redis unreachable at startup; requests will fail until it is", "err", err)
		rdb = nil
	}
	opts := rlredis.Options{Timeout: cfg.RedisTimeout}
	var c rlredis.Client
	if rdb != nil {
		c = rdb
	} else {
		// A lazily-connecting client so that Redis coming up later works.
		lazy, _ := rlredis.Dial(ctx, cfg.RedisAddr)
		if lazy == nil {
			lazy = rlredis.LazyClient(cfg.RedisAddr)
		}
		c = lazy
	}
	switch algorithm {
	case "fixed_window":
		return rlredis.NewFixedWindow(c, opts), nil
	case "token_bucket":
		return rlredis.NewTokenBucket(c, opts), nil
	case "sliding_counter":
		return rlredis.NewSlidingCounter(c, opts), nil
	default:
		return nil, fmt.Errorf("unknown algorithm %q for redis backend (want fixed_window|token_bucket|sliding_counter)", algorithm)
	}
}

func buildMemoryLimiter(ctx context.Context, algorithm string, clk clock.Clock) limiter.Limiter {
	var l limiter.Limiter
	var sweep func() int
	switch algorithm {
	case "token_bucket":
		tb := memory.NewTokenBucket(clk)
		l, sweep = tb, tb.Sweep
	case "sliding_log":
		sl := memory.NewSlidingLog(clk)
		l, sweep = sl, sl.Sweep
	case "sliding_counter":
		sc := memory.NewSlidingCounter(clk)
		l, sweep = sc, sc.Sweep
	default:
		fw := memory.NewFixedWindow(clk)
		l, sweep = fw, fw.Sweep
	}
	go memory.RunJanitor(ctx, time.Minute, sweep)
	return l
}

type skewed struct{ offset time.Duration }

func (s skewed) Now() time.Time { return time.Now().Add(s.offset) }

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
