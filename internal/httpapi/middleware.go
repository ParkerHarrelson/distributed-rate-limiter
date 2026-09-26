// Package httpapi wires the limiter into HTTP: a middleware that enforces the
// policy and a handful of demo endpoints to enforce it on.
package httpapi

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/metrics"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/policy"
)

// Header names. X-RateLimit-* are the de-facto standard (GitHub, Stripe,
// Twitter); Retry-After is RFC 9110. X-RateLimit-Policy names the rule that
// produced the headers so the loadgen can attribute denials.
const (
	HeaderUserID    = "X-User-ID"
	HeaderLimit     = "X-RateLimit-Limit"
	HeaderRemaining = "X-RateLimit-Remaining"
	HeaderReset     = "X-RateLimit-Reset"
	HeaderPolicy    = "X-RateLimit-Policy"
	HeaderInstance  = "X-Instance"
	HeaderRetry     = "Retry-After"
)

// Middleware enforces a Policy using a Limiter.
type Middleware struct {
	Limiter    limiter.Limiter
	Policy     *policy.Policy
	Metrics    *metrics.Metrics
	Logger     *slog.Logger
	InstanceID string
	// Backend is a label for metrics: "redis" or "memory".
	Backend string
}

// Wrap returns next guarded by the rate limiter.
//
// Every matching rule is evaluated and the request is denied if any denies.
// Note the subtlety this creates: if rule A allows (and consumes a token) but
// rule B denies, A's token is gone even though the request never ran. That
// over-counting is a known trade-off of composing independent limits; see
// docs/curriculum.md for the stretch exercise that fixes it.
func (m *Middleware) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userID := identify(r)
		checks := m.Policy.Resolve(userID, r.URL.Path)
		w.Header().Set(HeaderInstance, m.InstanceID)

		// Track the tightest allowed decision so the headers reflect the rule
		// the client is closest to exhausting.
		var tightest *limiter.Decision
		var tightestRule string

		for _, c := range checks {
			d, err := m.allow(r.Context(), c)
			if err != nil {
				m.Logger.Warn("rate limiter unavailable",
					"rule", c.Rule, "key", c.Key, "err", err)
				http.Error(w, "rate limiter unavailable", http.StatusServiceUnavailable)
				return
			}
			if !d.Allowed {
				setHeaders(w, d, c.Rule)
				retry := int(math.Ceil(d.RetryAfter.Seconds()))
				if retry < 1 {
					retry = 1
				}
				w.Header().Set(HeaderRetry, strconv.Itoa(retry))
				http.Error(w, fmt.Sprintf("rate limit exceeded (%s); retry in %s", c.Rule, d.RetryAfter.Round(time.Millisecond)), http.StatusTooManyRequests)
				return
			}
			if tightest == nil || d.Remaining < tightest.Remaining {
				dd := d
				tightest = &dd
				tightestRule = c.Rule
			}
		}
		if tightest != nil {
			setHeaders(w, *tightest, tightestRule)
		}
		next.ServeHTTP(w, r)
	})
}

// allow runs one check and records metrics for it.
func (m *Middleware) allow(ctx context.Context, c policy.Check) (limiter.Decision, error) {
	start := time.Now()
	d, err := m.Limiter.Allow(ctx, c.Key, c.Limit)
	m.Metrics.CheckDuration.WithLabelValues(m.Limiter.Name(), m.Backend).Observe(time.Since(start).Seconds())

	result := "allowed"
	switch {
	case err != nil:
		result = "error"
		m.Metrics.BackendErrors.Inc()
	case !d.Allowed:
		result = "denied"
	}
	m.Metrics.Decisions.WithLabelValues(c.Rule, m.Limiter.Name(), result).Inc()
	return d, err
}

func setHeaders(w http.ResponseWriter, d limiter.Decision, rule string) {
	h := w.Header()
	h.Set(HeaderLimit, strconv.Itoa(d.Limit))
	h.Set(HeaderRemaining, strconv.Itoa(d.Remaining))
	h.Set(HeaderReset, strconv.Itoa(int(math.Ceil(d.ResetAfter.Seconds()))))
	h.Set(HeaderPolicy, rule)
}

// identify extracts the caller identity: X-User-ID, else the client IP.
func identify(r *http.Request) string {
	if id := r.Header.Get(HeaderUserID); id != "" {
		return id
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return "ip:" + host
}

// IsNotImplemented reports whether err is the curriculum sentinel, so the
// server can log a friendlier message.
func IsNotImplemented(err error) bool { return errors.Is(err, limiter.ErrNotImplemented) }
