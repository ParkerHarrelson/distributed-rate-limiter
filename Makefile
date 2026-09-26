COMPOSE := docker compose -f deploy/docker-compose.yml
GRADLE  := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

## ---- build & test -------------------------------------------------------

.PHONY: build
build: ## Compile and package both jars (server/build/libs/server.jar, loadgen/build/libs/loadgen.jar)
	$(GRADLE) -q :server:bootJar :loadgen:jar

.PHONY: test
test: ## Unit tests only (Redis integration tests skipped)
	$(GRADLE) test -PskipIntegration

.PHONY: test-all
test-all: ## All tests; Redis ones use Testcontainers (Docker) or REDIS_ADDR if set
	$(GRADLE) test

.PHONY: test-integration
test-integration: ## Only the Redis integration tests (set REDIS_ADDR=localhost:6379 to reuse the stack's Redis)
	$(GRADLE) :core:test --tests 'dev.parkerharrelson.ratelimiter.core.redis.*' --rerun

.PHONY: test-v
test-v: ## Show every contract test as PASSED / SKIPPED / FAILED (skipped = exercise not done yet)
	$(GRADLE) :core:test --rerun -PskipIntegration 2>&1 | grep -E 'PASSED|SKIPPED|FAILED' | sed 's/dev.parkerharrelson.ratelimiter.core.//' | sort

.PHONY: bench
bench: ## JMH micro-benchmarks for the in-memory algorithms (results in core/build/results/jmh)
	$(GRADLE) :core:jmh

.PHONY: check
check: ## Full Gradle check (compile everything, run all tests)
	$(GRADLE) check

.PHONY: clean
clean: ## Remove build outputs
	$(GRADLE) clean

## ---- run ---------------------------------------------------------------

.PHONY: run
run: ## Run one replica locally against localhost:6379 (PORT=8080)
	$(GRADLE) :server:bootRun

.PHONY: run-memory
run-memory: ## Run one replica locally with the in-memory backend (no Redis needed)
	BACKEND=memory $(GRADLE) :server:bootRun

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

.PHONY: redis
redis: ## Start a standalone Redis on :6379 (for `make run` or REDIS_ADDR-based tests)
	@docker rm -f rl-redis >/dev/null 2>&1 || true
	docker run -d --name rl-redis -p 6379:6379 redis:7-alpine >/dev/null
	@echo "redis on localhost:6379 (stop: docker rm -f rl-redis)"

## ---- experiments -------------------------------------------------------

.PHONY: demo
demo: ## Bring the stack up and run the scripted demo
	./scripts/demo.sh

.PHONY: loadgen
loadgen: ## Run the load generator against the stack; e.g. make loadgen ARGS="-rps 200 -duration 30s"
	./scripts/loadgen.sh $(ARGS)

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
