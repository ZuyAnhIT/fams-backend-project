#!/usr/bin/env bash
# Tests for DELETE /api/v1/tenants/{tenantId}/invitations/{invitationId} (cancel invitation)
# Usage: BASE_URL=http://localhost:8080 bash test_cancel_invitation.sh

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

echo "=== Cancel Invitation Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
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
echo "Admin token obtained."
echo ""

# Setup: create a tenant
echo "--- Setup: Create test tenant ---"
SLUG="cancel-inv-$(date +%s)"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Cancel Inv Corp\",\"slug\":\"$SLUG\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

# Helper: send an invitation and return its id
send_invitation() {
    local email="$1"
    local resp
    resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"$email\"}")
    local status
    status=$(echo "$resp" | tail -n 1)
    local body
    body=$(echo "$resp" | head -n -1)
    if [ "$status" -ne 201 ]; then
        echo "SETUP FAILED: Could not send invitation to $email (HTTP $status)"
        exit 1
    fi
    echo "$body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

TS=$(date +%s)

# Setup: send a pending invitation to cancel
echo "--- Setup: Send invitation ---"
INV_EMAIL="cancel.test.${TS}@example.com"
INV_ID=$(send_invitation "$INV_EMAIL")
echo "Invitation id=$INV_ID for $INV_EMAIL"
echo ""

CANCEL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID"

# Test 1: Happy path — cancel a pending invitation
echo "--- Test 1: Happy path (cancel pending invitation) ---"
cancel_resp=$(curl -s -w "\n%{http_code}" \
    -X DELETE "$CANCEL_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancel_body=$(echo "$cancel_resp" | head -n -1)
cancel_status=$(echo "$cancel_resp" | tail -n 1)
if [ "$cancel_status" -eq 200 ]; then
    status_field=$(echo "$cancel_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$status_field" = "cancelled" ]; then
        echo "PASS: Happy path (HTTP 200, status=cancelled)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but status field is '$status_field', expected 'cancelled'"
        echo "Body: $cancel_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $cancel_status"
    echo "Body: $cancel_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Cancel already-cancelled invitation → 422
echo ""
echo "--- Test 2: Already cancelled ---"
run_test "Already cancelled" 422 \
    -X DELETE "$CANCEL_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 3: Cancel an accepted invitation → 422
echo ""
echo "--- Test 3: Cancel an accepted invitation ---"
EMAIL3="cancel.accepted.${TS}@example.com"
INV_ID3=$(send_invitation "$EMAIL3")
TOKEN3=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE id='$INV_ID3' LIMIT 1;" | tr -d ' \n')
# Accept it first
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$TOKEN3\",\"password\":\"Pass@1234x\"}"
run_test "Cancel accepted invitation" 422 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID3" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 4: Invitation not found → 404
echo ""
echo "--- Test 4: Invitation not found ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Invitation not found" 404 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$FAKE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 5: Cross-tenant — invitation from different tenant → 404
echo ""
echo "--- Test 5: Invitation from different tenant ---"
OTHER_SLUG="cancel-other-${TS}"
other_t=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp\",\"slug\":\"$OTHER_SLUG\"}")
OTHER_TENANT=$(echo "$other_t" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
EMAIL5="cross.tenant.${TS}@example.com"
# Create invitation in OTHER tenant
inv5_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$OTHER_TENANT/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMAIL5\"}")
INV_ID5=$(echo "$inv5_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
# Try to cancel using the original TENANT_ID → 404
run_test "Cross-tenant cancel" 404 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
EMAIL6="cancel.anon.${TS}@example.com"
INV_ID6=$(send_invitation "$EMAIL6")
run_test "Unauthenticated" 401 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID6"

# Test 7: User without permission → 403
echo ""
echo "--- Test 7: Forbidden (no employees:create permission) ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.cancel.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.cancel.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi

if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID6" \
        -H "Authorization: Bearer $NO_PERM_TOKEN"
else
    echo "SKIP: Could not obtain unprivileged token (email verification required)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
