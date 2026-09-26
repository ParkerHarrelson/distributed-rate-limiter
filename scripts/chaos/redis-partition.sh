#!/usr/bin/env bash
# Simulate a network partition between the API replicas and Redis. Unlike
# stopping Redis (connection refused, fast), a partition makes calls hang until
# the client timeout fires - the more expensive failure.
#   ./scripts/chaos/redis-partition.sh on
#   ./scripts/chaos/redis-partition.sh off
set -euo pipefail
TOXI="${TOXIPROXY_URL:-http://localhost:8474}"
case "${1:-on}" in
  on)
    curl -fsS -X POST "$TOXI/proxies/redis/toxics" -H 'Content-Type: application/json' \
      -d '{"name":"blackhole","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":0}}' >/dev/null
    echo "partition ON: Redis traffic is black-holed (connections hang)";;
  off)
    curl -fsS -X DELETE "$TOXI/proxies/redis/toxics/blackhole" && echo "partition OFF";;
  *) echo "usage: $0 on|off" >&2; exit 2;;
esac
