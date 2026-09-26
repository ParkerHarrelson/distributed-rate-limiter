// Package config reads the server's runtime settings from environment
// variables. Anything that describes *traffic policy* lives in the limits YAML
// (see package policy); anything that describes *this process* lives here.
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config is the fully-resolved server configuration.
type Config struct {
	// Port the HTTP server listens on. Env PORT, default 8080.
	Port int
	// InstanceID names this replica in logs and headers. Env INSTANCE_ID,
	// default hostname.
	InstanceID string
	// Backend is "redis" or "memory". Env BACKEND, default redis.
	Backend string
	// RedisAddr is host:port. Env REDIS_ADDR, default localhost:6379.
	RedisAddr string
	// RedisTimeout bounds each limiter call. Env REDIS_TIMEOUT, default 50ms.
	RedisTimeout time.Duration
	// LimitsFile is the path to the policy YAML. Env LIMITS_FILE, default configs/limits.yaml.
	LimitsFile string
	// Algorithm overrides the YAML default when set. Env ALGORITHM.
	Algorithm string
	// FailMode overrides the YAML default when set. Env FAIL_MODE.
	FailMode string
	// LocalShare is this instance's fraction of a limit when falling back to
	// local enforcement (1/N for N replicas). Env LOCAL_SHARE, default 1.0.
	LocalShare float64
	// ClockSkew shifts this instance's clock for chaos experiments. Only the
	// memory backend honours it (Redis has one clock for everyone, which is
	// the point). Env CLOCK_SKEW, default 0.
	ClockSkew time.Duration
}

// FromEnv builds a Config from the environment, applying defaults.
func FromEnv() (Config, error) {
	host, _ := os.Hostname()
	c := Config{
		Port:         8080,
		InstanceID:   host,
		Backend:      "redis",
		RedisAddr:    "localhost:6379",
		RedisTimeout: 50 * time.Millisecond,
		LimitsFile:   "configs/limits.yaml",
		LocalShare:   1.0,
	}
	var err error
	if v := os.Getenv("PORT"); v != "" {
		if c.Port, err = strconv.Atoi(v); err != nil {
			return c, fmt.Errorf("config: PORT: %w", err)
		}
	}
	if v := os.Getenv("INSTANCE_ID"); v != "" {
		c.InstanceID = v
	}
	if v := os.Getenv("BACKEND"); v != "" {
		c.Backend = v
	}
	if v := os.Getenv("REDIS_ADDR"); v != "" {
		c.RedisAddr = v
	}
	if v := os.Getenv("REDIS_TIMEOUT"); v != "" {
		if c.RedisTimeout, err = time.ParseDuration(v); err != nil {
			return c, fmt.Errorf("config: REDIS_TIMEOUT: %w", err)
		}
	}
	if v := os.Getenv("LIMITS_FILE"); v != "" {
		c.LimitsFile = v
	}
	c.Algorithm = os.Getenv("ALGORITHM")
	c.FailMode = os.Getenv("FAIL_MODE")
	if v := os.Getenv("LOCAL_SHARE"); v != "" {
		if c.LocalShare, err = strconv.ParseFloat(v, 64); err != nil {
			return c, fmt.Errorf("config: LOCAL_SHARE: %w", err)
		}
	}
	if v := os.Getenv("CLOCK_SKEW"); v != "" {
		if c.ClockSkew, err = time.ParseDuration(v); err != nil {
			return c, fmt.Errorf("config: CLOCK_SKEW: %w", err)
		}
	}
	switch c.Backend {
	case "redis", "memory":
	default:
		return c, fmt.Errorf("config: BACKEND must be redis or memory, got %q", c.Backend)
	}
	return c, nil
}
