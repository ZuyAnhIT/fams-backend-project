#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/consent
# Usage: BASE_URL=http://localhost:8080 bash test_consent.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Face ID Consent Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# Setup: create tenant
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Consent Corp\",\"slug\":\"consent-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

# Setup: create employee
echo "--- Setup: Create employee ---"
emp_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Alice","lastName":"Face","email":"alice.face@corp.com","employeeCode":"EMP-AF01","position":"Engineer","department":"Tech"}')
emp_body=$(echo "$emp_resp" | head -n -1)
emp_status=$(echo "$emp_resp" | tail -n 1)
if [ "$emp_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create employee (HTTP $emp_status)"
    exit 1
fi
EMP_ID=$(echo "$emp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee created: id=$EMP_ID"
echo ""

CONSENT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id/consent"

# Test 1: Admin CANNOT give consent on the employee's behalf — deliberate policy (2026-08-16+):
# biometric consent must come from the data subject themselves (Nghị định 13/2023/NĐ-CP, GDPR
# Art.9), not manufactured by HR even with face_id:manage. See FaceIdService.giveConsent javadoc.
echo "--- Test 1: Admin cannot give consent on employee's behalf (403, deliberate) ---"
run_test "Admin consent on behalf forbidden" 403 \
    -X POST "$CONSENT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 2 (was Test 3): Own employee gives consent — the only valid path
echo ""
echo "--- Test 2: Own employee gives consent ---"
INV_EMAIL="alice.linked.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INV_EMAIL\"}")
inv_status=$(echo "$inv_resp" | tail -n 1)
if [ "$inv_status" -ne 201 ]; then
    echo "SKIP: Could not create invitation (HTTP $inv_status) — skipping own-employee test"
else
    INV_TOKEN=$(docker exec fams-postgres psql -U "${DB_USER:-fams_user}" -d "${DB_NAME:-fams_db}" -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INV_EMAIL' AND status='pending' LIMIT 1;" \
        2>/dev/null | grep -oE '[0-9a-f-]{36}' | head -1 || true)
    if [ -z "$INV_TOKEN" ]; then
        echo "SKIP: Could not retrieve invitation token — skipping own-employee test"
    else
        accept_resp=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_URL/api/v1/invitations/accept" \
            -H "Content-Type: application/json" \
            -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Pass@linked1\",\"displayName\":\"Alice Face\"}")
        accept_body=$(echo "$accept_resp" | head -n -1)
        accept_status=$(echo "$accept_resp" | tail -n 1)
        if [ "$accept_status" -eq 200 ]; then
            EMP_TOKEN=$(echo "$accept_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
            jwt_payload=$(echo "$EMP_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null || true)
            EMP_USER_ID=$(echo "$jwt_payload" | grep -o '"sub":"[^"]*"' | cut -d'"' -f4 || true)
            # Find the employee record linked to this user
            OWN_EMP_ID=$(docker exec fams-postgres psql -U "${DB_USER:-fams_user}" -d "${DB_NAME:-fams_db}" -t -c \
                "SELECT id FROM employees WHERE user_id='$EMP_USER_ID' AND tenant_id='$TENANT_ID' LIMIT 1;" \
                2>/dev/null | grep -oE '[0-9a-f-]{36}' | head -1 || true)
            if [ -n "$OWN_EMP_ID" ]; then
                OWN_CONSENT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$OWN_EMP_ID/face-id/consent"
                own_consent_resp=$(curl -s -w "\n%{http_code}" \
                    -X POST "$OWN_CONSENT_URL" -H "Authorization: Bearer $EMP_TOKEN")
                own_body=$(echo "$own_consent_resp" | head -n -1)
                own_status=$(echo "$own_consent_resp" | tail -n 1)
                if [ "$own_status" -eq 200 ]; then
                    consent_given=$(echo "$own_body" | grep -o '"consentGiven":true' || true)
                    face_status=$(echo "$own_body" | grep -o '"status":"not_enrolled"' || true)
                    if [ -n "$consent_given" ] && [ -n "$face_status" ]; then
                        echo "PASS: Own employee gives consent (HTTP 200, consentGiven=true, status=not_enrolled)"
                        PASS=$((PASS + 1))
                    else
                        echo "FAIL: Own employee consent — unexpected body: $own_body"
                        FAIL=$((FAIL + 1))
                    fi
                else
                    echo "FAIL: Own employee consent — expected 200, got $own_status"
                    FAIL=$((FAIL + 1))
                fi

                # Test 3 (was Test 2): Idempotent — same employee giving consent again returns
                # 200 with the same state (not a 409/error on the second call)
                echo ""
                echo "--- Test 3: Idempotent consent (own employee calls twice) ---"
                run_test "Idempotent consent" 200 \
                    -X POST "$OWN_CONSENT_URL" -H "Authorization: Bearer $EMP_TOKEN"
            else
                echo "SKIP: Could not find own employee record — skipping own-employee tests"
            fi
        else
            echo "SKIP: Could not accept invitation (HTTP $accept_status) — skipping own-employee tests"
        fi
    fi
fi

# Test 4: Employee not found → 404
echo ""
echo "--- Test 4: Employee not found ---"
run_test "Employee not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/00000000-0000-0000-0000-000000000000/face-id/consent" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 5: Cross-tenant employee → 404
echo ""
echo "--- Test 5: Cross-tenant (employee belongs to different tenant) ---"
other_t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Consent Corp\",\"slug\":\"other-consent-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
OTHER_TENANT=$(echo "$other_t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant employee not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$OTHER_TENANT/employees/$EMP_ID/face-id/consent" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$CONSENT_URL"

# Test 7: A random unrelated authenticated user (not the employee, no face_id:manage) → 403.
# Note: this now overlaps in spirit with Test 1 — /consent is self-only regardless of RBAC
# permission, so this 403 comes from the same "not the data subject" check, not a separate
# permission gate. Kept as its own test since it still exercises a genuinely different caller.
echo ""
echo "--- Test 7: User without permission ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.consent.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"identifier\":\"noperm.consent.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X POST "$CONSENT_URL" \
        -H "Authorization: Bearer $NO_PERM_TOKEN"
else
    echo "SKIP: Could not obtain unprivileged token (email verification may be required)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
