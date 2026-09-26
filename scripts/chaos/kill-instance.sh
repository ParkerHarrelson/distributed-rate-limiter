#!/usr/bin/env bash
# Kill or restore an API replica. The remaining replicas must keep enforcing
# the same global limits (state is in Redis, not in the replica).
#   ./scripts/chaos/kill-instance.sh api-2 stop
#   ./scripts/chaos/kill-instance.sh api-2 start
set -euo pipefail
cd "$(dirname "$0")/../.."
SVC="${1:-api-2}"; ACTION="${2:-stop}"
docker compose -f deploy/docker-compose.yml "$ACTION" "$SVC" && echo "$SVC: $ACTION"
