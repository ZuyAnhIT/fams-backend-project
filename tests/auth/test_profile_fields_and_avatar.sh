#!/usr/bin/env bash
# Tests for Issue #4 (docs/issues/ISSUES.md): extra profile fields (dateOfBirth, hometown,
# gender, address) and real avatar file upload (POST /api/v1/auth/profile/avatar).
# Usage: BASE_URL=http://localhost:8080 bash test_profile_fields_and_avatar.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Profile Fields + Avatar Upload Tests ==="
echo "Target: $BASE_URL"
echo ""

TOKEN=$(register_verified_test_user_token "$BASE_URL" "Profile Test User" "profile_test_${TS}@fams.com")
if [ -z "$TOKEN" ]; then
    echo "SETUP FAILED: could not obtain a test user token"
    exit 1
fi

# Test 1: Update all new profile fields → 200, echoed back correctly
echo "--- Test 1: Update dateOfBirth/hometown/gender/address ---"
response=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"dateOfBirth":"1995-04-12","hometown":"Nghệ An","gender":"female","address":"123 Nguyễn Trãi, Q.1"}')
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)
if [ "$status" -eq 200 ] \
    && echo "$body" | grep -q '"hometown":"Nghệ An"' \
    && echo "$body" | grep -q '"dateOfBirth":"1995-04-12"' \
    && echo "$body" | grep -q '"gender":"female"'; then
    echo "PASS: Profile fields updated and echoed back correctly"
    PASS=$((PASS + 1))
else
    echo "FAIL: Profile fields update — HTTP $status, body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Future date of birth rejected → 400 (@Past validation)
echo ""
echo "--- Test 2: Future dateOfBirth rejected ---"
run_test "Future dateOfBirth rejected" 400 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"dateOfBirth":"2099-01-01"}'

# Test 3: Upload a valid PNG avatar → 200, avatarUrl present and fetchable
echo ""
echo "--- Test 3: Upload valid PNG avatar ---"
TMP_PNG=$(mktemp /tmp/test_avatar_XXXX.png)
python3 -c "
import struct, zlib
def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag+data))
raw = b''.join(b'\x00' + b'\xff\x00\x00' * 2 for _ in range(2))
ihdr = struct.pack('>IIBBBBB', 2, 2, 8, 2, 0, 0, 0)
open('$TMP_PNG','wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
"
upload_response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/profile/avatar" \
    -H "Authorization: Bearer $TOKEN" -F "file=@${TMP_PNG};type=image/png")
upload_body=$(echo "$upload_response" | head -n -1)
upload_status=$(echo "$upload_response" | tail -n 1)
avatar_url=$(echo "$upload_body" | grep -o '"avatarUrl":"[^"]*"' | cut -d'"' -f4)
if [ "$upload_status" -eq 200 ] && [ -n "$avatar_url" ]; then
    fetch_status=$(curl -s -o /dev/null -w "%{http_code}" "$avatar_url")
    if [ "$fetch_status" -eq 200 ]; then
        echo "PASS: Avatar uploaded (HTTP 200) and publicly fetchable (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Avatar uploaded but not fetchable at $avatar_url (HTTP $fetch_status)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Avatar upload — HTTP $upload_status, body: $upload_body"
    FAIL=$((FAIL + 1))
fi

# Test 4: Re-upload replaces the avatar — old URL 404s, new URL works
echo ""
echo "--- Test 4: Re-upload replaces old avatar (old URL 404s) ---"
upload_response2=$(curl -s -X POST "$BASE_URL/api/v1/auth/profile/avatar" \
    -H "Authorization: Bearer $TOKEN" -F "file=@${TMP_PNG};type=image/png")
avatar_url2=$(echo "$upload_response2" | grep -o '"avatarUrl":"[^"]*"' | cut -d'"' -f4)
old_status=$(curl -s -o /dev/null -w "%{http_code}" "$avatar_url")
new_status=$(curl -s -o /dev/null -w "%{http_code}" "$avatar_url2")
if [ "$old_status" -eq 404 ] && [ "$new_status" -eq 200 ]; then
    echo "PASS: Old avatar file cleaned up (404), new one served (200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected old=404/new=200, got old=$old_status/new=$new_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Wrong file type rejected → 400
echo ""
echo "--- Test 5: Non-image file rejected ---"
TMP_TXT=$(mktemp /tmp/test_notimage_XXXX.txt)
echo "not an image" > "$TMP_TXT"
run_test "Non-image upload rejected" 400 \
    -X POST "$BASE_URL/api/v1/auth/profile/avatar" \
    -H "Authorization: Bearer $TOKEN" -F "file=@${TMP_TXT};type=text/plain"

# Test 6: No auth → 401
echo ""
echo "--- Test 6: Unauthenticated avatar upload ---"
run_test "Unauthenticated avatar upload rejected" 401 \
    -X POST "$BASE_URL/api/v1/auth/profile/avatar" \
    -F "file=@${TMP_PNG};type=image/png"

# Test 7: PATCH /me can no longer set avatarUrl by pasting an arbitrary URL — the only way
# to set an avatar is a real upload through POST /auth/profile/avatar (S3/MinIO-backed).
echo ""
echo "--- Test 7: PATCH /me cannot set avatarUrl by pasting a URL ---"
before=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN" | grep -o '"avatarUrl":"[^"]*"')
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"avatarUrl":"https://evil.example.com/not-uploaded.png"}'
after=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN" | grep -o '"avatarUrl":"[^"]*"')
if [ "$before" = "$after" ] && ! echo "$after" | grep -q "evil.example.com"; then
    echo "PASS: Pasted avatarUrl via PATCH /me was ignored (still $after)"
    PASS=$((PASS + 1))
else
    echo "FAIL: PATCH /me accepted a pasted avatarUrl — before=$before after=$after"
    FAIL=$((FAIL + 1))
fi

# Test 8: DELETE /profile/avatar removes it — file deleted from storage, field cleared
echo ""
echo "--- Test 8: DELETE /profile/avatar removes the current avatar ---"
del_response=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/v1/auth/profile/avatar" \
    -H "Authorization: Bearer $TOKEN")
del_status=$(echo "$del_response" | tail -n 1)
del_body=$(echo "$del_response" | head -n -1)
gone_status=$(curl -s -o /dev/null -w "%{http_code}" "$avatar_url2")
if [ "$del_status" -eq 200 ] && echo "$del_body" | grep -q '"avatarUrl":null' && [ "$gone_status" -eq 404 ]; then
    echo "PASS: Avatar deleted (avatarUrl:null, file removed from storage — HTTP 404)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Delete avatar — status=$del_status body=$del_body storage_status=$gone_status"
    FAIL=$((FAIL + 1))
fi

rm -f "$TMP_PNG" "$TMP_TXT"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
