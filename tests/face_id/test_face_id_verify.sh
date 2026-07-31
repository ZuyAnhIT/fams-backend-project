#!/usr/bin/env bash
# Tests for standalone Face-ID verify API (Issue 5).
# POST /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/verify
# GET  /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/verify/{verifyRequestId}
# Usage: BASE_URL=http://localhost:8080 bash test_face_id_verify.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; echo "  Detail: $2"; FAIL=$((FAIL + 1)); }

http_status() {
    curl -s -o /dev/null -w "%{http_code}" "$@"
}

echo "=== Face-ID Standalone Verify API Test ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ──────────────────────────────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
[ "$login_status" -eq 200 ] || { echo "SETUP FAILED: admin login HTTP $login_status"; exit 1; }
TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

TS=$(date +%s)

echo "--- Setup: Create tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"FaceVerify Corp $TS\",\"slug\":\"fv-corp-$TS\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1); t_status=$(echo "$t_resp" | tail -n 1)
[ "$t_status" -eq 201 ] || { echo "SETUP FAILED: create tenant HTTP $t_status — $t_body"; exit 1; }
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

echo "--- Setup: Create employee ---"
emp_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"firstName\":\"Jane\",\"lastName\":\"Verify\",\"email\":\"jverify.$TS@example.com\"}")
emp_body=$(echo "$emp_resp" | head -n -1); emp_status=$(echo "$emp_resp" | tail -n 1)
[ "$emp_status" -eq 201 ] || { echo "SETUP FAILED: create employee HTTP $emp_status — $emp_body"; exit 1; }
EMP_ID=$(echo "$emp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee: $EMP_ID"

FACE_BASE="$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$EMP_ID/face-id"

echo ""
# ── Test 1: POST /verify without enrollment — should 404 ──────────────────────
echo "--- Test 1: Verify without enrollment returns 404 ---"
st=$(http_status -X POST "$FACE_BASE/verify" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"photoBase64":"dGVzdA==","requiresLiveness":false}')
if [ "$st" -eq 404 ]; then
    pass "POST /verify without enrollment → 404"
else
    fail "POST /verify without enrollment" "expected 404, got $st"
fi

echo ""
# ── Test 2: POST /verify unauthenticated — should 401 ─────────────────────────
echo "--- Test 2: Verify unauthenticated returns 401 ---"
st=$(http_status -X POST "$FACE_BASE/verify" \
    -H "Content-Type: application/json" \
    -d '{"photoBase64":"dGVzdA=="}')
if [ "$st" -eq 401 ]; then
    pass "POST /verify unauthenticated → 401"
else
    fail "POST /verify unauthenticated" "expected 401, got $st"
fi

echo ""
# ── Test 3: POST /verify missing photoBase64 — should 400 ─────────────────────
echo "--- Test 3: Verify with missing photoBase64 returns 400 ---"
st=$(http_status -X POST "$FACE_BASE/verify" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"requiresLiveness":false}')
if [ "$st" -eq 400 ]; then
    pass "POST /verify missing photoBase64 → 400"
else
    fail "POST /verify missing photoBase64" "expected 400, got $st"
fi

echo ""
# ── Enroll the employee so verify can proceed ──────────────────────────────────
echo "--- Setup: Give consent then enroll face via ai-service ---"
c_status=$(http_status -X POST "$FACE_BASE/consent" \
    -H "Authorization: Bearer $TOKEN")
[ "$c_status" -eq 200 ] || { echo "SETUP FAILED: consent HTTP $c_status"; exit 1; }
echo "Consent given."

# Enroll using the ai-service enroll endpoint directly (ai-service must be running)
# We hit the api-server enroll endpoint with 3 minimal 1x1 JPEG images in base64
TINY_JPEG="data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AJQAB/9k="

# Build 3 tiny JPEG files for multipart upload
TMPDIR_T=$(mktemp -d)
for i in 1 2 3; do
    echo -n "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AJQAB/9k=" | base64 -d > "$TMPDIR_T/photo$i.jpg"
done

enroll_status=$(http_status -X POST "$FACE_BASE/enroll" \
    -H "Authorization: Bearer $TOKEN" \
    -F "photos=@$TMPDIR_T/photo1.jpg;type=image/jpeg" \
    -F "photos=@$TMPDIR_T/photo2.jpg;type=image/jpeg" \
    -F "photos=@$TMPDIR_T/photo3.jpg;type=image/jpeg")
rm -rf "$TMPDIR_T"

# If enroll fails (likely because fams-ai is not running in test environment),
# we can still test the API contract for verify (it checks face profile status).
if [ "$enroll_status" -eq 200 ]; then
    echo "Face enrolled (fams-ai running)."
    ENROLLED=1
else
    echo "Enroll returned HTTP $enroll_status (fams-ai may be unavailable). Skipping enrolled-path tests."
    ENROLLED=0
fi

echo ""
# ── Test 4: POST /verify returns 202 (only if enrolled) ───────────────────────
echo "--- Test 4: POST /verify returns 202 and verifyRequestId ---"
if [ "$ENROLLED" -eq 1 ]; then
    verify_resp=$(curl -s -w "\n%{http_code}" -X POST "$FACE_BASE/verify" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d '{"photoBase64":"dGVzdA==","requiresLiveness":false}')
    verify_body=$(echo "$verify_resp" | head -n -1)
    verify_st=$(echo "$verify_resp" | tail -n 1)
    VERIFY_ID=$(echo "$verify_body" | grep -o '"verifyRequestId":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    status_field=$(echo "$verify_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$verify_st" -eq 202 ] && [ -n "$VERIFY_ID" ] && [ "$status_field" = "pending" ]; then
        pass "POST /verify → 202 with verifyRequestId and status=pending"
    else
        fail "POST /verify" "expected 202+verifyRequestId+pending, got HTTP $verify_st body=$verify_body"
        VERIFY_ID=""
    fi
else
    echo "SKIP: Test 4 — employee not enrolled"
    VERIFY_ID=""
fi

echo ""
# ── Test 5: GET /verify/{id} returns 200 with pending status ──────────────────
echo "--- Test 5: GET /verify/{verifyRequestId} returns pending result ---"
if [ -n "$VERIFY_ID" ]; then
    poll_resp=$(curl -s -w "\n%{http_code}" "$FACE_BASE/verify/$VERIFY_ID" \
        -H "Authorization: Bearer $TOKEN")
    poll_body=$(echo "$poll_resp" | head -n -1)
    poll_st=$(echo "$poll_resp" | tail -n 1)
    poll_status=$(echo "$poll_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$poll_st" -eq 200 ] && { [ "$poll_status" = "pending" ] || [ "$poll_status" = "pass" ] || [ "$poll_status" = "fail" ]; }; then
        pass "GET /verify/$VERIFY_ID → 200 status=$poll_status"
    else
        fail "GET /verify/$VERIFY_ID" "expected 200 with valid status, got HTTP $poll_st body=$poll_body"
    fi
else
    echo "SKIP: Test 5 — no verifyRequestId from test 4"
fi

echo ""
# ── Test 6: GET /verify/{random-id} returns 404 ───────────────────────────────
echo "--- Test 6: GET /verify with unknown ID returns 404 ---"
RANDOM_ID="00000000-0000-0000-0000-000000000001"
st=$(http_status "$FACE_BASE/verify/$RANDOM_ID" \
    -H "Authorization: Bearer $TOKEN")
if [ "$st" -eq 404 ]; then
    pass "GET /verify/unknown → 404"
else
    fail "GET /verify/unknown" "expected 404, got $st"
fi

echo ""
# ── Test 7: GET /verify/{id} unauthenticated returns 401 ──────────────────────
echo "--- Test 7: GET /verify unauthenticated returns 401 ---"
st=$(http_status "$FACE_BASE/verify/$RANDOM_ID")
if [ "$st" -eq 401 ]; then
    pass "GET /verify unauthenticated → 401"
else
    fail "GET /verify unauthenticated" "expected 401, got $st"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ] || exit 1
