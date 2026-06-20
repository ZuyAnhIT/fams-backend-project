#!/usr/bin/env bash
# Verifies that RBAC seed migrations ran successfully.
# Since no dedicated RBAC API exists yet, we confirm via indirect signals:
#   - Platform admin login still works (migrations didn't break the schema)
#   - Admin can reach a protected endpoint that requires ROLE_PLATFORM_ADMIN
#   - A tenant-scoped endpoint returns 403 for a non-admin user (RBAC enforced)
#
# Usage: BASE_URL=http://localhost:8080 bash test_rbac_seed.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== RBAC Seed Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── 1. Platform admin login ─────────────────────────────────────────────────
echo "--- Test 1: Platform admin login ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')

login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -eq 200 ]; then
    ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
    if [ -n "$ADMIN_TOKEN" ]; then
        pass "Platform admin login (HTTP 200, token received)"
    else
        fail "Platform admin login — HTTP 200 but no accessToken in body"
        echo "Body: $login_body"
    fi
else
    fail "Platform admin login — expected HTTP 200, got HTTP $login_status"
    echo "Body: $login_body"
    ADMIN_TOKEN=""
fi

# ─── 2. Admin can list tenants (ROLE_PLATFORM_ADMIN required) ────────────────
echo ""
echo "--- Test 2: Admin can access PLATFORM_ADMIN endpoint ---"
if [ -n "${ADMIN_TOKEN:-}" ]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "$BASE_URL/api/v1/tenants" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    if [ "$status" -eq 200 ]; then
        pass "Admin access to /api/v1/tenants (HTTP 200)"
    else
        fail "Admin access to /api/v1/tenants — expected HTTP 200, got HTTP $status"
    fi
else
    fail "Admin access to /api/v1/tenants — skipped (no token)"
fi

# ─── 3. Unauthenticated request to protected endpoint returns 401 ────────────
echo ""
echo "--- Test 3: Unauthenticated request is rejected ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated request rejected (HTTP 401)"
else
    fail "Unauthenticated request — expected HTTP 401, got HTTP $status"
fi

# ─── 4. Register a regular user and confirm they cannot reach platform endpoints
echo ""
echo "--- Test 4: Regular user cannot access PLATFORM_ADMIN endpoint ---"
REG_EMAIL="rbac_test_$(date +%s)@example.com"
reg_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$REG_EMAIL\",\"password\":\"Test@12345\",\"displayName\":\"RBAC Tester\"}")

if [ "$reg_status" -eq 201 ]; then
    user_login=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$REG_EMAIL\",\"password\":\"Test@12345\"}")

    user_body=$(echo "$user_login" | head -n -1)
    user_status=$(echo "$user_login" | tail -n 1)

    if [ "$user_status" -eq 200 ]; then
        USER_TOKEN=$(echo "$user_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
        forbidden_status=$(curl -s -o /dev/null -w "%{http_code}" \
            -X GET "$BASE_URL/api/v1/tenants" \
            -H "Authorization: Bearer $USER_TOKEN")
        if [ "$forbidden_status" -eq 403 ]; then
            pass "Regular user blocked from platform endpoint (HTTP 403)"
        else
            fail "Regular user platform endpoint — expected HTTP 403, got HTTP $forbidden_status"
        fi
    else
        fail "Regular user login — expected HTTP 200, got HTTP $user_status"
    fi
else
    fail "Register regular user — expected HTTP 201, got HTTP $reg_status"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
