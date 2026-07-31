#!/usr/bin/env bash
# Tests for Task 127: Face ID enrollment status report
# Usage: BASE_URL=http://localhost:8080 bash test_face_id_report.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

check_val() {
    local name="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

check_match() {
    local name="$1" actual="$2" pattern="$3"
    if [[ "$actual" =~ $pattern ]]; then
        echo "PASS: $name (=$actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — '$actual' does not match /$pattern/"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Face ID Enrollment Report Test (Task 127) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
[ "$(echo "$login_resp" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: login"; exit 1; }
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)
TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"FaceReport Corp\",\"slug\":\"facereport-${TS}\",\"ownerEmail\":\"admin@fams.com\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && { echo "SETUP FAILED: tenant"; exit 1; }

# Invite and register an employee so tenant is non-empty
INV_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"emp.report.${TS}@example.com\",\"role\":\"employee\"}")
[ "$(echo "$INV_RESP" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: invitation"; exit 1; }
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='emp.report.${TS}@example.com' ORDER BY created_at DESC LIMIT 1;" \
    | tr -d '[:space:]')
[ -z "$INV_TOKEN" ] && { echo "SETUP FAILED: invitation token not in DB"; exit 1; }

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

EMP_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"emp.report.${TS}@example.com\",\"password\":\"Employee@1234\"}")
[ "$(echo "$EMP_LOGIN" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: emp login"; exit 1; }
EMP_TOKEN=$(echo "$EMP_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="emp.report.${TS}@example.com"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: employee ID"; exit 1; }

echo "Tenant=$TENANT_ID  Employee=$EMP_ID"
echo ""

# ── Test 1: Unauthenticated → 401 ────────────────────────────────────────────
echo "--- Test 1: Unauthenticated → 401 ---"
HTTP=$(curl -s -o /dev/null -w '%{http_code}' \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment")
check_val "Unauthenticated → 401" "$HTTP" "401"

# ── Test 2: Authenticated → 200, valid shape ──────────────────────────────────
echo ""
echo "--- Test 2: Authenticated → 200, valid response shape ---"
RESP=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment")
HTTP=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment")
check_val "Report → HTTP 200" "$HTTP" "200"

TOTAL=$(echo "$RESP" | grep -o '"totalEmployees":[0-9]*' | grep -o '[0-9]*')
ENROLLED=$(echo "$RESP" | grep -o '"enrolledCount":[0-9]*' | grep -o '[0-9]*')
PENDING=$(echo "$RESP" | grep -o '"pendingCount":[0-9]*' | grep -o '[0-9]*')
NOT_ENROLLED=$(echo "$RESP" | grep -o '"notEnrolledCount":[0-9]*' | grep -o '[0-9]*')
REVOKED=$(echo "$RESP" | grep -o '"revokedCount":[0-9]*' | grep -o '[0-9]*')

check_match "totalEmployees is numeric (>=1)" "$TOTAL" "^[0-9]+$"
check_match "enrolledCount is numeric" "$ENROLLED" "^[0-9]+$"
check_match "notEnrolledCount is numeric" "$NOT_ENROLLED" "^[0-9]+$"

SUM=$((ENROLLED + PENDING + NOT_ENROLLED + REVOKED))
check_val "counts sum equals totalEmployees" "$SUM" "$TOTAL"
echo "  Totals: employees=$TOTAL enrolled=$ENROLLED pending=$PENDING notEnrolled=$NOT_ENROLLED revoked=$REVOKED"

# ── Test 3: records array present ────────────────────────────────────────────
echo ""
echo "--- Test 3: records array present ---"
HAS_CONTENT=$(echo "$RESP" | grep -o '"content":\[' | head -1)
check_val "records.content array present" "$HAS_CONTENT" '"content":['

# ── Test 4: Employee appears in report with faceIdStatus not_enrolled ─────────
echo ""
echo "--- Test 4: Employee appears in report with faceIdStatus=not_enrolled ---"
EMP_IN_REPORT=$(echo "$RESP" | grep -o "$EMP_ID" | head -1)
check_val "Employee appears in report" "$EMP_IN_REPORT" "$EMP_ID"

# ── Test 5: status filter enrolled → all rows are enrolled ───────────────────
echo ""
echo "--- Test 5: status=enrolled filter ---"
RESP_E=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment?status=enrolled")
HTTP_E=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment?status=enrolled")
check_val "status=enrolled → HTTP 200" "$HTTP_E" "200"
# enrolled count from filter should equal top-level enrolledCount
E_TOTAL=$(echo "$RESP_E" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*' | head -1)
check_val "enrolled filter totalElements = $ENROLLED" "${E_TOTAL:-0}" "$ENROLLED"

# ── Test 6: statusFilter echoed in response ───────────────────────────────────
echo ""
echo "--- Test 6: statusFilter field echoed ---"
STATUS_FIELD=$(echo "$RESP_E" | grep -o '"statusFilter":"[^"]*"' | head -1 | cut -d'"' -f4)
check_val "statusFilter echoed back as enrolled" "$STATUS_FIELD" "enrolled"

# ── Test 7: status=not_enrolled filter includes new employee ─────────────────
echo ""
echo "--- Test 7: status=not_enrolled filter includes new employee ---"
RESP_NE=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/face-id/enrollment?status=not_enrolled")
EMP_NE=$(echo "$RESP_NE" | grep -o "$EMP_ID" | head -1)
check_val "New employee in not_enrolled filter" "$EMP_NE" "$EMP_ID"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
[[ "$FAIL" -eq 0 ]]
