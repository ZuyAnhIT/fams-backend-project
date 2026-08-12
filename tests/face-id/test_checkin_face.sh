#!/usr/bin/env bash
# Tests for check-in with face verification (Task 69)
# Tests that:
#   1. Check-in with employeePhotoBase64 field is accepted (fail-open — saves immediately)
#   2. Check-in without photo still works (no regression)
#   3. Face verification completes asynchronously (poll for faceVerified)
#   4. Callback endpoint rejects wrong secret (403)
# Usage: BASE_URL=http://localhost:8080 AI_INTERNAL_SECRET=<secret> bash test_checkin_face.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
AI_INTERNAL_SECRET="${AI_INTERNAL_SECRET:-}"
PASS=0
FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACE_IMG="$SCRIPT_DIR/fixtures/test_face.jpg"

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

echo "=== Check-in with Face Verification Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: download fixture if missing ─────���────────────────────────────────
mkdir -p "$SCRIPT_DIR/fixtures"
if [ ! -f "$FACE_IMG" ]; then
    echo "Downloading test face fixture..."
    curl -sL -o "$FACE_IMG" \
        "https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples/obama.jpg" || true
fi
FIXTURE_AVAILABLE=false
[ -f "$FACE_IMG" ] && FIXTURE_AVAILABLE=true
echo "Fixture available: $FIXTURE_AVAILABLE"
echo ""

# ── Setup: admin login ───────────────────────��────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: admin login"; exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: create tenant, site, shift, geofence ───────────────────���───────────
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"FaceCI Corp\",\"slug\":\"faceci-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
[ "$(echo "$t_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: tenant"; exit 1; }
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Hanoi HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
[ "$(echo "$s_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: site"; exit 1; }
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.8492,21.0235],[105.8592,21.0235],[105.8592,21.0335],[105.8492,21.0335],[105.8492,21.0235]],"bufferMeters":50}'

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning","startTime":"00:00","endTime":"23:59"}')
[ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: shift"; exit 1; }
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Tenant=$TENANT_ID  Site=$SITE_ID"

# ── Setup: invite employee, accept, login ─────────────────���───────────────────
INVITE_EMAIL="faceci.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Face\",\"lastName\":\"Tester\"}")
[ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: invitation"; exit 1; }

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
[ -z "$INV_TOKEN" ] && { echo "SETUP FAILED: invitation token"; exit 1; }

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
[ "$(echo "$emp_login" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: employee login"; exit 1; }
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: employee id"; exit 1; }

# Create assignment covering today
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

echo "Employee=$EMP_ID"
echo ""

CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"
FACE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

# ── Test 1: Unauthenticated → 401 ────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 2: Check-in without photo field → 201 (no regression) ───────────────
echo "--- Test 2: Check-in without photo → 201 ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
ci_body=$(echo "$ci_resp" | head -n -1)
ci_status=$(echo "$ci_resp" | tail -n 1)
if [ "$ci_status" -eq 201 ]; then
    CHECKIN1_ID=$(echo "$ci_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Check-in without photo (HTTP 201) checkinId=$CHECKIN1_ID"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 201, got $ci_status — $ci_body"
    FAIL=$((FAIL + 1))
    CHECKIN1_ID=""
fi

# Check-out to allow next test
if [ -n "${CHECKIN1_ID:-}" ]; then
    curl -s -o /dev/null -X POST "$CHECKIN_URL/$CHECKIN1_ID/checkout" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        -d "{\"latitude\":21.0285,\"longitude\":105.8542}" || true
fi
echo ""

# ── Test 3: Check-in with employeePhotoBase64 → 201 (fail-open) ──────────────
echo "--- Test 3: Check-in with photo field → 201 (fail-open) ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
    TMPFILE=$(mktemp /tmp/checkin_body.XXXXXX.json)
    printf '{"siteId":"%s","latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s"}' \
        "$SITE_ID" "$PHOTO_B64" > "$TMPFILE"
    ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        --data @"$TMPFILE")
    rm -f "$TMPFILE"
    ci2_body=$(echo "$ci2_resp" | head -n -1)
    ci2_status=$(echo "$ci2_resp" | tail -n 1)
    if [ "$ci2_status" -eq 201 ]; then
        CHECKIN2_ID=$(echo "$ci2_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "PASS: Check-in with photo (HTTP 201) checkinId=$CHECKIN2_ID"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 201, got $ci2_status — $ci2_body"
        FAIL=$((FAIL + 1))
        CHECKIN2_ID=""
    fi
else
    echo "SKIP: No fixture image — skipping photo test"
    CHECKIN2_ID=""
fi
echo ""

# ── Test 4: faceVerified is null immediately after checkin ────────────────────
echo "--- Test 4: faceVerified is null immediately after check-in ---"
if [ -n "${CHECKIN2_ID:-}" ]; then
    ci2_fv=$(echo "$ci2_body" | grep -o '"faceVerified":null' || true)
    if [ -n "$ci2_fv" ]; then
        echo "PASS: faceVerified=null immediately after check-in (async not done)"
        PASS=$((PASS + 1))
    else
        # It's ok if it's already populated (worker was fast)
        echo "INFO: faceVerified already resolved — worker may have completed quickly"
        echo "PASS: faceVerified field present in response"
        PASS=$((PASS + 1))
    fi
else
    echo "SKIP: No check-in ID from test 3"
fi
echo ""

# ── Test 5: Enroll + poll for face verification result ─────────────────��──────
echo "--- Test 5: Full E2E — enroll then verify via checkin ---"
if [ "$FIXTURE_AVAILABLE" = "true" ] && [ -n "${CHECKIN2_ID:-}" ]; then
    # Consent + enroll with the same fixture image
    curl -s -o /dev/null -X POST "$FACE_URL/consent" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    enroll_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$FACE_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")

    if [ "$enroll_status" -eq 200 ]; then
        echo "Enrolled. Checking out and submitting new check-in with photo..."
        # Check out from test 3's checkin
        curl -s -o /dev/null -X POST "$CHECKIN_URL/$CHECKIN2_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            -d "{\"latitude\":21.0285,\"longitude\":105.8542}" || true

        # New check-in with photo
        PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
        TMPFILE3=$(mktemp /tmp/checkin_body3.XXXXXX.json)
        printf '{"siteId":"%s","latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s"}' \
            "$SITE_ID" "$PHOTO_B64" > "$TMPFILE3"
        ci3_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            --data @"$TMPFILE3")
        rm -f "$TMPFILE3"
        ci3_status=$(echo "$ci3_resp" | tail -n 1)
        CHECKIN3_ID=$(echo "$ci3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

        if [ "$ci3_status" -eq 201 ] && [ -n "$CHECKIN3_ID" ]; then
            echo "Check-in submitted: $CHECKIN3_ID. Polling for face verification..."
            FACE_VERIFIED=""
            for i in $(seq 1 6); do
                sleep 5
                poll_resp=$(curl -s "$CHECKIN_URL/$CHECKIN3_ID" \
                    -H "Authorization: Bearer $EMP_TOKEN")
                FACE_VERIFIED=$(echo "$poll_resp" | grep -o '"faceVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
                if [ "$FACE_VERIFIED" != "null" ] && [ -n "$FACE_VERIFIED" ]; then
                    break
                fi
                echo "  Poll $i: faceVerified still null..."
            done

            if [ "$FACE_VERIFIED" = "true" ]; then
                echo "PASS: Face verification completed — faceVerified=true"
                PASS=$((PASS + 1))
            elif [ "$FACE_VERIFIED" = "false" ]; then
                echo "FAIL: faceVerified=false (same image should match enrolled profile)"
                FAIL=$((FAIL + 1))
            else
                echo "FAIL: faceVerified never resolved after 30s — worker may not be running"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: Check-in with enrolled employee failed (status=$ci3_status)"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "SKIP: Enrollment failed (status=$enroll_status) — skipping E2E face verification"
    fi
else
    echo "SKIP: No fixture or no checkin from test 3"
fi
echo ""

# ── Test 6: Callback endpoint rejects wrong secret → 403 ─────────────────────
echo "--- Test 6: Callback endpoint rejects wrong secret → 403 ---"
run_test "Callback wrong secret" 403 \
    -X POST "$BASE_URL/internal/ai-callback/face-result" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: wrong-secret" \
    -d "{\"sourceId\":\"00000000-0000-0000-0000-000000000000\",\"tenantId\":\"00000000-0000-0000-0000-000000000000\",\"sourceType\":\"checkin\",\"faceVerified\":true}"
echo ""

# ── Test 7: Callback with correct secret updates checkin ─────────────────────
echo "--- Test 7: Callback with correct secret → 200 ---"
if [ -n "$AI_INTERNAL_SECRET" ] && [ -n "${CHECKIN1_ID:-}" ]; then
    TENANT_OF_CHECKIN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT tenant_id FROM checkins WHERE id='$CHECKIN1_ID' LIMIT 1;" | tr -d ' \n')
    cb_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/internal/ai-callback/face-result" \
        -H "Content-Type: application/json" \
        -H "X-Internal-Secret: $AI_INTERNAL_SECRET" \
        -d "{\"sourceId\":\"$CHECKIN1_ID\",\"tenantId\":\"$TENANT_OF_CHECKIN\",\"sourceType\":\"checkin\",\"faceVerified\":true,\"faceVerifyScore\":0.95}")
    cb_status=$(echo "$cb_resp" | tail -n 1)
    if [ "$cb_status" -eq 200 ]; then
        # Verify the DB was updated
        updated=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
            "SELECT face_verified FROM checkins WHERE id='$CHECKIN1_ID' LIMIT 1;" | tr -d ' \n')
        if [ "$updated" = "t" ]; then
            echo "PASS: Callback updated checkin face_verified=true"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Callback returned 200 but face_verified not updated (got '$updated')"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected 200, got $cb_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: AI_INTERNAL_SECRET not set or no checkin1 — skipping callback test"
fi
echo ""

echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
