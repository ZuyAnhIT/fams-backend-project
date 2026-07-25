#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/employees/import
# Usage: BASE_URL=http://localhost:8080 bash test_import_employees.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/fixtures"

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

# Setup: create tenant
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Import Corp\",\"slug\":\"import-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"
echo "Tenant created: id=$TENANT_ID"
echo ""

# Test 1: Happy path — 3 valid rows all succeed
echo "--- Test 1: Import 3 valid employees ---"
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

# Test 2: Mixed file — 1 valid, 2 invalid (bad email + missing firstName)
echo ""
echo "--- Test 2: Mixed file (1 valid, 2 invalid) ---"
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

# Test 3: Duplicate code within same file → second row fails
echo ""
echo "--- Test 3: Duplicate code within import file ---"
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

# Test 4: Duplicate code against existing DB record
echo ""
echo "--- Test 4: Duplicate code conflicts with existing employee ---"
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

# Test 5: Empty file (header only) → 0 rows
echo ""
echo "--- Test 5: Empty file (header only) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL/import" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "file=@$FIXTURES/empty_file.xlsx")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    total=$(echo "$body" | grep -o '"totalRows":[0-9]*' | cut -d: -f2 || true)
    if [ "$total" = "0" ]; then
        echo "PASS: Empty file (totalRows=0)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Empty file — totalRows=$total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Empty file — expected HTTP 200, got HTTP $status"
    FAIL=$((FAIL + 1))
fi

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
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

# Test 7: No permission → 403
echo ""
echo "--- Test 7: Forbidden ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.import.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.import.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
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
