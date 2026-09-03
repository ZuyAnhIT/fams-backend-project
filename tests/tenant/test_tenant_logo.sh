#!/usr/bin/env bash
# Tests for POST/DELETE /api/v1/tenants/{id}/logo (#08 — upload a company logo image file
# from disk instead of pasting a pre-hosted URL). Owner-only, Platform Admin exempt — same
# guard as PATCH /tenants/{id}. See TenantService.updateLogoFile / TenantLogoStorageService.
# Usage: BASE_URL=http://localhost:8080 bash test_tenant_logo.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
check() { if [ "$1" = "$2" ]; then echo "PASS: $3"; PASS=$((PASS+1)); else echo "FAIL: $3 (expected $2, got $1)"; FAIL=$((FAIL+1)); fi; }

echo "=== Tenant Logo Upload Tests ==="
echo "Target: $BASE_URL"

ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$ADMIN_TOKEN" ] || { echo "SETUP FAILED: admin login"; exit 1; }

TS=$(date +%s)
OWNER_EMAIL="logo_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Logo Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$OWNER_EMAIL';" > /dev/null
OWNER_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$OWNER_EMAIL\",\"password\":\"TestPass1\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "LogoRegular")

TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Logo Test Corp\",\"slug\":\"logo-test-$TS\",\"ownerEmail\":\"$OWNER_EMAIL\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TENANT_ID" ] || { echo "SETUP FAILED: create tenant"; exit 1; }
echo "Test tenant: $TENANT_ID"

TMP=$(mktemp -d)
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\x0d\x0a\x2d\xb4\x00\x00\x00\x00IEND\xaeB\x60\x82' > "$TMP/logo.png"
echo "not an image" > "$TMP/bad.txt"

echo ""
echo "--- Test 1: owner uploads a PNG logo ---"
r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" \
    -H "Authorization: Bearer $OWNER_TOKEN" -F "file=@$TMP/logo.png")
code=$(echo "$r" | tail -1); body=$(echo "$r" | head -n -1)
check "$code" "200" "owner logo upload returns 200"
LOGO_URL=$(echo "$body" | grep -o '"logoUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
case "$LOGO_URL" in */logos/*) echo "PASS: logoUrl points at logos/ prefix"; PASS=$((PASS+1));; *) echo "FAIL: logoUrl unexpected: $LOGO_URL"; FAIL=$((FAIL+1));; esac

echo "--- Test 2: uploaded logo is publicly reachable ---"
PUB=$(echo "$LOGO_URL" | sed 's#http://[^/]*#http://localhost:9000#')
pub_code=$(curl -s -o /dev/null -w "%{http_code}" "$PUB")
check "$pub_code" "200" "public GET of logo object returns 200"

echo "--- Test 3: non-image rejected (400) ---"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" \
    -H "Authorization: Bearer $OWNER_TOKEN" -F "file=@$TMP/bad.txt")
check "$code" "400" "non-image file rejected"

echo "--- Test 4: non-owner forbidden (403) ---"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" \
    -H "Authorization: Bearer $REGULAR_TOKEN" -F "file=@$TMP/logo.png")
check "$code" "403" "non-owner logo upload forbidden"

echo "--- Test 5: platform admin allowed (support exemption) ---"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -F "file=@$TMP/logo.png")
check "$code" "200" "platform admin logo upload allowed"

echo "--- Test 6: owner clears logo (DELETE) ---"
r=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" \
    -H "Authorization: Bearer $OWNER_TOKEN")
code=$(echo "$r" | tail -1); body=$(echo "$r" | head -n -1)
check "$code" "200" "owner logo delete returns 200"
echo "$body" | grep -q '"logoUrl":null' && { echo "PASS: logoUrl cleared to null"; PASS=$((PASS+1)); } || { echo "FAIL: logoUrl not cleared"; FAIL=$((FAIL+1)); }

echo "--- Test 7: unauthenticated rejected (401) ---"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/logo" -F "file=@$TMP/logo.png")
check "$code" "401" "unauthenticated logo upload rejected"

rm -rf "$TMP"
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
[ "$FAIL" -eq 0 ]
