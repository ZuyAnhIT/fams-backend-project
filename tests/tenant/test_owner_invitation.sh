#!/usr/bin/env bash
# Tests for Issue #12 (docs/issues/ISSUES.md): creating a tenant with an ownerEmail different
# from the creator invites THAT person (not the creator) as tenant admin.
# Usage: BASE_URL=http://localhost:8080 bash test_owner_invitation.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Tenant Owner Invitation Tests (Issue #12) ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Setup: register a regular (non-platform-admin) creator ---"
CREATOR_EMAIL="owner_invite_creator_${TS}@fams.com"
CREATOR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Owner Invite Creator" "$CREATOR_EMAIL")

echo ""
echo "--- Test 1: Create tenant with ownerEmail different from creator ---"
OWNER_EMAIL="owner_invite_owner_${TS}@fams.com"
SLUG="owner-invite-${TS}"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $CREATOR_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Owner Invite Test $TS\",\"slug\":\"$SLUG\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
status=$(echo "$resp" | tail -n1); body=$(echo "$resp" | head -n -1)
TENANT_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || true)
if [ "$status" -eq 201 ] && [ -n "$TENANT_ID" ]; then
    pass "Tenant created with ownerEmail (HTTP 201)"
else
    fail "Expected 201 with a tenant id, got $status: $body"
fi

echo ""
echo "--- Test 2: Creator still keeps TENANT_ADMIN in the new tenant (not locked out) ---"
creator_role=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
SELECT r.name FROM user_roles ur
JOIN users u ON u.id = ur.user_id
JOIN roles r ON r.id = ur.role_id
WHERE ur.tenant_id = '$TENANT_ID' AND u.email = '$CREATOR_EMAIL';" | tr -d ' ')
if [ "$creator_role" = "TENANT_ADMIN" ]; then
    pass "Creator retains TENANT_ADMIN access in the new tenant"
else
    fail "Expected creator to have TENANT_ADMIN, got '$creator_role'"
fi

echo ""
echo "--- Test 3: Invitation was sent to the OWNER's email, not the creator's ---"
invite_row=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
SELECT ei.email || '|' || ei.status || '|' || r.name
FROM employee_invitations ei
JOIN roles r ON r.id = ei.role_id
WHERE ei.tenant_id = '$TENANT_ID';" | tr -d ' ')
if [ "$invite_row" = "${OWNER_EMAIL}|pending|TENANT_ADMIN" ]; then
    pass "Pending TENANT_ADMIN invitation exists for the owner's email (not the creator's)"
else
    fail "Expected '${OWNER_EMAIL}|pending|TENANT_ADMIN', got '$invite_row'"
fi

echo ""
echo "--- Test 4: Backend actually attempted to send the email to the owner (not the creator) ---"
sleep 5
log_hit=$(docker logs fams-api --tail 500 2>&1 | grep -c "invitation email to $OWNER_EMAIL" || true)
if [ "$log_hit" -ge 1 ]; then
    pass "EmailService attempted delivery to the owner's address"
else
    fail "Expected a 'Sent invitation email to $OWNER_EMAIL' log line"
fi

echo ""
echo "--- Test 5: ownerEmail equal to the creator's own email is a silent no-op (no self-invite) ---"
SLUG2="self-owner-${TS}"
resp2=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $CREATOR_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Self Owner Test $TS\",\"slug\":\"$SLUG2\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"$CREATOR_EMAIL\"}")
status2=$(echo "$resp2" | tail -n1); body2=$(echo "$resp2" | head -n -1)
TENANT_ID2=$(echo "$body2" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || true)
if [ "$status2" -eq 201 ] && [ -n "$TENANT_ID2" ]; then
    invite_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT count(*) FROM employee_invitations WHERE tenant_id='$TENANT_ID2';" | tr -d ' ')
    if [ "$invite_count" = "0" ]; then
        pass "No self-invitation created when ownerEmail matches the creator's own email"
    else
        fail "Expected 0 invitations, got $invite_count"
    fi
else
    fail "Setup failed for self-owner tenant: $status2"
fi

echo ""
echo "--- Test 6: Omitting ownerEmail entirely still works exactly as before (regression guard) ---"
SLUG3="no-owner-${TS}"
status3=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $CREATOR_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"No Owner Test $TS\",\"slug\":\"$SLUG3\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
if [ "$status3" -eq 201 ]; then
    pass "Creating a tenant without ownerEmail still works (HTTP 201)"
else
    fail "Expected 201, got $status3"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
