#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/dashboard/supervisor
# Covers task 121 (Supervisor dashboard — employees at supervised sites)
# Usage: BASE_URL=http://localhost:8080 bash test_supervisor_dashboard.sh

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

echo "=== Supervisor Dashboard Tests (task 121) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Supervisor Corp ${TS}\",\"slug\":\"sup-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Construction Site A","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create supervisor employee
SUP_EMAIL="supervisor.${TS}@example.com"
sup_inv=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$SUP_EMAIL\",\"firstName\":\"Site\",\"lastName\":\"Supervisor\"}")
if [ "$(echo "$sup_inv" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: supervisor invitation"; exit 1; fi

SUP_INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$SUP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$SUP_INV_TOKEN\",\"password\":\"Supervisor@1234\"}"

sup_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$SUP_EMAIL\",\"password\":\"Supervisor@1234\"}")
if [ "$(echo "$sup_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: supervisor login"; exit 1; fi
SUP_TOKEN=$(echo "$sup_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

SUP_EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$SUP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Create worker employee
WORKER_EMAIL="worker.${TS}@example.com"
wrk_inv=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$WORKER_EMAIL\",\"firstName\":\"Site\",\"lastName\":\"Worker\"}")
if [ "$(echo "$wrk_inv" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: worker invitation"; exit 1; fi

WRK_INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$WORKER_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$WRK_INV_TOKEN\",\"password\":\"Worker@1234\"}"

wrk_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$WORKER_EMAIL\",\"password\":\"Worker@1234\"}")
if [ "$(echo "$wrk_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: worker login"; exit 1; fi
WORKER_TOKEN=$(echo "$wrk_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

WORKER_EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$WORKER_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

TODAY=$(date -u +%Y-%m-%d)

# Assign supervisor with role=supervisor
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$SUP_EMP_ID\",\"startDate\":\"$TODAY\",\"role\":\"supervisor\"}"

# Assign worker with role=worker
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$WORKER_EMP_ID\",\"startDate\":\"$TODAY\",\"role\":\"worker\"}"

echo "Setup complete. TENANT_ID=$TENANT_ID  SITE_ID=$SITE_ID  SUP_EMP_ID=$SUP_EMP_ID  WORKER_EMP_ID=$WORKER_EMP_ID"
echo ""

SUP_DASH_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/dashboard/supervisor"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$SUP_DASH_URL"
echo ""

# ── Test 2: Supervisor with profile gets dashboard → 200 ─────────────────────
echo "--- Test 2: Supervisor gets dashboard → 200 ---"
dash_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $SUP_TOKEN" "$SUP_DASH_URL")
dash_body=$(echo "$dash_resp" | head -n -1)
dash_status=$(echo "$dash_resp" | tail -n 1)
if [ "$dash_status" -eq 200 ]; then
    echo "PASS: Supervisor dashboard HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $dash_status — $dash_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: supervisedSites contains assigned site ────────────────────────────
echo "--- Test 3: supervisedSites contains the assigned site ---"
if [ "$dash_status" -eq 200 ]; then
    if echo "$dash_body" | grep -q "$SITE_ID"; then
        echo "PASS: Supervised site present in response"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected siteId $SITE_ID in supervisedSites — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: expectedToday reflects both assignments ───────────────────────────
echo "--- Test 4: expectedToday >= 2 (supervisor + worker assigned) ---"
if [ "$dash_status" -eq 200 ]; then
    expected=$(echo "$dash_body" | grep -o '"expectedToday":[0-9]*' | head -1 | cut -d: -f2)
    if [ -n "$expected" ] && [ "$expected" -ge 2 ]; then
        echo "PASS: expectedToday=$expected"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 2, got expectedToday=$expected"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: onSiteNow = 0 before any check-in ────────────────────────────────
echo "--- Test 5: onSiteNow = 0 before any check-in ---"
if [ "$dash_status" -eq 200 ]; then
    on_site=$(echo "$dash_body" | grep -o '"onSiteNow":[0-9]*' | head -1 | cut -d: -f2)
    if [ "$on_site" -eq 0 ]; then
        echo "PASS: onSiteNow=0 before check-in"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0, got onSiteNow=$on_site"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Worker checks in → onSiteNow becomes 1 ───────────────────────────
echo "--- Test 6: After worker check-in, onSiteNow = 1 ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $WORKER_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
ci_status=$(echo "$ci_resp" | tail -n 1)
if [ "$ci_status" -eq 201 ]; then
    dash_resp2=$(curl -s -w "\n%{http_code}" \
        -H "Authorization: Bearer $SUP_TOKEN" "$SUP_DASH_URL")
    dash_body2=$(echo "$dash_resp2" | head -n -1)
    dash_status2=$(echo "$dash_resp2" | tail -n 1)
    on_site2=$(echo "$dash_body2" | grep -o '"onSiteNow":[0-9]*' | head -1 | cut -d: -f2)
    if [ "$dash_status2" -eq 200 ] && [ "$on_site2" -eq 1 ]; then
        echo "PASS: onSiteNow=1 after worker check-in"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected onSiteNow=1, got $on_site2 (HTTP $dash_status2) — $dash_body2"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: worker check-in failed (HTTP $ci_status) — skipping"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: onSiteEmployees list has the worker ───────────────────────────────
echo "--- Test 7: onSiteEmployees includes the checked-in worker ---"
if [ "$ci_status" -eq 201 ]; then
    if echo "$dash_body2" | grep -q "$WORKER_EMP_ID"; then
        echo "PASS: Worker present in onSiteEmployees"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected workerId $WORKER_EMP_ID in onSiteEmployees — $dash_body2"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Worker-only employee gets empty supervisedSites ───────────────────
echo "--- Test 8: Employee with only worker role gets empty supervisedSites ---"
worker_dash=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $WORKER_TOKEN" "$SUP_DASH_URL")
worker_body=$(echo "$worker_dash" | head -n -1)
worker_status=$(echo "$worker_dash" | tail -n 1)
if [ "$worker_status" -eq 200 ]; then
    if echo "$worker_body" | grep -q '"supervisedSites":\[\]'; then
        echo "PASS: Worker gets empty supervisedSites"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected empty supervisedSites for worker — $worker_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $worker_status — $worker_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Platform admin (no employee profile) → 404 ────────────────────────
echo "--- Test 9: Caller with no employee profile → 404 ---"
run_test "No employee profile → 404" 404 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$SUP_DASH_URL"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
