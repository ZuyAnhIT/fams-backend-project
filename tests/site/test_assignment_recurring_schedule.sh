#!/usr/bin/env bash
# Tests for Issue #14 (docs/issues/ISSUES.md): assignments restricted to specific weekdays
# (days_of_week bitmask on assignments). Verifies:
#   - an assignment with daysOfWeek NOT including today is excluded from today's
#     checkin-eligibility (GET .../checkin/available-sites), the live gating mechanism
#   - an assignment with daysOfWeek including today IS included
#   - omitting daysOfWeek entirely still means "every day" (regression guard)
#   - empty daysOfWeek array is rejected (400)
#   - clearDaysOfWeek reverts a restricted assignment back to every day
# Usage: BASE_URL=http://localhost:8080 bash test_assignment_recurring_schedule.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Assignment Recurring Schedule Tests (Issue #14) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login as platform admin ───────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1

TS=$(date +%s)

# ── Setup: tenant + one site (this test only needs 1 site, avoiding the trial-plan
#    1-site limit that unrelated tests bump into) ───────────────────────────────
echo "--- Setup: Create tenant + site ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Recurring Corp ${TS}\",\"slug\":\"recurring-corp-${TS}\"}")
[ "$(echo "$t_resp" | tail -n1)" -eq 201 ] || { echo "SETUP FAILED: tenant"; exit 1; }
TENANT_ID=$(echo "$t_resp" | head -n -1 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542,"address":"123 Test St"}')
[ "$(echo "$s_resp" | tail -n1)" -eq 201 ] || { echo "SETUP FAILED: site"; exit 1; }
SITE_ID=$(echo "$s_resp" | head -n -1 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "tenant=$TENANT_ID site=$SITE_ID"

# ── Setup: invite employee, accept invitation (links userId to employee) ──────
echo "--- Setup: Invite and accept invitation ---"
INVITE_EMAIL="emp.recurring.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Mai\",\"lastName\":\"Le\"}")
[ "$(echo "$inv_resp" | tail -n1)" -eq 201 ] || { echo "SETUP FAILED: invitation"; exit 1; }

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" | tr -d ' \n')
[ -z "$INV_TOKEN" ] && echo "SETUP FAILED: could not read invitation token" && exit 1

accept_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\",\"displayName\":\"Mai Le\"}")
[ "$(echo "$accept_resp" | tail -n1)" -eq 200 ] || { echo "SETUP FAILED: accept invitation"; exit 1; }

EMP_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -z "$EMP_TOKEN" ] && echo "SETUP FAILED: employee login" && exit 1

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" | tr -d ' \n')
[ -z "$EMP_ID" ] && echo "SETUP FAILED: could not read employee id" && exit 1
echo "employee=$EMP_ID"
echo ""

ASSIGN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments"
AVAILABLE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/available-sites"

# ── Compute today's ISO weekday name (1=Monday..7=Sunday) ────────────────────
DAYS=(MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY)
TODAY_ISO=$(date +%u)
TODAY_NAME=${DAYS[$((TODAY_ISO - 1))]}
OTHER_DAYS=()
for d in "${DAYS[@]}"; do
    [ "$d" != "$TODAY_NAME" ] && OTHER_DAYS+=("\"$d\"")
done
OTHER_DAYS_JSON="[$(IFS=,; echo "${OTHER_DAYS[*]}")]"
echo "Today is $TODAY_NAME (ISO $TODAY_ISO). Restricting to every day except today: $OTHER_DAYS_JSON"
echo ""

is_available() {
    curl -s -X GET "$AVAILABLE_URL" -H "Authorization: Bearer $EMP_TOKEN" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(len(d.get('data', [])))
"
}

# ── Test 1: Assignment restricted to every day except today → not available today ─
echo "--- Test 1: daysOfWeek excludes today → site not in available-sites ---"
a1_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"daysOfWeek\":$OTHER_DAYS_JSON}")
a1_status=$(echo "$a1_resp" | tail -n1)
a1_body=$(echo "$a1_resp" | head -n -1)
if [ "$a1_status" -eq 201 ]; then
    ASSIGN_ID=$(echo "$a1_body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
    pass "Assignment created with restricted daysOfWeek (HTTP 201)"
else
    fail "Expected 201, got $a1_status: $a1_body"
fi

count=$(is_available)
if [ "$count" -eq 0 ]; then
    pass "Site correctly absent from available-sites today (0 items)"
else
    fail "Expected 0 available sites today, got $count"
fi
echo ""

# ── Test 2: Same assignment's response echoes back daysOfWeek ────────────────
echo "--- Test 2: Response includes the stored daysOfWeek ---"
if echo "$a1_body" | grep -q "$TODAY_NAME" 2>/dev/null; then
    fail "Response unexpectedly includes today ($TODAY_NAME) in daysOfWeek: $a1_body"
elif echo "$a1_body" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
days = set(d.get('daysOfWeek') or [])
sys.exit(0 if '$TODAY_NAME' not in days and len(days) == 6 else 1)
"; then
    pass "daysOfWeek in response correctly excludes today, has 6 days"
else
    fail "daysOfWeek in response did not match expectation: $a1_body"
fi
echo ""

# ── Test 3: clearDaysOfWeek reverts assignment to every day ──────────────────
echo "--- Test 3: clearDaysOfWeek=true reverts to every day → now available today ---"
u_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_URL/$ASSIGN_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearDaysOfWeek":true}')
u_status=$(echo "$u_resp" | tail -n1)
u_body=$(echo "$u_resp" | head -n -1)
if [ "$u_status" -eq 200 ] && echo "$u_body" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
sys.exit(0 if d.get('daysOfWeek') is None else 1)
"; then
    pass "clearDaysOfWeek accepted, daysOfWeek is now null (HTTP 200)"
else
    fail "Expected 200 with daysOfWeek=null, got $u_status: $u_body"
fi

count=$(is_available)
if [ "$count" -eq 1 ]; then
    pass "Site now appears in available-sites after clearing restriction"
else
    fail "Expected 1 available site after clearing daysOfWeek, got $count"
fi
echo ""

# ── Test 4: Cancel, then re-create restricted to ONLY today → available today ─
echo "--- Test 4: daysOfWeek = [today only] → site available today ---"
curl -s -o /dev/null -X DELETE "$ASSIGN_URL/$ASSIGN_ID" -H "Authorization: Bearer $ADMIN_TOKEN"

a2_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"daysOfWeek\":[\"$TODAY_NAME\"]}")
a2_status=$(echo "$a2_resp" | tail -n1)
if [ "$a2_status" -eq 201 ]; then
    ASSIGN2_ID=$(echo "$a2_resp" | head -n -1 | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
    pass "Assignment created restricted to today only (HTTP 201)"
else
    fail "Expected 201, got $a2_status: $(echo "$a2_resp" | head -n -1)"
fi

count=$(is_available)
if [ "$count" -eq 1 ]; then
    pass "Site correctly present in available-sites today (restricted to today)"
else
    fail "Expected 1 available site today, got $count"
fi
echo ""

# ── Test 5: Omitting daysOfWeek entirely still means every day (regression) ──
echo "--- Test 5: Omitting daysOfWeek entirely → every day (regression guard) ---"
curl -s -o /dev/null -X DELETE "$ASSIGN_URL/$ASSIGN2_ID" -H "Authorization: Bearer $ADMIN_TOKEN"

a3_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\"}")
a3_status=$(echo "$a3_resp" | tail -n1)
a3_body=$(echo "$a3_resp" | head -n -1)
if [ "$a3_status" -eq 201 ] && echo "$a3_body" | python3 -c "
import sys, json
sys.exit(0 if json.load(sys.stdin)['data'].get('daysOfWeek') is None else 1)
"; then
    pass "Assignment without daysOfWeek defaults to null (every day)"
else
    fail "Expected daysOfWeek=null by default, got: $a3_body"
fi

count=$(is_available)
if [ "$count" -eq 1 ]; then
    pass "Default (no daysOfWeek) assignment is available every day, including today"
else
    fail "Expected 1 available site, got $count"
fi
ASSIGN3_ID=$(echo "$a3_body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo ""

# ── Test 6: Empty daysOfWeek array is rejected (400) ─────────────────────────
echo "--- Test 6: Empty daysOfWeek array → 400 ---"
curl -s -o /dev/null -X DELETE "$ASSIGN_URL/$ASSIGN3_ID" -H "Authorization: Bearer $ADMIN_TOKEN"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"daysOfWeek\":[]}")
if [ "$status" -eq 400 ]; then
    pass "Empty daysOfWeek array rejected (HTTP 400)"
else
    fail "Expected 400, got $status"
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
