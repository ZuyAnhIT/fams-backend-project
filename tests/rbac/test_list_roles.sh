#!/usr/bin/env bash
# Tests for GET /api/v1/roles
# Usage: BASE_URL=http://localhost:8080 bash test_list_roles.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== List Roles Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── Obtain admin token ──────────────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "FATAL: admin login failed (HTTP $login_status) — aborting"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')

# ─── 1. Happy path: list all roles (no filter) ───────────────────────────────
echo "--- Test 1: List roles (no filter, admin) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')
    if [ "${total:-0}" -ge 5 ]; then
        pass "List roles — HTTP 200, totalElements=$total (≥5 system roles seeded)"
    else
        fail "List roles" "HTTP 200 but totalElements=$total, expected ≥5"
        echo "Body: $body"
    fi
else
    fail "List roles" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── 2. Filter isSystem=true ─────────────────────────────────────────────────
echo ""
echo "--- Test 2: Filter isSystem=true ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')
    if [ "${total:-0}" -ge 5 ]; then
        pass "Filter isSystem=true — HTTP 200, totalElements=$total"
    else
        fail "Filter isSystem=true" "expected ≥5, got $total"
    fi
else
    fail "Filter isSystem=true" "expected HTTP 200, got HTTP $status"
fi

# ─── 3. Filter isSystem=false (no custom roles yet → 0 results) ──────────────
echo ""
echo "--- Test 3: Filter isSystem=false ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?isSystem=false" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    pass "Filter isSystem=false — HTTP 200 (custom roles may be 0)"
else
    fail "Filter isSystem=false" "expected HTTP 200, got HTTP $status"
fi

# ─── 4. Search by name ───────────────────────────────────────────────────────
echo ""
echo "--- Test 4: Search by name 'ADMIN' ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?search=ADMIN" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')
    if [ "${total:-0}" -ge 1 ]; then
        pass "Search 'ADMIN' — HTTP 200, totalElements=$total"
    else
        fail "Search 'ADMIN'" "expected ≥1 match, got $total"
        echo "Body: $body"
    fi
else
    fail "Search 'ADMIN'" "expected HTTP 200, got HTTP $status"
fi

# ─── 5. Search with no match ─────────────────────────────────────────────────
echo ""
echo "--- Test 5: Search with no match ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?search=zzz_no_match_zzz" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')
    if [ "${total:-1}" -eq 0 ]; then
        pass "Search no match — HTTP 200, totalElements=0"
    else
        fail "Search no match" "expected totalElements=0, got $total"
    fi
else
    fail "Search no match" "expected HTTP 200, got HTTP $status"
fi

# ─── 6. Pagination ───────────────────────────────────────────────────────────
echo ""
echo "--- Test 6: Pagination (size=2, page=0) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?size=2&page=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    content_count=$(echo "$body" | grep -o '"id"' | wc -l | tr -d ' ')
    size_val=$(echo "$body" | grep -o '"size":[0-9]*' | head -1 | grep -o '[0-9]*')
    if [ "${content_count:-0}" -le 2 ] && [ "${size_val:-0}" -eq 2 ]; then
        pass "Pagination size=2 — HTTP 200, ≤2 items returned"
    else
        fail "Pagination" "size=$size_val content_count=$content_count"
        echo "Body: $body"
    fi
else
    fail "Pagination" "expected HTTP 200, got HTTP $status"
fi

# ─── 7. Unauthenticated → 401 ────────────────────────────────────────────────
echo ""
echo "--- Test 7: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── 8. Invalid size → 400 ───────────────────────────────────────────────────
echo ""
echo "--- Test 8: size=0 → 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?size=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 400 ]; then
    pass "size=0 rejected — HTTP 400"
else
    fail "size=0 validation" "expected HTTP 400, got HTTP $status"
fi

# ─── 9. tenantId that doesn't exist → 404 ───────────────────────────────────
echo ""
echo "--- Test 9: Non-existent tenantId → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles?tenantId=00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Non-existent tenantId — HTTP 404"
else
    fail "Non-existent tenantId" "expected HTTP 404, got HTTP $status"
fi

# ─── 10. Verify PLATFORM_ADMIN role is in the response ──────────────────────
echo ""
echo "--- Test 10: PLATFORM_ADMIN role present in results ---"
resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?search=PLATFORM_ADMIN" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if echo "$resp" | grep -q '"PLATFORM_ADMIN"'; then
    pass "PLATFORM_ADMIN role found in results"
else
    fail "PLATFORM_ADMIN role" "not found in response"
    echo "Body: $resp"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
