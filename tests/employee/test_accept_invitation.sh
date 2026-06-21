#!/usr/bin/env bash
# Tests for POST /api/v1/invitations/accept (accept employee invitation)
# Usage: BASE_URL=http://localhost:8080 bash test_accept_invitation.sh

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

ACCEPT_URL="$BASE_URL/api/v1/invitations/accept"

echo "=== Accept Invitation Tests ==="
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
SLUG="accept-inv-test-$(date +%s)"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Accept Inv Corp\",\"slug\":\"$SLUG\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

# Setup: send an invitation
echo "--- Setup: Send invitation ---"
TS=$(date +%s)
INVITE_EMAIL="invitee.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Alice\",\"lastName\":\"Smith\"}")
inv_body=$(echo "$inv_resp" | head -n -1)
inv_status=$(echo "$inv_resp" | tail -n 1)
if [ "$inv_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not send invitation (HTTP $inv_status)"
    echo "Body: $inv_body"
    exit 1
fi
echo "Invitation sent to $INVITE_EMAIL"
echo ""

# We need the token — query the DB via Docker to get it
echo "--- Setup: Retrieve invitation token from DB ---"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$INV_TOKEN" ]; then
    echo "SETUP FAILED: Could not retrieve invitation token from DB"
    exit 1
fi
echo "Token: $INV_TOKEN"
echo ""

# Test 1: Happy path — accept invitation, new user
echo "--- Test 1: Happy path (new user, with password) ---"
accept_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$ACCEPT_URL" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"NewPass@1234\",\"displayName\":\"Alice Smith\"}")
accept_body=$(echo "$accept_resp" | head -n -1)
accept_status=$(echo "$accept_resp" | tail -n 1)
if [ "$accept_status" -eq 200 ]; then
    token_present=$(echo "$accept_body" | grep -o '"accessToken":"[^"]*"' | head -1 || true)
    if [ -n "$token_present" ]; then
        echo "PASS: Happy path (HTTP 200, tokens returned)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but no accessToken in response"
        echo "Body: $accept_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $accept_status"
    echo "Body: $accept_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Same token reused — 422 (already accepted)
echo ""
echo "--- Test 2: Token already accepted ---"
run_test "Already accepted" 422 \
    -X POST "$ACCEPT_URL" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"NewPass@1234\"}"

# Test 3: Missing token → 400
echo ""
echo "--- Test 3: Missing token ---"
run_test "Missing token" 400 \
    -X POST "$ACCEPT_URL" \
    -H "Content-Type: application/json" \
    -d '{"password":"NewPass@1234"}'

# Test 4: Non-existent token → 404
echo ""
echo "--- Test 4: Token not found ---"
run_test "Token not found" 404 \
    -X POST "$ACCEPT_URL" \
    -H "Content-Type: application/json" \
    -d '{"token":"00000000-0000-0000-0000-000000000000","password":"NewPass@1234"}'

# Test 5: New invitation for existing user — user already in tenant → 409
echo ""
echo "--- Test 5: User already a tenant member ---"
INVITE_EMAIL2="invitee2.${TS}@example.com"
# Send a second invite and accept it to create the user
inv2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\"}")
inv2_status=$(echo "$inv2_resp" | tail -n 1)
if [ "$inv2_status" -eq 201 ]; then
    TOKEN2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL2' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    # Accept once
    curl -s -o /dev/null \
        -X POST "$ACCEPT_URL" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$TOKEN2\",\"password\":\"NewPass@1234\"}"
    # Send another invite for same email
    inv3_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"$INVITE_EMAIL2\"}")
    inv3_status=$(echo "$inv3_resp" | tail -n 1)
    if [ "$inv3_status" -eq 201 ]; then
        TOKEN3=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
            "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL2' AND status='pending' LIMIT 1;" \
            | tr -d ' \n')
        run_test "User already tenant member" 409 \
            -X POST "$ACCEPT_URL" \
            -H "Content-Type: application/json" \
            -d "{\"token\":\"$TOKEN3\",\"password\":\"AnotherPass@123\"}"
    else
        echo "SKIP: Could not create second invitation (HTTP $inv3_status)"
    fi
else
    echo "SKIP: Could not create second invitation for test 5"
fi

# Test 6: No password for new email → 400
echo ""
echo "--- Test 6: Missing password for new account ---"
INVITE_EMAIL3="invitee3.${TS}@example.com"
inv4_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL3\"}")
inv4_status=$(echo "$inv4_resp" | tail -n 1)
if [ "$inv4_status" -eq 201 ]; then
    TOKEN4=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL3' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    run_test "No password for new account" 400 \
        -X POST "$ACCEPT_URL" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$TOKEN4\"}"
else
    echo "SKIP: Could not create invitation for test 6 (HTTP $inv4_status)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
