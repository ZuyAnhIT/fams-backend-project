#!/usr/bin/env bash
# Tests for DELETE /api/v1/user-roles/{id} (Revoke role from user)
# Usage: BASE_URL=http://localhost:8080 bash test_revoke_role.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Revoke Role from User Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── Obtain platform admin token ─────────────────────────────────────────────
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

# ─── Get tenant ID ────────────────────────────────────────────────────────────
tenant_resp=$(curl -s -X GET "$BASE_URL/api/v1/tenants?size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
TENANT_ID=$(echo "$tenant_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -z "$TENANT_ID" ]; then
    echo "FATAL: no tenant found — aborting"
    exit 1
fi
echo "Using tenantId: $TENANT_ID"

# ─── Get EMPLOYEE system role ID ─────────────────────────────────────────────
roles_resp=$(curl -s -X GET "$BASE_URL/api/v1/roles?isSystem=true&search=EMPLOYEE" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
EMPLOYEE_ROLE_ID=$(echo "$roles_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -z "$EMPLOYEE_ROLE_ID" ]; then
    echo "FATAL: EMPLOYEE role not found — aborting"
    exit 1
fi
echo "Using EMPLOYEE roleId: $EMPLOYEE_ROLE_ID"

UNIQUE_SUFFIX="$$"
TS_REG=$(date +%s)

# Helper: register a test user and return their UUID (via GET /auth/me)
# Pass a numeric index (1, 2, 3...) to generate a unique email per call — the helper's
# own default email (derived from date +%s + $$) can collide when called several times
# in quick succession within this same process, so an explicit unique email is used here.
register_user() {
    local idx="$1"
    local email="revoke_test_${TS_REG}_${UNIQUE_SUFFIX}_${idx}@fams.com"
    local token
    token=$(register_verified_test_user_token "$BASE_URL" "Revoke Test $idx" "$email" "Test@1234")
    [ -z "$token" ] && return
    curl -s -X GET "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $token" \
        | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//'
}

# Helper: assign a role and return the assignment ID
assign_role() {
    local user_id="$1" role_id="$2" tenant_id="$3"
    local resp
    resp=$(curl -s \
        -X POST "$BASE_URL/api/v1/user-roles" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"$user_id\",\"roleId\":\"$role_id\",\"tenantId\":\"$tenant_id\"}")
    echo "$resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//'
}

# ─── Setup: create a test user and assign a role ──────────────────────────────
echo "Setting up test user..."
TARGET_USER_ID=$(register_user 1)
if [ -z "$TARGET_USER_ID" ]; then
    echo "FATAL: could not register test user — aborting"
    exit 1
fi
echo "Test user: $TARGET_USER_ID"

ASSIGNMENT_ID=$(assign_role "$TARGET_USER_ID" "$EMPLOYEE_ROLE_ID" "$TENANT_ID")
if [ -z "$ASSIGNMENT_ID" ]; then
    echo "FATAL: could not create assignment — aborting"
    exit 1
fi
echo "Assignment ID: $ASSIGNMENT_ID"
echo ""

# ─── 1. Happy path: revoke the assignment ────────────────────────────────────
echo "--- Test 1: Revoke role assignment ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/user-roles/$ASSIGNMENT_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    pass "Revoke assignment — HTTP 200"
else
    fail "Revoke assignment" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Re-revoke already-revoked assignment → 404 ───────────────────────────
echo ""
echo "--- Test 2: Re-revoke already-revoked assignment → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/user-roles/$ASSIGNMENT_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Re-revoke returns 404"
else
    fail "Re-revoke" "expected HTTP 404, got HTTP $status"
fi

# ─── 3. After revoke, same role can be re-assigned ───────────────────────────
echo ""
echo "--- Test 3: Role can be re-assigned after revoke ---"
NEW_ASSIGNMENT_ID=$(assign_role "$TARGET_USER_ID" "$EMPLOYEE_ROLE_ID" "$TENANT_ID")
if [ -n "$NEW_ASSIGNMENT_ID" ]; then
    pass "Re-assign after revoke succeeded — new assignment id=$NEW_ASSIGNMENT_ID"
    # Clean up
    curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/user-roles/$NEW_ASSIGNMENT_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
else
    fail "Re-assign after revoke" "could not re-assign the role"
fi

# ─── 4. Non-existent assignment → 404 ────────────────────────────────────────
echo ""
echo "--- Test 4: Non-existent assignment → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/user-roles/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Non-existent assignment — HTTP 404"
else
    fail "Non-existent assignment" "expected HTTP 404, got HTTP $status"
fi

# ─── 5. Unauthenticated → 401 ────────────────────────────────────────────────
echo ""
echo "--- Test 5: Unauthenticated → 401 ---"
# Create a fresh assignment to attempt to revoke
TARGET_USER_ID2=$(register_user 2)
FRESH_ID=$(assign_role "$TARGET_USER_ID2" "$EMPLOYEE_ROLE_ID" "$TENANT_ID")
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/user-roles/$FRESH_ID")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi
# Clean up
curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/user-roles/$FRESH_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ─── 6. After revoke, role no longer blocks role deletion ────────────────────
echo ""
echo "--- Test 6: Revoke unblocks role deletion ---"
TARGET_USER_ID3=$(register_user 3)
create_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"Deletable Role $UNIQUE_SUFFIX\",\"permissionIds\":[]}")
DELETABLE_ROLE_ID=$(echo "$create_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -n "$DELETABLE_ROLE_ID" ] && [ -n "$TARGET_USER_ID3" ]; then
    AID=$(assign_role "$TARGET_USER_ID3" "$DELETABLE_ROLE_ID" "$TENANT_ID")

    # Deleting role while assigned should fail
    del_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "$BASE_URL/api/v1/roles/$DELETABLE_ROLE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")

    if [ "$del_status" -ne 200 ]; then
        # Revoke the assignment
        curl -s -o /dev/null \
            -X DELETE "$BASE_URL/api/v1/user-roles/$AID" \
            -H "Authorization: Bearer $ADMIN_TOKEN"

        # Now delete the role — should succeed
        del_status2=$(curl -s -o /dev/null -w "%{http_code}" \
            -X DELETE "$BASE_URL/api/v1/roles/$DELETABLE_ROLE_ID" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        if [ "$del_status2" -eq 200 ]; then
            pass "Revoke unblocks role deletion — role deleted after revoke"
        else
            fail "Revoke unblocks deletion" "role delete after revoke returned HTTP $del_status2"
        fi
    else
        fail "Revoke unblocks deletion" "role was deletable even with active assignment (unexpected)"
        curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/roles/$DELETABLE_ROLE_ID" \
            -H "Authorization: Bearer $ADMIN_TOKEN"
    fi
else
    echo "--- Test 6: Skipped (could not set up) ---"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
