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
    -d "{\"name\":\"FaceCheck Corp\",\"slug\":\"facecheck-${TS}\",\"ownerEmail\":\"admin@fams.com\"}" \
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
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
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

# ── Test 2: Respond with photo, but employee NOT enrolled yet → fail synchronously ─
# Per FaceIdController/CheckResponseService (confirmed via code, #103 audit 2026-08-18): a photo
# alone does not trigger the async match — the employee must already be enrolled
# (FaceProfile.status="enrolled"). Not-enrolled fails immediately regardless of whether a photo
# was sent, same as Test 1. The async path is only reachable once actually enrolled (see Test 3).
echo "--- Test 2: location_face mode, photo provided but NOT enrolled → fails sync (same as Test 1) ---"
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
    check_val "With photo, not enrolled → HTTP 200" "$r2_status" "200"
    check_val "With photo, not enrolled → faceVerified=false" "$r2_fv" "false"
    check_val "With photo, not enrolled → outcome=fail" "$r2_out" "fail"
else
    echo "SKIP: No fixture — skipping photo test"
    CHECK2=""
fi
echo ""

# ── Test 3: E2E — enroll, respond, poll for faceVerified ─────────────────────
echo "--- Test 3: E2E — enrolled employee face verification via check response ---"
if [ "$FIXTURE_AVAILABLE" = "true" ] && [ -n "${CHECK2:-}" ]; then
    # Consent must come from the data subject (employee), not HR/Admin on their behalf —
    # POST /consent deliberately rejects ADMIN_TOKEN with 403 (see FaceIdService.giveConsent).
    curl -s -o /dev/null -X POST "$FACE_URL/consent" \
        -H "Authorization: Bearer $EMP_TOKEN"
    # HR-assisted enrollment (raw photos, non-self-service) is fine via ADMIN_TOKEN.
    enroll_st=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$FACE_URL/enroll" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg" \
        -F "photos=@$FACE_IMG;type=image/jpeg")

    if [ "$enroll_st" -eq 200 ]; then
        # enroll() lands in reviewStatus=pending, not yet active for checkin — must be approved
        # by HR/Admin before the profile's status flips to "enrolled" (FaceIdService.approveEnrollment).
        approve_st=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$FACE_URL/approve" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        if [ "$approve_st" -ne 200 ]; then
            echo "FAIL: Approve enrollment failed (status=$approve_st)"
            FAIL=$((FAIL + 1))
        fi
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

# ── Test 4: location_face_liveness mode — requires an active-liveness challenge (Task 104,
# upgraded 2026-08-18 from passive single-photo liveness by explicit user decision — mirrors
# check-in's gps_face_liveness challenge flow, see CheckResponseService#consumeRandomCheckChallenge)
echo "--- Test 4a: location_face_liveness mode, NO challengeId → rejected (422 FACE_ID_REQUIRED) ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    CHECK4A=$(insert_check "location_face_liveness")
    r4a_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_CHECKS/$CHECK4A/respond" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        -d '{"latitude":21.0285,"longitude":105.8542}')
    check_val "No challengeId → HTTP 422" "$r4a_status" "422"
else
    echo "SKIP: No fixture"
fi
echo ""

echo "--- Test 4b: location_face_liveness mode, with a passed random_check challenge → 200, async liveness ---"
if [ "$FIXTURE_AVAILABLE" = "true" ]; then
    CHECK4=$(insert_check "location_face_liveness")
    # Synthesize an already-'passed' active-liveness challenge — driving the real capture flow
    # (center + 2 random pose actions) needs an actual moving face, which a single static fixture
    # image can't provide. Bypass the challenge/frames endpoints directly the same way other
    # tests in this suite insert scheduled_checks directly, and copy the fixture onto the
    # storage bind mount at the exact path fams-ai's worker reads (see storage_service.py).
    CHALLENGE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT gen_random_uuid();" | tr -d ' \n')
    # /app/storage is root-owned inside the fams-ai container (matches how the app itself writes
    # there) — write via docker exec instead of the host bind-mount path directly.
    docker exec fams-ai mkdir -p "/app/storage/liveness_challenges/$TENANT_ID"
    docker cp "$FACE_IMG" "fams-ai:/app/storage/liveness_challenges/$TENANT_ID/$CHALLENGE_ID.jpg"
    docker exec fams-postgres psql -U fams_user -d fams_db -c "
        INSERT INTO liveness_challenges
          (id, tenant_id, employee_id, purpose, actions, status, center_frame_path, site_id,
           created_at, expires_at, completed_at)
        VALUES
          ('$CHALLENGE_ID', '$TENANT_ID', '$EMP_ID', 'random_check', ARRAY['center','turn_left','blink'],
           'passed', '/app/storage/liveness_challenges/$TENANT_ID/$CHALLENGE_ID.jpg', '$SITE_ID',
           now(), now() + interval '90 seconds', now());" > /dev/null

    r4=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_CHECKS/$CHECK4/respond" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        -d "{\"latitude\":21.0285,\"longitude\":105.8542,\"livenessChallengeId\":\"$CHALLENGE_ID\"}")
    r4_body=$(echo "$r4" | head -n -1)
    r4_status=$(echo "$r4" | tail -n 1)
    r4_lv=$(echo "$r4_body" | grep -o '"livenessVerified":[^,}]*' | cut -d: -f2 | tr -d ' "')
    check_val "With passed challenge → HTTP 200" "$r4_status" "200"
    check_val "Liveness mode → livenessVerified=null initially" "$r4_lv" "null"

    # Challenge must now be 'consumed' — atomic single-use guard
    challenge_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT status FROM liveness_challenges WHERE id='$CHALLENGE_ID';" | tr -d ' \n')
    check_val "Challenge consumed after use" "$challenge_status" "consumed"

    # Poll for the async face-match result. The worker does not re-run passive single-frame
    # liveness for the challenge path: the active challenge already proved liveness. It must,
    # however, preserve that proof as liveness_verified=true in the callback instead of NULL,
    # because clients correctly interpret NULL as verification still pending. This mirrors
    # check-in's identical callback path after a gps_face_liveness challenge.
    RESP4_ID=$(echo "$r4_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$RESP4_ID" ]; then
        echo "Polling for face-match result (via challenge frame)..."
        FV_RESULT=""
        for i in $(seq 1 8); do
            sleep 5
            fv_poll=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
                "SELECT face_verified FROM check_responses WHERE id='$RESP4_ID';" | tr -d ' \n')
            if [ -n "$fv_poll" ] && [ "$fv_poll" != "" ]; then
                FV_RESULT="$fv_poll"
                break
            fi
            echo "  Poll $i: face_verified still NULL..."
        done

        if [ "$FV_RESULT" = "t" ]; then
            echo "PASS: face_verified=true via challenge frame (same enrolled employee)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: face_verified never resolved to true via challenge frame (got '$FV_RESULT') — worker may not be running"
            FAIL=$((FAIL + 1))
        fi

        lv_final=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
            "SELECT liveness_verified FROM check_responses WHERE id='$RESP4_ID';" | tr -d ' \n')
        check_val "liveness_verified=true (proven via active challenge)" "$lv_final" "t"

        outcome4=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
            "SELECT outcome FROM check_responses WHERE id='$RESP4_ID';" | tr -d ' \n')
        check_val "outcome=pass (location+face ok, liveness trusted via challenge)" "$outcome4" "pass"
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
