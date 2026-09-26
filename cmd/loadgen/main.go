// Command loadgen drives traffic at one or more server replicas and reports
// how the rate limiter behaved. It is deliberately open-loop: requests are
// sent at a fixed arrival rate regardless of how fast responses come back,
// which is what a population of independent clients looks like and what a
// rate limiter has to cope with.
//
// Two modes:
//
//	steady    constant aggregate RPS spread across users and targets. Shows
//	          whether the enforced rate matches the configured limit and what
//	          the limiter costs in latency.
//	boundary  quiet, then a burst just before a window boundary and another
//	          just after it. Shows the fixed-window 2x problem and how the
//	          other algorithms handle the same traffic.
//
// Output: a summary table on stdout (markdown, paste-able into docs), and
// optionally a per-second CSV (-out) for plotting.
package main

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"math/rand/v2"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

type result struct {
	at       time.Time
	latency  time.Duration
	status   int
	target   int
	policy   string
	instance string
	err      bool
}

type options struct {
	targets  []string
	users    int
	rps      float64
	duration time.Duration
	path     string
	mode     string
	period   time.Duration
	burst    int
	cycles   int
	out      string
	jsonOut  string
	timeout  time.Duration
	label    string
}

func main() {
	var o options
	var targets string
	flag.StringVar(&targets, "targets", "http://localhost:8081,http://localhost:8082,http://localhost:8083", "comma-separated base URLs, requests round-robin across them")
	flag.IntVar(&o.users, "users", 5, "number of distinct X-User-ID values (user-0 .. user-N-1)")
	flag.Float64Var(&o.rps, "rps", 100, "steady mode: aggregate requests per second")
	flag.DurationVar(&o.duration, "duration", 20*time.Second, "steady mode: how long to run")
	flag.StringVar(&o.path, "path", "/api/cheap", "request path")
	flag.StringVar(&o.mode, "mode", "steady", "steady | boundary")
	flag.DurationVar(&o.period, "period", 10*time.Second, "boundary mode: the limit's period (bursts align to it)")
	flag.IntVar(&o.burst, "burst", 20, "boundary mode: requests per burst, per user")
	flag.IntVar(&o.cycles, "cycles", 3, "boundary mode: how many boundaries to test")
	flag.StringVar(&o.out, "out", "", "write per-second CSV here")
	flag.StringVar(&o.jsonOut, "json", "", "write JSON summary here")
	flag.DurationVar(&o.timeout, "timeout", 2*time.Second, "per-request timeout")
	flag.StringVar(&o.label, "label", "", "free-text label included in the summary (e.g. algorithm name)")
	flag.Parse()
	o.targets = strings.Split(targets, ",")

	client := &http.Client{Timeout: o.timeout, Transport: &http.Transport{
		MaxIdleConnsPerHost: 256, MaxConnsPerHost: 0, IdleConnTimeout: 30 * time.Second,
	}}

	var results []result
	var start time.Time
	switch o.mode {
	case "steady":
		start = time.Now()
		results = runSteady(client, o)
	case "boundary":
		start = time.Now()
		results = runBoundary(client, o)
	default:
		fmt.Fprintf(os.Stderr, "unknown mode %q\n", o.mode)
		os.Exit(2)
	}
	report(o, start, results)
}

// ---------------------------------------------------------------------------

func fire(client *http.Client, o options, target int, user string, out chan<- result, wg *sync.WaitGroup) {
	defer wg.Done()
	req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, o.targets[target]+o.path, nil)
	req.Header.Set("X-User-ID", user)
	t0 := time.Now()
	resp, err := client.Do(req)
	r := result{at: t0, latency: time.Since(t0), target: target}
	if err != nil {
		r.err = true
		r.status = 0
	} else {
		r.status = resp.StatusCode
		r.policy = resp.Header.Get("X-RateLimit-Policy")
		r.instance = resp.Header.Get("X-Instance")
		_ = resp.Body.Close()
	}
	out <- r
}

func collect(out <-chan result, done <-chan struct{}) []result {
	var results []result
	for {
		select {
		case r := <-out:
			results = append(results, r)
		case <-done:
			for {
				select {
				case r := <-out:
					results = append(results, r)
				default:
					return results
				}
			}
		}
	}
}

func runSteady(client *http.Client, o options) []result {
	fmt.Fprintf(os.Stderr, "steady: %.0f rps for %s across %d users and %d targets -> %s\n",
		o.rps, o.duration, o.users, len(o.targets), o.path)

	out := make(chan result, 4096)
	done := make(chan struct{})
	var results []result
	var collectWG sync.WaitGroup
	collectWG.Add(1)
	go func() { defer collectWG.Done(); results = collect(out, done) }()

	interval := time.Duration(float64(time.Second) / o.rps)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	deadline := time.After(o.duration)
	var wg sync.WaitGroup
	i := 0
loop:
	for {
		select {
		case <-deadline:
			break loop
		case <-ticker.C:
			user := fmt.Sprintf("user-%d", rand.IntN(o.users))
			wg.Add(1)
			go fire(client, o, i%len(o.targets), user, out, &wg)
			i++
		}
	}
	wg.Wait()
	close(done)
	collectWG.Wait()
	return results
}

// runBoundary waits for the next wall-clock boundary of o.period, then sends
// o.burst requests per user 200ms before it and o.burst per user 200ms after.
// Because window algorithms align to absolute time (floor(now/period)), the
// loadgen can predict exactly where the boundary falls.
func runBoundary(client *http.Client, o options) []result {
	fmt.Fprintf(os.Stderr, "boundary: %d cycles of %d-request bursts per user (x%d users) straddling %s boundaries -> %s\n",
		o.cycles, o.burst, o.users, o.period, o.path)

	out := make(chan result, 8192)
	done := make(chan struct{})
	var results []result
	var collectWG sync.WaitGroup
	collectWG.Add(1)
	go func() { defer collectWG.Done(); results = collect(out, done) }()

	const lead = 200 * time.Millisecond
	var wg sync.WaitGroup
	burst := func() {
		for u := 0; u < o.users; u++ {
			user := fmt.Sprintf("user-%d", u)
			for i := 0; i < o.burst; i++ {
				wg.Add(1)
				go fire(client, o, i%len(o.targets), user, out, &wg)
			}
		}
		wg.Wait()
	}
	for c := 0; c < o.cycles; c++ {
		now := time.Now()
		next := now.Truncate(o.period).Add(o.period)
		if next.Sub(now) < lead+500*time.Millisecond {
			next = next.Add(o.period)
		}
		fmt.Fprintf(os.Stderr, "  cycle %d: boundary at %s (in %s)\n", c+1, next.Format("15:04:05.000"), next.Sub(now).Round(time.Millisecond))
		time.Sleep(time.Until(next.Add(-lead)))
		burst()
		time.Sleep(time.Until(next.Add(lead)))
		burst()
	}
	close(done)
	collectWG.Wait()
	return results
}

// ---------------------------------------------------------------------------

type summary struct {
	Label        string         `json:"label"`
	Mode         string         `json:"mode"`
	Path         string         `json:"path"`
	Targets      int            `json:"targets"`
	Users        int            `json:"users"`
	OfferedRPS   float64        `json:"offered_rps"`
	Duration     float64        `json:"duration_s"`
	Total        int            `json:"total"`
	Allowed      int            `json:"allowed"`
	Denied       int            `json:"denied"`
	Errors       int            `json:"errors"`
	Other        int            `json:"other_status"`
	AllowedRPS   float64        `json:"allowed_rps"`
	LatencyP50ms float64        `json:"latency_p50_ms"`
	LatencyP95ms float64        `json:"latency_p95_ms"`
	LatencyP99ms float64        `json:"latency_p99_ms"`
	LatencyMaxms float64        `json:"latency_max_ms"`
	ByInstance   map[string]int `json:"by_instance"`
	ByPolicy     map[string]int `json:"denied_by_policy"`
	Boundaries   []boundaryStat `json:"boundaries,omitempty"`
}

type boundaryStat struct {
	Boundary      string `json:"boundary"`
	BeforeAllowed int    `json:"before_allowed"`
	AfterAllowed  int    `json:"after_allowed"`
	Sent          int    `json:"sent_each_side"`
}

func percentile(sorted []time.Duration, p float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(float64(len(sorted)-1) * p)
	return sorted[idx]
}

func report(o options, start time.Time, results []result) {
	s := summary{Label: o.label, Mode: o.mode, Path: o.path, Targets: len(o.targets), Users: o.users,
		ByInstance: map[string]int{}, ByPolicy: map[string]int{}}
	if o.mode == "steady" {
		s.OfferedRPS = o.rps
	}
	var lat []time.Duration
	var end time.Time
	for _, r := range results {
		s.Total++
		switch {
		case r.err:
			s.Errors++
		case r.status == http.StatusOK:
			s.Allowed++
			s.ByInstance[r.instance]++
		case r.status == http.StatusTooManyRequests:
			s.Denied++
			s.ByPolicy[r.policy]++
		default:
			s.Other++
		}
		lat = append(lat, r.latency)
		if r.at.After(end) {
			end = r.at
		}
	}
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	s.Duration = end.Sub(start).Seconds()
	if s.Duration <= 0 {
		s.Duration = 1
	}
	s.AllowedRPS = float64(s.Allowed) / s.Duration
	ms := func(d time.Duration) float64 { return float64(d.Microseconds()) / 1000 }
	s.LatencyP50ms = ms(percentile(lat, 0.50))
	s.LatencyP95ms = ms(percentile(lat, 0.95))
	s.LatencyP99ms = ms(percentile(lat, 0.99))
	if len(lat) > 0 {
		s.LatencyMaxms = ms(lat[len(lat)-1])
	}

	if o.mode == "boundary" {
		s.Boundaries = boundaryStats(o, results)
	}

	fmt.Println()
	if o.label != "" {
		fmt.Printf("### %s (%s, %s)\n\n", o.label, o.mode, o.path)
	}
	fmt.Println("| metric | value |")
	fmt.Println("|---|---|")
	fmt.Printf("| requests sent | %d |\n", s.Total)
	fmt.Printf("| allowed (200) | %d |\n", s.Allowed)
	fmt.Printf("| denied (429) | %d |\n", s.Denied)
	fmt.Printf("| errors / other | %d / %d |\n", s.Errors, s.Other)
	if o.mode == "steady" {
		fmt.Printf("| offered rps | %.1f |\n", s.OfferedRPS)
		fmt.Printf("| allowed rps | %.1f |\n", s.AllowedRPS)
	}
	fmt.Printf("| latency p50 / p95 / p99 / max (ms) | %.2f / %.2f / %.2f / %.2f |\n", s.LatencyP50ms, s.LatencyP95ms, s.LatencyP99ms, s.LatencyMaxms)
	fmt.Printf("| allowed by instance | %s |\n", fmtMap(s.ByInstance))
	if len(s.ByPolicy) > 0 {
		fmt.Printf("| denied by policy | %s |\n", fmtMap(s.ByPolicy))
	}
	if len(s.Boundaries) > 0 {
		fmt.Println()
		fmt.Println("| boundary | sent each side | allowed before | allowed after | allowed within ~400ms |")
		fmt.Println("|---|---|---|---|---|")
		for _, b := range s.Boundaries {
			fmt.Printf("| %s | %d | %d | %d | **%d** |\n", b.Boundary, b.Sent, b.BeforeAllowed, b.AfterAllowed, b.BeforeAllowed+b.AfterAllowed)
		}
	}
	fmt.Println()

	if o.out != "" {
		if err := writeCSV(o.out, start, results); err != nil {
			fmt.Fprintln(os.Stderr, "write csv:", err)
		}
	}
	if o.jsonOut != "" {
		f, err := os.Create(o.jsonOut)
		if err == nil {
			enc := json.NewEncoder(f)
			enc.SetIndent("", "  ")
			_ = enc.Encode(s)
			_ = f.Close()
		}
	}
}

func boundaryStats(o options, results []result) []boundaryStat {
	byBoundary := map[time.Time]*boundaryStat{}
	var order []time.Time
	for _, r := range results {
		b := r.at.Truncate(o.period)
		if r.at.Sub(b) > o.period/2 {
			b = b.Add(o.period) // sent before the boundary
		}
		st, ok := byBoundary[b]
		if !ok {
			st = &boundaryStat{Boundary: b.Format("15:04:05"), Sent: o.users * o.burst}
			byBoundary[b] = st
			order = append(order, b)
		}
		if r.status == http.StatusOK {
			if r.at.Before(b) {
				st.BeforeAllowed++
			} else {
				st.AfterAllowed++
			}
		}
	}
	sort.Slice(order, func(i, j int) bool { return order[i].Before(order[j]) })
	out := make([]boundaryStat, 0, len(order))
	for _, b := range order {
		out = append(out, *byBoundary[b])
	}
	return out
}

func writeCSV(path string, start time.Time, results []result) error {
	type bucket struct {
		allowed, denied, errors int
		lat                     []time.Duration
	}
	buckets := map[int]*bucket{}
	maxSec := 0
	for _, r := range results {
		sec := int(r.at.Sub(start).Seconds())
		b, ok := buckets[sec]
		if !ok {
			b = &bucket{}
			buckets[sec] = b
		}
		switch {
		case r.err:
			b.errors++
		case r.status == http.StatusOK:
			b.allowed++
		case r.status == http.StatusTooManyRequests:
			b.denied++
		}
		b.lat = append(b.lat, r.latency)
		if sec > maxSec {
			maxSec = sec
		}
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	w := csv.NewWriter(f)
	_ = w.Write([]string{"second", "allowed", "denied", "errors", "p50_ms", "p99_ms"})
	for sec := 0; sec <= maxSec; sec++ {
		b := buckets[sec]
		if b == nil {
			_ = w.Write([]string{fmt.Sprint(sec), "0", "0", "0", "0", "0"})
			continue
		}
		sort.Slice(b.lat, func(i, j int) bool { return b.lat[i] < b.lat[j] })
		_ = w.Write([]string{
			fmt.Sprint(sec), fmt.Sprint(b.allowed), fmt.Sprint(b.denied), fmt.Sprint(b.errors),
			fmt.Sprintf("%.2f", float64(percentile(b.lat, .5).Microseconds())/1000),
			fmt.Sprintf("%.2f", float64(percentile(b.lat, .99).Microseconds())/1000),
		})
	}
	w.Flush()
	return w.Error()
}

func fmtMap(m map[string]int) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		name := k
		if name == "" {
			name = "?"
		}
		parts = append(parts, fmt.Sprintf("%s=%d", name, m[k]))
	}
	return strings.Join(parts, ", ")
}
