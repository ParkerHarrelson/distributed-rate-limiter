package httpapi

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/clock"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter/memory"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/metrics"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/policy"
)

func newServer(t *testing.T, l limiter.Limiter, rules ...policy.Rule) http.Handler {
	t.Helper()
	p, err := policy.New(rules)
	if err != nil {
		t.Fatal(err)
	}
	m := metrics.New()
	mw := &Middleware{
		Limiter:    l,
		Policy:     p,
		Metrics:    m,
		Logger:     slog.New(slog.NewTextHandler(io.Discard, nil)),
		InstanceID: "test-1",
		Backend:    "memory",
	}
	return NewRouter(RouterOptions{
		Middleware: mw, Metrics: m, Policy: p,
		Health: func() Health { return Health{Status: "ok"} },
	})
}

func get(h http.Handler, path, user string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if user != "" {
		req.Header.Set(HeaderUserID, user)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestMiddleware_AllowsThenDenies(t *testing.T) {
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	h := newServer(t, memory.NewFixedWindow(clk),
		policy.Rule{Name: "u", Scope: policy.ScopeUser, Limit: limiter.Limit{Rate: 2, Period: 10 * time.Second}},
	)

	for i := 0; i < 2; i++ {
		rec := get(h, "/api/cheap", "alice")
		if rec.Code != http.StatusOK {
			t.Fatalf("request %d: status %d, want 200; body %s", i+1, rec.Code, rec.Body)
		}
		if got := rec.Header().Get(HeaderRemaining); got != string(rune('1'-i)) {
			t.Errorf("request %d: %s = %q, want %d", i+1, HeaderRemaining, got, 1-i)
		}
		if rec.Header().Get(HeaderInstance) != "test-1" {
			t.Error("missing instance header")
		}
	}

	rec := get(h, "/api/cheap", "alice")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("3rd request: status %d, want 429", rec.Code)
	}
	if rec.Header().Get(HeaderPolicy) != "u" {
		t.Errorf("%s = %q, want u", HeaderPolicy, rec.Header().Get(HeaderPolicy))
	}
	if rec.Header().Get(HeaderRetry) == "" || rec.Header().Get(HeaderRetry) == "0" {
		t.Errorf("Retry-After = %q, want >= 1", rec.Header().Get(HeaderRetry))
	}

	// Another user is unaffected.
	if rec := get(h, "/api/cheap", "bob"); rec.Code != http.StatusOK {
		t.Fatalf("bob: status %d, want 200", rec.Code)
	}
	// Unlimited routes are unaffected.
	if rec := get(h, "/healthz", "alice"); rec.Code != http.StatusOK {
		t.Fatalf("healthz: status %d, want 200", rec.Code)
	}
}

func TestMiddleware_ComposesRules(t *testing.T) {
	clk := clock.NewFake(time.Unix(1_700_000_000, 0))
	h := newServer(t, memory.NewFixedWindow(clk),
		policy.Rule{Name: "u", Scope: policy.ScopeUser, Limit: limiter.Limit{Rate: 10, Period: 10 * time.Second}},
		policy.Rule{Name: "exp", Scope: policy.ScopeUserEndpoint, Endpoints: []string{"/api/expensive"},
			Limit: limiter.Limit{Rate: 1, Period: 10 * time.Second}},
	)

	rec := get(h, "/api/expensive", "alice")
	if rec.Code != http.StatusOK {
		t.Fatalf("first expensive: %d", rec.Code)
	}
	// Headers should describe the tightest rule (exp: 0 remaining), not u.
	if rec.Header().Get(HeaderPolicy) != "exp" || rec.Header().Get(HeaderRemaining) != "0" {
		t.Errorf("headers = policy %q remaining %q, want exp/0",
			rec.Header().Get(HeaderPolicy), rec.Header().Get(HeaderRemaining))
	}
	if rec := get(h, "/api/expensive", "alice"); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("second expensive: %d, want 429", rec.Code)
	}
	// Cheap endpoint still fine under rule u.
	if rec := get(h, "/api/cheap", "alice"); rec.Code != http.StatusOK {
		t.Fatalf("cheap: %d, want 200", rec.Code)
	}
}

func TestMiddleware_BackendErrorIs503(t *testing.T) {
	broken := limiter.Func{Label: "broken", AllowFn: func(context.Context, string, limiter.Limit) (limiter.Decision, error) {
		return limiter.Decision{}, errors.New("redis: connection refused")
	}}
	h := newServer(t, broken,
		policy.Rule{Name: "u", Scope: policy.ScopeUser, Limit: limiter.Limit{Rate: 2, Period: time.Second}},
	)
	if rec := get(h, "/api/cheap", "alice"); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503 when the limiter errors and no resilience layer is configured", rec.Code)
	}
}

func TestIdentify_FallsBackToIP(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/cheap", nil)
	req.RemoteAddr = "10.1.2.3:5555"
	if got := identify(req); got != "ip:10.1.2.3" {
		t.Fatalf("identify = %q", got)
	}
}
