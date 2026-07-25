#!/usr/bin/env bash
# Tests for GET /api/v1/permissions (Permissions grouped by resource)
# Usage: BASE_URL=http://localhost:8080 bash test_list_permissions.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== List Permissions (Grouped) Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── Obtain admin token ───────────────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "FATAL: admin login failed (HTTP $login_status) — aborting"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')

# ─── 1. Happy path: returns grouped permissions ───────────────────────────────
echo "--- Test 1: List permissions grouped ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/permissions" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    group_count=$(echo "$body" | grep -o '"resource"' | wc -l | tr -d ' ')
    if [ "${group_count:-0}" -ge 10 ]; then
        pass "List grouped — HTTP 200, $group_count resource groups"
    else
        fail "List grouped" "expected ≥10 resource groups, got $group_count"
        echo "Body: $body"
    fi
else
    fail "List grouped" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Each group has resource, permissionCount, permissions array ───────────
echo ""
echo "--- Test 2: Response structure is correct ---"
resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/permissions" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

has_resource=$(echo "$resp" | grep -c '"resource"' || true)
has_count=$(echo "$resp" | grep -c '"permissionCount"' || true)
has_permissions=$(echo "$resp" | grep -c '"permissions"' || true)

if [ "${has_resource:-0}" -ge 1 ] && [ "${has_count:-0}" -ge 1 ] && [ "${has_permissions:-0}" -ge 1 ]; then
    pass "Response has resource, permissionCount, permissions fields"
else
    fail "Response structure" "resource=$has_resource permissionCount=$has_count permissions=$has_permissions"
    echo "Body: $resp"
fi

# ─── 3. Known resources are present ──────────────────────────────────────────
echo ""
echo "--- Test 3: Known resources present ---"
for resource in employees sites shifts checkins attendance roles permissions; do
    if echo "$resp" | grep -q "\"$resource\""; then
        pass "Resource '$resource' present"
    else
        fail "Resource '$resource' present" "not found in response"
    fi
done

# ─── 4. Each permission has id, name, action, resource fields ────────────────
echo ""
echo "--- Test 4: Permission items have required fields ---"
has_id=$(echo "$resp" | grep -o '"id"' | wc -l | tr -d ' ')
has_action=$(echo "$resp" | grep -o '"action"' | wc -l | tr -d ' ')

if [ "${has_id:-0}" -ge 10 ] && [ "${has_action:-0}" -ge 10 ]; then
    pass "Permission items have id, action fields (≥10 each)"
else
    fail "Permission item fields" "id=$has_id action=$has_action"
fi

# ─── 5. Total permission count ≥ 38 (seeded) ─────────────────────────────────
echo ""
echo "--- Test 5: At least 38 permissions seeded ---"
total_perms=$(echo "$resp" | grep -o '"id":"[^"]*"' | wc -l | tr -d ' ')
if [ "${total_perms:-0}" -ge 38 ]; then
    pass "Total permissions: $total_perms (≥38)"
else
    fail "Total permissions" "expected ≥38, got $total_perms"
fi

# ─── 6. Unauthenticated → 401 ────────────────────────────────────────────────
echo ""
echo "--- Test 6: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/permissions")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── 7. Verify employees:create permission is present ────────────────────────
echo ""
echo "--- Test 7: employees:create permission present ---"
if echo "$resp" | grep -q '"employees:create"'; then
    pass "employees:create permission found"
else
    fail "employees:create" "not found in response"
fi

# ─── 8. Groups are sorted alphabetically by resource ─────────────────────────
echo ""
echo "--- Test 8: Resources sorted alphabetically ---"
resources=$(echo "$resp" | grep -o '"resource":"[^"]*"' | sed 's/"resource":"//;s/"//')
sorted=$(echo "$resources" | sort)
if [ "$resources" = "$sorted" ]; then
    pass "Resources sorted alphabetically"
else
    fail "Resource sort order" "resources not in alphabetical order"
    echo "Got: $resources"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
