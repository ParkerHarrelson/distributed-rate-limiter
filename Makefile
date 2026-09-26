COMPOSE := docker compose -f deploy/docker-compose.yml
GOFLAGS := -trimpath
export PATH := $(PATH):/opt/homebrew/bin:$(HOME)/go/bin

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

## ---- build & test -------------------------------------------------------

.PHONY: build
build: ## Build server and loadgen binaries into ./bin
	@mkdir -p bin
	go build $(GOFLAGS) -o bin/server ./cmd/server
	go build $(GOFLAGS) -o bin/loadgen ./cmd/loadgen

.PHONY: test
test: ## Unit tests (Redis tests skip unless REDIS_ADDR is set)
	go test -race -count=1 ./...

.PHONY: test-v
test-v: ## Unit tests, verbose, showing which exercises are still skipped
	go test -race -count=1 -v ./internal/limiter/... ./internal/resilience/... 2>&1 | grep -E '^(---|\s+---|ok|FAIL)'

.PHONY: redis
redis: ## Start a standalone Redis on :6379 for integration tests
	@docker rm -f rl-redis >/dev/null 2>&1 || true
	docker run -d --name rl-redis -p 6379:6379 redis:7-alpine >/dev/null
	@echo "redis on localhost:6379 (stop: docker rm -f rl-redis)"

.PHONY: test-integration
test-integration: ## Run Redis integration tests (needs `make redis` or a running stack)
	REDIS_ADDR=$${REDIS_ADDR:-localhost:6379} go test -race -count=1 -v ./internal/limiter/redis/ 2>&1 | grep -E '^(---|\s+---|ok|FAIL|\s+.*\.go:)'

.PHONY: bench
bench: ## Micro-benchmarks for the in-memory algorithms
	go test -run '^$$' -bench . -benchmem ./internal/limiter/memory/

.PHONY: lint
lint: ## go vet + golangci-lint (if installed)
	go vet ./...
	@command -v golangci-lint >/dev/null && golangci-lint run ./... || echo "golangci-lint not installed; skipped (brew install golangci-lint)"

.PHONY: fmt
fmt: ## gofmt everything
	gofmt -w ./cmd ./internal

.PHONY: tidy
tidy: ## go mod tidy
	go mod tidy

## ---- run ---------------------------------------------------------------

.PHONY: run
run: ## Run one server locally against localhost:6379 (PORT=8080)
	go run ./cmd/server

.PHONY: run-memory
run-memory: ## Run one server locally with the in-memory backend (no Redis needed)
	BACKEND=memory go run ./cmd/server

## ---- stack --------------------------------------------------------------

.PHONY: up
up: ## Start the full stack (3 replicas, Redis, Toxiproxy, Prometheus, Grafana)
	$(COMPOSE) up -d --build --remove-orphans
	./scripts/wait-healthy.sh

.PHONY: down
down: ## Stop the stack and remove volumes
	$(COMPOSE) down -v --remove-orphans
	@docker rm -f rl-redis >/dev/null 2>&1 || true

.PHONY: restart-api
restart-api: ## Recreate the API replicas (use with ALGORITHM=... FAIL_MODE=...)
	$(COMPOSE) up -d --force-recreate api-1 api-2 api-3
	./scripts/wait-healthy.sh

.PHONY: logs
logs: ## Tail API logs
	$(COMPOSE) logs -f api-1 api-2 api-3

.PHONY: ps
ps: ## Show stack status
	$(COMPOSE) ps

.PHONY: flush
flush: ## Wipe all limiter state in Redis
	$(COMPOSE) exec -T redis redis-cli FLUSHALL

## ---- experiments -------------------------------------------------------

.PHONY: demo
demo: ## Bring the stack up and run the scripted demo
	./scripts/demo.sh

.PHONY: loadgen
loadgen: ## Run the load generator against the stack; e.g. make loadgen ARGS="-rps 200 -duration 30s"
	go run ./cmd/loadgen $(ARGS)

.PHONY: bench-all
bench-all: ## Compare every algorithm under identical load; writes results/
	./scripts/bench.sh

.PHONY: chaos-latency chaos-partition chaos-redis-down chaos-kill chaos-reset
chaos-latency: ## Inject Redis latency: make chaos-latency MS=200 (MS=off to remove)
	./scripts/chaos/redis-latency.sh $(or $(MS),200)
chaos-partition: ## Black-hole Redis traffic: make chaos-partition STATE=on|off
	./scripts/chaos/redis-partition.sh $(or $(STATE),on)
chaos-redis-down: ## Stop/start Redis: make chaos-redis-down ACTION=stop|start
	./scripts/chaos/redis-down.sh $(or $(ACTION),stop)
chaos-kill: ## Stop/start a replica: make chaos-kill SVC=api-2 ACTION=stop|start
	./scripts/chaos/kill-instance.sh $(or $(SVC),api-2) $(or $(ACTION),stop)
chaos-reset: ## Remove all faults
	./scripts/chaos/reset.sh
