#!/usr/bin/env bash
# Compare algorithms under identical traffic. Writes results/<algo>-*.{csv,json}
# and prints markdown tables to paste into docs/results.md.
#
#   ./scripts/bench.sh                          # all implemented algorithms
#   ALGOS="fixed_window token_bucket" ./scripts/bench.sh
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f deploy/docker-compose.yml"
ALGOS="${ALGOS:-fixed_window sliding_counter token_bucket}"
STEADY_RPS="${STEADY_RPS:-50}"     # 5 users * 50/10s = 25 rps allowed; offer 2x
STEADY_DURATION="${STEADY_DURATION:-30s}"
mkdir -p results

$COMPOSE up -d --build redis toxiproxy prometheus grafana
for algo in $ALGOS; do
  echo
  echo "################ $algo ################"
  ALGORITHM="$algo" $COMPOSE up -d --force-recreate api-1 api-2 api-3
  ./scripts/wait-healthy.sh
  # confirm the algorithm is actually live and implemented
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'X-User-ID: probe' http://localhost:8081/api/cheap)
  if [ "$code" != "200" ]; then
    echo "!! $algo returned HTTP $code on a fresh key - not implemented yet? skipping"
    continue
  fi
  # flush limiter state between runs so algorithms start from the same place
  docker compose -f deploy/docker-compose.yml exec -T redis redis-cli FLUSHALL >/dev/null

  go run ./cmd/loadgen -mode steady -users 5 -rps "$STEADY_RPS" -duration "$STEADY_DURATION" \
     -label "$algo" -out "results/$algo-steady.csv" -json "results/$algo-steady.json" | tee "results/$algo-steady.md"

  docker compose -f deploy/docker-compose.yml exec -T redis redis-cli FLUSHALL >/dev/null
  go run ./cmd/loadgen -mode boundary -users 3 -burst 50 -period 10s -cycles 3 \
     -label "$algo" -json "results/$algo-boundary.json" | tee "results/$algo-boundary.md"
done
echo
echo "results written to results/"
