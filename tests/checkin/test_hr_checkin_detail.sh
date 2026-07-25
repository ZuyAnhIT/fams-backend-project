#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/checkin/{checkinId}/detail
# Covers task 79 (HR views full check-in evidence with embedded context)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_checkin_detail.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== HR Check-in Detail Tests (task 79) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Detail Corp ${TS}\",\"slug\":\"detail-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","code":"MAIN","address":"123 Main St","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning","startTime":"08:00","endTime":"17:00","earlyCheckinMinutes":15,"lateCheckoutMinutes":30}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="det.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Detail\",\"lastName\":\"Worker\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"test-device-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. CHECKIN_ID=$CHECKIN_ID"
echo ""

DETAIL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/detail"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$DETAIL_URL"
echo ""

# ── Test 2: Employee token (no checkins:read perm) → 403 ─────────────────────
echo "--- Test 2: Employee without checkins:read → 403 ---"
run_test "Employee forbidden" 403 -s \
    -H "Authorization: Bearer $EMP_TOKEN" "$DETAIL_URL"
echo ""

# ── Test 3: Non-existent ID → 404 ────────────────────────────────────────────
echo "--- Test 3: Non-existent checkin ID → 404 ---"
run_test "Non-existent ID" 404 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/00000000-0000-0000-0000-000000000000/detail"
echo ""

# ── Test 4: Platform admin gets full detail → 200 ────────────────────────────
echo "--- Test 4: Platform admin gets full detail → 200 ---"
det_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$DETAIL_URL")
det_body=$(echo "$det_resp" | head -n -1)
det_status=$(echo "$det_resp" | tail -n 1)
if [ "$det_status" -eq 200 ]; then
    echo "PASS: Detail returned (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $det_status — $det_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Employee context embedded ────────────────────────────────────────
echo "--- Test 5: employee object embedded with name fields ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    emp_first=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('employee',{}).get('firstName',''))" 2>/dev/null)
    emp_last=$(echo "$DATA"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('employee',{}).get('lastName',''))"  2>/dev/null)
    if [ -n "$emp_first" ] && [ -n "$emp_last" ]; then
        echo "PASS: employee embedded (firstName=$emp_first lastName=$emp_last)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: employee object missing or incomplete — $DATA"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Site context embedded ────────────────────────────────────────────
echo "--- Test 6: site object embedded with name and timezone ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    site_name=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('site',{}).get('name',''))" 2>/dev/null)
    site_tz=$(echo "$DATA"   | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('site',{}).get('timezone',''))" 2>/dev/null)
    if [ -n "$site_name" ] && [ -n "$site_tz" ]; then
        echo "PASS: site embedded (name=$site_name timezone=$site_tz)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: site object missing or incomplete — $DATA"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Shift context embedded ───────────────────────────────────────────
echo "--- Test 7: shift object embedded with startTime/endTime ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    shift_name=$(echo "$DATA"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('shift',{}).get('name',''))" 2>/dev/null)
    shift_start=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('shift',{}).get('startTime',''))" 2>/dev/null)
    if [ -n "$shift_name" ] && [ -n "$shift_start" ]; then
        echo "PASS: shift embedded (name=$shift_name startTime=$shift_start)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: shift object missing or incomplete — $DATA"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: GPS evidence fields present ──────────────────────────────────────
echo "--- Test 8: GPS evidence fields present ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    has_lat=$(echo "$DATA"   | python3 -c "import sys,json; d=json.load(sys.stdin); print('checkInLat' in d)" 2>/dev/null)
    has_lon=$(echo "$DATA"   | python3 -c "import sys,json; d=json.load(sys.stdin); print('checkInLon' in d)" 2>/dev/null)
    has_risk=$(echo "$DATA"  | python3 -c "import sys,json; d=json.load(sys.stdin); print('gpsRiskScore' in d)" 2>/dev/null)
    has_geofence=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print('checkInInsideGeofence' in d)" 2>/dev/null)
    if [ "$has_lat" = "True" ] && [ "$has_lon" = "True" ] && \
       [ "$has_risk" = "True" ] && [ "$has_geofence" = "True" ]; then
        echo "PASS: All GPS evidence fields present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing GPS fields — lat=$has_lat lon=$has_lon risk=$has_risk geofence=$has_geofence"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: message field present ────────────────────────────────────────────
echo "--- Test 9: message field present ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    msg=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message',''))" 2>/dev/null)
    if [ -n "$msg" ]; then
        echo "PASS: message present (\"$msg\")"
        PASS=$((PASS + 1))
    else
        echo "FAIL: message field missing"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: checkOutAt null before checkout, populated after ─────────────────
echo "--- Test 10: checkOutAt null before checkout, populated after ---"
if [ "$det_status" -eq 200 ]; then
    DATA=$(echo "$det_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
    checkout_before=$(echo "$DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('checkOutAt'))" 2>/dev/null)
    if [ "$checkout_before" = "None" ]; then
        # Now checkout and re-fetch
        curl -s -o /dev/null -X POST \
            "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
            -d '{"latitude":21.0285,"longitude":105.8542}'
        det2_resp=$(curl -s -w "\n%{http_code}" \
            -H "Authorization: Bearer $ADMIN_TOKEN" "$DETAIL_URL")
        det2_body=$(echo "$det2_resp" | head -n -1)
        DATA2=$(echo "$det2_body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d.get('data',{})))" 2>/dev/null)
        checkout_after=$(echo "$DATA2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('checkOutAt'))" 2>/dev/null)
        work_mins=$(echo "$DATA2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('workMinutes'))" 2>/dev/null)
        if [ "$checkout_after" != "None" ] && [ "$work_mins" != "None" ]; then
            echo "PASS: checkOutAt populated after checkout (workMinutes=$work_mins)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: checkOutAt still null or workMinutes missing after checkout"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: checkOutAt should be null before checkout, got $checkout_before"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
