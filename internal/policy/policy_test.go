package policy

import (
	"reflect"
	"testing"
	"time"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

var (
	perSec = limiter.Limit{Rate: 10, Period: time.Second}
	perMin = limiter.Limit{Rate: 100, Period: time.Minute}
)

func mustNew(t *testing.T, rules ...Rule) *Policy {
	t.Helper()
	p, err := New(rules)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return p
}

func TestResolve_Scopes(t *testing.T) {
	p := mustNew(t,
		Rule{Name: "u", Scope: ScopeUser, Limit: perMin},
		Rule{Name: "e", Scope: ScopeEndpoint, Limit: perSec},
		Rule{Name: "ue", Scope: ScopeUserEndpoint, Limit: perSec},
		Rule{Name: "g", Scope: ScopeGlobal, Limit: perSec},
	)
	got := p.Resolve("alice", "/api/cheap")
	want := []Check{
		{Rule: "u", Key: "u:alice", Limit: perMin},
		{Rule: "e", Key: "e:/api/cheap", Limit: perSec},
		{Rule: "ue", Key: "ue:alice:/api/cheap", Limit: perSec},
		{Rule: "g", Key: "g", Limit: perSec},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Resolve = %+v\nwant %+v", got, want)
	}
}

func TestResolve_Filters(t *testing.T) {
	p := mustNew(t,
		Rule{Name: "expensive", Scope: ScopeUser, Endpoints: []string{"/api/expensive"}, Limit: perSec},
		Rule{Name: "vip", Scope: ScopeUser, Users: []string{"vip-*"}, Limit: perMin},
		Rule{Name: "admin-api", Scope: ScopeEndpoint, Endpoints: []string{"/admin/*"}, Limit: perSec},
	)

	cases := []struct {
		user, endpoint string
		wantRules      []string
	}{
		{"alice", "/api/cheap", nil},
		{"alice", "/api/expensive", []string{"expensive"}},
		{"vip-bob", "/api/cheap", []string{"vip"}},
		{"vip-bob", "/api/expensive", []string{"expensive", "vip"}},
		{"alice", "/admin/users", []string{"admin-api"}},
		{"alice", "/administrator", nil},
	}
	for _, c := range cases {
		var got []string
		for _, ch := range p.Resolve(c.user, c.endpoint) {
			got = append(got, ch.Rule)
		}
		if !reflect.DeepEqual(got, c.wantRules) {
			t.Errorf("Resolve(%q, %q) rules = %v, want %v", c.user, c.endpoint, got, c.wantRules)
		}
	}
}

func TestNew_Validation(t *testing.T) {
	bad := [][]Rule{
		nil,
		{{Name: "", Scope: ScopeUser, Limit: perSec}},
		{{Name: "a:b", Scope: ScopeUser, Limit: perSec}},
		{{Name: "x", Scope: "tenant", Limit: perSec}},
		{{Name: "x", Scope: ScopeUser, Limit: limiter.Limit{Rate: 0, Period: time.Second}}},
		{{Name: "x", Scope: ScopeUser, Limit: perSec}, {Name: "x", Scope: ScopeGlobal, Limit: perSec}},
	}
	for i, rules := range bad {
		if _, err := New(rules); err == nil {
			t.Errorf("case %d: expected validation error for %+v", i, rules)
		}
	}
}

func TestParse(t *testing.T) {
	src := []byte(`
algorithm: token_bucket
fail_mode: open
rules:
  - name: user-global
    scope: user
    limit: { rate: 50, period: 10s, burst: 20 }
  - name: expensive
    scope: user_endpoint
    endpoints: [/api/expensive]
    limit: { rate: 5, period: 1m }
`)
	p, f, err := Parse(src)
	if err != nil {
		t.Fatal(err)
	}
	if f.Algorithm != "token_bucket" || f.FailMode != "open" {
		t.Errorf("file header = %+v", f)
	}
	rules := p.Rules()
	if len(rules) != 2 {
		t.Fatalf("got %d rules, want 2", len(rules))
	}
	if want := (limiter.Limit{Rate: 50, Period: 10 * time.Second, Burst: 20}); rules[0].Limit != want {
		t.Errorf("rule[0].Limit = %v, want %v", rules[0].Limit, want)
	}
	if want := (limiter.Limit{Rate: 5, Period: time.Minute}); rules[1].Limit != want {
		t.Errorf("rule[1].Limit = %v, want %v", rules[1].Limit, want)
	}

	if _, _, err := Parse([]byte("rules:\n  - name: x\n    scope: user\n    limit: {rate: 1, period: soon}\n")); err == nil {
		t.Error("expected error for unparseable period")
	}
}
