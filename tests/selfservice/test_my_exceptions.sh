#!/usr/bin/env bash
# Tests for the merged "needs my explanation" inbox
#   GET /api/v1/tenants/{tenantId}/me/exceptions
#
# Focus (#19, 2026-09-04): a tenant member WITHOUT an employee profile (pure tenant_admin /
# HR / company owner) must get an empty inbox (HTTP 200), not the 404 that the underlying
# getCheckinHistory / listMyViolations throw on a missing profile — that 404 surfaced as a
# dead-end red "Không thể tải hộp thư" error on Web + App.
#
# Usage: BASE_URL=http://localhost:8080 bash test_my_exceptions.sh

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

echo "=== My Exceptions inbox tests (#19) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
[ "$(echo "$login_resp" | tail -n 1)" -eq 200 ] || { echo "SETUP FAILED: admin login"; exit 1; }
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Exc Inbox ${TS}\",\"slug\":\"exc-inbox-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
[ "$(echo "$t_resp" | tail -n 1)" -eq 201 ] || { echo "SETUP FAILED: tenant"; exit 1; }
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Exc Site\",\"code\":\"EX-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="exc.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Exc\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" | tr -d ' \n')
emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Seed one unresolved violation for the employee.
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
  INSERT INTO violations (tenant_id, employee_id, site_id, violation_type, check_date, description)
  VALUES ('$TENANT_ID','$EMP_ID','$SITE_ID','no_response', CURRENT_DATE, 'Missed random check');" > /dev/null

echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID"
echo ""

# ── Tests ─────────────────────────────────────────────────────────────────────
echo "--- Test 1: tenant owner WITHOUT an employee profile ---"
# admin@fams.com owns this tenant but has no employees row here.
resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$TENANT_ID/me/exceptions" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
status=$(echo "$resp" | tail -n 1)
body=$(echo "$resp" | head -n -1)
check_val "no-profile caller → HTTP 200 (was 404)" "$status" "200"
check_val "no-profile caller → empty data array" "$(echo "$body" | grep -o '"data":\[\]')" '"data":[]'

echo ""
echo "--- Test 2: real employee still sees their exceptions ---"
resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$TENANT_ID/me/exceptions" \
    -H "Authorization: Bearer $EMP_TOKEN")
status=$(echo "$resp" | tail -n 1)
body=$(echo "$resp" | head -n -1)
check_val "employee caller → HTTP 200" "$status" "200"
check_val "employee caller → the seeded violation is listed" \
    "$(echo "$body" | grep -o '"sourceType":"violation"' | head -1)" '"sourceType":"violation"'
check_val "employee caller → reasonType no_response" \
    "$(echo "$body" | grep -o '"reasonType":"no_response"' | head -1)" '"reasonType":"no_response"'

echo ""
echo "--- Test 3: unauthenticated ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/tenants/$TENANT_ID/me/exceptions")
check_val "no token → HTTP 401" "$status" "401"

# ── Teardown ──────────────────────────────────────────────────────────────────
echo ""
echo "--- Teardown ---"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
DO \$\$
DECLARE t uuid := '$TENANT_ID';
BEGIN
  DELETE FROM notification_delivery_logs WHERE notification_id IN (SELECT id FROM notifications WHERE tenant_id=t);
  DELETE FROM notifications WHERE tenant_id=t;
  DELETE FROM violations WHERE tenant_id=t;
  DELETE FROM assignments WHERE tenant_id=t;
  DELETE FROM audit_logs WHERE actor_id IN (SELECT user_id FROM employees WHERE tenant_id=t);
  DELETE FROM employees WHERE tenant_id=t;
  DELETE FROM employee_invitations WHERE tenant_id=t;
  DELETE FROM users WHERE email='$EMP_EMAIL';
  DELETE FROM shifts WHERE tenant_id=t;
  DELETE FROM sites WHERE tenant_id=t;
  DELETE FROM tenant_subscriptions WHERE tenant_id=t;
  DELETE FROM tenant_settings WHERE tenant_id=t;
  DELETE FROM user_roles WHERE tenant_id=t;
  DELETE FROM audit_logs WHERE tenant_id=t;
  DELETE FROM tenants WHERE id=t;
END \$\$;" > /dev/null 2>&1 || echo "(teardown best-effort)"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
