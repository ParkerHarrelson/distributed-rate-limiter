#!/usr/bin/env bash
# Hard-stop Redis (connection refused) or bring it back.
#   ./scripts/chaos/redis-down.sh stop
#   ./scripts/chaos/redis-down.sh start
set -euo pipefail
cd "$(dirname "$0")/../.."
case "${1:-stop}" in
  stop)  docker compose -f deploy/docker-compose.yml stop redis && echo "redis stopped";;
  start) docker compose -f deploy/docker-compose.yml start redis && echo "redis started (state was lost: no persistence)";;
  *) echo "usage: $0 stop|start" >&2; exit 2;;
esac
