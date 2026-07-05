#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/enroll
# Usage: BASE_URL=http://localhost:8080 bash test_enroll.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"

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

echo "=== Face ID Enrollment Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: download test face fixture if not present
mkdir -p "$FIXTURE_DIR"
FACE_IMG="$FIXTURE_DIR/test_face.jpg"
if [ ! -f "$FACE_IMG" ]; then
    echo "--- Setup: Downloading test face fixture ---"
    curl -s -L \
        "https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples/obama.jpg" \
        -o "$FACE_IMG" 2>/dev/null
    if [ ! -s "$FACE_IMG" ]; then
        echo "SETUP WARNING: Could not download test face image — happy-path tests will be skipped"
        rm -f "$FACE_IMG"
    else
        echo "Test face fixture downloaded: $FACE_IMG"
    fi
fi
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# Setup: create tenant + employee
echo "--- Setup: Create tenant and employee ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Enroll Corp\",\"slug\":\"enroll-corp-${TS}\"}")
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && { echo "SETUP FAILED: Could not create tenant"; exit 1; }

emp_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Bob","lastName":"Enroll","email":"bob.enroll@corp.com","employeeCode":"EMP-BE01","position":"Engineer","department":"Tech"}')
EMP_ID=$(echo "$emp_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: Could not create employee"; exit 1; }
echo "Tenant=$TENANT_ID  Employee=$EMP_ID"
echo ""

ENROLL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id/enroll"

# Test 1: Enroll without consent → 400
echo "--- Test 1: Enroll without consent ---"
if [ -f "$FACE_IMG" ]; then
    run_test "Enroll without consent" 400 \
        -X POST "$ENROLL_URL" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg"
else
    echo "SKIP: No fixture image — skipping"
fi

# Test 2: Wrong photo count (1 photo) → 400
echo ""
echo "--- Test 2: Too few photos (1) ---"
if [ -f "$FACE_IMG" ]; then
    run_test "Too few photos" 400 \
        -X POST "$ENROLL_URL" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg"
else
    echo "SKIP: No fixture image — skipping"
fi

# Setup: give consent before remaining tests
echo ""
echo "--- Setup: Give consent ---"
curl -s -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id/consent" \
    -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null
echo "Consent recorded."
echo ""

# Test 3: Wrong photo count (1 photo, after consent) → 400
echo "--- Test 3: Too few photos after consent (1) → 400 ---"
if [ -f "$FACE_IMG" ]; then
    run_test "Too few photos (after consent)" 400 \
        -X POST "$ENROLL_URL" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg"
else
    echo "SKIP: No fixture image — skipping"
fi

# Test 4: Happy path — enroll with 3 face photos
echo ""
echo "--- Test 4: Happy path — enroll with 3 photos ---"
if [ -f "$FACE_IMG" ]; then
    enroll_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$ENROLL_URL" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")
    enroll_body=$(echo "$enroll_resp" | head -n -1)
    enroll_status=$(echo "$enroll_resp" | tail -n 1)
    if [ "$enroll_status" -eq 200 ]; then
        enrolled=$(echo "$enroll_body" | grep -o '"status":"enrolled"' || true)
        if [ -n "$enrolled" ]; then
            echo "PASS: Happy path enrollment (HTTP 200, status=enrolled)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Happy path — HTTP 200 but status not enrolled"
            echo "Body: $enroll_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Happy path — expected HTTP 200, got HTTP $enroll_status"
        echo "Body: $enroll_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No fixture image — skipping happy path"
fi

# Test 5: Employee not found → 404
echo ""
echo "--- Test 5: Employee not found ---"
run_test "Employee not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/00000000-0000-0000-0000-000000000000/face-id/enroll" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -F "photos=@${FACE_IMG:-/dev/null};type=image/jpeg" \
    -F "photos=@${FACE_IMG:-/dev/null};type=image/jpeg" \
    -F "photos=@${FACE_IMG:-/dev/null};type=image/jpeg"

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$ENROLL_URL"

# Test 7: No permission → 403
echo ""
echo "--- Test 7: User without permission ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.enroll.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.enroll.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X POST "$ENROLL_URL" \
        -H "Authorization: Bearer $NO_PERM_TOKEN"
else
    echo "SKIP: Could not obtain unprivileged token (email verification may be required)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
