// Package policy decides WHICH limits apply to a request. The limiter package
// answers "may this key make another request under this limit?"; this package
// answers "what keys and what limits does this request map to?".
//
// A request is identified by (userID, endpoint). Each Rule declares a Scope
// that selects which of those dimensions become part of the Redis/memory key:
//
//	user           one bucket per user across all endpoints
//	endpoint       one bucket per endpoint shared by all users
//	user_endpoint  one bucket per (user, endpoint) pair
//	global         one bucket for everything
//
// Rules can be restricted to certain endpoints or users (exact match or a
// trailing-* prefix such as "vip-*"). All matching rules apply and a request
// must pass every one of them.
package policy

import (
	"fmt"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/ParkerHarrelson/distributed-rate-limiter/internal/limiter"
)

// Scope selects which request attributes are part of a rule's key.
type Scope string

// Supported scopes.
const (
	ScopeUser         Scope = "user"
	ScopeEndpoint     Scope = "endpoint"
	ScopeUserEndpoint Scope = "user_endpoint"
	ScopeGlobal       Scope = "global"
)

// Rule is one configured limit.
type Rule struct {
	Name      string
	Scope     Scope
	Endpoints []string // empty = all endpoints
	Users     []string // empty = all users
	Limit     limiter.Limit
}

func (r Rule) validate() error {
	if r.Name == "" {
		return fmt.Errorf("rule has no name")
	}
	if strings.ContainsAny(r.Name, ": ") {
		return fmt.Errorf("rule %q: name may not contain ':' or spaces", r.Name)
	}
	switch r.Scope {
	case ScopeUser, ScopeEndpoint, ScopeUserEndpoint, ScopeGlobal:
	default:
		return fmt.Errorf("rule %q: unknown scope %q", r.Name, r.Scope)
	}
	if err := r.Limit.Validate(); err != nil {
		return fmt.Errorf("rule %q: %w", r.Name, err)
	}
	return nil
}

func (r Rule) matches(userID, endpoint string) bool {
	return matchAny(r.Users, userID) && matchAny(r.Endpoints, endpoint)
}

// matchAny returns true if patterns is empty or any pattern matches value.
// A pattern ending in '*' matches by prefix; anything else matches exactly.
func matchAny(patterns []string, value string) bool {
	if len(patterns) == 0 {
		return true
	}
	for _, p := range patterns {
		if strings.HasSuffix(p, "*") {
			if strings.HasPrefix(value, strings.TrimSuffix(p, "*")) {
				return true
			}
		} else if p == value {
			return true
		}
	}
	return false
}

// key builds the limiter key for this rule and request. Colons separate the
// parts; rule names cannot contain colons (validated) so keys are unambiguous.
func (r Rule) key(userID, endpoint string) string {
	switch r.Scope {
	case ScopeUser:
		return r.Name + ":" + userID
	case ScopeEndpoint:
		return r.Name + ":" + endpoint
	case ScopeUserEndpoint:
		return r.Name + ":" + userID + ":" + endpoint
	default:
		return r.Name
	}
}

// Check is one limiter call the middleware must make for a request.
type Check struct {
	Rule  string
	Key   string
	Limit limiter.Limit
}

// Policy is an ordered, validated set of rules.
type Policy struct {
	rules []Rule
}

// New validates rules and returns a Policy. Rule names must be unique.
func New(rules []Rule) (*Policy, error) {
	if len(rules) == 0 {
		return nil, fmt.Errorf("policy: at least one rule is required")
	}
	seen := make(map[string]bool, len(rules))
	for _, r := range rules {
		if err := r.validate(); err != nil {
			return nil, fmt.Errorf("policy: %w", err)
		}
		if seen[r.Name] {
			return nil, fmt.Errorf("policy: duplicate rule name %q", r.Name)
		}
		seen[r.Name] = true
	}
	out := make([]Rule, len(rules))
	copy(out, rules)
	return &Policy{rules: out}, nil
}

// Resolve returns every check that applies to a request, in rule order.
// An empty result means the request is not rate limited at all.
func (p *Policy) Resolve(userID, endpoint string) []Check {
	var checks []Check
	for _, r := range p.rules {
		if r.matches(userID, endpoint) {
			checks = append(checks, Check{Rule: r.Name, Key: r.key(userID, endpoint), Limit: r.Limit})
		}
	}
	return checks
}

// Rules returns a copy of the configured rules.
func (p *Policy) Rules() []Rule {
	out := make([]Rule, len(p.rules))
	copy(out, p.rules)
	return out
}

// ---------------------------------------------------------------------------
// YAML loading
// ---------------------------------------------------------------------------

// File is the on-disk shape of configs/limits.yaml.
type File struct {
	// Algorithm is the default algorithm: fixed_window | sliding_counter | token_bucket.
	Algorithm string `yaml:"algorithm"`
	// FailMode is what to do when the backend is unreachable: "" | open | closed | local.
	FailMode string     `yaml:"fail_mode"`
	Rules    []ruleYAML `yaml:"rules"`
}

type ruleYAML struct {
	Name      string    `yaml:"name"`
	Scope     Scope     `yaml:"scope"`
	Endpoints []string  `yaml:"endpoints"`
	Users     []string  `yaml:"users"`
	Limit     limitYAML `yaml:"limit"`
}

type limitYAML struct {
	Rate   int    `yaml:"rate"`
	Period string `yaml:"period"`
	Burst  int    `yaml:"burst"`
}

// LoadFile reads and validates a limits file.
func LoadFile(path string) (*Policy, File, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, File{}, fmt.Errorf("policy: read %s: %w", path, err)
	}
	return Parse(raw)
}

// Parse validates YAML bytes.
func Parse(raw []byte) (*Policy, File, error) {
	var f File
	if err := yaml.Unmarshal(raw, &f); err != nil {
		return nil, File{}, fmt.Errorf("policy: parse yaml: %w", err)
	}
	rules := make([]Rule, 0, len(f.Rules))
	for _, r := range f.Rules {
		period, err := time.ParseDuration(r.Limit.Period)
		if err != nil {
			return nil, File{}, fmt.Errorf("policy: rule %q: bad period %q: %w", r.Name, r.Limit.Period, err)
		}
		rules = append(rules, Rule{
			Name:      r.Name,
			Scope:     r.Scope,
			Endpoints: r.Endpoints,
			Users:     r.Users,
			Limit:     limiter.Limit{Rate: r.Limit.Rate, Period: period, Burst: r.Limit.Burst},
		})
	}
	p, err := New(rules)
	if err != nil {
		return nil, File{}, err
	}
	return p, f, nil
}
