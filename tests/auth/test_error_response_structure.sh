#!/usr/bin/env bash
# Tests for enriched error responses (Task 129)
# Verifies that error responses include error_code and user_message fields
# Usage: BASE_URL=http://localhost:8080 bash test_error_response_structure.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "PASS: $name (contains '$needle')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — response did not contain '$needle'"
        echo "  Body: $haystack"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Error Response Structure Tests (Task 129) ==="
echo "Target: $BASE_URL"
echo ""

# ── 1. Invalid credentials — INVALID_CREDENTIALS code ────────────────────────
echo "--- 1. Invalid credentials (401) ---"
cred_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"nobody@nowhere.com","password":"wrongpass123"}')
check_contains "Error has success:false" "$cred_resp" '"success":false'
check_contains "Error has errorCode" "$cred_resp" '"errorCode"'
check_contains "Error code is INVALID_CREDENTIALS" "$cred_resp" '"INVALID_CREDENTIALS"'
check_contains "Error has userMessage" "$cred_resp" '"userMessage"'
check_contains "userMessage is in Vietnamese" "$cred_resp" 'mật khẩu'

# ── 2. Validation error — VALIDATION_ERROR code ───────────────────────────────
echo ""
echo "--- 2. Validation error (400) ---"
val_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"not-an-email","password":"x"}')
check_contains "Validation error has success:false" "$val_resp" '"success":false'
check_contains "Validation error has errorCode" "$val_resp" '"errorCode"'
check_contains "Validation errorCode is VALIDATION_ERROR" "$val_resp" '"VALIDATION_ERROR"'
check_contains "Validation error has userMessage" "$val_resp" '"userMessage"'

# ── 3. Resource not found — RESOURCE_NOT_FOUND code ──────────────────────────
echo ""
echo "--- 3. Resource not found (404) ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: admin login"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

nf_resp=$(curl -s \
    "$BASE_URL/api/v1/audit-logs/00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "Not found has success:false" "$nf_resp" '"success":false'
check_contains "Not found has errorCode" "$nf_resp" '"errorCode"'
check_contains "Not found errorCode is RESOURCE_NOT_FOUND" "$nf_resp" '"RESOURCE_NOT_FOUND"'
check_contains "Not found has userMessage in Vietnamese" "$nf_resp" 'Không tìm thấy'

# ── 4. Access denied — ACCESS_DENIED code ────────────────────────────────────
echo ""
echo "--- 4. Access denied (403) ---"
TS=$(date +%s)
TENANT_OWNER_EMAIL="err_corp_owner_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$TENANT_OWNER_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Err Corp Owner\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$TENANT_OWNER_EMAIL';" > /dev/null
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Err Corp ${TS}\",\"slug\":\"err-corp-${TS}\",\"ownerEmail\":\"$TENANT_OWNER_EMAIL\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: create tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="err.emp.${TS}@example.com"
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Err\",\"lastName\":\"Emp\"}"

inv_page=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
INV_TOKEN=$(echo "$inv_page" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

ad_resp=$(curl -s \
    "$BASE_URL/api/v1/audit-logs" \
    -H "Authorization: Bearer $EMP_TOKEN")
check_contains "Access denied has success:false" "$ad_resp" '"success":false'
check_contains "Access denied has errorCode" "$ad_resp" '"errorCode"'
check_contains "Access denied errorCode is ACCESS_DENIED" "$ad_resp" '"ACCESS_DENIED"'
check_contains "Access denied has Vietnamese userMessage" "$ad_resp" 'không có quyền'

# ── 5. Malformed body — MALFORMED_REQUEST code ───────────────────────────────
echo ""
echo "--- 5. Malformed request body (400) ---"
mal_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d 'this is not json')
check_contains "Malformed body has errorCode" "$mal_resp" '"errorCode"'
check_contains "Malformed errorCode is MALFORMED_REQUEST" "$mal_resp" '"MALFORMED_REQUEST"'

# ── 6. Success response has NO errorCode/userMessage ─────────────────────────
echo ""
echo "--- 6. Success response has no errorCode or userMessage ---"
ok_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if echo "$ok_resp" | grep -q '"errorCode"'; then
    echo "FAIL: Success response should NOT contain errorCode"
    FAIL=$((FAIL + 1))
else
    echo "PASS: Success response has no errorCode"
    PASS=$((PASS + 1))
fi
if echo "$ok_resp" | grep -q '"userMessage"'; then
    echo "FAIL: Success response should NOT contain userMessage"
    FAIL=$((FAIL + 1))
else
    echo "PASS: Success response has no userMessage"
    PASS=$((PASS + 1))
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: $PASS passed, $FAIL failed"
echo "============================================"
[ "$FAIL" -eq 0 ]
