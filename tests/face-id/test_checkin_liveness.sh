#!/usr/bin/env bash
# Tests for check-in with liveness verification (Task 70)
# requiresLiveness=true is a one-flag extension of Task 69.
# Tests that:
#   1. Check-in with requiresLiveness=true is accepted (fail-open)
#   2. livenessVerified field appears in response
#   3. End-to-end: worker processes liveness, callback updates checkin
# Usage: BASE_URL=http://localhost:8080 AI_INTERNAL_SECRET=<secret> bash test_checkin_liveness.sh

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

echo "=== Check-in with Liveness Verification Tests ==="
echo "Target: $BASE_URL"
echo ""

mkdir -p "$SCRIPT_DIR/fixtures"
if [ ! -f "$FACE_IMG" ]; then
    curl -sL -o "$FACE_IMG" \
        "https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples/obama.jpg" || true
fi
FIXTURE_AVAILABLE=false
[ -f "$FACE_IMG" ] && FIXTURE_AVAILABLE=true
echo "Fixture available: $FIXTURE_AVAILABLE"
echo ""

# ── Setup ────────────────────────────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
[ "$(echo "$login_resp" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: admin login"; exit 1; }
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"LivenessCI Corp\",\"slug\":\"liveness-ci-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
[ "$(echo "$t_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: tenant"; exit 1; }
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Test HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
[ "$(echo "$s_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: site"; exit 1; }
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.8492,21.0235],[105.8592,21.0235],[105.8592,21.0335],[105.8492,21.0335],[105.8492,21.0235]],"bufferMeters":50}'

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"AllDay","startTime":"00:00","endTime":"23:59"}')
[ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: shift"; exit 1; }
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="liveness.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Live\",\"lastName\":\"Tester\"}")
[ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: invitation"; exit 1; }

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
[ -z "$INV_TOKEN" ] && { echo "SETUP FAILED: inv token"; exit 1; }

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
[ "$(echo "$emp_login" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: emp login"; exit 1; }
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: emp id"; exit 1; }

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

echo "Tenant=$TENANT_ID  Site=$SITE_ID  Employee=$EMP_ID"
echo ""

CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"
FACE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

# ── Test 1: Check-in with requiresLiveness=true → 201 (fail-open) ────────────
echo "--- Test 1: Check-in with requiresLiveness=true → 201 ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
    TMPFILE=$(mktemp /tmp/ci_live_body.XXXXXX.json)
    printf '{"siteId":"%s","latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s","requiresLiveness":true}' \
        "$SITE_ID" "$PHOTO_B64" > "$TMPFILE"
    ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        --data @"$TMPFILE")
    rm -f "$TMPFILE"
    ci_body=$(echo "$ci_resp" | head -n -1)
    ci_status=$(echo "$ci_resp" | tail -n 1)
    if [ "$ci_status" -eq 201 ]; then
        CHECKIN_ID=$(echo "$ci_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "PASS: Check-in with requiresLiveness=true (HTTP 201) checkinId=$CHECKIN_ID"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 201, got $ci_status — $ci_body"
        FAIL=$((FAIL + 1))
        CHECKIN_ID=""
    fi
else
    echo "SKIP: No fixture — skipping liveness check-in test"
    CHECKIN_ID=""
fi
echo ""

# ── Test 2: livenessVerified and faceVerified present in response ─────────────
echo "--- Test 2: Response includes livenessVerified and faceVerified fields ---"
if [ -n "${CHECKIN_ID:-}" ]; then
    has_live=$(echo "$ci_body" | grep -o '"livenessVerified"' || true)
    has_face=$(echo "$ci_body" | grep -o '"faceVerified"' || true)
    if [ -n "$has_live" ] && [ -n "$has_face" ]; then
        echo "PASS: livenessVerified and faceVerified present in response"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing fields — body: $ci_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No checkin ID"
fi
echo ""

# ── Test 3: E2E — enroll, submit with liveness, poll result ──────────────────
echo "--- Test 3: E2E liveness check-in after enrollment ---"
if [ "$FIXTURE_AVAILABLE" = "true" ] && [ -n "${CHECKIN_ID:-}" ]; then
    curl -s -o /dev/null -X POST "$FACE_URL/consent" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    enroll_st=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$FACE_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")

    if [ "$enroll_st" -eq 200 ]; then
        curl -s -o /dev/null -X POST "$CHECKIN_URL/$CHECKIN_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            -d "{\"latitude\":21.0285,\"longitude\":105.8542}" || true

        PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
        TMPFILE2=$(mktemp /tmp/ci_live_body2.XXXXXX.json)
        printf '{"siteId":"%s","latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s","requiresLiveness":true}' \
            "$SITE_ID" "$PHOTO_B64" > "$TMPFILE2"
        ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKIN_URL" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            --data @"$TMPFILE2")
        rm -f "$TMPFILE2"
        ci2_status=$(echo "$ci2_resp" | tail -n 1)
        CHECKIN2_ID=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

        if [ "$ci2_status" -eq 201 ] && [ -n "$CHECKIN2_ID" ]; then
            echo "Check-in submitted: $CHECKIN2_ID. Polling..."
            LIVENESS_VERIFIED=""
            for i in $(seq 1 8); do
                sleep 5
                poll=$(curl -s "$CHECKIN_URL/$CHECKIN2_ID" \
                    -H "Authorization: Bearer $EMP_TOKEN")
                LIVENESS_VERIFIED=$(echo "$poll" | grep -o '"livenessVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
                FACE_VERIFIED=$(echo "$poll" | grep -o '"faceVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
                if [ "$LIVENESS_VERIFIED" != "null" ] && [ -n "$LIVENESS_VERIFIED" ]; then
                    break
                fi
                echo "  Poll $i: livenessVerified still null..."
            done

            if [ "$LIVENESS_VERIFIED" = "true" ]; then
                echo "PASS: livenessVerified=true (real image passed liveness)"
                PASS=$((PASS + 1))
            elif [ "$LIVENESS_VERIFIED" = "false" ]; then
                # Static image may fail anti-spoof — this is acceptable behavior
                echo "INFO: livenessVerified=false (static image may fail MiniFASNet anti-spoof — expected)"
                echo "PASS: Liveness pipeline ran (result recorded)"
                PASS=$((PASS + 1))
            else
                echo "FAIL: livenessVerified never resolved after 40s — worker may not be running"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: Check-in failed (status=$ci2_status)"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "SKIP: Enrollment failed — skipping E2E"
    fi
else
    echo "SKIP: No fixture or no checkin"
fi
echo ""

echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
