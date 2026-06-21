#!/usr/bin/env bash
# Tests for POST /api/v1/roles (Create custom role)
# Usage: BASE_URL=http://localhost:8080 bash test_create_role.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Create Custom Role Tests ==="
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

# ─── Obtain a valid permission ID ────────────────────────────────────────────
perm_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/permissions?size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
PERM_ID=$(echo "$perm_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

UNIQUE_SUFFIX="$$"

# ─── 1. Happy path: create role with no permissions ──────────────────────────
echo "--- Test 1: Create custom role (no permissions) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Test Role $UNIQUE_SUFFIX\",\"description\":\"Test role created by automated test\"}")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 201 ]; then
    created_id=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
    is_system=$(echo "$body" | grep -o '"system":[a-z]*' | sed 's/"system"://')
    if [ "$is_system" = "false" ] && [ -n "$created_id" ]; then
        pass "Create role — HTTP 201, id=$created_id, system=false"
        CREATED_ROLE_ID="$created_id"
    else
        fail "Create role" "system=$is_system or missing id"
        echo "Body: $body"
        CREATED_ROLE_ID=""
    fi
else
    fail "Create role" "expected HTTP 201, got HTTP $status"
    echo "Body: $body"
    CREATED_ROLE_ID=""
fi

# ─── 2. Happy path: create role with permissions (if permission endpoint exists) ─
if [ -n "$PERM_ID" ]; then
    echo ""
    echo "--- Test 2: Create role with permissions ---"
    resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/roles" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Role With Perms $UNIQUE_SUFFIX\",\"permissionIds\":[\"$PERM_ID\"]}")
    body=$(echo "$resp" | head -n -1)
    status=$(echo "$resp" | tail -n 1)

    if [ "$status" -eq 201 ]; then
        perm_count=$(echo "$body" | grep -o '"permissionCount":[0-9]*' | grep -o '[0-9]*')
        if [ "${perm_count:-0}" -ge 1 ]; then
            pass "Create role with permissions — HTTP 201, permissionCount=$perm_count"
        else
            fail "Create role with permissions" "permissionCount=$perm_count, expected ≥1"
            echo "Body: $body"
        fi
    else
        fail "Create role with permissions" "expected HTTP 201, got HTTP $status"
        echo "Body: $body"
    fi
else
    echo "--- Test 2: Skipped (no permission endpoint available) ---"
fi

# ─── 3. Validation: missing name → 400 ───────────────────────────────────────
echo ""
echo "--- Test 3: Missing name → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing name rejected — HTTP 400"
else
    fail "Missing name validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 4. Validation: missing tenantId → 400 ───────────────────────────────────
echo ""
echo "--- Test 4: Missing tenantId → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Some Role\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing tenantId rejected — HTTP 400"
else
    fail "Missing tenantId validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 5. Validation: name too long → 400 ──────────────────────────────────────
echo ""
echo "--- Test 5: Name > 100 chars → 400 ---"
LONG_NAME=$(python3 -c "print('A'*101)")
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"$LONG_NAME\"}")
if [ "$status" -eq 400 ]; then
    pass "Name too long rejected — HTTP 400"
else
    fail "Name length validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 6. Duplicate name → 409 ─────────────────────────────────────────────────
if [ -n "$CREATED_ROLE_ID" ]; then
    echo ""
    echo "--- Test 6: Duplicate role name → 409 ---"
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/roles" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Test Role $UNIQUE_SUFFIX\"}")
    if [ "$status" -eq 409 ]; then
        pass "Duplicate name rejected — HTTP 409"
    else
        fail "Duplicate name" "expected HTTP 409, got HTTP $status"
    fi
else
    echo "--- Test 6: Skipped (no created role to duplicate) ---"
fi

# ─── 7. Non-existent tenantId → 404 ──────────────────────────────────────────
echo ""
echo "--- Test 7: Non-existent tenantId → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"00000000-0000-0000-0000-000000000000\",\"name\":\"Ghost Role\"}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent tenantId — HTTP 404"
else
    fail "Non-existent tenantId" "expected HTTP 404, got HTTP $status"
fi

# ─── 8. Non-existent permissionId → 404 ─────────────────────────────────────
echo ""
echo "--- Test 8: Non-existent permissionId → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Perm Ghost Role $UNIQUE_SUFFIX\",\"permissionIds\":[\"00000000-0000-0000-0000-000000000001\"]}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent permissionId — HTTP 404"
else
    fail "Non-existent permissionId" "expected HTTP 404, got HTTP $status"
fi

# ─── 9. Unauthenticated → 401 ────────────────────────────────────────────────
echo ""
echo "--- Test 9: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Anon Role\"}")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── 10. Verify created role is system=false and appears in list ─────────────
if [ -n "$CREATED_ROLE_ID" ]; then
    echo ""
    echo "--- Test 10: Created role appears in list with isSystem=false ---"
    resp=$(curl -s \
        -X GET "$BASE_URL/api/v1/roles?tenantId=$TENANT_ID&isSystem=false" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    if echo "$resp" | grep -q "\"$CREATED_ROLE_ID\""; then
        pass "Created role found in isSystem=false list"
    else
        fail "Created role in list" "role $CREATED_ROLE_ID not found in isSystem=false results"
        echo "Body: $resp"
    fi
else
    echo "--- Test 10: Skipped (no created role to verify) ---"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
