#!/usr/bin/env bash
# Wait until every API replica answers /healthz.
set -euo pipefail
PORTS=(${PORTS:-8081 8082 8083})
for i in $(seq 1 60); do
  ok=0
  for p in "${PORTS[@]}"; do
    if curl -fsS "http://localhost:$p/healthz" >/dev/null 2>&1; then ok=$((ok+1)); fi
  done
  if [ "$ok" -eq "${#PORTS[@]}" ]; then
    echo "all ${#PORTS[@]} replicas healthy"
    exit 0
  fi
  sleep 1
done
echo "replicas did not become healthy in time" >&2
exit 1
