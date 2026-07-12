#!/usr/bin/env bash
# Tests for data masking layer (task 145)
# Verifies that email and phone are masked in EmployeeResponse
# for non-admin callers, and unmasked for admin callers.
#
# Required env vars:
#   BASE_URL         - e.g. http://localhost:8080
#   TENANT_ID        - a valid tenant UUID
#   EMPLOYEE_ID      - a valid employee UUID within TENANT_ID
#   HR_TOKEN         - JWT for HR manager (should see masked PII)
#   ADMIN_TOKEN      - JWT for TENANT_ADMIN (users:create permission — should see full PII)
#
# Optional:
#   PLATFORM_ADMIN_TOKEN - JWT for platform admin (should see full PII)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-}"
EMPLOYEE_ID="${EMPLOYEE_ID:-}"
HR_TOKEN="${HR_TOKEN:-}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
PLATFORM_ADMIN_TOKEN="${PLATFORM_ADMIN_TOKEN:-}"

PASS=0
FAIL=0

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

check_masked() {
    local name="$1" field="$2" token="$3" url="$4"
    local body
    body=$(curl -s -H "Authorization: Bearer $token" "$url")
    local value
    value=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('$field',''))" 2>/dev/null || echo "")
    # masked email contains *** but still has @ sign
    if echo "$value" | grep -q '\*\*\*'; then
        echo "PASS: $name — '$field' is masked (value: $value)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — '$field' does NOT appear masked (value: $value)"
        FAIL=$((FAIL + 1))
    fi
}

check_unmasked() {
    local name="$1" field="$2" token="$3" url="$4"
    local body
    body=$(curl -s -H "Authorization: Bearer $token" "$url")
    local value
    value=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('$field',''))" 2>/dev/null || echo "")
    if echo "$value" | grep -q '\*\*\*'; then
        echo "FAIL: $name — '$field' is masked but should be plain (value: $value)"
        FAIL=$((FAIL + 1))
    else
        echo "PASS: $name — '$field' is unmasked (value: $value)"
        PASS=$((PASS + 1))
    fi
}

if [ -z "$TENANT_ID" ] || [ -z "$EMPLOYEE_ID" ]; then
    echo "SKIP: TENANT_ID and EMPLOYEE_ID required for masking tests"
    echo ""
    echo "=== Results ==="
    echo "PASS: $PASS  FAIL: $FAIL"
    [ "$FAIL" -eq 0 ]
    exit 0
fi

EMPLOYEE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMPLOYEE_ID"

echo "=== Email + phone masking for HR callers ==="

if [ -z "$HR_TOKEN" ]; then
    echo "SKIP: HR_TOKEN not set"
else
    check_masked "HR caller sees masked email" "email" "$HR_TOKEN" "$EMPLOYEE_URL"
    check_masked "HR caller sees masked phone" "phone" "$HR_TOKEN" "$EMPLOYEE_URL"
fi

echo ""
echo "=== Email + phone unmasked for TENANT_ADMIN callers ==="

if [ -z "$ADMIN_TOKEN" ]; then
    echo "SKIP: ADMIN_TOKEN not set"
else
    check_unmasked "TENANT_ADMIN sees plain email" "email" "$ADMIN_TOKEN" "$EMPLOYEE_URL"
    check_unmasked "TENANT_ADMIN sees plain phone" "phone" "$ADMIN_TOKEN" "$EMPLOYEE_URL"
fi

echo ""
echo "=== Email + phone unmasked for PLATFORM_ADMIN callers ==="

if [ -z "$PLATFORM_ADMIN_TOKEN" ]; then
    echo "SKIP: PLATFORM_ADMIN_TOKEN not set"
else
    check_unmasked "PLATFORM_ADMIN sees plain email" "email" "$PLATFORM_ADMIN_TOKEN" "$EMPLOYEE_URL"
    check_unmasked "PLATFORM_ADMIN sees plain phone" "phone" "$PLATFORM_ADMIN_TOKEN" "$EMPLOYEE_URL"
fi

echo ""
echo "=== Results ==="
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
