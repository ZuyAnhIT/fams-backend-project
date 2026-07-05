#!/usr/bin/env bash
# Tests for DELETE /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id
# Usage: BASE_URL=http://localhost:8080 bash test_revoke.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACE_IMG="$SCRIPT_DIR/fixtures/test_face.jpg"

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Face ID Revoke Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as admin
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: login"; exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)
TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Revoke Corp\",\"slug\":\"revoke-corp-${TS}\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && { echo "SETUP FAILED: tenant"; exit 1; }

EMP_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Dave","lastName":"Revoke","email":"dave.revoke@corp.com","employeeCode":"EMP-DR01","position":"Eng","department":"Tech"}' \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: employee"; exit 1; }
echo "Tenant=$TENANT_ID  Employee=$EMP_ID"

BASE_FACE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

# Test 1: Revoke without a face profile → 404
echo ""
echo "--- Test 1: Revoke with no profile (404) ---"
run_test "Revoke no profile" 404 \
    -X DELETE "$BASE_FACE_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Setup: consent + enroll (if fixture available)
curl -s -X POST "$BASE_FACE_URL/consent" -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null
ENROLLED=false
if [ -f "$FACE_IMG" ]; then
    enroll_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_FACE_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")
    [ "$enroll_status" -eq 200 ] && ENROLLED=true
fi

# Test 2: Happy path — revoke an enrolled profile
echo ""
echo "--- Test 2: Happy path revoke ---"
revoke_resp=$(curl -s -w "\n%{http_code}" \
    -X DELETE "$BASE_FACE_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
revoke_body=$(echo "$revoke_resp" | head -n -1)
revoke_status=$(echo "$revoke_resp" | tail -n 1)
if [ "$revoke_status" -eq 200 ]; then
    s=$(echo "$revoke_body" | grep -o '"status":"revoked"' || true)
    if [ -n "$s" ]; then
        echo "PASS: Happy path revoke (HTTP 200, status=revoked)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected body: $revoke_body"; FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200, got $revoke_status — body: $revoke_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Status after revoke → revoked
echo ""
echo "--- Test 3: Status after revoke ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_FACE_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    s=$(echo "$body" | grep -o '"status":"revoked"' || true)
    if [ -n "$s" ]; then
        echo "PASS: Status after revoke = revoked"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected body: $body"; FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200, got $status"; FAIL=$((FAIL + 1))
fi

# Test 4: Unauthenticated → 401
echo ""
echo "--- Test 4: Unauthenticated ---"
run_test "Unauthenticated" 401 -X DELETE "$BASE_FACE_URL"

# Test 5: Employee not found → 404
echo ""
echo "--- Test 5: Employee not found ---"
run_test "Employee not found" 404 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/00000000-0000-0000-0000-000000000000/face-id" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
