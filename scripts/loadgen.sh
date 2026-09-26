#!/usr/bin/env bash
# Runs the load generator, building its jar first if needed.
#   ./scripts/loadgen.sh -mode steady -rps 100 -duration 30s
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=loadgen/build/libs/loadgen.jar
if [ ! -f "$JAR" ] || [ -n "$(find loadgen/src -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  ./gradlew -q :loadgen:jar
fi
exec java -jar "$JAR" "$@"
