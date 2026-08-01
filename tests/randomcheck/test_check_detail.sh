#!/usr/bin/env bash
# Tests for HR view scheduled check detail (task 110)
# GET /api/v1/tenants/{tenantId}/scheduled-checks/{checkId}
# Usage: BASE_URL=http://localhost:8080 bash test_check_detail.sh

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

echo "=== Scheduled Check Detail Tests (task 110) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Detail Corp ${TS}\",\"slug\":\"detail-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Detail Site\",\"code\":\"DS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="detail.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Detail\",\"lastName\":\"Emp\"}"
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
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
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

# Create a manual check
ch_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\",\"reason\":\"test manual check\"}")
if [ "$(echo "$ch_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check"; exit 1; fi
CHECK_ID=$(echo "$ch_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

DETAIL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/$CHECK_ID"
echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID CHECK=$CHECK_ID"
echo ""

# ── Test 1: GET /{checkId} returns 200 ────────────────────────────────────────
echo "--- Test 1: GET /{checkId} returns 200 ---"
run_test "GET detail returns 200" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$DETAIL_URL"

# ── Test 2: Response contains correct check fields ────────────────────────────
echo ""
echo "--- Test 2: Response contains correct check fields ---"
detail_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$DETAIL_URL")
actual_id=$(echo "$detail_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "id matches" "$actual_id" "$CHECK_ID"

# ── Test 3: status is 'sent' ──────────────────────────────────────────────────
echo ""
echo "--- Test 3: status is 'sent' ---"
actual_status=$(echo "$detail_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "status=sent" "$actual_status" "sent"

# ── Test 4: employeeId matches ────────────────────────────────────────────────
echo ""
echo "--- Test 4: employeeId matches ---"
actual_emp=$(echo "$detail_body" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "employeeId matches" "$actual_emp" "$EMP_ID"

# ── Test 5: response field is null when check not responded ───────────────────
echo ""
echo "--- Test 5: response field is null for unreplied check ---"
has_null_response=$(echo "$detail_body" | grep -c '"response":null' || true)
check_val "response=null for unreplied check" "$has_null_response" "1"

# ── Test 6: configSnapshot is present ────────────────────────────────────────
echo ""
echo "--- Test 6: configSnapshot is present ---"
has_snapshot=$(echo "$detail_body" | grep -c '"configSnapshot"' || true)
check_val "configSnapshot present" "$has_snapshot" "1"

# ── Test 7: 404 for non-existent check ───────────────────────────────────────
echo ""
echo "--- Test 7: 404 for non-existent check UUID ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Non-existent check returns 404" 404 \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/$FAKE_ID"

# ── Test 8: 404 when check belongs to another tenant ─────────────────────────
echo ""
echo "--- Test 8: 404 when check belongs to different tenant ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"other-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant check returns 404" 404 \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$T2_ID/scheduled-checks/$CHECK_ID"

# ── Test 9: No permission returns 403 ────────────────────────────────────────
echo ""
echo "--- Test 9: No permission returns 403 ---"
noauth_email="detail.noauth.${TS}@example.com"
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
    -d "{\"identifier\":\"$noauth_email\",\"password\":\"Employee@1234\"}")
NOAUTH_TOKEN=$(echo "$noauth_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -H "Authorization: Bearer $NOAUTH_TOKEN" "$DETAIL_URL"

# ── Test 10: Detail with response populated after responding ──────────────────
echo ""
echo "--- Test 10: response field populated after responding ---"
# Manually insert a responded check and a check_response row
ASGN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM assignments WHERE employee_id='$EMP_ID' AND site_id='$SITE_ID' AND deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
CFG_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL LIMIT 1;" \
    | tr -d ' \n')

RESP_CHECK_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO scheduled_checks
    (tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
     config_snapshot, check_date, check_index, scheduled_at, expires_at, status)
  VALUES
    ('$TENANT_ID','$ASGN_ID','$EMP_ID','$SITE_ID','$SHIFT_ID','$CFG_ID',
     '{\"checkMode\":\"location_only\"}',
     CURRENT_DATE, -5,
     now() - interval '10 minutes',
     now() - interval '5 minutes',
     'responded')
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
INSERT INTO check_responses
  (tenant_id, scheduled_check_id, employee_id, responded_at,
   latitude, longitude, location_verified, outcome)
VALUES
  ('$TENANT_ID','$RESP_CHECK_ID','$EMP_ID', now() - interval '7 minutes',
   10.7769, 106.7009, TRUE, 'pass');" > /dev/null

resp_detail=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/$RESP_CHECK_ID")
has_response=$(echo "$resp_detail" | grep -c '"outcome":"pass"' || true)
check_val "response.outcome=pass present" "$has_response" "1"

# ── Test 11: Response latitude/longitude present ──────────────────────────────
echo ""
echo "--- Test 11: Response latitude/longitude values present ---"
has_lat=$(echo "$resp_detail" | grep -c '"latitude"' || true)
check_val "latitude present in response" "$has_lat" "1"

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
