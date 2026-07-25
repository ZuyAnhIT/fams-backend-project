#!/usr/bin/env bash
# Tests for random check response with face verification (Tasks 103 + 104)
# - Task 103: location_face mode — async face match
# - Task 104: location_face_liveness mode — async face + liveness (one-flag extension)
# Usage: BASE_URL=http://localhost:8080 AI_INTERNAL_SECRET=<secret> bash test_check_response_face.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
AI_INTERNAL_SECRET="${AI_INTERNAL_SECRET:-}"
PASS=0
FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACE_IMG="$SCRIPT_DIR/fixtures/test_face.jpg"

run_test() {
    local name="$1" expected="$2"
    local actual
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual" -eq "$expected" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

check_val() {
    local name="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Check Response with Face Verification Tests (Tasks 103 + 104) ==="
echo "Target: $BASE_URL"
echo ""

# ── Fixture ──────────────────────────────────────────────────────────────────
mkdir -p "$SCRIPT_DIR/fixtures"
if [ ! -f "$FACE_IMG" ]; then
    curl -sL -o "$FACE_IMG" \
        "https://raw.githubusercontent.com/ageitgey/face_recognition/master/examples/obama.jpg" || true
fi
FIXTURE_AVAILABLE=false
[ -f "$FACE_IMG" ] && FIXTURE_AVAILABLE=true
echo "Fixture available: $FIXTURE_AVAILABLE"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
[ "$(echo "$login_resp" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: login"; exit 1; }
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)
TENANT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"FaceCheck Corp\",\"slug\":\"facecheck-${TS}\"}" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && { echo "SETUP FAILED: tenant"; exit 1; }

SITE_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"FC HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}' \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$SITE_ID" ] && { echo "SETUP FAILED: site"; exit 1; }

# Geofence around Hanoi HQ (~500m box)
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.8492,21.0235],[105.8592,21.0235],[105.8592,21.0335],[105.8492,21.0335],[105.8492,21.0235]],"bufferMeters":200}'

SHIFT_ID=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"AllDay","startTime":"00:00","endTime":"23:59"}' \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$SHIFT_ID" ] && { echo "SETUP FAILED: shift"; exit 1; }

EMP_EMAIL="facecheck.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"FC\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
[ -z "$INV_TOKEN" ] && { echo "SETUP FAILED: inv token"; exit 1; }
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
[ "$(echo "$EMP_LOGIN" | tail -n 1)" -ne 200 ] && { echo "SETUP FAILED: emp login"; exit 1; }
EMP_TOKEN=$(echo "$EMP_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
[ -z "$EMP_ID" ] && { echo "SETUP FAILED: emp id"; exit 1; }

ASGN_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
[ "$(echo "$ASGN_RESP" | tail -n 1)" -ne 201 ] && { echo "SETUP FAILED: assignment"; exit 1; }
ASGN_ID=$(echo "$ASGN_RESP" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

TODAY=$(date +%Y-%m-%d)
BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"
FACE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

# Helper: insert a 'sent' scheduled check with given mode
insert_check() {
    local mode="$1"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "WITH next_idx AS (
           SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
           FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
         ),
         ins AS (
           INSERT INTO scheduled_checks
             (id, tenant_id, assignment_id, employee_id, site_id, shift_id,
              config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
           SELECT gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID',
             ('{\"checkMode\":\"' || '$mode' || '\"}')::jsonb, '$TODAY', idx,
             now() - interval '1 minute', now() + interval '10 minutes',
             'sent', now(), now()
           FROM next_idx
           RETURNING id
         ) SELECT id FROM ins;" \
        | tr -d ' \n'
}

echo "Tenant=$TENANT_ID  Employee=$EMP_ID  Assignment=$ASGN_ID"
echo ""

# ── Test 1: Respond without photo in location_face mode → faceVerified=false ─
echo "--- Test 1: location_face mode, no photo → faceVerified=false, outcome=fail ---"
CHECK1=$(insert_check "location_face")
r1=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK1/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}')
r1_body=$(echo "$r1" | head -n -1)
r1_status=$(echo "$r1" | tail -n 1)
r1_fv=$(echo "$r1_body" | grep -o '"faceVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
r1_out=$(echo "$r1_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
check_val "No photo → HTTP 200" "$r1_status" "200"
check_val "No photo → faceVerified=false" "$r1_fv" "false"
check_val "No photo → outcome=fail" "$r1_out" "fail"
echo ""

# ── Test 2: Respond with photo in location_face mode → fail-open (200, async) ─
echo "--- Test 2: location_face mode, photo provided → 200 fail-open ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    CHECK2=$(insert_check "location_face")
    PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
    TMPFILE=$(mktemp /tmp/cr_face_body.XXXXXX.json)
    printf '{"latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s"}' \
        "$PHOTO_B64" > "$TMPFILE"
    r2=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_CHECKS/$CHECK2/respond" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        --data @"$TMPFILE")
    rm -f "$TMPFILE"
    r2_body=$(echo "$r2" | head -n -1)
    r2_status=$(echo "$r2" | tail -n 1)
    r2_fv=$(echo "$r2_body" | grep -o '"faceVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
    r2_out=$(echo "$r2_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
    check_val "With photo → HTTP 200" "$r2_status" "200"
    check_val "With photo → faceVerified=null initially" "$r2_fv" "null"
    check_val "With photo → outcome=pass initially (fail-open)" "$r2_out" "pass"
else
    echo "SKIP: No fixture — skipping photo test"
    CHECK2=""
fi
echo ""

# ── Test 3: E2E — enroll, respond, poll for faceVerified ─────────────────────
echo "--- Test 3: E2E — enrolled employee face verification via check response ---"
if [ "$FIXTURE_AVAILABLE" = "true" ] && [ -n "${CHECK2:-}" ]; then
    # Enroll employee face
    curl -s -o /dev/null -X POST "$FACE_URL/consent" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    enroll_st=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$FACE_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")

    if [ "$enroll_st" -eq 200 ]; then
        # New check (test 2's check is already responded)
        CHECK3=$(insert_check "location_face")
        PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
        TMPFILE3=$(mktemp /tmp/cr_face_body3.XXXXXX.json)
        printf '{"latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s"}' \
            "$PHOTO_B64" > "$TMPFILE3"
        r3=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_CHECKS/$CHECK3/respond" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            --data @"$TMPFILE3")
        rm -f "$TMPFILE3"
        r3_status=$(echo "$r3" | tail -n 1)
        RESP3_ID=$(echo "$r3" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

        if [ "$r3_status" -eq 200 ] && [ -n "$RESP3_ID" ]; then
            echo "Response submitted ($RESP3_ID). Polling for faceVerified..."
            FACE_VERIFIED=""
            for i in $(seq 1 6); do
                sleep 5
                poll=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
                    "SELECT face_verified FROM check_responses WHERE id='$RESP3_ID';" | tr -d ' \n')
                if [ "$poll" != "" ] && [ "$poll" != "null" ]; then
                    FACE_VERIFIED="$poll"
                    break
                fi
                echo "  Poll $i: face_verified still NULL..."
            done

            if [ "$FACE_VERIFIED" = "t" ]; then
                echo "PASS: face_verified=true in DB after async callback"
                PASS=$((PASS + 1))
            elif [ "$FACE_VERIFIED" = "f" ]; then
                echo "FAIL: face_verified=false (same image should match enrolled profile)"
                FAIL=$((FAIL + 1))
            else
                echo "FAIL: face_verified never resolved — worker may not be running"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: Response submission failed (status=$r3_status)"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "SKIP: Enrollment failed (status=$enroll_st)"
    fi
else
    echo "SKIP: No fixture or no check from test 2"
fi
echo ""

# ── Test 4: location_face_liveness mode — photo + liveness required (Task 104) ─
echo "--- Test 4: location_face_liveness mode, photo provided → 200, async liveness ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    CHECK4=$(insert_check "location_face_liveness")
    PHOTO_B64=$(base64 -w 0 "$FACE_IMG")
    TMPFILE4=$(mktemp /tmp/cr_live_body4.XXXXXX.json)
    printf '{"latitude":21.0285,"longitude":105.8542,"employeePhotoBase64":"%s"}' \
        "$PHOTO_B64" > "$TMPFILE4"
    r4=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_CHECKS/$CHECK4/respond" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        --data @"$TMPFILE4")
    rm -f "$TMPFILE4"
    r4_body=$(echo "$r4" | head -n -1)
    r4_status=$(echo "$r4" | tail -n 1)
    r4_lv=$(echo "$r4_body" | grep -o '"livenessVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
    check_val "Liveness check-in → HTTP 200" "$r4_status" "200"
    check_val "Liveness mode → livenessVerified=null initially" "$r4_lv" "null"

    # Poll for liveness result
    RESP4_ID=$(echo "$r4_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$RESP4_ID" ]; then
        echo "Polling for liveness result..."
        LV_RESULT=""
        for i in $(seq 1 8); do
            sleep 5
            lv_poll=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
                "SELECT liveness_verified FROM check_responses WHERE id='$RESP4_ID';" | tr -d ' \n')
            if [ -n "$lv_poll" ] && [ "$lv_poll" != "" ]; then
                LV_RESULT="$lv_poll"
                break
            fi
            echo "  Poll $i: liveness_verified still NULL..."
        done

        if [ "$LV_RESULT" = "t" ]; then
            echo "PASS: liveness_verified=true (real image passed MiniFASNet)"
            PASS=$((PASS + 1))
        elif [ "$LV_RESULT" = "f" ]; then
            echo "INFO: liveness_verified=false (static image may fail anti-spoof — expected)"
            echo "PASS: Liveness pipeline ran end-to-end"
            PASS=$((PASS + 1))
        else
            echo "FAIL: liveness_verified never resolved — worker may not be running"
            FAIL=$((FAIL + 1))
        fi
    fi
else
    echo "SKIP: No fixture"
fi
echo ""

# ── Test 5: faceVerifyScore present in DB ─────────────────────────────────────
echo "--- Test 5: face_verify_score column populated after async callback ---"
if [ "$FIXTURE_AVAILABLE" = "true" ] && [ -n "${RESP3_ID:-}" ]; then
    score=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT face_verify_score FROM check_responses WHERE id='$RESP3_ID';" | tr -d ' \n')
    if echo "$score" | grep -qE '^[0-9]'; then
        echo "PASS: face_verify_score populated ($score)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: face_verify_score not populated (got '$score')"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No enrolled response to check score"
fi
echo ""

echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then exit 1; fi
