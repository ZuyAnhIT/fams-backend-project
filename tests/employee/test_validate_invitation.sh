#!/usr/bin/env bash
# Tests for GET /api/v1/invitations/validate?token=UUID
# Usage: BASE_URL=http://localhost:8080 bash test_validate_invitation.sh

set -uo pipefail

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

echo "=== Validate Invitation Tests ==="
echo "Target: $BASE_URL"
echo ""

VALIDATE_URL="$BASE_URL/api/v1/invitations/validate"

# ─── Setup: admin token + tenant + send invitation ────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

TS=$(date +%s)
echo "--- Setup: Create test tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Validate Corp $TS\",\"slug\":\"validate-corp-$TS\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

echo "--- Setup: Send invitation to new email (no existing account) ---"
NEW_EMAIL="validate.new.$TS@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$NEW_EMAIL\",\"firstName\":\"New\",\"lastName\":\"User\"}")
inv_body=$(echo "$inv_resp" | head -n -1)
inv_status=$(echo "$inv_resp" | tail -n 1)
if [ "$inv_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not send invitation (HTTP $inv_status)"
    exit 1
fi
INV_ID=$(echo "$inv_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Invitation sent: id=$INV_ID to $NEW_EMAIL"
echo ""

# ─── Get the token from the invitation list ───────────────────────────────────
# NOTE: InvitationResponse does not expose the token field for security;
# we retrieve the token via the list endpoint which includes token for this test scenario.
# Since token isn't in response, we use a known-bad UUID to test 404, and skip token-based
# happy-path assertions that require the raw token.

FAKE_TOKEN="00000000-0000-0000-0000-000000000000"

# ─── Test 1: Token not found → 404 ───────────────────────────────────────────
echo "--- Test 1: Unknown token → 404 ---"
run_test "Unknown token" 404 \
    -X GET "$VALIDATE_URL?token=$FAKE_TOKEN"

# ─── Test 2: No token param → 400 ────────────────────────────────────────────
echo ""
echo "--- Test 2: Missing token param → 400 ---"
run_test "Missing token param" 400 \
    -X GET "$VALIDATE_URL"

# ─── Test 3: Invalid UUID format → 400 ───────────────────────────────────────
echo ""
echo "--- Test 3: Malformed token → 400 ---"
run_test "Malformed token" 400 \
    -X GET "$VALIDATE_URL?token=not-a-uuid"

# ─── Test 4: No auth required (endpoint is public) ───────────────────────────
echo ""
echo "--- Test 4: No auth header still returns 404 (public endpoint) ---"
run_test "Public — no auth needed" 404 \
    -X GET "$VALIDATE_URL?token=$FAKE_TOKEN"

# ─── Test 5: Cancel the invitation and validate → 422 ────────────────────────
# We need the raw token to do a proper validate. Since InvitationResponse doesn't
# expose the token, we test the cancelled/non-pending path via the cancel API first,
# then demonstrate the 422 path. We can get a token indirectly by querying the list
# for an INV_ID match. Since token isn't in the API response, we skip the full
# happy-path curl test and instead document the expected behavior.
echo ""
echo "--- Test 5: Cancelled invitation validate → 422 ---"
# Cancel the invitation we created
cancel_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations/$INV_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$cancel_status" -eq 200 ]; then
    # Send a second invitation so we can also test the validate happy path
    # via a fresh pending invitation; but we still lack the raw token in the response.
    # Verify cancel worked and create a new pending invitation for further reference
    echo "INFO: Invitation cancelled (HTTP $cancel_status)"
    echo "SKIP: Raw invitation token not exposed by API — 422 path cannot be fully tested via curl only"
else
    echo "SKIP: Cancel returned HTTP $cancel_status — skipping test 5"
fi

# ─── Test 6: Send a second invitation to existing admin user → isExistingUser=true ─
# We cannot get the raw token from the API response, but we can create the invitation
# and verify the invitation was created (201). The isExistingUser check would be true
# for admin@fams.com which already has an account.
echo ""
echo "--- Test 6: Invitation for existing user account (setup only — token not accessible via curl) ---"
EXISTING_EMAIL="admin@fams.com"
inv2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EXISTING_EMAIL\"}")
inv2_status=$(echo "$inv2_resp" | tail -n 1)
if [ "$inv2_status" -eq 201 ] || [ "$inv2_status" -eq 409 ]; then
    echo "INFO: Existing-user invitation setup: HTTP $inv2_status"
    echo "INFO: When called with the invitation token, isExistingUser=true would be returned"
    echo "SKIP: Raw token not exposed in API — full isExistingUser=true assertion requires DB access"
else
    echo "INFO: Could not create existing-user invitation (HTTP $inv2_status)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
