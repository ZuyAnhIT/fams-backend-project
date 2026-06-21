#!/usr/bin/env bash
# Tests for PUT /api/v1/roles/{id} (Update custom role)
# Usage: BASE_URL=http://localhost:8080 bash test_update_role.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Update Custom Role Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── Obtain platform admin token ─────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "FATAL: admin login failed (HTTP $login_status) — aborting"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')

# ─── Obtain a valid tenant ID ─────────────────────────────────────────────────
tenant_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants?size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
TENANT_ID=$(echo "$tenant_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -z "$TENANT_ID" ]; then
    echo "FATAL: no tenant found — aborting"
    exit 1
fi
echo "Using tenantId: $TENANT_ID"

UNIQUE_SUFFIX="$$"

# ─── Create a custom role to update ──────────────────────────────────────────
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Role To Update $UNIQUE_SUFFIX\",\"description\":\"Original description\",\"permissionIds\":[]}")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)

if [ "$create_status" -ne 201 ]; then
    echo "FATAL: could not create test role (HTTP $create_status) — aborting"
    echo "Body: $create_body"
    exit 1
fi
ROLE_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "Created test role: $ROLE_ID"

# ─── Also create a second role to test duplicate name conflict ────────────────
create_resp2=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Other Role $UNIQUE_SUFFIX\",\"permissionIds\":[]}")
create_body2=$(echo "$create_resp2" | head -n -1)
create_status2=$(echo "$create_resp2" | tail -n 1)

if [ "$create_status2" -ne 201 ]; then
    echo "FATAL: could not create second test role (HTTP $create_status2) — aborting"
    exit 1
fi
OTHER_ROLE_NAME="Other Role $UNIQUE_SUFFIX"

# ─── Obtain a valid permission ID ────────────────────────────────────────────
PERM_ID=""
perm_resp=$(curl -s -X GET "$BASE_URL/api/v1/permissions?size=2" -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null)
if echo "$perm_resp" | grep -q '"id"'; then
    PERM_IDS=$(echo "$perm_resp" | grep -o '"id":"[^"]*"' | head -2 | sed 's/"id":"//;s/"//' | tr '\n' ',' | sed 's/,$//')
    PERM_ID=$(echo "$perm_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
fi

echo ""

# ─── 1. Happy path: update name and description ───────────────────────────────
echo "--- Test 1: Update name and description ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Updated Role $UNIQUE_SUFFIX\",\"description\":\"Updated description\",\"permissionIds\":[]}")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    updated_name=$(echo "$body" | grep -o '"name":"[^"]*"' | head -1 | sed 's/"name":"//;s/"//')
    updated_desc=$(echo "$body" | grep -o '"description":"[^"]*"' | head -1 | sed 's/"description":"//;s/"//')
    if [ "$updated_name" = "Updated Role $UNIQUE_SUFFIX" ] && [ "$updated_desc" = "Updated description" ]; then
        pass "Update name/description — HTTP 200, name and description updated"
    else
        fail "Update name/description" "name='$updated_name' description='$updated_desc'"
        echo "Body: $body"
    fi
else
    fail "Update name/description" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Happy path: add permissions ──────────────────────────────────────────
if [ -n "$PERM_ID" ]; then
    echo ""
    echo "--- Test 2: Update with permissions ---"
    resp=$(curl -s -w "\n%{http_code}" \
        -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"Updated Role $UNIQUE_SUFFIX\",\"description\":\"With perms\",\"permissionIds\":[\"$PERM_ID\"]}")
    body=$(echo "$resp" | head -n -1)
    status=$(echo "$resp" | tail -n 1)

    if [ "$status" -eq 200 ]; then
        perm_count=$(echo "$body" | grep -o '"permissionCount":[0-9]*' | grep -o '[0-9]*')
        if [ "${perm_count:-0}" -ge 1 ]; then
            pass "Update with permissions — HTTP 200, permissionCount=$perm_count"
        else
            fail "Update with permissions" "permissionCount=$perm_count, expected ≥1"
            echo "Body: $body"
        fi
    else
        fail "Update with permissions" "expected HTTP 200, got HTTP $status"
        echo "Body: $body"
    fi

    # ─── 3. Happy path: clear permissions (set to empty list) ────────────────
    echo ""
    echo "--- Test 3: Clear all permissions ---"
    resp=$(curl -s -w "\n%{http_code}" \
        -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"Updated Role $UNIQUE_SUFFIX\",\"description\":\"No perms\",\"permissionIds\":[]}")
    body=$(echo "$resp" | head -n -1)
    status=$(echo "$resp" | tail -n 1)

    if [ "$status" -eq 200 ]; then
        perm_count=$(echo "$body" | grep -o '"permissionCount":[0-9]*' | grep -o '[0-9]*')
        if [ "${perm_count:-1}" -eq 0 ]; then
            pass "Clear permissions — HTTP 200, permissionCount=0"
        else
            fail "Clear permissions" "permissionCount=$perm_count, expected 0"
            echo "Body: $body"
        fi
    else
        fail "Clear permissions" "expected HTTP 200, got HTTP $status"
        echo "Body: $body"
    fi
else
    echo "--- Test 2/3: Skipped (no permission endpoint available) ---"
fi

# ─── 4. Validation: missing name → 400 ───────────────────────────────────────
echo ""
echo "--- Test 4: Missing name → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"description\":\"No name\",\"permissionIds\":[]}")
if [ "$status" -eq 400 ]; then
    pass "Missing name rejected — HTTP 400"
else
    fail "Missing name validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 5. Validation: name too long → 400 ──────────────────────────────────────
echo ""
echo "--- Test 5: Name > 100 chars → 400 ---"
LONG_NAME=$(python3 -c "print('A'*101)")
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$LONG_NAME\",\"permissionIds\":[]}")
if [ "$status" -eq 400 ]; then
    pass "Name too long rejected — HTTP 400"
else
    fail "Name length validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 6. Validation: missing permissionIds → 400 ──────────────────────────────
echo ""
echo "--- Test 6: Missing permissionIds → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Some name\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing permissionIds rejected — HTTP 400"
else
    fail "Missing permissionIds validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 7. Duplicate name → 409 ─────────────────────────────────────────────────
echo ""
echo "--- Test 7: Duplicate name → 409 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$OTHER_ROLE_NAME\",\"permissionIds\":[]}")
if [ "$status" -eq 409 ]; then
    pass "Duplicate name rejected — HTTP 409"
else
    fail "Duplicate name" "expected HTTP 409, got HTTP $status"
fi

# ─── 8. Update system role → 400 ─────────────────────────────────────────────
echo ""
echo "--- Test 8: Update system role → 400 ---"
sys_role_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
SYS_ROLE_ID=$(echo "$sys_role_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -n "$SYS_ROLE_ID" ]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X PUT "$BASE_URL/api/v1/roles/$SYS_ROLE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"Hacked System Role\",\"permissionIds\":[]}")
    if [ "$status" -eq 400 ]; then
        pass "System role update blocked — HTTP 400"
    else
        fail "System role protection" "expected HTTP 400, got HTTP $status"
    fi
else
    echo "--- Test 8: Skipped (no system role found) ---"
fi

# ─── 9. Non-existent role → 404 ──────────────────────────────────────────────
echo ""
echo "--- Test 9: Non-existent role → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Ghost Role\",\"permissionIds\":[]}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent role — HTTP 404"
else
    fail "Non-existent role" "expected HTTP 404, got HTTP $status"
fi

# ─── 10. Non-existent permissionId → 404 ────────────────────────────────────
echo ""
echo "--- Test 10: Non-existent permissionId → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Updated Role $UNIQUE_SUFFIX\",\"permissionIds\":[\"00000000-0000-0000-0000-000000000001\"]}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent permissionId — HTTP 404"
else
    fail "Non-existent permissionId" "expected HTTP 404, got HTTP $status"
fi

# ─── 11. Unauthenticated → 401 ───────────────────────────────────────────────
echo ""
echo "--- Test 11: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Anon Update\",\"permissionIds\":[]}")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── 12. Same name on same role (no conflict) → 200 ─────────────────────────
echo ""
echo "--- Test 12: Same name on same role → 200 (no conflict) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Updated Role $UNIQUE_SUFFIX\",\"description\":\"Same name is fine\",\"permissionIds\":[]}")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    pass "Same name on same role — HTTP 200"
else
    fail "Same name self-update" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
