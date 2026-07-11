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

# Tenant A — used for the main test data
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"ListCheck Corp ${TS}\",\"slug\":\"listcheck-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant A"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Tenant B — used to verify cross-tenant isolation (summary returns 0)
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"ListCheck Corp B ${TS}\",\"slug\":\"listcheckb-${TS}\"}")
if [ "$(echo "$t2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant B"; exit 1; fi
TENANT_B_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Site A\",\"code\":\"SA-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site A"; exit 1; fi
SITE_A_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_A_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create employee via invitation — extract token from POST /invitations response (token field now included)
EMP_EMAIL="listchk.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"List\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi
INV_TOKEN=$(echo "$inv_resp" | head -n -1 | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: could not extract invitation token from response"; exit 1; fi

# Accept invitation — creates employee + user account
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

# Get employee ID by listing employees and searching by email
emp_list=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/employees?search=$EMP_EMAIL&size=5")
EMP_ID=$(echo "$emp_list" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: could not find employee after accept"; exit 1; fi

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

# Seed two manual checks so we have data to query
ch1_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_A_ID\",\"employeeId\":\"$EMP_ID\"}")
if [ "$(echo "$ch1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check 1 ($(echo "$ch1_resp" | tail -n 1))"; exit 1; fi
CHECK1_ID=$(echo "$ch1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ch2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_A_ID\",\"employeeId\":\"$EMP_ID\"}")
if [ "$(echo "$ch2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check 2"; exit 1; fi
CHECK2_ID=$(echo "$ch2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"
SUMMARY_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/summary"
LIST_URL_B="$BASE_URL/api/v1/tenants/$TENANT_B_ID/scheduled-checks"
SUMMARY_URL_B="$BASE_URL/api/v1/tenants/$TENANT_B_ID/scheduled-checks/summary"
echo "Setup complete. TENANT=$TENANT_ID TENANT_B=$TENANT_B_ID SITE=$SITE_A_ID EMP=$EMP_ID"
echo "Checks seeded: $CHECK1_ID  $CHECK2_ID"
echo ""

# ── Test 1: List returns 200 ───────────────────────────────────────────────────
echo "--- Test 1: GET /scheduled-checks returns 200 ---"
run_test "List returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$LIST_URL"

# ── Test 2: Response has paginated content array ───────────────────────────────
echo ""
echo "--- Test 2: Paginated content array present ---"
list_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$LIST_URL")
has_content=$(echo "$list_body" | grep -c '"content"' || true)
check_val "content array in response" "$has_content" "1"

# ── Test 3: totalElements >= 2 ────────────────────────────────────────────────
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

# ── Test 4: Filter by employeeId returns >= 2 checks ─────────────────────────
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

# ── Test 5: Filter by siteId returns >= 2 checks ─────────────────────────────
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

# ── Test 6: Filter by status=sent returns >= 2 checks ────────────────────────
echo ""
echo "--- Test 6: Filter by status=sent ---"
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

# ── Test 7: Filter by status=pending returns 0 (manual checks start as sent) ──
echo ""
echo "--- Test 7: Filter by status=pending returns 0 for our employee ---"
pending_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?employeeId=$EMP_ID&status=pending")
pending_count=$(echo "$pending_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "status=pending for our employee is 0" "$pending_count" "0"

# ── Test 8: Pagination — size=1 returns 1 item ───────────────────────────────
echo ""
echo "--- Test 8: page=0&size=1 returns 1 item in content ---"
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

# ── Test 9: Date range filter includes today ──────────────────────────────────
echo ""
TODAY=$(date +%Y-%m-%d)
echo "--- Test 9: dateFrom=$TODAY&dateTo=$TODAY includes our checks ---"
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

# ── Test 10: Date range in the past returns 0 ────────────────────────────────
echo ""
echo "--- Test 10: dateFrom=2020-01-01&dateTo=2020-01-31 returns 0 ---"
old_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?dateFrom=2020-01-01&dateTo=2020-01-31")
old_count=$(echo "$old_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "old date range returns 0" "$old_count" "0"

# ── Test 11: GET /summary returns 200 ────────────────────────────────────────
echo ""
echo "--- Test 11: GET /summary returns 200 ---"
run_test "Summary returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$SUMMARY_URL"

# ── Test 12: Summary counts.sent >= 2 ────────────────────────────────────────
echo ""
echo "--- Test 12: Summary counts.sent >= 2 ---"
sum_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$SUMMARY_URL")
sent_count=$(echo "$sum_body" | grep -o '"sent":[0-9]*' | head -1 | cut -d: -f2)
if [ "${sent_count:-0}" -ge 2 ]; then
    echo "PASS: summary counts.sent=$sent_count (>=2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: summary counts.sent=$sent_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 13: Summary has total field ─────────────────────────────────────────
echo ""
echo "--- Test 13: Summary response has total field ---"
has_total=$(echo "$sum_body" | grep -c '"total"' || true)
check_val "total field present" "$has_total" "1"

# ── Test 14: Tenant B summary returns total=0 (cross-tenant isolation) ────────
echo ""
echo "--- Test 14: Tenant B summary returns total=0 (no data in that tenant) ---"
sum_b_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$SUMMARY_URL_B")
sum_b_total=$(echo "$sum_b_body" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2)
check_val "Tenant B summary total=0" "${sum_b_total:-0}" "0"

# ── Test 15: Tenant B list returns totalElements=0 (cross-tenant isolation) ───
echo ""
echo "--- Test 15: Tenant B list returns totalElements=0 ---"
list_b_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$LIST_URL_B")
list_b_count=$(echo "$list_b_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
check_val "Tenant B list totalElements=0" "${list_b_count:-0}" "0"

# ── Test 16: No auth returns 401 ─────────────────────────────────────────────
echo ""
echo "--- Test 16: No auth token returns 401 ---"
run_test "No token returns 401" 401 \
    "$LIST_URL"

# ── Test 17: No permission returns 403 ───────────────────────────────────────
echo ""
echo "--- Test 17: No permission (plain employee) returns 403 ---"
noauth_email="listchk.noauth.${TS}@example.com"
noauth_inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$noauth_email\",\"firstName\":\"No\",\"lastName\":\"Auth\"}")
if [ "$(echo "$noauth_inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: noauth invitation"; exit 1; fi
NOAUTH_INV_TOKEN=$(echo "$noauth_inv_resp" | head -n -1 | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$NOAUTH_INV_TOKEN\",\"password\":\"Employee@1234\"}"

noauth_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$noauth_email\",\"password\":\"Employee@1234\"}")
NOAUTH_TOKEN=$(echo "$noauth_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

run_test "No permission returns 403" 403 \
    -H "Authorization: Bearer $NOAUTH_TOKEN" "$LIST_URL"

# ── Test 18: Summary no permission returns 403 ───────────────────────────────
echo ""
echo "--- Test 18: Summary without permission returns 403 ---"
run_test "Summary no permission 403" 403 \
    -H "Authorization: Bearer $NOAUTH_TOKEN" "$SUMMARY_URL"

# ── Test 19: Combined filter status=sent + siteId returns >= 2 ───────────────
echo ""
echo "--- Test 19: Combined filter status=sent + siteId=Site A returns >= 2 ---"
combo_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$LIST_URL?status=sent&siteId=$SITE_A_ID")
combo_count=$(echo "$combo_body" | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2)
if [ "${combo_count:-0}" -ge 2 ]; then
    echo "PASS: combined filter returned $combo_count checks"
    PASS=$((PASS + 1))
else
    echo "FAIL: combined filter returned $combo_count (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 20: Summary with date range = today includes counts ─────────────────
echo ""
echo "--- Test 20: Summary filtered by today includes sent counts ---"
sum_today=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$SUMMARY_URL?dateFrom=$TODAY&dateTo=$TODAY")
sum_sent=$(echo "$sum_today" | grep -o '"sent":[0-9]*' | head -1 | cut -d: -f2)
if [ "${sum_sent:-0}" -ge 2 ]; then
    echo "PASS: summary today counts.sent=$sum_sent (>=2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: summary today counts.sent=$sum_sent (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
