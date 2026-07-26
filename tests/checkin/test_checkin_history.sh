#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/checkin/history
# Covers task 77 (employee views own check-in history with pagination/date filter)
# Usage: BASE_URL=http://localhost:8080 bash test_checkin_history.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Employee Check-in History Tests (task 77) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"History Corp ${TS}\",\"slug\":\"history-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="hist.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Hist\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

# Create 2 check-ins (check-in then checkout, then check-in again)
ci1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
if [ "$(echo "$ci1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in 1"; exit 1; fi
CI1_ID=$(echo "$ci1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI1_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'

ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
if [ "$(echo "$ci2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in 2"; exit 1; fi

echo "Setup complete (2 check-ins created)."
echo ""

HISTORY_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/history"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$HISTORY_URL"
echo ""

# ── Test 2: Happy path → 200 with paginated content ──────────────────────────
echo "--- Test 2: Get history → 200 with records ---"
hist_resp=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $EMP_TOKEN" "$HISTORY_URL")
hist_body=$(echo "$hist_resp" | head -n -1)
hist_status=$(echo "$hist_resp" | tail -n 1)
if [ "$hist_status" -eq 200 ]; then
    total=$(echo "$hist_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ -n "$total" ] && [ "$total" -ge 2 ]; then
        echo "PASS: History returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 2 records, got totalElements=$total — $hist_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $hist_status — $hist_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Pagination fields present ────────────────────────────────────────
echo "--- Test 3: Pagination fields present ---"
if [ "$hist_status" -eq 200 ]; then
    if echo "$hist_body" | grep -q '"page"' && echo "$hist_body" | grep -q '"totalPages"' && \
       echo "$hist_body" | grep -q '"first"' && echo "$hist_body" | grep -q '"last"'; then
        echo "PASS: All pagination fields present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing pagination fields — $hist_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Records contain message field ─────────────────────────────────────
echo "--- Test 4: Records include message field ---"
if [ "$hist_status" -eq 200 ]; then
    if echo "$hist_body" | grep -q '"message"'; then
        echo "PASS: message field present in history records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: message field missing from history records"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Sorted newest first ───────────────────────────────────────────────
echo "--- Test 5: Records sorted newest first ---"
if [ "$hist_status" -eq 200 ]; then
    # Extract all checkInAt timestamps from content array
    timestamps=$(echo "$hist_body" | grep -o '"checkInAt":"[^"]*"' | cut -d'"' -f4)
    prev=""
    sorted=true
    while IFS= read -r ts; do
        if [ -n "$prev" ] && [ "$ts" \> "$prev" ]; then
            sorted=false
            break
        fi
        prev="$ts"
    done <<< "$timestamps"
    if $sorted; then
        echo "PASS: Records are sorted newest first"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Records are not sorted newest first"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Page size respected ───────────────────────────────────────────────
echo "--- Test 6: page=0&size=1 returns only 1 record ---"
page1_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $EMP_TOKEN" "$HISTORY_URL?page=0&size=1")
page1_body=$(echo "$page1_resp" | head -n -1)
page1_status=$(echo "$page1_resp" | tail -n 1)
if [ "$page1_status" -eq 200 ]; then
    content_count=$(echo "$page1_body" | grep -o '"checkInAt"' | wc -l | tr -d ' ')
    if [ "$content_count" -eq 1 ]; then
        echo "PASS: size=1 returns exactly 1 record"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 1 record with size=1, got $content_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $page1_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Date filter (future date → 0 results) ────────────────────────────
echo "--- Test 7: from=2030-01-01 → 0 results ---"
future_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    "$HISTORY_URL?from=2030-01-01T00:00:00Z")
future_body=$(echo "$future_resp" | head -n -1)
future_status=$(echo "$future_resp" | tail -n 1)
if [ "$future_status" -eq 200 ]; then
    total=$(echo "$future_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -eq 0 ]; then
        echo "PASS: Future date filter returns 0 records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0 records with future from date, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $future_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Employee only sees their own records ──────────────────────────────
echo "--- Setup: Second employee with their own check-in ---"
INVITE_EMAIL2="hist.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"Hist2\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -eq 201 ]; then
    INV_TOKEN2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL2' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$INV_TOKEN2\",\"password\":\"Employee@1234\"}"
    emp2_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$INVITE_EMAIL2\",\"password\":\"Employee@1234\"}")
    EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

    echo "--- Test 8: Second employee history is isolated from first employee ---"
    hist2_resp=$(curl -s -w "\n%{http_code}" \
        -H "Authorization: Bearer $EMP2_TOKEN" "$HISTORY_URL")
    hist2_body=$(echo "$hist2_resp" | head -n -1)
    hist2_status=$(echo "$hist2_resp" | tail -n 1)
    if [ "$hist2_status" -eq 200 ]; then
        total2=$(echo "$hist2_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
        if [ "$total2" -eq 0 ]; then
            echo "PASS: Second employee sees 0 records (correct isolation)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Second employee should see 0 records, got $total2"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected HTTP 200, got $hist2_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Could not create second employee"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
