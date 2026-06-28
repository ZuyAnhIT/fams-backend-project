#!/usr/bin/env bash
# Tests for HR list + summary of scheduled checks (task 109)
# GET /api/v1/tenants/{tenantId}/scheduled-checks          (paginated, filterable)
# GET /api/v1/tenants/{tenantId}/scheduled-checks/summary  (status counts)
# Usage: BASE_URL=http://localhost:8080 bash test_list_scheduled_checks.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
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

echo "=== List & Summary Scheduled Checks Tests (task 109) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"ListCheck Corp ${TS}\",\"slug\":\"listcheck-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Site A\",\"code\":\"SA-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site A"; exit 1; fi
SITE_A_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Site B\",\"code\":\"SB-${TS}\",\"address\":\"2 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site B"; exit 1; fi
SITE_B_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_A_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="listchk.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"List\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_A_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi

# Seed two manual checks via /manual endpoint so we have data to query
ch1_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_A_ID\",\"employeeId\":\"$EMP_ID\"}")
if [ "$(echo "$ch1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check 1"; exit 1; fi
CHECK1_ID=$(echo "$ch1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ch2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_A_ID\",\"employeeId\":\"$EMP_ID\"}")
if [ "$(echo "$ch2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check 2"; exit 1; fi
CHECK2_ID=$(echo "$ch2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"
SUMMARY_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/summary"
echo "Setup complete. TENANT=$TENANT_ID SITE_A=$SITE_A_ID SITE_B=$SITE_B_ID EMP=$EMP_ID"
echo "Checks seeded: $CHECK1_ID  $CHECK2_ID"
echo ""

# ── Test 1: List returns 200 ───────────────────────────────────────────────────
echo "--- Test 1: GET /scheduled-checks returns 200 ---"
run_test "List returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$LIST_URL"

# ── Test 2: Response is paginated (content array present) ─────────────────────
echo ""
echo "--- Test 2: Paginated content array present ---"
list_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$LIST_URL")
has_content=$(echo "$list_body" | grep -c '"content"' || true)
check_val "content array in response" "$has_content" "1"

# ── Test 3: totalElements >= 2 (we seeded 2 checks) ──────────────────────────
echo ""
echo "--- Test 3: totalElements >= 2 ---"
total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${total:-0}" -ge 2 ]; then
    echo "PASS: totalElements=$total (>=2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: totalElements=$total (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 4: Filter by employeeId returns only that employee's checks ───────────
echo ""
echo "--- Test 4: Filter by employeeId ---"
filtered_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?employeeId=$EMP_ID")
emp_count=$(echo "$filtered_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${emp_count:-0}" -ge 2 ]; then
    echo "PASS: employeeId filter returned $emp_count checks"
    PASS=$((PASS + 1))
else
    echo "FAIL: employeeId filter returned $emp_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 5: Filter by siteId returns only that site's checks ──────────────────
echo ""
echo "--- Test 5: Filter by siteId (Site A) ---"
site_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?siteId=$SITE_A_ID")
site_count=$(echo "$site_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${site_count:-0}" -ge 2 ]; then
    echo "PASS: siteId filter returned $site_count checks"
    PASS=$((PASS + 1))
else
    echo "FAIL: siteId filter returned $site_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 6: Filter by siteId for Site B returns 0 ────────────────────────────
echo ""
echo "--- Test 6: Filter by siteId (Site B) returns 0 ---"
site_b_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?siteId=$SITE_B_ID")
site_b_count=$(echo "$site_b_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "Site B has 0 checks" "$site_b_count" "0"

# ── Test 7: Filter by status=sent returns only sent checks ────────────────────
echo ""
echo "--- Test 7: Filter by status=sent ---"
status_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?status=sent")
status_count=$(echo "$status_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${status_count:-0}" -ge 2 ]; then
    echo "PASS: status=sent filter returned $status_count checks"
    PASS=$((PASS + 1))
else
    echo "FAIL: status=sent filter returned $status_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: Filter by status=pending returns 0 (all our checks are 'sent') ────
echo ""
echo "--- Test 8: Filter by status=pending returns 0 (manual checks start as sent) ---"
pending_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?employeeId=$EMP_ID&status=pending")
pending_count=$(echo "$pending_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "status=pending for our employee is 0" "$pending_count" "0"

# ── Test 9: Pagination — size=1 returns 1 item ────────────────────────────────
echo ""
echo "--- Test 9: page=0&size=1 returns 1 item in content ---"
page_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?page=0&size=1")
page_size=$(echo "$page_body" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
content_count=$(echo "$page_body" | grep -o '"id":"[^"]*"' | wc -l | tr -d ' ')
check_val "size=1 returns size=1" "$page_size" "1"
if [ "${content_count:-0}" -ge 1 ]; then
    echo "PASS: content has $content_count id(s)"
    PASS=$((PASS + 1))
else
    echo "FAIL: content has $content_count id(s) (expected >=1)"
    FAIL=$((FAIL + 1))
fi

# ── Test 10: Date range filter includes today ─────────────────────────────────
echo ""
TODAY=$(date +%Y-%m-%d)
echo "--- Test 10: dateFrom=$TODAY&dateTo=$TODAY includes our checks ---"
date_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?dateFrom=$TODAY&dateTo=$TODAY")
date_count=$(echo "$date_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${date_count:-0}" -ge 2 ]; then
    echo "PASS: date range today returned $date_count checks"
    PASS=$((PASS + 1))
else
    echo "FAIL: date range today returned $date_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: Date range in the past returns 0 ────────────────────────────────
echo ""
echo "--- Test 11: dateFrom=2020-01-01&dateTo=2020-01-31 returns 0 ---"
old_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?dateFrom=2020-01-01&dateTo=2020-01-31")
old_count=$(echo "$old_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "old date range returns 0" "$old_count" "0"

# ── Test 12: GET /summary returns 200 ────────────────────────────────────────
echo ""
echo "--- Test 12: GET /summary returns 200 ---"
run_test "Summary returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$SUMMARY_URL"

# ── Test 13: Summary counts.sent >= 2 ────────────────────────────────────────
echo ""
echo "--- Test 13: Summary counts.sent >= 2 ---"
sum_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$SUMMARY_URL")
sent_count=$(echo "$sum_body" | grep -o '"sent":[0-9]*' | head -1 | cut -d: -f2)
if [ "${sent_count:-0}" -ge 2 ]; then
    echo "PASS: summary counts.sent=$sent_count (>=2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: summary counts.sent=$sent_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 14: Summary has total field ─────────────────────────────────────────
echo ""
echo "--- Test 14: Summary response has total field ---"
has_total=$(echo "$sum_body" | grep -c '"total"' || true)
check_val "total field present" "$has_total" "1"

# ── Test 15: Summary with siteId filter for Site B returns total=0 ────────────
echo ""
echo "--- Test 15: Summary filtered by Site B returns total=0 ---"
sum_b_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$SUMMARY_URL?siteId=$SITE_B_ID")
sum_b_total=$(echo "$sum_b_body" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2)
check_val "Site B summary total=0" "$sum_b_total" "0"

# ── Test 16: No permission returns 403 ───────────────────────────────────────
echo ""
echo "--- Test 16: No permission returns 403 ---"
# Create a user with no randomcheck perms
noauth_email="listchk.noauth.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$noauth_email\",\"firstName\":\"No\",\"lastName\":\"Auth\"}"
noauth_inv=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$noauth_email' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$noauth_inv\",\"password\":\"Employee@1234\"}"
noauth_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$noauth_email\",\"password\":\"Employee@1234\"}")
NOAUTH_TOKEN=$(echo "$noauth_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

run_test "No permission returns 403" 403 \
    -H "Authorization: Bearer $NOAUTH_TOKEN" "$LIST_URL"

# ── Test 17: Summary no permission returns 403 ────────────────────────────────
echo ""
echo "--- Test 17: Summary without permission returns 403 ---"
run_test "Summary no permission 403" 403 \
    -H "Authorization: Bearer $NOAUTH_TOKEN" "$SUMMARY_URL"

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
