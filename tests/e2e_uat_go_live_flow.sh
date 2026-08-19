#!/usr/bin/env bash
# #148 — Kịch bản kiểm thử end-to-end (UAT go-live flow)
# AC: "Chuẩn bị data mẫu; test tenant->employee->site->assignment->checkin->summary->
#      random check->violation->report"
#
# Unlike tests/run_all.sh (runs every suite independently, each with its own throwaway tenant),
# this script chains ONE tenant/employee/site through the exact AC sequence with real dependent
# IDs, end to end — the automated counterpart to docs/testing/manual-test-scenarios.md's B.8
# scenario (kept in sync: B.8 documents the same flow for a human to follow manually, including
# the Face ID / saved-filter / masking / audit-trace steps this script doesn't cover).
#
# Usage: BASE_URL=http://localhost:8080 bash e2e_uat_go_live_flow.sh

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

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "PASS: $name"; PASS=$((PASS + 1))
    else
        echo "FAIL: $name — response did not contain '$needle'"; FAIL=$((FAIL + 1))
    fi
}

echo "=== E2E UAT Go-Live Flow (Task 148) ==="
echo "Target: $BASE_URL"
echo ""

# ── Step 0: Platform Admin login ─────────────────────────────────────────────
echo "--- Step 0: Platform Admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
[ "$(echo "$login_resp" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: admin login"; exit 1; }
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "OK"
echo ""

TS=$(date +%s)

# ── Step 1: tenant ────────────────────────────────────────────────────────────
echo "--- Step 1: Tạo tenant mới hoàn toàn (POST /tenants) ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"UAT GoLive Corp ${TS}\",\"slug\":\"uat-golive-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_status=$(echo "$t_resp" | tail -n 1)
run_test "POST /tenants (has ownerEmail)" 201 -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"UAT GoLive Corp check-${TS}\",\"slug\":\"uat-golive-check-${TS}\",\"ownerEmail\":\"admin@fams.com\"}"
[ "$t_status" -ne 201 ] && { echo "SETUP FAILED: tenant creation"; exit 1; }
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "TENANT_ID=$TENANT_ID"
echo ""

# ── Step 2: site + shift ──────────────────────────────────────────────────────
echo "--- Step 2: Tạo site + ca làm việc (POST .../sites, .../shifts) ---"
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"UAT HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
[ "$(echo "$s_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: site"; exit 1; }
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ca ngay","startTime":"00:00","endTime":"23:59"}')
[ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: shift"; exit 1; }
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "SITE_ID=$SITE_ID  SHIFT_ID=$SHIFT_ID"
echo ""

# ── Step 3: employee (invite → accept) ────────────────────────────────────────
echo "--- Step 3: Mời nhân viên, chấp nhận lời mời (POST .../invitations, /invitations/accept) ---"
EMP_EMAIL="uat.golive.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"UAT\",\"lastName\":\"Employee\"}")
[ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: invitation"; exit 1; }

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
[ -z "$INV_TOKEN" ] && { echo "SETUP FAILED: invitation token"; exit 1; }

accept_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}")
check_val "Invitation accepted" "$(echo "$accept_resp" | tail -n 1)" "200"

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: employee id"; exit 1; }
echo "EMP_ID=$EMP_ID"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
[ "$(echo "$emp_login" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: employee login"; exit 1; }
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""

# ── Step 4: assignment ────────────────────────────────────────────────────────
echo "--- Step 4: Phân công nhân viên vào site + ca (POST .../assignments) ---"
asgn_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
[ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: assignment"; exit 1; }
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "ASGN_ID=$ASGN_ID"
echo ""

# ── Step 5: check-in / check-out ──────────────────────────────────────────────
echo "--- Step 5: Nhân viên check-in trong geofence, sau đó check-out (POST /checkin, /checkin/{id}/checkout) ---"
CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
ci_status=$(echo "$ci_resp" | tail -n 1)
check_val "Check-in status" "$ci_status" "201"
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "CHECKIN_ID=$CHECKIN_ID"

co_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL/$CHECKIN_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}')
check_val "Check-out status" "$(echo "$co_resp" | tail -n 1)" "200"
echo ""

# ── Step 6: attendance summary ────────────────────────────────────────────────
echo "--- Step 6: Tính lại bảng công (POST .../attendance/recompute) ---"
TODAY=$(date +%Y-%m-%d)
recompute_resp=$(curl -s -w "\n%{http_code}" -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/attendance/recompute?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_val "Recompute status" "$(echo "$recompute_resp" | tail -n 1)" "200"

daily_body=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/daily?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "Daily report includes today's checkin" "$daily_body" "\"$EMP_ID\""
echo ""

# ── Step 7: random check → no-response violation ──────────────────────────────
echo "--- Step 7: Kiểm tra ngẫu nhiên → không phản hồi → tự sinh vi phạm ---"
cfg_resp=$(curl -s -w "\n%{http_code}" -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checksPerShift":1,"minIntervalMinutes":60,"allowedStartTime":"00:00:00","allowedEndTime":"23:59:00","checkMode":"location_only","applicableRoles":[],"responseWindowSeconds":300}')
check_val "Random check config created" "$(echo "$cfg_resp" | tail -n 1)" "201"

CFG_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL AND deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Insert an already-expired scheduled check directly (same technique as
# tests/randomcheck/test_no_response_violation.sh) rather than waiting real minutes for a
# dispatched check to time out — this is a weekly-cron-adjacent async flow with no
# faster manual-trigger-then-wait path suitable for a CI-speed script.
EXPIRED_CHECK=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "WITH next_idx AS (
       SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
       FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
     ),
     ins AS (
       INSERT INTO scheduled_checks
         (id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
          config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
       SELECT gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID', '$CFG_ID',
         '{\"configId\":\"$CFG_ID\",\"checkMode\":\"location_only\"}'::jsonb, '$TODAY', idx,
         now() - interval '10 minutes', now() - interval '1 second',
         'sent', now(), now()
       FROM next_idx
       RETURNING id
     ) SELECT id FROM ins;" | tr -d ' \n')
echo "EXPIRED_CHECK=$EXPIRED_CHECK"

proc_resp=$(curl -s -w "\n%{http_code}" -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/process-expired" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_val "process-expired status" "$(echo "$proc_resp" | tail -n 1)" "200"
v_created=$(echo "$proc_resp" | head -n -1 | grep -o '"violationsCreated":[0-9]*' | cut -d: -f2)
check_val "violationsCreated" "$v_created" "1"

VIOLATION_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM violations WHERE scheduled_check_id='$EXPIRED_CHECK' LIMIT 1;" | tr -d ' \n')
[ -z "$VIOLATION_ID" ] && { echo "FAILED: no violation row created"; FAIL=$((FAIL + 1)); }
echo "VIOLATION_ID=$VIOLATION_ID"
echo ""

# ── Step 8: HR resolves the violation ─────────────────────────────────────────
echo "--- Step 8: HR xử lý vi phạm (POST .../violations/{id}/confirm) ---"
if [ -n "$VIOLATION_ID" ]; then
    confirm_resp=$(curl -s -w "\n%{http_code}" -X POST \
        "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID/confirm" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN")
    check_val "Confirm violation status" "$(echo "$confirm_resp" | tail -n 1)" "200"
    check_contains "Violation resolved=true" "$(echo "$confirm_resp" | head -n -1)" '"resolved":true'
fi
echo ""

# ── Step 9: report + export ───────────────────────────────────────────────────
echo "--- Step 9: HR xem báo cáo chấm công + xuất Excel (GET .../reports/attendance/monthly, /export) ---"
YEAR=$(date +%Y)
MONTH=$(date +%-m)
monthly_body=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/monthly?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "Monthly report includes employee" "$monthly_body" "\"$EMP_ID\""

# Bonus cross-feature check: this employee has a random-check-failure row this month (Step 7),
# so the payroll-readiness guard (2026-07-31 audit) must refuse a plain export without an
# explicit override — proves random-check violations actually feed into the payroll guard, not
# just recorded in isolation.
run_test "Export WITHOUT confirmDespiteWarnings is blocked (409, payroll guard)" 409 \
    -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/export?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

export_status=$(curl -s -o /tmp/e2e_uat_export.xlsx -w "%{http_code}" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/export?year=$YEAR&month=$MONTH&confirmDespiteWarnings=true" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_val "Export WITH confirmDespiteWarnings status" "$export_status" "200"
export_size=$(stat -c%s /tmp/e2e_uat_export.xlsx 2>/dev/null || echo 0)
if [ "$export_size" -gt 1000 ]; then
    echo "PASS: Export file is non-trivial size ($export_size bytes)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Export file too small ($export_size bytes) — likely empty/error body"
    FAIL=$((FAIL + 1))
fi
rm -f /tmp/e2e_uat_export.xlsx

violation_report_body=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/violations?from=$TODAY&to=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "Violation report shows totalViolations >= 1" "$violation_report_body" '"totalViolations":1'
echo ""

echo "========================================="
echo "TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID SITE_ID=$SITE_ID VIOLATION_ID=$VIOLATION_ID"
echo "Results: $PASS passed, $FAIL failed"
if [ "$FAIL" -eq 0 ]; then
    echo "ALL TESTS PASSED — full tenant->employee->site->assignment->checkin->summary->random check->violation->report chain verified"
else
    echo "SOME TESTS FAILED"
    exit 1
fi
