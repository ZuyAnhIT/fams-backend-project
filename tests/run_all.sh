#!/usr/bin/env bash
# Runs all test suites and prints a summary.
# Usage: BASE_URL=http://localhost:8080 bash run_all.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
export BASE_URL

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TOTAL_SUITES=0
FAILED_SUITES=0
FAILED_NAMES=()

run_suite() {
    local script="$1"
    local name
    name=$(basename "$script")
    TOTAL_SUITES=$((TOTAL_SUITES + 1))

    echo "=============================="
    echo "Running: $name"
    echo "=============================="

    if bash "$script"; then
        echo "[SUITE PASSED] $name"
    else
        echo "[SUITE FAILED] $name"
        FAILED_SUITES=$((FAILED_SUITES + 1))
        FAILED_NAMES+=("$name")
    fi
    echo ""
}

# Discover and run all test scripts
for suite in "$SCRIPT_DIR"/auth/test_*.sh "$SCRIPT_DIR"/tenant/test_*.sh "$SCRIPT_DIR"/subscription/test_*.sh "$SCRIPT_DIR"/rbac/test_*.sh "$SCRIPT_DIR"/employee/test_*.sh "$SCRIPT_DIR"/workspace/test_*.sh "$SCRIPT_DIR"/site/test_*.sh "$SCRIPT_DIR"/checkin/test_*.sh "$SCRIPT_DIR"/attendance/test_*.sh "$SCRIPT_DIR"/randomcheck/test_*.sh "$SCRIPT_DIR"/violation/test_*.sh "$SCRIPT_DIR"/dashboard/test_*.sh "$SCRIPT_DIR"/report/test_*.sh "$SCRIPT_DIR"/search/test_*.sh "$SCRIPT_DIR"/notification/test_*.sh "$SCRIPT_DIR"/audit/test_*.sh "$SCRIPT_DIR"/security/test_*.sh; do
    if [ -f "$suite" ]; then
        [[ "$(basename "$suite")" == *manual* ]] && continue
        run_suite "$suite"
    fi
done

echo "=============================="
echo "SUMMARY"
echo "=============================="
echo "Total suites : $TOTAL_SUITES"
echo "Passed       : $((TOTAL_SUITES - FAILED_SUITES))"
echo "Failed       : $FAILED_SUITES"

if [ "$FAILED_SUITES" -gt 0 ]; then
    echo ""
    echo "Failed suites:"
    for name in "${FAILED_NAMES[@]}"; do
        echo "  - $name"
    done
    exit 1
fi

echo ""
echo "All test suites passed."
exit 0
