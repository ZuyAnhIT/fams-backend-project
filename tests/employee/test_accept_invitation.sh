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

# Setup: create a tenant
echo "--- Setup: Create test tenant ---"
SLUG="accept-inv-test-$(date +%s)"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Accept Inv Corp\",\"slug\":\"$SLUG\",\"ownerEmail\":\"admin@fams.com\"}")
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

# Test 1b: Accept invitation without firstName/lastName — name parsed from displayName
echo ""
echo "--- Test 1b: displayName parsed when invitation has no firstName/lastName ---"
INVITE_EMAIL_NONAME="noname.${TS}@example.com"
noname_inv_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL_NONAME\"}")
noname_inv_status=$(echo "$noname_inv_resp" | tail -n 1)
if [ "$noname_inv_status" -eq 201 ]; then
    NONAME_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL_NONAME' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    accept_noname_resp=$(curl -s \
        -X POST "$ACCEPT_URL" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$NONAME_TOKEN\",\"password\":\"NewPass@1234\",\"displayName\":\"John Doe\"}")
    accept_noname_http=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"dummy.check.${TS}@example.com\"}" 2>/dev/null || echo "0")
    # Check employee was created with correct name, not "Unknown"
    emp_list=$(curl -s \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        "$BASE_URL/api/v1/tenants/$TENANT_ID/employees?search=John")
    emp_first=$(echo "$emp_list" | grep -o '"firstName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    emp_last=$(echo "$emp_list" | grep -o '"lastName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$emp_first" = "John" ] && [ "$emp_last" = "Doe" ]; then
        echo "PASS: displayName parsed correctly (firstName=$emp_first, lastName=$emp_last)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: displayName not parsed — firstName='$emp_first', lastName='$emp_last' (expected John/Doe)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Could not create no-name invitation (HTTP $noname_inv_status)"
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

# ---------------------------------------------------------------------------
# Phone account linking tests (Issue 1 fix)
# ---------------------------------------------------------------------------
echo ""
echo "=== Phone Account Linking Tests ==="

# Setup: register a user with phone only (no email)
PHONE_USER_PHONE="+849${TS: -8}"
PHONE_USER_PASS="PhonePass@1234"

echo "--- Setup: Register phone-only account ---"
# Use the phone registration endpoint to create a phone-only user
phone_reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$PHONE_USER_PHONE\",\"password\":\"$PHONE_USER_PASS\",\"displayName\":\"Phone Only User\"}")
phone_reg_body=$(echo "$phone_reg_resp" | head -n -1)
phone_reg_status=$(echo "$phone_reg_resp" | tail -n 1)
if [ "$phone_reg_status" -ne 201 ] && [ "$phone_reg_status" -ne 200 ]; then
    echo "SKIP: Phone-only registration returned HTTP $phone_reg_status — skipping phone-linking tests"
    echo "Body: $phone_reg_body"
else
    # Send invitation with phone hint
    INVITE_PHONE_EMAIL="phone.link.${TS}@example.com"
    inv_phone_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"$INVITE_PHONE_EMAIL\",\"phone\":\"$PHONE_USER_PHONE\"}")
    inv_phone_body=$(echo "$inv_phone_resp" | head -n -1)
    inv_phone_status=$(echo "$inv_phone_resp" | tail -n 1)

    if [ "$inv_phone_status" -ne 201 ]; then
        echo "SKIP: Could not create phone-hinted invitation (HTTP $inv_phone_status)"
        echo "Body: $inv_phone_body"
    else
        PHONE_INV_TOKEN=$(echo "$inv_phone_body" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "Invitation with phone hint created, token=$PHONE_INV_TOKEN"

        # Test: validate shows existingPhoneUser = true
        echo ""
        echo "--- Test: validate returns existingPhoneUser=true ---"
        validate_resp=$(curl -s "$BASE_URL/api/v1/invitations/validate?token=$PHONE_INV_TOKEN")
        phone_flag=$(echo "$validate_resp" | grep -o '"isExistingPhoneUser":true' || true)
        if [ -n "$phone_flag" ]; then
            echo "PASS: validate returns isExistingPhoneUser=true"
            PASS=$((PASS + 1))
        else
            echo "FAIL: validate did not return isExistingPhoneUser=true"
            echo "Body: $validate_resp"
            FAIL=$((FAIL + 1))
        fi

        # Test: link invitation to existing phone account — happy path
        echo ""
        echo "--- Test: Accept invitation by linking to existing phone account ---"
        link_resp=$(curl -s -w "\n%{http_code}" \
            -X POST "$ACCEPT_URL" \
            -H "Content-Type: application/json" \
            -d "{\"token\":\"$PHONE_INV_TOKEN\",\"existingPhone\":\"$PHONE_USER_PHONE\",\"existingPassword\":\"$PHONE_USER_PASS\"}")
        link_body=$(echo "$link_resp" | head -n -1)
        link_status=$(echo "$link_resp" | tail -n 1)
        if [ "$link_status" -eq 200 ]; then
            token_present=$(echo "$link_body" | grep -o '"accessToken":"[^"]*"' | head -1 || true)
            if [ -n "$token_present" ]; then
                echo "PASS: Phone account linking returned HTTP 200 with tokens"
                PASS=$((PASS + 1))
            else
                echo "FAIL: HTTP 200 but no accessToken in response"
                echo "Body: $link_body"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: Phone account linking — expected HTTP 200, got HTTP $link_status"
            echo "Body: $link_body"
            FAIL=$((FAIL + 1))
        fi

        # Test: wrong password for phone account → 400
        echo ""
        echo "--- Test: Wrong password for phone account ---"
        INVITE_PHONE_EMAIL2="phone.link2.${TS}@example.com"
        inv_phone2_resp=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            -d "{\"email\":\"$INVITE_PHONE_EMAIL2\",\"phone\":\"$PHONE_USER_PHONE\"}")
        inv_phone2_body=$(echo "$inv_phone2_resp" | head -n -1)
        inv_phone2_status=$(echo "$inv_phone2_resp" | tail -n 1)
        if [ "$inv_phone2_status" -eq 201 ]; then
            PHONE_INV_TOKEN2=$(echo "$inv_phone2_body" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
            run_test "Wrong phone account password" 400 \
                -X POST "$ACCEPT_URL" \
                -H "Content-Type: application/json" \
                -d "{\"token\":\"$PHONE_INV_TOKEN2\",\"existingPhone\":\"$PHONE_USER_PHONE\",\"existingPassword\":\"WrongPass@999\"}"
        else
            echo "SKIP: Could not create second phone invitation for wrong-password test"
        fi

        # Test: existingPhone without existingPassword → 400
        echo ""
        echo "--- Test: existingPhone without existingPassword ---"
        if [ -n "${PHONE_INV_TOKEN2:-}" ]; then
            run_test "existingPhone without existingPassword" 400 \
                -X POST "$ACCEPT_URL" \
                -H "Content-Type: application/json" \
                -d "{\"token\":\"$PHONE_INV_TOKEN2\",\"existingPhone\":\"$PHONE_USER_PHONE\"}"
        else
            echo "SKIP: No available token for this test"
        fi

        # Test: non-existent phone number → 404
        echo ""
        echo "--- Test: Non-existent phone account ---"
        INVITE_PHONE_EMAIL3="phone.link3.${TS}@example.com"
        inv_phone3_resp=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            -d "{\"email\":\"$INVITE_PHONE_EMAIL3\"}")
        inv_phone3_body=$(echo "$inv_phone3_resp" | head -n -1)
        inv_phone3_status=$(echo "$inv_phone3_resp" | tail -n 1)
        if [ "$inv_phone3_status" -eq 201 ]; then
            PHONE_INV_TOKEN3=$(echo "$inv_phone3_body" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
            run_test "Non-existent phone account" 404 \
                -X POST "$ACCEPT_URL" \
                -H "Content-Type: application/json" \
                -d "{\"token\":\"$PHONE_INV_TOKEN3\",\"existingPhone\":\"+84000000000\",\"existingPassword\":\"SomePass@1\"}"
        else
            echo "SKIP: Could not create invitation for non-existent-phone test"
        fi

        # Test: duplicate phone hint invitation → 409
        echo ""
        echo "--- Test: Duplicate pending phone invitation ---"
        dup_resp=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            -d "{\"email\":\"dup.phone.${TS}@example.com\",\"phone\":\"$PHONE_USER_PHONE\"}")
        dup_status=$(echo "$dup_resp" | tail -n 1)
        if [ "$dup_status" -eq 201 ]; then
            run_test "Duplicate phone invitation" 409 \
                -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
                -H "Content-Type: application/json" \
                -H "Authorization: Bearer $ADMIN_TOKEN" \
                -d "{\"email\":\"dup.phone2.${TS}@example.com\",\"phone\":\"$PHONE_USER_PHONE\"}"
        else
            echo "SKIP: Could not create initial phone invitation for duplicate test"
        fi
    fi
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
