#!/usr/bin/env bash
# Tests for JWT access token containing tenantId and role claims after login
# Verifies: POST /api/v1/auth/login enriches JWT with tenantId + role from user_roles table
# Usage: BASE_URL=http://localhost:8080 bash test_jwt_tenant_role_claims.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

echo "=== JWT Tenant/Role Claims Tests ==="
echo "Target: $BASE_URL"
echo ""

# Helper: base64url-decode a JWT segment
decode_jwt_payload() {
    local payload="$1"
    local padded="$payload"
    local mod=$(( ${#payload} % 4 ))
    if [ "$mod" -eq 2 ]; then padded="${payload}=="; fi
    if [ "$mod" -eq 3 ]; then padded="${payload}="; fi
    echo "$padded" | tr '_-' '/+' | base64 -d 2>/dev/null || true
}

# Helper: extract a claim value from a JWT (returns empty string if absent)
jwt_claim() {
    local token="$1"
    local claim="$2"
    local payload_b64
    payload_b64=$(echo "$token" | cut -d'.' -f2)
    decode_jwt_payload "$payload_b64" | grep -o "\"$claim\":[^,}]*" | head -1 | sed "s/\"$claim\"://" | tr -d '"' || true
}

# Helper: extract userId (sub) from token
jwt_sub() {
    local token="$1"
    local payload_b64
    payload_b64=$(echo "$token" | cut -d'.' -f2)
    decode_jwt_payload "$payload_b64" | grep -o '"sub":"[^"]*"' | head -1 | sed 's/"sub":"//;s/"//' || true
}

# ─── Setup: Login as platform admin ──────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
ADMIN_USER_ID=$(jwt_sub "$ADMIN_TOKEN")
echo "Admin userId: $ADMIN_USER_ID"
echo ""

# ─── Test 1: Admin JWT before any role assignment — tenantId and role absent ─
echo "--- Test 1: Token before role assignment has no tenantId/role ---"
TENANT_BEFORE=$(jwt_claim "$ADMIN_TOKEN" "tenantId")
ROLE_BEFORE=$(jwt_claim "$ADMIN_TOKEN" "role")

if [ -z "$TENANT_BEFORE" ] || [ "$TENANT_BEFORE" = "null" ]; then
    echo "PASS: tenantId absent before role assignment"
    PASS=$((PASS + 1))
else
    echo "FAIL: tenantId='$TENANT_BEFORE' expected absent/null before role assignment"
    FAIL=$((FAIL + 1))
fi

if [ -z "$ROLE_BEFORE" ] || [ "$ROLE_BEFORE" = "null" ]; then
    echo "PASS: role absent before role assignment"
    PASS=$((PASS + 1))
else
    echo "FAIL: role='$ROLE_BEFORE' expected absent/null before role assignment"
    FAIL=$((FAIL + 1))
fi

# ─── Setup: Create a tenant ───────────────────────────────────────────────────
echo ""
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"JWT Claim Corp\",\"slug\":\"jwt-claim-$TS\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# ─── Setup: Get EMPLOYEE role id ──────────────────────────────────────────────
echo "--- Setup: Get EMPLOYEE role id ---"
roles_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true&search=EMPLOYEE" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
EMPLOYEE_ROLE_ID=$(echo "$roles_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -z "$EMPLOYEE_ROLE_ID" ]; then
    echo "SETUP FAILED: EMPLOYEE role not found"
    exit 1
fi
echo "EMPLOYEE role id: $EMPLOYEE_ROLE_ID"

# ─── Setup: Assign admin user an EMPLOYEE role in the test tenant ─────────────
echo "--- Setup: Assign admin user role in tenant ---"
assign_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"userId\":\"$ADMIN_USER_ID\",\"roleId\":\"$EMPLOYEE_ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
assign_status=$(echo "$assign_resp" | tail -n 1)
if [ "$assign_status" -ne 201 ] && [ "$assign_status" -ne 200 ] && [ "$assign_status" -ne 409 ]; then
    echo "SETUP FAILED: Could not assign role (HTTP $assign_status)"
    echo "Body: $(echo "$assign_resp" | head -n -1)"
    exit 1
fi
echo "Role assigned."
echo ""

# ─── Test 2: Login again — token now includes tenantId and role ───────────────
echo "--- Test 2: Token after role assignment has tenantId ---"
login2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login2_body=$(echo "$login2_resp" | head -n -1)
login2_status=$(echo "$login2_resp" | tail -n 1)
if [ "$login2_status" -ne 200 ]; then
    echo "FAIL: Re-login failed (HTTP $login2_status)"
    FAIL=$((FAIL + 1))
else
    NEW_TOKEN=$(echo "$login2_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

    TENANT_AFTER=$(jwt_claim "$NEW_TOKEN" "tenantId")
    ROLE_AFTER=$(jwt_claim "$NEW_TOKEN" "role")

    if [ "$TENANT_AFTER" = "$TENANT_ID" ]; then
        echo "PASS: tenantId=$TENANT_AFTER in token after role assignment"
        PASS=$((PASS + 1))
    else
        echo "FAIL: tenantId expected '$TENANT_ID', got '$TENANT_AFTER'"
        FAIL=$((FAIL + 1))
    fi

    echo ""
    echo "--- Test 3: Token after role assignment has role name ---"
    if [ -n "$ROLE_AFTER" ] && [ "$ROLE_AFTER" != "null" ]; then
        echo "PASS: role='$ROLE_AFTER' present in token"
        PASS=$((PASS + 1))
    else
        echo "FAIL: role claim missing or null after role assignment"
        FAIL=$((FAIL + 1))
    fi

    # ─── Test 4: All required base claims still present ───────────────────────
    echo ""
    echo "--- Test 4: Base claims still present after change ---"
    SUB=$(jwt_claim "$NEW_TOKEN" "sub")
    EMAIL_C=$(jwt_claim "$NEW_TOKEN" "email")
    IS_ADMIN=$(jwt_claim "$NEW_TOKEN" "isPlatformAdmin")

    if [ -n "$SUB" ]; then
        echo "PASS: sub (userId) present: $SUB"
        PASS=$((PASS + 1))
    else
        echo "FAIL: sub missing from token"
        FAIL=$((FAIL + 1))
    fi
    if [ -n "$EMAIL_C" ]; then
        echo "PASS: email claim present: $EMAIL_C"
        PASS=$((PASS + 1))
    else
        echo "FAIL: email claim missing"
        FAIL=$((FAIL + 1))
    fi
    if [ "$IS_ADMIN" = "true" ]; then
        echo "PASS: isPlatformAdmin=true preserved"
        PASS=$((PASS + 1))
    else
        echo "FAIL: isPlatformAdmin expected 'true', got '$IS_ADMIN'"
        FAIL=$((FAIL + 1))
    fi
fi

# ─── Cleanup: revoke the assigned role so we don't corrupt other tests ─────────
echo ""
echo "--- Cleanup: Revoke test role assignment ---"
# Find the assignment id
assignments_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/user-roles?userId=$ADMIN_USER_ID&tenantId=$TENANT_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null || true)
ASSIGN_ID=$(echo "$assignments_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -n "$ASSIGN_ID" ]; then
    revoke_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "$BASE_URL/api/v1/user-roles/$ASSIGN_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null || true)
    echo "Revoke HTTP: $revoke_status"
else
    echo "Could not find assignment id to revoke — skipping cleanup"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
