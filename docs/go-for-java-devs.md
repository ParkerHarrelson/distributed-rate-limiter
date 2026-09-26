# Go for Java developers: just enough for this repo

This is not a Go tutorial. It is the subset of Go you will meet in this
codebase, with the Java idea it replaces. Read it once, then read
`internal/limiter/memory/fixedwindow.go` with it open.

## Packages, not classes

A directory is a package. Every file in it starts with `package memory`.
There is no `public`/`private` keyword: an identifier that starts with a
capital letter is exported (public), lowercase is package-private.

```go
type FixedWindow struct { ... }   // exported type
func NewFixedWindow(...)          // exported constructor (Go has no `new Foo()` with logic)
type fixedWindowState struct{}    // package-private
```

`internal/` is special: packages under it can only be imported from within
this module. It is how Go does "not part of the public API".

## Structs and methods

```go
type FixedWindow struct {
    clk     clock.Clock
    mu      sync.Mutex
    windows map[string]*fixedWindowState
}

func (f *FixedWindow) Allow(ctx context.Context, key string, limit limiter.Limit) (limiter.Decision, error) {
```

`(f *FixedWindow)` is the receiver: `f` is `this`. The `*` means the method
gets a pointer to the struct, so it can mutate fields. Almost always use
pointer receivers for anything with a mutex or a map.

## Interfaces are implicit

```go
type Limiter interface {
    Allow(ctx context.Context, key string, limit Limit) (Decision, error)
    Name() string
}
```

No `implements`. Any type with those two methods *is* a `Limiter`. This is
how `memory.FixedWindow`, `redis.FixedWindow` and your `TokenBucket` all
plug into the same middleware and the same test suite.

## Errors are return values

```go
d, err := l.Allow(ctx, key, lim)
if err != nil {
    return fmt.Errorf("checking %s: %w", key, err)   // %w wraps, like a cause
}
```

There are no exceptions for expected failures. `panic` exists but is for
programmer errors only. `errors.Is(err, limiter.ErrNotImplemented)` is
`instanceof` for wrapped errors.

Multiple return values are normal: `(Decision, error)`. The blank identifier
`_` discards one: `_, err := ...`.

## Pointers, without the pain

`&x` takes the address, `*p` dereferences, but you rarely write `*p` because
Go auto-dereferences for field access: `st.count++` works whether `st` is a
struct or a pointer to one. Structs are value types (copied on assignment,
like a C# struct), so maps of mutable state hold pointers:
`map[string]*fixedWindowState`.

`nil` is `null`. A nil map read returns the zero value; a nil map *write*
panics, so constructors call `make(map[string]*T)`.

## Maps

```go
st, ok := f.windows[key]      // ok is false if absent (no null check dance)
if !ok { st = &fixedWindowState{}; f.windows[key] = st }
delete(f.windows, key)
for k, v := range f.windows { ... }   // iteration order is random on purpose
```

Maps are not thread-safe. `sync.Map` exists but a plain map + mutex is
usually faster and clearer.

## Mutex and defer

```go
f.mu.Lock()
defer f.mu.Unlock()   // runs when the function returns, any path
```

`defer` is `finally` without the block. `sync.Mutex` is `ReentrantLock` but
NOT reentrant: locking twice in the same goroutine deadlocks. There is also
`sync.RWMutex` (`RLock` for readers).

`sync/atomic` gives `atomic.Int64` etc. (`AtomicLong`). Prefer a mutex until
you have measured.

## Goroutines and WaitGroup

```go
var wg sync.WaitGroup
for i := 0; i < 32; i++ {
    wg.Add(1)
    go func() {          // goroutine: a cheap thread, ~2KB stack
        defer wg.Done()
        ...
    }()
}
wg.Wait()
```

`go f()` runs `f` concurrently. Channels (`chan`) are typed queues for
communicating between goroutines; the loadgen uses one. The race detector,
`go test -race`, is the tool you wish Java had; the Makefile turns it on.

## Time

`time.Duration` is an `int64` of nanoseconds with a type on it.
`10 * time.Second` is a Duration. `now.Sub(then)` is a Duration.
`now.UnixNano()` is an int64. Dividing Durations is integer division; convert
to `float64` explicitly if you need fractions. `time.Now()` is the wall clock,
which is why everything here takes a `clock.Clock` instead: tests use a fake.

## Zero values

Every type has a usable zero value: `0`, `""`, `nil`, `false`, and a struct
with all fields zeroed. `var d limiter.Decision` is a valid, all-false
decision. Constructors are only needed when a zero value is not usable (a
nil map).

## Testing

```go
func TestThing(t *testing.T) {
    t.Run("subtest", func(t *testing.T) {
        if got != want {
            t.Fatalf("got %v, want %v", got, want)   // stop this test
        }
        t.Errorf(...)                                // record failure, continue
    })
}
```

`go test ./...` runs everything. Files end in `_test.go`. There is no
assertion library in the stdlib; `if got != want { t.Fatalf(...) }` is the
idiom. Tests in the same package can see private identifiers.
`go test -run 'TestTokenBucket/Refill' -v ./internal/limiter/memory/` runs
one subtest.

## Formatting and tooling

`gofmt` is not optional; there is one style. `go vet` catches common
mistakes. `go build ./...` compiles everything. Unused imports and unused
local variables are compile errors, not warnings, which is annoying for ten
minutes and then you stop noticing.

## Things that will bite you coming from Java

- `:=` declares and assigns; `=` only assigns. Redeclaring with `:=` in an
  inner scope shadows the outer variable silently.
- Integer division truncates. `7 / 2 == 3`. `int(x)` truncates floats toward
  zero, it does not round.
- Slices (`[]T`) are views onto an array: `a := b[1:]` shares memory with `b`.
  `append` may or may not reallocate. Read "Go Slices: usage and internals"
  when you hit a weird bug in the sliding log.
- No generics gymnastics needed here; the code uses none beyond the stdlib.
- `for` is the only loop. `for i := range 10`, `for k, v := range m`,
  `for cond {}`, `for {}`.
- Strings are immutable byte sequences; `len(s)` is bytes, not characters.

## Reading order

1. `internal/limiter/limiter.go` (the interface)
2. `internal/clock/clock.go`
3. `internal/limiter/memory/fixedwindow.go` (the worked example)
4. `internal/limiter/conformance/conformance.go` (what your code must satisfy)
5. `internal/limiter/memory/memory_test.go` (how the suite is invoked)

Then the Tour of Go (https://go.dev/tour) for anything still unclear, and
Effective Go (https://go.dev/doc/effective_go) once things compile.
