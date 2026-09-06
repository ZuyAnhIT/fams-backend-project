#!/usr/bin/env bash
# Tests for the employee import template, non-mutating validation and import endpoints.
# The temporary tenant always has a separate verified owner; Platform Admin only provisions
# it and must not be assigned a company role as a side effect of this test.
# Usage: BASE_URL=http://localhost:8080 bash test_import_employees.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/fixtures"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

echo "=== Import Employees Tests ==="
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
    echo "SETUP FAILED: Could not login (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

TS=$(date +%s)
# A Platform Admin provisions the tenant but must never become its company owner/member.
echo "--- Setup: Register a separate company owner ---"
OWNER_EMAIL="import_owner_${TS}_$$@fams.test"
OWNER_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Import Test Owner" "$OWNER_EMAIL")
if [ -z "$OWNER_TOKEN" ]; then
    echo "SETUP FAILED: Could not create a verified company owner"
    exit 1
fi
echo "Verified owner account created."
echo ""

# Setup: create tenant
echo "--- Setup: Create test tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Import Corp\",\"slug\":\"import-corp-${TS}-$$\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"
echo "Tenant created: id=$TENANT_ID"
echo ""

# Test 1: Download the standard Vietnamese template
echo "--- Test 1: Download Vietnamese import template ---"
template_file=$(mktemp)
template_headers=$(mktemp)
template_status=$(curl -s -o "$template_file" -D "$template_headers" -w "%{http_code}" \
    -X GET "$EMP_URL/import/template" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
template_disposition=$(tr -d '\r' < "$template_headers" | grep -i '^content-disposition:' || true)
if [ "$template_status" -eq 200 ] \
        && echo "$template_disposition" | grep -q 'mau-import-nhan-vien.xlsx' \
        && unzip -tqq "$template_file"; then
    echo "PASS: Template is a valid .xlsx with the expected Vietnamese filename"
    PASS=$((PASS + 1))
else
    echo "FAIL: Template download/format invalid (HTTP $template_status, disposition=$template_disposition)"
    FAIL=$((FAIL + 1))
fi
rm -f "$template_file" "$template_headers"

# Test 2: Validate field errors without writing employee data
echo ""
echo "--- Test 2: Preflight validation is non-mutating ---"
before_count=$(curl -s "$EMP_URL?size=1" -H "Authorization: Bearer $ADMIN_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2 || true)
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import/validate" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/mixed_employees.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
after_count=$(curl -s "$EMP_URL?size=1" -H "Authorization: Bearer $ADMIN_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | head -1 | cut -d: -f2 || true)
valid_rows=$(echo "$body" | grep -o '"validRows":[0-9]*' | cut -d: -f2 || true)
invalid_rows=$(echo "$body" | grep -o '"invalidRows":[0-9]*' | cut -d: -f2 || true)
if [ "$status" -eq 200 ] && [ "$valid_rows" = "1" ] && [ "$invalid_rows" = "2" ] \
        && [ "$before_count" = "$after_count" ] \
        && echo "$body" | grep -q '"field":"email"' \
        && echo "$body" | grep -q '"field":"firstName"'; then
    echo "PASS: Validation reports field errors and leaves employee count unchanged ($before_count)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Validation contract/non-mutation failed (HTTP $status, before=$before_count, after=$after_count)"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Happy path — 3 valid rows all succeed
echo ""
echo "--- Test 3: Import 3 valid employees ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/valid_employees.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalRows":[0-9]*' | cut -d: -f2 || true)
    success=$(echo "$body" | grep -o '"successCount":[0-9]*' | cut -d: -f2 || true)
    failed=$(echo "$body" | grep -o '"failedCount":[0-9]*' | cut -d: -f2 || true)
    if [ "$total" = "3" ] && [ "$success" = "3" ] && [ "$failed" = "0" ]; then
        echo "PASS: Import 3 valid (total=$total success=$success failed=$failed)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Import 3 valid — total=$total success=$success failed=$failed"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Import 3 valid — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 4: Mixed file — 1 valid, 2 invalid (bad email + missing firstName)
echo ""
echo "--- Test 4: Mixed file (1 valid, 2 invalid) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/mixed_employees.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalRows":[0-9]*' | cut -d: -f2 || true)
    success=$(echo "$body" | grep -o '"successCount":[0-9]*' | cut -d: -f2 || true)
    failed=$(echo "$body" | grep -o '"failedCount":[0-9]*' | cut -d: -f2 || true)
    if [ "$total" = "3" ] && [ "$success" = "1" ] && [ "$failed" = "2" ]; then
        echo "PASS: Mixed file (total=$total success=$success failed=$failed)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Mixed file — total=$total success=$success failed=$failed"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Mixed file — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 5: Duplicate code within same file → second row fails
echo ""
echo "--- Test 5: Duplicate code within import file ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/duplicate_code.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    success=$(echo "$body" | grep -o '"successCount":[0-9]*' | cut -d: -f2 || true)
    failed=$(echo "$body" | grep -o '"failedCount":[0-9]*' | cut -d: -f2 || true)
    if [ "$success" = "1" ] && [ "$failed" = "1" ]; then
        echo "PASS: Duplicate code in file (success=$success failed=$failed)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Duplicate code — success=$success failed=$failed"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Duplicate code — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 6: Duplicate code against existing DB record
echo ""
echo "--- Test 6: Duplicate code conflicts with existing employee ---"
# Create an employee first with a known code
curl -s -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Existing\",\"lastName\":\"Emp\",\"employeeCode\":\"EMP-IMPORT-001\"}" > /dev/null
# Now try to import valid_employees.xlsx which contains EMP-IMPORT-001 already
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/valid_employees.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    failed=$(echo "$body" | grep -o '"failedCount":[0-9]*' | cut -d: -f2 || true)
    if [ "$failed" -ge 1 ]; then
        echo "PASS: DB duplicate code detected (failedCount=$failed)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: DB duplicate code not detected — failedCount=$failed"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: DB duplicate — expected HTTP 200, got HTTP $status"
    FAIL=$((FAIL + 1))
fi

# Test 7: Empty file (header only) → an explicit data error
echo ""
echo "--- Test 7: Empty file (header only) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/empty_file.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalRows":[0-9]*' | cut -d: -f2 || true)
    if [ "$total" = "0" ] && echo "$body" | grep -q 'File chưa có dòng dữ liệu nhân viên'; then
        echo "PASS: Empty file reports totalRows=0 and an actionable error"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Empty file — totalRows=$total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Empty file — expected HTTP 200, got HTTP $status"
    FAIL=$((FAIL + 1))
fi

# Test 8: Unauthenticated → 401
echo ""
echo "--- Test 8: Unauthenticated ---"
actual=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$EMP_URL/import" \
    -F "file=@$FIXTURES/valid_employees.xlsx")
if [ "$actual" -eq 401 ]; then
    echo "PASS: Unauthenticated (HTTP 401)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unauthenticated — expected 401, got $actual"
    FAIL=$((FAIL + 1))
fi

# Test 9: No permission → 403
echo ""
echo "--- Test 9: Forbidden ---"
NO_PERM_EMAIL="noperm.import.${TS}.$$@fams.test"
NO_PERM_TOKEN=$(register_verified_test_user_token "$BASE_URL" "No Import Permission" "$NO_PERM_EMAIL")
if [ -n "$NO_PERM_TOKEN" ]; then
    actual=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$EMP_URL/import" \
        -H "Authorization: Bearer $NO_PERM_TOKEN" \
        -F "file=@$FIXTURES/valid_employees.xlsx")
    if [ "$actual" -eq 403 ]; then
        echo "PASS: No permission forbidden (HTTP 403)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: No permission — expected 403, got $actual"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Could not obtain unprivileged token (email verification required)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
