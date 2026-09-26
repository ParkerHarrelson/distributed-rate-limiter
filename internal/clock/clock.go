// Package clock abstracts time so that rate-limiting algorithms can be tested
// deterministically. Production code uses Real; tests use Fake and call
// Advance to move time forward without sleeping.
package clock

import (
	"sync"
	"time"
)

// Clock is the minimal time source a limiter needs.
type Clock interface {
	Now() time.Time
}

// Real reads the system clock.
type Real struct{}

// Now returns the current wall-clock time.
func (Real) Now() time.Time { return time.Now() }

// Fake is a manually-advanced clock for tests. It is safe for concurrent use.
type Fake struct {
	mu  sync.Mutex
	now time.Time
}

// NewFake returns a Fake clock frozen at start.
func NewFake(start time.Time) *Fake {
	return &Fake{now: start}
}

// Now returns the frozen time.
func (f *Fake) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

// Advance moves the clock forward by d.
func (f *Fake) Advance(d time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = f.now.Add(d)
}

// Set jumps the clock to t (useful for simulating skew or window boundaries).
func (f *Fake) Set(t time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = t
}
