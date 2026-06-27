#!/usr/bin/env bash
# Wait for the API to be ready, then seed demo data.
# Run this after "make dev-d" on first setup.
#
# Usage: bash scripts/dev-start.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Waiting for API at $BASE_URL ..."
for i in $(seq 1 300); do
    if curl -sf "$BASE_URL/actuator/health" > /dev/null 2>&1; then
        echo "API is up (${i}s)."
        break
    fi
    if [ "$i" -eq 300 ]; then
        echo "Timed out after 300s. Is 'make dev-d' running?"
        exit 1
    fi
    sleep 1
done

echo ""
bash "$SCRIPT_DIR/seed.sh"
