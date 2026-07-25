#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id
# and faceId field in GET /api/v1/tenants/{tenantId}/employees/{employeeId}
# Usage: BASE_URL=http://localhost:8080 bash test_status.sh

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

echo "=== Face ID Status Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as admin
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login (HTTP $login_status)"; exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# Setup: create tenant + employee
TS=$(date +%s)
TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Status Corp\",\"slug\":\"status-corp-${TS}\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && { echo "SETUP FAILED: tenant"; exit 1; }

EMP_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Carol","lastName":"Status","email":"carol.status@corp.com","employeeCode":"EMP-CS01","position":"PM","department":"PM"}' \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: employee"; exit 1; }
echo "Tenant=$TENANT_ID  Employee=$EMP_ID"
echo ""

STATUS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

# Test 1: Status before consent → not_enrolled, consentGiven=false
echo "--- Test 1: Status before consent (not_enrolled) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$STATUS_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    s=$(echo "$body" | grep -o '"status":"not_enrolled"' || true)
    c=$(echo "$body" | grep -o '"consentGiven":false' || true)
    if [ -n "$s" ] && [ -n "$c" ]; then
        echo "PASS: Status before consent (not_enrolled, consentGiven=false)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected body: $body"; FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200, got $status"; FAIL=$((FAIL + 1))
fi

# Setup: give consent
curl -s -X POST "$STATUS_URL/consent" -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null

# Test 2: Status after consent → not_enrolled, consentGiven=true
echo ""
echo "--- Test 2: Status after consent (not_enrolled, consentGiven=true) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$STATUS_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    s=$(echo "$body" | grep -o '"status":"not_enrolled"' || true)
    c=$(echo "$body" | grep -o '"consentGiven":true' || true)
    if [ -n "$s" ] && [ -n "$c" ]; then
        echo "PASS: After consent (not_enrolled, consentGiven=true)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected body: $body"; FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200, got $status"; FAIL=$((FAIL + 1))
fi

# Test 3: faceId field in EmployeeDetailResponse after consent
echo ""
echo "--- Test 3: faceId field in GET /employees/{id} ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    faceid=$(echo "$body" | grep -o '"faceId":{[^}]*}' || true)
    if [ -n "$faceid" ]; then
        echo "PASS: faceId field populated in EmployeeDetailResponse"
        PASS=$((PASS + 1))
    else
        echo "FAIL: faceId field missing or null — body: $body"; FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected 200, got $status"; FAIL=$((FAIL + 1))
fi

# Setup: enroll (if fixture available)
if [ -f "$FACE_IMG" ]; then
    curl -s -X POST "$STATUS_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" > /dev/null
    echo ""
    echo "--- Test 4: Status after enrollment (enrolled) ---"
    resp=$(curl -s -w "\n%{http_code}" \
        -X GET "$STATUS_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
    body=$(echo "$resp" | head -n -1)
    status=$(echo "$resp" | tail -n 1)
    if [ "$status" -eq 200 ]; then
        s=$(echo "$body" | grep -o '"status":"enrolled"' || true)
        if [ -n "$s" ]; then
            echo "PASS: Status after enrollment (enrolled)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Unexpected body after enrollment: $body"; FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected 200, got $status"; FAIL=$((FAIL + 1))
    fi
else
    echo ""
    echo "--- Test 4: SKIP (no fixture) ---"
fi

# Test 5: Unauthenticated → 401
echo ""
echo "--- Test 5: Unauthenticated ---"
run_test "Unauthenticated" 401 -X GET "$STATUS_URL"

# Test 6: Employee not found → 404
echo ""
echo "--- Test 6: Employee not found ---"
run_test "Employee not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/00000000-0000-0000-0000-000000000000/face-id" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
