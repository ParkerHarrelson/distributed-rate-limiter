package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/metrics"
	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/policy"
)

// Health is what /healthz reports.
type Health struct {
	Status     string `json:"status"`
	InstanceID string `json:"instance_id"`
	Algorithm  string `json:"algorithm"`
	Backend    string `json:"backend"`
	FailMode   string `json:"fail_mode,omitempty"`
	Breaker    string `json:"breaker,omitempty"`
}

// RouterOptions configure NewRouter.
type RouterOptions struct {
	Middleware *Middleware
	Metrics    *metrics.Metrics
	Policy     *policy.Policy
	Health     func() Health
}

// NewRouter builds the server's handler tree.
//
//	GET /api/cheap        rate limited, returns immediately
//	GET /api/expensive    rate limited, simulates 20ms of work
//	GET /api/echo/{name}  rate limited, demonstrates a path with a parameter
//	GET /healthz          not rate limited
//	GET /limits           not rate limited; dumps the active policy
//	GET /metrics          not rate limited; Prometheus exposition
func NewRouter(o RouterOptions) http.Handler {
	mux := http.NewServeMux()

	api := http.NewServeMux()
	api.HandleFunc("GET /api/cheap", instrument(o.Metrics, "/api/cheap", cheap))
	api.HandleFunc("GET /api/expensive", instrument(o.Metrics, "/api/expensive", expensive))
	api.HandleFunc("GET /api/echo/{name}", instrument(o.Metrics, "/api/echo/{name}", echo))
	mux.Handle("/api/", o.Middleware.Wrap(api))

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, o.Health())
	})
	mux.HandleFunc("GET /limits", func(w http.ResponseWriter, _ *http.Request) {
		type rule struct {
			Name      string   `json:"name"`
			Scope     string   `json:"scope"`
			Endpoints []string `json:"endpoints,omitempty"`
			Users     []string `json:"users,omitempty"`
			Limit     string   `json:"limit"`
		}
		var out []rule
		for _, r := range o.Policy.Rules() {
			out = append(out, rule{r.Name, string(r.Scope), r.Endpoints, r.Users, r.Limit.String()})
		}
		writeJSON(w, http.StatusOK, out)
	})
	mux.Handle("GET /metrics", o.Metrics.Handler())
	return mux
}

func cheap(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "cost": "cheap"})
}

func expensive(w http.ResponseWriter, _ *http.Request) {
	time.Sleep(20 * time.Millisecond)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "cost": "expensive"})
}

func echo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "name": r.PathValue("name")})
}

// instrument records request count and latency under a fixed route label.
// The 429s produced by the middleware are recorded there, not here, since
// they never reach the handler; see statusRecorder in the middleware wrapper.
func instrument(m *metrics.Metrics, route string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		h(rec, r)
		m.HTTPRequests.WithLabelValues(route, strconv.Itoa(rec.status)).Inc()
		m.HTTPDuration.WithLabelValues(route).Observe(time.Since(start).Seconds())
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
