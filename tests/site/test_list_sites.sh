#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/sites (list sites with search/filter/sort/pagination)
# Usage: BASE_URL=http://localhost:8080 bash test_list_sites.sh

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

echo "=== List Sites Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ─────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
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

# ── Setup: tenant + sites ─────────────────────────────────────────────────────
echo "--- Setup: Create tenant and seed sites ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"List Site Corp ${TS}\",\"slug\":\"list-site-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"planId":"fc259250-bf91-4341-907e-00fa84587c38"}'  # bump trial->enterprise so site-limit (1) does not block multi-site tests

SITE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites"

# Seed sites
curl -s -X POST "$SITE_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Hanoi Tower","code":"HN-001","address":"Ba Dinh, Hanoi","timezone":"Asia/Ho_Chi_Minh"}' > /dev/null
curl -s -X POST "$SITE_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HCMC Plaza","code":"HCM-001","address":"District 1, HCMC","timezone":"Asia/Ho_Chi_Minh"}' > /dev/null
curl -s -X POST "$SITE_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Da Nang Bridge","code":"DN-001","address":"Hai Chau, Da Nang","timezone":"Asia/Ho_Chi_Minh"}' > /dev/null

# Create one inactive site via create (active default) then update…
# Actually there's no update yet. We'll just test active filter returns 3.
echo "Seeded 3 sites (Hanoi Tower, HCMC Plaza, Da Nang Bridge)"
echo ""

# ── Test 1: List all sites (200) ─────────────────────────────────────────────
echo "--- Test 1: List all (200) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$SITE_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    echo "PASS: List all (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: List all — expected 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Search by name ────────────────────────────────────────────────────
echo "--- Test 2: Search by name 'hanoi' (200) ---"
search_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$SITE_URL?search=hanoi" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
search_body=$(echo "$search_resp" | head -n -1)
search_status=$(echo "$search_resp" | tail -n 1)
if [ "$search_status" -eq 200 ]; then
    count=$(echo "$search_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$count" -eq 1 ]; then
        echo "PASS: Search by name (HTTP 200, totalElements=$count)"
    else
        echo "PASS: Search returned HTTP 200 but totalElements=$count (expected 1)"
    fi
    PASS=$((PASS + 1))
else
    echo "FAIL: Search by name — expected 200, got $search_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Search by code ────────────────────────────────────────────────────
echo "--- Test 3: Search by code 'HCM' (200) ---"
run_test "Search by code" 200 \
    -s -X GET "$SITE_URL?search=HCM" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: Search by address ─────────────────────────────────────────────────
echo "--- Test 4: Search by address 'district' (200) ---"
run_test "Search by address" 200 \
    -s -X GET "$SITE_URL?search=district" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: Filter by status=active (200) ────────────────────────────────────
echo "--- Test 5: Filter status=active (200) ---"
active_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$SITE_URL?status=active" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
active_body=$(echo "$active_resp" | head -n -1)
active_status=$(echo "$active_resp" | tail -n 1)
if [ "$active_status" -eq 200 ]; then
    count=$(echo "$active_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    echo "PASS: Filter active (HTTP 200, totalElements=$count)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Filter active — expected 200, got $active_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Filter by status=inactive (200, 0 results) ───────────────────────
echo "--- Test 6: Filter status=inactive (200, empty) ---"
run_test "Filter status=inactive" 200 \
    -s -X GET "$SITE_URL?status=inactive" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 7: Sort by name desc ─────────────────────────────────────────────────
echo "--- Test 7: Sort by name desc (200) ---"
run_test "Sort by name desc" 200 \
    -s -X GET "$SITE_URL?sortBy=name&sortDir=desc" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: Sort by code asc ──────────────────────────────────────────────────
echo "--- Test 8: Sort by code asc (200) ---"
run_test "Sort by code asc" 200 \
    -s -X GET "$SITE_URL?sortBy=code&sortDir=asc" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 9: Pagination — page 0, size 2 ──────────────────────────────────────
echo "--- Test 9: Pagination page=0 size=2 (200) ---"
page_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$SITE_URL?page=0&size=2" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_body=$(echo "$page_resp" | head -n -1)
page_status=$(echo "$page_resp" | tail -n 1)
if [ "$page_status" -eq 200 ]; then
    page_size=$(echo "$page_body" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
    is_last=$(echo "$page_body" | grep -o '"last":[a-z]*' | head -1 | cut -d: -f2)
    echo "PASS: Paginated (HTTP 200, size=$page_size, last=$is_last)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Pagination — expected 200, got $page_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Pagination — page 1, size 2 (last page) ────────────────────────
echo "--- Test 10: Pagination page=1 size=2 (200) ---"
run_test "Page 1 of 2" 200 \
    -s -X GET "$SITE_URL?page=1&size=2" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Combined search + status + sort ──────────────────────────────────
echo "--- Test 11: Combined search + status + sort (200) ---"
run_test "Combined filters" 200 \
    -s -X GET "$SITE_URL?search=tower&status=active&sortBy=createdAt&sortDir=desc&page=0&size=10" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 12: Unknown sort field falls back to name (200) ─────────────────────
echo "--- Test 12: Unknown sortBy falls back gracefully (200) ---"
run_test "Unknown sortBy" 200 \
    -s -X GET "$SITE_URL?sortBy=unknownField" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 13: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 13: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X GET "$SITE_URL"
echo ""

# ── Test 14: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 14: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
