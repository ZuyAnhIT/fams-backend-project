#!/usr/bin/env bash
# Tests for employeeName and siteName hydration in attendance summary endpoints.
# Usage: BASE_URL=http://localhost:8080 bash test_attendance_names.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1" expected="$2"
    shift 2
    local actual
    actual=$(curl -s -o /dev/null -w "%{http_code}" "$@")
    if [ "$actual" -eq "$expected" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Attendance Name Hydration Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ──────────────────────────────────────────────────────────────────────

echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
[ "$login_status" -eq 200 ] || { echo "SETUP FAILED: admin login HTTP $login_status"; exit 1; }
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

TS=$(date +%s)

echo "--- Setup: Create tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Att Names Corp\",\"slug\":\"att-names-$TS\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1); t_status=$(echo "$t_resp" | tail -n 1)
[ "$t_status" -eq 201 ] || { echo "SETUP FAILED: create tenant HTTP $t_status"; exit 1; }
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

echo "--- Setup: Create site ---"
site_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Headquarters\",\"code\":\"HQ-$TS\",\"address\":\"123 Main St\",\"timezone\":\"UTC\"}")
site_body=$(echo "$site_resp" | head -n -1); site_status=$(echo "$site_resp" | tail -n 1)
[ "$site_status" -eq 201 ] || { echo "SETUP FAILED: create site HTTP $site_status — $site_body"; exit 1; }
SITE_ID=$(echo "$site_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site: $SITE_ID"

echo "--- Setup: Create employee ---"
emp_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Nguyen\",\"lastName\":\"Van A\",\"email\":\"nva.$TS@example.com\"}")
emp_body=$(echo "$emp_resp" | head -n -1); emp_status=$(echo "$emp_resp" | tail -n 1)
[ "$emp_status" -eq 201 ] || { echo "SETUP FAILED: create employee HTTP $emp_status — $emp_body"; exit 1; }
EMP_ID=$(echo "$emp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee: $EMP_ID"

echo "--- Setup: Seed attendance summary via DB ---"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
  INSERT INTO attendance_summaries
    (id, tenant_id, employee_id, site_id, attendance_date,
     total_work_minutes, session_count, status, is_late, late_minutes,
     is_early_leave, early_leave_minutes, ot_minutes, missing_checkout,
     created_at, updated_at)
  VALUES
    (gen_random_uuid(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', CURRENT_DATE - 1,
     480, 1, 'present', false, 0, false, 0, 0, false, now(), now())
  ON CONFLICT DO NOTHING;
" 2>&1
echo "Attendance summary seeded."
echo ""

# ── Tests ─────────────────────────────────────────────────────────────────────

ATTENDANCE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

echo "--- Test 1: listSummaries returns employeeName and siteName ---"
list_resp=$(curl -s "$ATTENDANCE_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
emp_name=$(echo "$list_resp" | grep -o '"employeeName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
site_name=$(echo "$list_resp" | grep -o '"siteName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ "$emp_name" = "Nguyen Van A" ] && [ "$site_name" = "Headquarters" ]; then
    echo "PASS: listSummaries — employeeName='$emp_name' siteName='$site_name'"
    PASS=$((PASS + 1))
else
    echo "FAIL: listSummaries — got employeeName='$emp_name' siteName='$site_name'"
    echo "Body: $list_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 2: getSummary returns employeeName and siteName ---"
# Extract ID from nested "data.content[0].id"
SUMMARY_ID=$(echo "$list_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -z "$SUMMARY_ID" ]; then
    echo "SKIP: No summary ID found in list response"
else
    get_resp=$(curl -s "$ATTENDANCE_URL/$SUMMARY_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
    emp_name2=$(echo "$get_resp" | grep -o '"employeeName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    site_name2=$(echo "$get_resp" | grep -o '"siteName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$emp_name2" = "Nguyen Van A" ] && [ "$site_name2" = "Headquarters" ]; then
        echo "PASS: getSummary — employeeName='$emp_name2' siteName='$site_name2'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: getSummary — got employeeName='$emp_name2' siteName='$site_name2'"
        echo "Body: $get_resp"
        FAIL=$((FAIL + 1))
    fi
fi

echo ""
echo "--- Test 3: listMonthlyAttendance (HR) returns employeeName and siteName ---"
YEAR=$(date +%Y)
MONTH=$(date +%-m)

monthly_resp=$(curl -s "$ATTENDANCE_URL/monthly?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
emp_name3=$(echo "$monthly_resp" | grep -o '"employeeName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
site_name3=$(echo "$monthly_resp" | grep -o '"siteName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)

if [ "$emp_name3" = "Nguyen Van A" ] && [ "$site_name3" = "Headquarters" ]; then
    echo "PASS: listMonthlyAttendance — employeeName='$emp_name3' siteName='$site_name3'"
    PASS=$((PASS + 1))
else
    echo "FAIL: listMonthlyAttendance — got employeeName='$emp_name3' siteName='$site_name3'"
    echo "Body: $monthly_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 4: listSummaries unauthenticated returns 401 ---"
run_test "listSummaries unauthenticated" 401 \
    "$ATTENDANCE_URL"

echo ""
echo "--- Test 5: listSummaries returns 200 (no 500 on name resolution) ---"
run_test "listSummaries returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$ATTENDANCE_URL"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

[ "$FAIL" -eq 0 ] || exit 1
