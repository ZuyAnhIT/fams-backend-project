#!/usr/bin/env bash
# Tests for violation creation on failed random check responses (task 107)
# location_fail / face_fail / liveness_fail violations
# Usage: BASE_URL=http://localhost:8080 bash test_fail_violations.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
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

echo "=== Fail Violation Tests (task 107) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Viol Corp ${TS}\",\"slug\":\"viol-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Trial plan (auto-assigned on tenant creation) caps sites at 1 — Test 12 below creates a second
# site in this same tenant, so upgrade to "pro" right away.
PRO_PLAN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM plans WHERE name='pro' AND deleted_at IS NULL;" | tr -d ' \n')
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"planId\":\"$PRO_PLAN_ID\"}"

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Viol Site\",\"code\":\"VS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Geofence: small polygon around (10.0, 106.0) — far from where we'll send bad coords
geo_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "coordinates": [
        [106.0001, 10.0001],
        [106.0002, 10.0001],
        [106.0002, 10.0002],
        [106.0001, 10.0002],
        [106.0001, 10.0001]
      ],
      "bufferMeters": 10
    }')
geo_status=$(echo "$geo_resp" | tail -n 1)
if [ "$geo_status" -ne 201 ] && [ "$geo_status" -ne 200 ]; then
    echo "SETUP FAILED: geofence (HTTP $geo_status)"
    echo "$(echo "$geo_resp" | head -n -1)"
    exit 1
fi
echo "Geofence created around (10.0001-10.0002, 106.0001-106.0002)"

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="viol.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Viol\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$EMP_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Face-mode tests (7-9) need an enrolled profile — since 2026-07-31, submit() immediately fails
# (no async AI call at all) for a check requiring face verification when the employee has no
# status='enrolled' FaceProfile, matching the documented behavior on
# CreateRandomCheckConfigRequest.checkMode. See docs/api/random-check-config-review.md §3.
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "INSERT INTO face_profiles (tenant_id, employee_id, consent_given, status, enrolled_at)
     VALUES ('$TENANT_ID', '$EMP_ID', TRUE, 'enrolled', now());" > /dev/null

AI_INTERNAL_SECRET="${AI_INTERNAL_SECRET:-fams_ai_secret_local_dev}"

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

TODAY=$(date +%Y-%m-%d)
BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

# Helper: insert a sent check with given mode directly in DB
insert_sent_check() {
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
             '{\"checkMode\":\"$mode\"}'::jsonb, '$TODAY', idx,
             now() - interval '2 minutes', now() + interval '5 minutes',
             'sent', now(), now()
           FROM next_idx
           RETURNING id
         ) SELECT id FROM ins;" \
        | tr -d ' \n'
}

# Helper: insert a check_response directly in DB in the "pending async face verification" state
# (status=responded on the parent check, outcome tentatively 'pass', face_verified=NULL) — the
# exact state submit() leaves a location_face(_liveness) response in after a photo is accepted
# but before the AI callback arrives. Bypasses ever calling POST .../respond so the REAL fams-ai
# worker (running in this dev environment) never picks up a Redis job for a fake photo and races
# with the callback this test simulates manually.
insert_pending_response() {
    local check_id="$1"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "UPDATE scheduled_checks SET status='responded' WHERE id='$check_id';
         INSERT INTO check_responses
           (id, tenant_id, scheduled_check_id, employee_id, responded_at,
            latitude, longitude, location_verified, face_verified, liveness_verified, outcome)
         VALUES (gen_random_uuid(), '$TENANT_ID', '$check_id', '$EMP_ID', now(),
                 10.00015, 106.00015, TRUE, NULL, NULL, 'pass')
         RETURNING id;" \
        | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1
}

echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID SITE=$SITE_ID"
echo ""

# ── Test 1: Passing location (inside geofence) → outcome=pass, no violation ───
echo "--- Test 1: Coords inside geofence → outcome=pass, no location_fail violation ---"
CHECK_PASS=$(insert_sent_check "location_only")
pass_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_PASS/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.00015,"longitude":106.00015}')
pass_status=$(echo "$pass_resp" | tail -n 1)
pass_body=$(echo "$pass_resp" | head -n -1)
pass_outcome=$(echo "$pass_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
pass_viols=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_PASS';" | tr -d ' \n')
if [ "$pass_status" -eq 200 ] && [ "$pass_outcome" = "pass" ] && [ "$pass_viols" -eq 0 ]; then
    echo "PASS: Inside geofence → outcome=pass, 0 violations"
    PASS=$((PASS + 1))
else
    echo "FAIL: status=$pass_status outcome=$pass_outcome violations=$pass_viols"
    echo "Body: $pass_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 2: Failing location (outside geofence) → outcome=fail ────────────────
echo ""
echo "--- Test 2: Coords outside geofence → outcome=fail ---"
CHECK_LOC=$(insert_sent_check "location_only")
# Use Ho Chi Minh City coords — far from the geofence polygon above
loc_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_LOC/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}')
loc_body=$(echo "$loc_resp" | head -n -1)
loc_outcome=$(echo "$loc_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
loc_loc_verified=$(echo "$loc_body" | grep -o '"locationVerified":[a-z]*' | cut -d: -f2)
check_val "Outside geofence outcome" "$loc_outcome" "fail"
check_val "locationVerified=false" "$loc_loc_verified" "false"

# ── Test 3: location_fail violation created ───────────────────────────────────
echo ""
echo "--- Test 3: location_fail violation created in DB ---"
loc_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_LOC' AND violation_type='location_fail';" \
    | tr -d ' \n')
check_val "location_fail violation count" "$loc_viol" "1"

# ── Test 4: Violation has correct fields ─────────────────────────────────────
echo ""
echo "--- Test 4: location_fail violation has correct employee and site ---"
viol_emp=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT employee_id FROM violations WHERE scheduled_check_id='$CHECK_LOC' AND violation_type='location_fail';" \
    | tr -d ' \n')
check_val "Violation employee_id" "$viol_emp" "$EMP_ID"

# ── Test 5: check_response_id populated on the violation ─────────────────────
echo ""
echo "--- Test 5: violation.check_response_id is set ---"
has_resp_id=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT CASE WHEN check_response_id IS NOT NULL THEN '1' ELSE '0' END
     FROM violations WHERE scheduled_check_id='$CHECK_LOC' AND violation_type='location_fail';" \
    | tr -d ' \n')
check_val "violation.check_response_id not null" "$has_resp_id" "1"

# ── Test 6: face_fail — missing face image in location_face mode ───────────────
echo ""
echo "--- Test 6: Missing face image in location_face mode → face_fail violation ---"
CHECK_FACE=$(insert_sent_check "location_face")
face_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_FACE/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.00015,"longitude":106.00015}')
face_body=$(echo "$face_resp" | head -n -1)
face_outcome=$(echo "$face_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
face_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_FACE' AND violation_type='face_fail';" \
    | tr -d ' \n')
if [ "$face_outcome" = "fail" ] && [ "$face_viol" -eq 1 ]; then
    echo "PASS: Missing face image → outcome=fail, face_fail violation created"
    PASS=$((PASS + 1))
else
    echo "FAIL: face_outcome=$face_outcome face_viol=$face_viol"
    FAIL=$((FAIL + 1))
fi

# ── Test 7: face provided in location_face mode → no face_fail ───────────────
echo ""
echo "--- Test 7: Face image provided (enrolled employee) in location_face mode → no face_fail ---"
# submit() only accepts employeePhotoBase64 for the "photo submitted" branch — faceImageUrl is
# just stored metadata (a previously-uploaded URL), never read for verification. With a photo
# submitted and an enrolled profile, faceVerified stays null (pending async AI) until the
# fams-ai callback arrives — simulated here via the internal callback endpoint directly.
CHECK_FACE_OK=$(insert_sent_check "location_face")
RESP_FACE_OK=$(insert_pending_response "$CHECK_FACE_OK")
curl -s -o /dev/null -X POST "$BASE_URL/internal/ai-callback/face-result" \
    -H "X-Internal-Secret: $AI_INTERNAL_SECRET" -H "Content-Type: application/json" \
    -d "{\"sourceType\":\"check_response\",\"sourceId\":\"$RESP_FACE_OK\",\"faceVerified\":true,\"faceVerifyScore\":0.95}"
faceok_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_CHECKS/$CHECK_FACE_OK")
faceok_outcome=$(echo "$faceok_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
faceok_face_verified=$(echo "$faceok_body" | grep -o '"faceVerified":[a-z]*' | cut -d: -f2)
face_no_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_FACE_OK' AND violation_type='face_fail';" \
    | tr -d ' \n')
if [ "$faceok_outcome" = "pass" ] && [ "$faceok_face_verified" = "true" ] && [ "$face_no_viol" -eq 0 ]; then
    echo "PASS: Face provided → outcome=pass, faceVerified=true, 0 face_fail violations"
    PASS=$((PASS + 1))
else
    echo "FAIL: outcome=$faceok_outcome faceVerified=$faceok_face_verified violations=$face_no_viol"
    echo "Body: $faceok_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: liveness_fail — score below threshold in location_face_liveness ───
echo ""
echo "--- Test 8: Liveness fails (face matched, liveness didn't) in location_face_liveness → liveness_fail ---"
# Liveness is decided by the async AI callback's livenessVerified field, never a client-submitted
# score — location_face_liveness mode independently fails on faceVerified=true+livenessVerified=false
# (found via audit 2026-07-31: previously not enforced at all — see
# docs/api/attendance-management-api.md §1... actually docs/api/random-check-config-review.md §1).
CHECK_LIVE=$(insert_sent_check "location_face_liveness")
RESP_LIVE=$(insert_pending_response "$CHECK_LIVE")
curl -s -o /dev/null -X POST "$BASE_URL/internal/ai-callback/face-result" \
    -H "X-Internal-Secret: $AI_INTERNAL_SECRET" -H "Content-Type: application/json" \
    -d "{\"sourceType\":\"check_response\",\"sourceId\":\"$RESP_LIVE\",\"faceVerified\":true,\"livenessVerified\":false,\"faceVerifyScore\":0.9}"
live_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_CHECKS/$CHECK_LIVE")
live_outcome=$(echo "$live_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
live_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_LIVE' AND violation_type='liveness_fail';" \
    | tr -d ' \n')
if [ "$live_outcome" = "fail" ] && [ "$live_viol" -eq 1 ]; then
    echo "PASS: Liveness failed (face matched) → outcome=fail, liveness_fail violation created"
    PASS=$((PASS + 1))
else
    echo "FAIL: live_outcome=$live_outcome live_viol=$live_viol"
    echo "Body: $live_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 9: liveness above threshold → pass ───────────────────────────────────
echo ""
echo "--- Test 9: Face AND liveness both verified in location_face_liveness → pass ---"
CHECK_LIVE_OK=$(insert_sent_check "location_face_liveness")
RESP_LIVE_OK=$(insert_pending_response "$CHECK_LIVE_OK")
curl -s -o /dev/null -X POST "$BASE_URL/internal/ai-callback/face-result" \
    -H "X-Internal-Secret: $AI_INTERNAL_SECRET" -H "Content-Type: application/json" \
    -d "{\"sourceType\":\"check_response\",\"sourceId\":\"$RESP_LIVE_OK\",\"faceVerified\":true,\"livenessVerified\":true,\"faceVerifyScore\":0.95}"
liveok_body=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_CHECKS/$CHECK_LIVE_OK")
liveok_outcome=$(echo "$liveok_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
liveok_viols=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_LIVE_OK';" | tr -d ' \n')
if [ "$liveok_outcome" = "pass" ] && [ "$liveok_viols" -eq 0 ]; then
    echo "PASS: Face + liveness both verified → outcome=pass, 0 violations"
    PASS=$((PASS + 1))
else
    echo "FAIL: outcome=$liveok_outcome violations=$liveok_viols"
    echo "Body: $liveok_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 10: Multiple failures → multiple violations ──────────────────────────
echo ""
echo "--- Test 10: Location fail + face fail (location_face, outside geofence) → 2 violations ---"
CHECK_MULTI=$(insert_sent_check "location_face")
multi_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_MULTI/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}')
multi_body=$(echo "$multi_resp" | head -n -1)
multi_outcome=$(echo "$multi_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
multi_viols=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_MULTI';" | tr -d ' \n')
if [ "$multi_outcome" = "fail" ] && [ "$multi_viols" -eq 2 ]; then
    echo "PASS: Both location and face fail → outcome=fail, 2 violations"
    PASS=$((PASS + 1))
else
    echo "FAIL: outcome=$multi_outcome violations=$multi_viols (expected fail and 2)"
    echo "Body: $multi_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: location_only mode — no face check even if no image ──────────────
echo ""
echo "--- Test 11: location_only mode ignores face/liveness fields ---"
CHECK_LOC_ONLY=$(insert_sent_check "location_only")
loconly_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_LOC_ONLY/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.00015,"longitude":106.00015}')
loconly_body=$(echo "$loconly_resp" | head -n -1)
loconly_outcome=$(echo "$loconly_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
loconly_face_verified=$(echo "$loconly_body" | grep -o '"faceVerified":[^,}]*' | cut -d: -f2 | tr -d '"')
loconly_viols=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$CHECK_LOC_ONLY';" | tr -d ' \n')
if [ "$loconly_outcome" = "pass" ] && [ "$loconly_viols" -eq 0 ]; then
    echo "PASS: location_only → pass, no violations, faceVerified=$loconly_face_verified (null expected)"
    PASS=$((PASS + 1))
else
    echo "FAIL: outcome=$loconly_outcome violations=$loconly_viols"
    FAIL=$((FAIL + 1))
fi

# ── Test 12: No geofence configured → location passes by default ──────────────
echo ""
echo "--- Test 12: No geofence on site → location check skipped (pass) ---"
# Create a second site with no geofence
s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoFence Site\",\"code\":\"NF-${TS}\",\"address\":\"2 St\",\"timezone\":\"UTC\"}")
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Evening","startTime":"18:00","endTime":"23:00"}')
SHIFT2_ID=$(echo "$sh2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
asgn2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
ASGN2_ID=$(echo "$asgn2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Insert check for site2 directly
NF_CHECK=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "WITH ins AS (
       INSERT INTO scheduled_checks
         (id, tenant_id, assignment_id, employee_id, site_id, shift_id,
          config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
       VALUES (gen_random_uuid(), '$TENANT_ID', '$ASGN2_ID', '$EMP_ID', '$SITE2_ID', '$SHIFT2_ID',
         '{\"checkMode\":\"location_only\"}'::jsonb, '$TODAY', 1,
         now() - interval '2 minutes', now() + interval '5 minutes',
         'sent', now(), now())
       RETURNING id
     ) SELECT id FROM ins;" | tr -d ' \n')

nf_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$NF_CHECK/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":-33.8688,"longitude":151.2093}')
nf_body=$(echo "$nf_resp" | head -n -1)
nf_outcome=$(echo "$nf_body" | grep -o '"outcome":"[^"]*"' | cut -d'"' -f4)
nf_viols=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$NF_CHECK';" | tr -d ' \n')
if [ "$nf_outcome" = "pass" ] && [ "$nf_viols" -eq 0 ]; then
    echo "PASS: No geofence → location passes, 0 violations"
    PASS=$((PASS + 1))
else
    echo "FAIL: outcome=$nf_outcome violations=$nf_viols"
    echo "Body: $nf_body"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
