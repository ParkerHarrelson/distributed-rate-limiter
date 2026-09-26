#!/usr/bin/env bash
# Add (or remove) latency between the API replicas and Redis via Toxiproxy.
#   ./scripts/chaos/redis-latency.sh 200      # +200ms (+-50ms jitter) on every Redis call
#   ./scripts/chaos/redis-latency.sh off
set -euo pipefail
TOXI="${TOXIPROXY_URL:-http://localhost:8474}"
if [ "${1:-}" = "off" ]; then
  curl -fsS -X DELETE "$TOXI/proxies/redis/toxics/latency" && echo "latency removed"
  exit 0
fi
MS="${1:-200}"
curl -fsS -X POST "$TOXI/proxies/redis/toxics" -H 'Content-Type: application/json' \
  -d "{\"name\":\"latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"latency\":$MS,\"jitter\":$((MS/4))}}" >/dev/null
echo "Redis latency +${MS}ms (jitter $((MS/4))ms). Redis timeout in the API is REDIS_TIMEOUT (default 50ms)."
