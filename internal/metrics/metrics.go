// Package metrics defines the Prometheus instruments the server exposes.
// Everything the Grafana dashboard and the results write-up rely on is here.
package metrics

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics holds every instrument. Label cardinality is kept deliberately low:
// rule and algorithm are bounded by config, result has three values, path is
// the matched route not the raw URL.
type Metrics struct {
	registry *prometheus.Registry

	// Decisions counts limiter outcomes: result = allowed | denied | error.
	Decisions *prometheus.CounterVec
	// CheckDuration measures one limiter Allow call (i.e. the Redis round trip).
	CheckDuration *prometheus.HistogramVec
	// BackendErrors counts limiter calls that returned an error.
	BackendErrors prometheus.Counter
	// FallbackDecisions counts decisions made by the resilience layer instead
	// of the primary limiter: mode = open | closed | local.
	FallbackDecisions *prometheus.CounterVec
	// HTTPRequests counts responses by route and status.
	HTTPRequests *prometheus.CounterVec
	// HTTPDuration measures full request handling time by route.
	HTTPDuration *prometheus.HistogramVec
	// Info is a constant 1 carrying build/config labels for dashboards.
	Info *prometheus.GaugeVec
}

// New creates and registers all instruments in a private registry.
func New() *Metrics {
	reg := prometheus.NewRegistry()
	m := &Metrics{
		registry: reg,
		Decisions: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "ratelimiter_decisions_total",
			Help: "Rate limit decisions by rule, algorithm and result.",
		}, []string{"rule", "algorithm", "result"}),
		CheckDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "ratelimiter_check_duration_seconds",
			Help:    "Latency of a single limiter Allow call.",
			Buckets: []float64{.0001, .00025, .0005, .001, .0025, .005, .01, .025, .05, .1, .25, .5, 1},
		}, []string{"algorithm", "backend"}),
		BackendErrors: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "ratelimiter_backend_errors_total",
			Help: "Limiter calls that failed (e.g. Redis unreachable or timed out).",
		}),
		FallbackDecisions: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "ratelimiter_fallback_decisions_total",
			Help: "Decisions made by the resilience layer while the primary backend was failing.",
		}, []string{"mode", "result"}),
		HTTPRequests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "HTTP responses by route and status code.",
		}, []string{"route", "status"}),
		HTTPDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "End-to-end HTTP request latency by route.",
			Buckets: prometheus.DefBuckets,
		}, []string{"route"}),
		Info: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "ratelimiter_info",
			Help: "Static configuration of this instance (always 1).",
		}, []string{"instance_id", "algorithm", "backend", "fail_mode"}),
	}
	reg.MustRegister(
		m.Decisions, m.CheckDuration, m.BackendErrors, m.FallbackDecisions,
		m.HTTPRequests, m.HTTPDuration, m.Info,
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
	)
	return m
}

// Handler serves the /metrics endpoint.
func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}
