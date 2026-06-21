#!/usr/bin/env bash
# Tests for POST /api/v1/user-roles (Assign role to user)
# Usage: BASE_URL=http://localhost:8080 bash test_assign_role.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Assign Role to User Tests ==="
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
ADMIN_USER_ID=$(echo "$login_body" | grep -o '"userId":"[^"]*"' | head -1 | sed 's/"userId":"//;s/"//')

# ─── Get tenant ID ────────────────────────────────────────────────────────────
tenant_resp=$(curl -s -X GET "$BASE_URL/api/v1/tenants?size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
TENANT_ID=$(echo "$tenant_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -z "$TENANT_ID" ]; then
    echo "FATAL: no tenant found — aborting"
    exit 1
fi
echo "Using tenantId: $TENANT_ID"

# ─── Get a system role ID (EMPLOYEE) ─────────────────────────────────────────
roles_resp=$(curl -s -X GET "$BASE_URL/api/v1/roles?isSystem=true&search=EMPLOYEE" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
EMPLOYEE_ROLE_ID=$(echo "$roles_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -z "$EMPLOYEE_ROLE_ID" ]; then
    echo "FATAL: EMPLOYEE system role not found — aborting"
    exit 1
fi
echo "Using EMPLOYEE roleId: $EMPLOYEE_ROLE_ID"

# ─── Register a fresh test user (phone-only to get immediate token) ───────────
UNIQUE_SUFFIX="$$"
TS_REG=$(date +%s)
TEST_PHONE="+849$(printf '%07d' $(( (TS_REG + UNIQUE_SUFFIX) % 10000000 )))"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"Test@1234\",\"displayName\":\"Test User $UNIQUE_SUFFIX\"}")
reg_body=$(echo "$reg_resp" | head -n -1)
reg_status=$(echo "$reg_resp" | tail -n 1)

if [ "$reg_status" -ne 201 ] && [ "$reg_status" -ne 200 ]; then
    echo "FATAL: could not register test user (HTTP $reg_status) — aborting"
    echo "Body: $reg_body"
    exit 1
fi
TARGET_USER_ID=$(echo "$reg_body" | grep -o '"userId":"[^"]*"' | head -1 | sed 's/"userId":"//;s/"//')

if [ -z "$TARGET_USER_ID" ]; then
    # Extract userId from JWT sub claim (second segment, base64url decode)
    NEW_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
    if [ -n "$NEW_TOKEN" ]; then
        PAYLOAD=$(echo "$NEW_TOKEN" | cut -d'.' -f2 | tr '_-' '/+')
        # Pad base64
        case $((${#PAYLOAD} % 4)) in
            2) PAYLOAD="${PAYLOAD}==" ;;
            3) PAYLOAD="${PAYLOAD}=" ;;
        esac
        TARGET_USER_ID=$(echo "$PAYLOAD" | base64 -d 2>/dev/null | grep -o '"sub":"[^"]*"' | sed 's/"sub":"//;s/"//')
    fi
fi

if [ -z "$TARGET_USER_ID" ]; then
    echo "FATAL: could not extract userId from registration response — aborting"
    echo "Body: $reg_body"
    exit 1
fi
echo "Test user registered: $TARGET_USER_ID"
echo ""

# ─── 1. Happy path: assign system role to user ───────────────────────────────
echo "--- Test 1: Assign EMPLOYEE role to user ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 201 ]; then
    role_name=$(echo "$body" | grep -o '"roleName":"[^"]*"' | sed 's/"roleName":"//;s/"//')
    assigned_by=$(echo "$body" | grep -o '"assignedBy":"[^"]*"' | sed 's/"assignedBy":"//;s/"//')
    if [ "$role_name" = "EMPLOYEE" ]; then
        pass "Assign role — HTTP 201, roleName=$role_name, assignedBy=$assigned_by"
    else
        fail "Assign role" "roleName=$role_name, expected EMPLOYEE"
        echo "Body: $body"
    fi
else
    fail "Assign role" "expected HTTP 201, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Duplicate assignment → 409 ───────────────────────────────────────────
echo ""
echo "--- Test 2: Duplicate assignment → 409 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 409 ]; then
    pass "Duplicate assignment rejected — HTTP 409"
else
    fail "Duplicate assignment" "expected HTTP 409, got HTTP $status"
fi

# ─── 3. Assign a custom role ─────────────────────────────────────────────────
echo ""
echo "--- Test 3: Assign custom role ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Custom Assign Role $UNIQUE_SUFFIX\",\"permissionIds\":[]}")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)
CUSTOM_ROLE_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ "$create_status" -eq 201 ] && [ -n "$CUSTOM_ROLE_ID" ]; then
    resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/user-roles" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$CUSTOM_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
    body=$(echo "$resp" | head -n -1)
    status=$(echo "$resp" | tail -n 1)
    if [ "$status" -eq 201 ]; then
        pass "Assign custom role — HTTP 201"
    else
        fail "Assign custom role" "expected HTTP 201, got HTTP $status"
        echo "Body: $body"
    fi
else
    echo "--- Test 3: Skipped (could not create custom role) ---"
fi

# ─── 4. Non-existent user → 404 ──────────────────────────────────────────────
echo ""
echo "--- Test 4: Non-existent user → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"00000000-0000-0000-0000-000000000001\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent user — HTTP 404"
else
    fail "Non-existent user" "expected HTTP 404, got HTTP $status"
fi

# ─── 5. Non-existent role → 404 ──────────────────────────────────────────────
echo ""
echo "--- Test 5: Non-existent role → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"00000000-0000-0000-0000-000000000002\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent role — HTTP 404"
else
    fail "Non-existent role" "expected HTTP 404, got HTTP $status"
fi

# ─── 6. Non-existent tenant → 404 ────────────────────────────────────────────
echo ""
echo "--- Test 6: Non-existent tenant → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"00000000-0000-0000-0000-000000000003\"}")
if [ "$status" -eq 404 ]; then
    pass "Non-existent tenant — HTTP 404"
else
    fail "Non-existent tenant" "expected HTTP 404, got HTTP $status"
fi

# ─── 7. Validation: missing userId → 400 ─────────────────────────────────────
echo ""
echo "--- Test 7: Missing userId → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing userId rejected — HTTP 400"
else
    fail "Missing userId" "expected HTTP 400, got HTTP $status"
fi

# ─── 8. Validation: missing roleId → 400 ─────────────────────────────────────
echo ""
echo "--- Test 8: Missing roleId → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing roleId rejected — HTTP 400"
else
    fail "Missing roleId" "expected HTTP 400, got HTTP $status"
fi

# ─── 9. Validation: missing tenantId → 400 ───────────────────────────────────
echo ""
echo "--- Test 9: Missing tenantId → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\"}")
if [ "$status" -eq 400 ]; then
    pass "Missing tenantId rejected — HTTP 400"
else
    fail "Missing tenantId" "expected HTTP 400, got HTTP $status"
fi

# ─── 10. Unauthenticated → 401 ───────────────────────────────────────────────
echo ""
echo "--- Test 10: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── 11. Role from wrong tenant → 400 ────────────────────────────────────────
if [ -n "${CUSTOM_ROLE_ID:-}" ]; then
    # Create a second tenant's role — we can simulate by creating another tenant
    # For simplicity: use a non-existent tenant UUID in the assignment to trigger wrong-tenant check
    echo ""
    echo "--- Test 11: Custom role assigned to wrong tenant → 400 ---"
    tenant2_resp=$(curl -s -X GET "$BASE_URL/api/v1/tenants?size=2" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    TENANT2_ID=$(echo "$tenant2_resp" | grep -o '"id":"[^"]*"' | sed 's/"id":"//;s/"//' | tail -1)
    if [ -n "$TENANT2_ID" ] && [ "$TENANT2_ID" != "$TENANT_ID" ]; then
        status=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$BASE_URL/api/v1/user-roles" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"userId\":\"$TARGET_USER_ID\",\"roleId\":\"$CUSTOM_ROLE_ID\",\"tenantId\":\"$TENANT2_ID\"}")
        if [ "$status" -eq 400 ]; then
            pass "Wrong-tenant role assignment blocked — HTTP 400"
        else
            fail "Wrong-tenant role" "expected HTTP 400, got HTTP $status"
        fi
    else
        echo "--- Test 11: Skipped (only one tenant available) ---"
    fi
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
