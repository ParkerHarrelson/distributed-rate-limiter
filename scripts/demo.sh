#!/usr/bin/env bash
# One-command demo: bring the stack up, hammer it, show the limiter working.
#
#   ./scripts/demo.sh                      # fixed_window (default)
#   ALGORITHM=token_bucket ./scripts/demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f deploy/docker-compose.yml"
ALGORITHM="${ALGORITHM:-fixed_window}"

echo "==> starting stack (algorithm=$ALGORITHM)"
ALGORITHM="$ALGORITHM" $COMPOSE up -d --build --remove-orphans
./scripts/wait-healthy.sh

echo
echo "==> active policy (from api-1)"
curl -s localhost:8081/limits | python3 -m json.tool 2>/dev/null || curl -s localhost:8081/limits
echo

echo "==> single user burst: 60 requests as user 'alice' spread across 3 replicas (limit is 50/10s)"
allowed=0; denied=0
for i in $(seq 1 60); do
  port=$((8081 + (i % 3)))
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'X-User-ID: alice' "http://localhost:$port/api/cheap")
  if [ "$code" = "200" ]; then allowed=$((allowed+1)); else denied=$((denied+1)); fi
done
echo "    allowed=$allowed denied=$denied  <- one global budget enforced across all replicas"
echo
echo "==> response headers on a denied request"
curl -s -D - -o /dev/null -H 'X-User-ID: alice' http://localhost:8081/api/cheap | grep -iE '^(HTTP|X-RateLimit|Retry-After|X-Instance)'
echo

echo "==> steady load: 5 users x 2x their limit for 20s"
go run ./cmd/loadgen -mode steady -users 5 -rps 50 -duration 20s -label "$ALGORITHM" -out "results/demo-$ALGORITHM.csv"

echo "Grafana:    http://localhost:3000   (dashboard: Distributed Rate Limiter)"
echo "Prometheus: http://localhost:9090"
echo "Stop with:  make down"
