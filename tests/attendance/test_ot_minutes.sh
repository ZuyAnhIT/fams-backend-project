#!/usr/bin/env bash
# Tests for ot_minutes calculation (Task 83)
# Verifies OT is computed when checkout is after shift end and allowOvertime=true.
# Usage: BASE_URL=http://localhost:8080 bash test_ot_minutes.sh

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

echo "=== OT Minutes Tests (task 83) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"OT Corp ${TS}\",\"slug\":\"ot-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"UTC","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# OT shift: starts 00:00, ends 00:01. allowOvertime and lateCheckoutMinutes set via ot-config endpoint.
# Any checkout after 00:01 UTC generates OT. Tests run well after midnight UTC so this is reliable.
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"OT Shift","startTime":"00:00","endTime":"00:01"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: OT shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Enable OT on shift: lateCheckoutMinutes=1440 (24h cap) so any checkout after 00:01 counts as OT
ot_cfg=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts/$SHIFT_ID/ot-config" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvertime":true,"lateCheckoutMinutes":1440,"earlyCheckinMinutes":0}')
if [ "$ot_cfg" -ne 200 ]; then echo "SETUP FAILED: OT config (HTTP $ot_cfg)"; exit 1; fi

# No-OT shift: allowOvertime stays false (default) — no OT regardless of checkout time
sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"No-OT Shift","startTime":"00:00","endTime":"23:59"}')
if [ "$(echo "$sh2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: no-OT shift"; exit 1; fi
SHIFT2_ID=$(echo "$sh2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# ── Employee 1: OT-eligible shift ─────────────────────────────────────────────
setup_employee() {
    local email="$1"
    local shift_id="$2"
    local inv_resp
    inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"$email\",\"firstName\":\"OT\",\"lastName\":\"Emp\"}")
    if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation $email"; exit 1; fi

    local inv_token
    inv_token=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$email' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$inv_token\",\"password\":\"Employee@1234\"}"

    local emp_login
    emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"Employee@1234\"}")
    echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4
}

EMP1_EMAIL="ot.emp1.${TS}@example.com"
EMP1_TOKEN=$(setup_employee "$EMP1_EMAIL" "$SHIFT_ID")

EMP1_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$EMP1_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment 1"; exit 1; fi

ci1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP1_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-ot-1\"}")
if [ "$(echo "$ci1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in 1"; exit 1; fi
CI1_ID=$(echo "$ci1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

co1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI1_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP1_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":10.0}')
if [ "$(echo "$co1_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: check-out 1"; exit 1; fi

# ── Employee 2: no-OT shift ────────────────────────────────────────────────────
EMP2_EMAIL="ot.emp2.${TS}@example.com"
EMP2_TOKEN=$(setup_employee "$EMP2_EMAIL" "$SHIFT2_ID")

EMP2_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$EMP2_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"shiftId\":\"$SHIFT2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment 2"; exit 1; fi

ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-ot-2\"}")
if [ "$(echo "$ci2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in 2"; exit 1; fi
CI2_ID=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

co2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI2_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":10.0}')
if [ "$(echo "$co2_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: check-out 2"; exit 1; fi

echo "Setup complete. TENANT_ID=$TENANT_ID EMP1=$EMP1_ID EMP2=$EMP2_ID"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

# ── Test 1: Response contains otMinutes field ─────────────────────────────────
echo "--- Test 1: Summary has otMinutes field ---"
list_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP1_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    has_ot=$(echo "$list_body" | grep -c '"otMinutes"' || true)
    if [ "${has_ot:-0}" -ge 1 ]; then
        echo "PASS: Response contains 'otMinutes' field"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing otMinutes field — $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: otMinutes > 0 for OT-eligible shift (shift ends at 00:01 UTC) ────
echo "--- Test 2: otMinutes > 0 when checkout is after shift end (allowOvertime=true) ---"
if [ "$list_status" -eq 200 ]; then
    ot_val=$(echo "$list_body" | grep -o '"otMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${ot_val:-0}" -gt 0 ]; then
        echo "PASS: otMinutes=$ot_val > 0 for OT-eligible shift"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected otMinutes>0, got $ot_val (shift ends 00:01 UTC, checkout is now)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: otMinutes capped at lateCheckoutMinutes (1440) ────────────────────
echo "--- Test 3: otMinutes <= lateCheckoutMinutes (1440) ---"
if [ "$list_status" -eq 200 ]; then
    ot_val=$(echo "$list_body" | grep -o '"otMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${ot_val:-1441}" -le 1440 ]; then
        echo "PASS: otMinutes=$ot_val <= 1440 (within cap)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: otMinutes=$ot_val exceeds lateCheckoutMinutes=1440"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: otMinutes = 0 when allowOvertime=false ────────────────────────────
echo "--- Test 4: otMinutes=0 when allowOvertime=false (no-OT shift) ---"
no_ot_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP2_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
no_ot_body=$(echo "$no_ot_resp" | head -n -1)
no_ot_status=$(echo "$no_ot_resp" | tail -n 1)
if [ "$no_ot_status" -eq 200 ]; then
    ot2_val=$(echo "$no_ot_body" | grep -o '"otMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${ot2_val:-1}" -eq 0 ]; then
        echo "PASS: otMinutes=0 for allowOvertime=false shift"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected otMinutes=0, got $ot2_val (allowOvertime=false)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $no_ot_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: No shift → otMinutes=0 ───────────────────────────────────────────
echo "--- Test 5: No shift linked → otMinutes=0 ---"
EMP3_EMAIL="ot.emp3.${TS}@example.com"
EMP3_TOKEN=$(setup_employee "$EMP3_EMAIL" "")

EMP3_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$EMP3_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP3_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn3_resp" | tail -n 1)" -ne 201 ]; then
    echo "SKIP: Could not create no-shift assignment"
    FAIL=$((FAIL + 1))
else
    ci3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP3_TOKEN" \
        -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-ot-3\"}")
    CI3_ID=$(echo "$ci3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI3_ID/checkout" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP3_TOKEN" \
        -d '{"latitude":21.0285,"longitude":105.8542}'

    ns_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP3_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    ns_body=$(echo "$ns_resp" | head -n -1)
    ns_status=$(echo "$ns_resp" | tail -n 1)
    if [ "$ns_status" -eq 200 ]; then
        ns_ot=$(echo "$ns_body" | grep -o '"otMinutes":[0-9]*' | head -1 | cut -d: -f2)
        if [ "${ns_ot:-1}" -eq 0 ]; then
            echo "PASS: No-shift employee: otMinutes=0"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Expected otMinutes=0, got $ns_ot (no shift)"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: no-shift query HTTP $ns_status"
        FAIL=$((FAIL + 1))
    fi
fi
echo ""

# ── Test 6: Employee /me includes otMinutes ───────────────────────────────────
echo "--- Test 6: Employee /me includes otMinutes ---"
me_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/me" \
    -H "Authorization: Bearer $EMP1_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)
if [ "$me_status" -eq 200 ]; then
    has_ot=$(echo "$me_body" | grep -c '"otMinutes"' || true)
    if [ "${has_ot:-0}" -ge 1 ]; then
        ot_me=$(echo "$me_body" | grep -o '"otMinutes":[0-9]*' | head -1 | cut -d: -f2)
        echo "PASS: /me contains otMinutes=$ot_me"
        PASS=$((PASS + 1))
    else
        echo "FAIL: /me missing otMinutes — $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $me_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
