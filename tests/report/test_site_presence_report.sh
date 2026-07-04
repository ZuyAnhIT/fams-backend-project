#!/usr/bin/env bash
# Tests for site presence report API (Task 126)
# Verifies real-time presence snapshot per site.
# Usage: BASE_URL=http://localhost:8080 bash test_site_presence_report.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Site Presence Report Tests (Task 126) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: platform admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# ── Setup: create isolated tenant + one site ──────────────────────────────────
echo "--- Setup: create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Presence Corp ${TS}\",\"slug\":\"presence-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Setup: create site ---"
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
s_status=$(echo "$s_resp" | tail -n 1)
if [ "$s_status" -ne 201 ]; then echo "SETUP FAILED: site (HTTP $s_status)"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID SITE_ID=$SITE_ID"
echo ""

REPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/sites/presence"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$REPORT_URL"
echo ""

# ── Test 2: Admin with no filter → 200 ───────────────────────────────────────
echo "--- Test 2: Admin requests presence report → 200 ---"
report_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
report_body=$(echo "$report_resp" | head -n -1)
report_status=$(echo "$report_resp" | tail -n 1)
if [ "$report_status" -eq 200 ]; then
    echo "PASS: Presence report returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $report_status — $report_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Response has all required top-level fields ────────────────────────
echo "--- Test 3: Response contains required aggregate fields ---"
if [ "$report_status" -eq 200 ]; then
    has_reported=$(echo "$report_body" | grep -c '"reportedAt"' || true)
    has_total_sites=$(echo "$report_body" | grep -c '"totalSites"' || true)
    has_total_present=$(echo "$report_body" | grep -c '"totalPresent"' || true)
    has_total_assigned=$(echo "$report_body" | grep -c '"totalAssigned"' || true)
    has_total_absent=$(echo "$report_body" | grep -c '"totalAbsent"' || true)
    has_sites=$(echo "$report_body" | grep -c '"sites"' || true)
    if [ "${has_reported:-0}" -ge 1 ] && [ "${has_total_sites:-0}" -ge 1 ] && \
       [ "${has_total_present:-0}" -ge 1 ] && [ "${has_total_assigned:-0}" -ge 1 ] && \
       [ "${has_total_absent:-0}" -ge 1 ] && [ "${has_sites:-0}" -ge 1 ]; then
        echo "PASS: Response has all required aggregate fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing required fields — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: sites section has pagination metadata ─────────────────────────────
echo "--- Test 4: sites section has pagination metadata ---"
if [ "$report_status" -eq 200 ]; then
    has_content=$(echo "$report_body" | grep -c '"content"' || true)
    has_total_el=$(echo "$report_body" | grep -c '"totalElements"' || true)
    if [ "${has_content:-0}" -ge 1 ] && [ "${has_total_el:-0}" -ge 1 ]; then
        echo "PASS: sites section has content, totalElements"
        PASS=$((PASS + 1))
    else
        echo "FAIL: sites section missing pagination metadata — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Report includes the created site ──────────────────────────────────
echo "--- Test 5: Created site appears in report ---"
if [ "$report_status" -eq 200 ]; then
    total_sites=$(echo "$report_body" | grep -o '"totalSites":[0-9]*' | cut -d: -f2)
    if [ "${total_sites:-0}" -ge 1 ]; then
        echo "PASS: totalSites=$total_sites (at least 1)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalSites>=1, got $total_sites"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Each site entry has required fields ───────────────────────────────
echo "--- Test 6: Site entries have siteId, siteName, presentCount, absentCount ---"
if [ "$report_status" -eq 200 ]; then
    has_site_id=$(echo "$report_body" | grep -c '"siteId"' || true)
    has_site_name=$(echo "$report_body" | grep -c '"siteName"' || true)
    has_present=$(echo "$report_body" | grep -c '"presentCount"' || true)
    has_absent=$(echo "$report_body" | grep -c '"absentCount"' || true)
    has_assigned=$(echo "$report_body" | grep -c '"assignedCount"' || true)
    if [ "${has_site_id:-0}" -ge 1 ] && [ "${has_site_name:-0}" -ge 1 ] && \
       [ "${has_present:-0}" -ge 1 ] && [ "${has_absent:-0}" -ge 1 ] && \
       [ "${has_assigned:-0}" -ge 1 ]; then
        echo "PASS: Site entries have siteId, siteName, presentCount, absentCount, assignedCount"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Site entries missing fields — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Filter by siteId → 200 ───────────────────────────────────────────
echo "--- Test 7: Filter by siteId → 200 ---"
site_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?siteId=$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
site_body=$(echo "$site_resp" | head -n -1)
site_status=$(echo "$site_resp" | tail -n 1)
if [ "$site_status" -eq 200 ]; then
    site_count=$(echo "$site_body" | grep -o '"totalSites":[0-9]*' | cut -d: -f2)
    if [ "${site_count:-0}" -eq 1 ]; then
        echo "PASS: siteId filter returned exactly 1 site"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalSites=1, got $site_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $site_status — $site_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Non-existent siteId → 200 with 0 sites ───────────────────────────
echo "--- Test 8: Non-existent siteId → 200 with 0 sites ---"
fake_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?siteId=00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
fake_body=$(echo "$fake_resp" | head -n -1)
fake_status=$(echo "$fake_resp" | tail -n 1)
if [ "$fake_status" -eq 200 ]; then
    fake_count=$(echo "$fake_body" | grep -o '"totalSites":[0-9]*' | cut -d: -f2)
    if [ "${fake_count:-0}" -eq 0 ]; then
        echo "PASS: Non-existent siteId returns totalSites=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalSites=0, got $fake_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $fake_status — $fake_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Custom pagination → 200 ──────────────────────────────────────────
echo "--- Test 9: Custom pagination params → 200 ---"
run_test "Custom pagination" 200 \
    -s "$REPORT_URL?page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: New site has 0 present (no check-ins yet) ───────────────────────
echo "--- Test 10: New site has presentCount=0 (no check-ins) ---"
if [ "$site_status" -eq 200 ]; then
    present=$(echo "$site_body" | grep -o '"totalPresent":[0-9]*' | cut -d: -f2)
    if [ "${present:-0}" -eq 0 ]; then
        echo "PASS: New site has totalPresent=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalPresent=0 for new site, got $present"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 7 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
