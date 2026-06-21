#!/usr/bin/env bash
# Tests for DELETE /api/v1/roles/{id} (Delete/disable custom role)
# Usage: BASE_URL=http://localhost:8080 bash test_delete_role.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Delete Custom Role Tests ==="
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

# Helper: create a throwaway custom role
create_role() {
    local name="$1"
    local resp
    resp=$(curl -s \
        -X POST "$BASE_URL/api/v1/roles" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"$name\",\"permissionIds\":[]}")
    echo "$resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//'
}

# ─── 1. Happy path: delete a custom role ─────────────────────────────────────
echo "--- Test 1: Delete custom role ---"
ROLE_ID=$(create_role "Delete Me $UNIQUE_SUFFIX")
if [ -z "$ROLE_ID" ]; then
    echo "FATAL: could not create test role — aborting"
    exit 1
fi
echo "Created role: $ROLE_ID"

resp=$(curl -s -w "\n%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    pass "Delete role — HTTP 200"
else
    fail "Delete role" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Deleted role no longer appears in list ───────────────────────────────
echo ""
echo "--- Test 2: Deleted role absent from list ---"
list_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?tenantId=$TENANT_ID&isSystem=false" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if echo "$list_resp" | grep -q "\"$ROLE_ID\""; then
    fail "Deleted role absent" "role $ROLE_ID still appears in list"
    echo "Body: $list_resp"
else
    pass "Deleted role absent from list — not returned"
fi

# ─── 3. Delete already-deleted role → 404 ────────────────────────────────────
echo ""
echo "--- Test 3: Delete already-deleted role → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/roles/$ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Re-delete returns 404"
else
    fail "Re-delete" "expected HTTP 404, got HTTP $status"
fi

# ─── 4. Delete system role → 400 ─────────────────────────────────────────────
echo ""
echo "--- Test 4: Delete system role → 400 ---"
sys_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
SYS_ROLE_ID=$(echo "$sys_resp" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

if [ -n "$SYS_ROLE_ID" ]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "$BASE_URL/api/v1/roles/$SYS_ROLE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    if [ "$status" -eq 400 ]; then
        pass "System role delete blocked — HTTP 400"
    else
        fail "System role protection" "expected HTTP 400, got HTTP $status"
    fi
else
    echo "--- Test 4: Skipped (no system role found) ---"
fi

# ─── 5. Delete non-existent role → 404 ───────────────────────────────────────
echo ""
echo "--- Test 5: Non-existent role → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/roles/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Non-existent role — HTTP 404"
else
    fail "Non-existent role" "expected HTTP 404, got HTTP $status"
fi

# ─── 6. Unauthenticated → 401 ────────────────────────────────────────────────
echo ""
echo "--- Test 6: Unauthenticated → 401 ---"
DUMMY_ROLE_ID=$(create_role "Auth Test Role $UNIQUE_SUFFIX")
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/roles/$DUMMY_ROLE_ID")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi
# Clean up
curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/roles/$DUMMY_ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ─── 7. Name can be reused after deletion ────────────────────────────────────
echo ""
echo "--- Test 7: Name can be reused after role is deleted ---"
REUSE_NAME="Reusable Role $UNIQUE_SUFFIX"
REUSE_ID=$(create_role "$REUSE_NAME")
curl -s -o /dev/null \
    -X DELETE "$BASE_URL/api/v1/roles/$REUSE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"$REUSE_NAME\",\"permissionIds\":[]}")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 201 ]; then
    pass "Name reuse after deletion — HTTP 201"
    # Clean up
    NEW_ID=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
    curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/roles/$NEW_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
else
    fail "Name reuse after deletion" "expected HTTP 201, got HTTP $status"
    echo "Body: $body"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
