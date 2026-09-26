#!/usr/bin/env bash
# Remove all toxics and restore every service.
set -euo pipefail
cd "$(dirname "$0")/../.."
TOXI="${TOXIPROXY_URL:-http://localhost:8474}"
for t in latency blackhole; do curl -s -X DELETE "$TOXI/proxies/redis/toxics/$t" >/dev/null || true; done
docker compose -f deploy/docker-compose.yml start redis api-1 api-2 api-3 >/dev/null 2>&1 || true
echo "chaos reset"
