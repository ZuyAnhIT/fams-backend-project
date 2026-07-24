#!/usr/bin/env bash
# Tests for Issue #6 (docs/issues/ISSUES.md): device/session management — previously there
# was no way to list active sessions, no way to log out a SPECIFIC device (only "log out
# myself" or "log out everyone including myself"), and no display metadata (no last-used
# timestamp, no user-agent/IP).
# Usage: BASE_URL=http://localhost:8080 bash test_sessions.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Session Management Tests (Issue #6) ==="
echo "Target: $BASE_URL"
echo ""

EMAIL="sessions_${TS}@fams.com"
register_verified_test_user_token "$BASE_URL" "Session Test" "$EMAIL" > /dev/null

TOKEN_A=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL\",\"password\":\"TestPass1\",\"deviceId\":\"device-a-${TS}\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
TOKEN_B=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL\",\"password\":\"TestPass1\",\"deviceId\":\"device-b-${TS}\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then
    echo "SETUP FAILED: could not log in from two devices"
    exit 1
fi

# Test 1: List sessions shows both devices, with device A marked current
echo "--- Test 1: GET /auth/sessions lists both devices ---"
SESSIONS_A=$(curl -s -X GET "$BASE_URL/api/v1/auth/sessions" -H "Authorization: Bearer $TOKEN_A")
count=$(echo "$SESSIONS_A" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))")
current_device=$(echo "$SESSIONS_A" | python3 -c "
import sys,json
d = json.load(sys.stdin)['data']
current = [s for s in d if s['current']]
print(current[0]['deviceId'] if len(current) == 1 else 'MISMATCH')
")
if [ "$count" -ge 2 ] && [ "$current_device" = "device-a-${TS}" ]; then
    pass "Sessions list has >= 2 entries, device-a correctly marked current"
else
    fail "Expected >=2 sessions with device-a current, got count=$count current=$current_device"
fi

# Test 2: Display metadata present (userAgent/ipAddress/lastUsedAt) — was entirely missing before
echo ""
echo "--- Test 2: Session entries carry display metadata ---"
has_metadata=$(echo "$SESSIONS_A" | python3 -c "
import sys,json
d = json.load(sys.stdin)['data']
print('yes' if all(s.get('userAgent') and s.get('lastUsedAt') and s.get('createdAt') for s in d) else 'no')
")
if [ "$has_metadata" = "yes" ]; then
    pass "userAgent/lastUsedAt/createdAt present on every session"
else
    fail "Missing display metadata on at least one session"
fi

# Test 3: Log out device B specifically (from device A's session, targeting B's ID) —
# previously impossible since the only logout endpoint required already holding the raw
# refresh token of the device being logged out.
echo ""
echo "--- Test 3: DELETE /auth/sessions/{id} logs out a SPECIFIC other device ---"
device_b_id=$(echo "$SESSIONS_A" | python3 -c "
import sys,json
d = json.load(sys.stdin)['data']
match = [s['id'] for s in d if s['deviceId'] == 'device-b-${TS}']
print(match[0] if match else '')
")
if [ -n "$device_b_id" ]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/v1/auth/sessions/$device_b_id" \
        -H "Authorization: Bearer $TOKEN_A")
    if [ "$status" -eq 200 ]; then
        pass "Targeted logout of device-b succeeded from device-a's session (HTTP 200)"
    else
        fail "Targeted logout — expected HTTP 200, got $status"
    fi
else
    fail "Could not find device-b's session ID in the list"
fi

# Test 4: Device B's session is gone from the list, device A's remains
echo ""
echo "--- Test 4: Device-b no longer appears in sessions list ---"
remaining=$(curl -s -X GET "$BASE_URL/api/v1/auth/sessions" -H "Authorization: Bearer $TOKEN_A" \
    | python3 -c "import sys,json; print(','.join(s['deviceId'] for s in json.load(sys.stdin)['data']))")
if [[ "$remaining" == *"device-a-${TS}"* ]] && [[ "$remaining" != *"device-b-${TS}"* ]]; then
    pass "device-a still present, device-b removed after targeted logout"
else
    fail "Unexpected remaining sessions: $remaining"
fi

device_b_access_status=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $TOKEN_B")
if [ "$device_b_access_status" -eq 401 ]; then
    pass "Targeted logout invalidates device-b access token immediately"
else
    fail "Targeted logout left device-b access token usable (HTTP $device_b_access_status)"
fi

# Test 5: Logging out a non-existent/already-revoked session ID → 404
echo ""
echo "--- Test 5: DELETE already-revoked session → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/v1/auth/sessions/$device_b_id" \
    -H "Authorization: Bearer $TOKEN_A")
if [ "$status" -eq 404 ]; then
    pass "Re-deleting an already-revoked session returns 404"
else
    fail "Expected 404, got $status"
fi

# Test 6: A user cannot revoke another user's session (ownership check)
echo ""
echo "--- Test 6: Cannot revoke a session belonging to a different user ---"
OTHER_EMAIL="sessions_other_${TS}@fams.com"
OTHER_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Other User" "$OTHER_EMAIL")
own_session_id=$(curl -s -X GET "$BASE_URL/api/v1/auth/sessions" -H "Authorization: Bearer $TOKEN_A" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/v1/auth/sessions/$own_session_id" \
    -H "Authorization: Bearer $OTHER_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Cross-user session revoke rejected (HTTP 404 — doesn't leak whether the ID exists)"
else
    fail "Expected 404 for cross-user revoke attempt, got $status"
fi

# Test 7: logout/others keeps the caller's own session alive, revokes all the rest
echo ""
echo "--- Test 7: POST /auth/logout/others keeps current session, revokes the rest ---"
EMAIL2="sessions_others_${TS}@fams.com"
register_verified_test_user_token "$BASE_URL" "Logout Others Test" "$EMAIL2" > /dev/null
T_A=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL2\",\"password\":\"TestPass1\",\"deviceId\":\"keep-me-${TS}\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
T_B=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL2\",\"password\":\"TestPass1\",\"deviceId\":\"drop-me-${TS}\"}" \
    | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

logout_others_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/logout/others" \
    -H "Authorization: Bearer $T_A")
remaining2=$(curl -s -X GET "$BASE_URL/api/v1/auth/sessions" -H "Authorization: Bearer $T_A" \
    | python3 -c "import sys,json; print(','.join(s['deviceId'] for s in json.load(sys.stdin)['data']))")
if [ "$logout_others_status" -eq 200 ] && [[ "$remaining2" == *"keep-me-${TS}"* ]] && [[ "$remaining2" != *"drop-me-${TS}"* ]]; then
    pass "logout/others: current session kept, other device(s) revoked"
else
    fail "logout/others — status=$logout_others_status remaining=$remaining2"
fi

kept_access_status=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $T_A")
dropped_access_status=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $T_B")
if [ "$kept_access_status" -eq 200 ] && [ "$dropped_access_status" -eq 401 ]; then
    pass "logout/others keeps current access token and immediately invalidates other device access tokens"
else
    fail "logout/others access-token state unexpected: kept=$kept_access_status dropped=$dropped_access_status"
fi

# Test 8: No auth → 401 on all three new endpoints
echo ""
echo "--- Test 8: Unauthenticated access rejected on all new endpoints ---"
s1=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/auth/sessions")
s2=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/v1/auth/sessions/00000000-0000-0000-0000-000000000000")
s3=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/logout/others")
if [ "$s1" -eq 401 ] && [ "$s2" -eq 401 ] && [ "$s3" -eq 401 ]; then
    pass "All three endpoints reject unauthenticated requests (401)"
else
    fail "Expected all 401, got list=$s1 delete=$s2 others=$s3"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
