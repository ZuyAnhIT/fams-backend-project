#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/invitations (list invitations with filter/pagination)
# Usage: BASE_URL=http://localhost:8080 bash test_list_invitations.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

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

echo "=== List Invitations Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ADMIN_TOKEN" ]; then
    echo "SETUP FAILED: Could not extract admin token"
    exit 1
fi
echo "Admin token obtained."
echo ""

# Setup: create a test tenant
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
SLUG="list-inv-test-$TS"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"List Inv Corp\",\"slug\":\"$SLUG\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/invitations"

# Setup: send two invitations so the list is non-empty
EMAIL_A="listinv.a.$TS@example.com"
EMAIL_B="listinv.b.$TS@example.com"
curl -s -o /dev/null \
    -X POST "$LIST_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMAIL_A\",\"firstName\":\"Alice\"}"
curl -s -o /dev/null \
    -X POST "$LIST_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMAIL_B\",\"firstName\":\"Bob\"}"
echo "Seed invitations sent: $EMAIL_A, $EMAIL_B"
echo ""

# Test 1: Happy path — list all invitations (paginated response)
echo "--- Test 1: Happy path (no filters) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)

if [ "$list_status" -eq 200 ]; then
    has_content=$(echo "$list_body" | grep -o '"content":\[' | head -1)
    has_total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | head -1)
    total=$(echo "$has_total" | cut -d: -f2)
    if [ -n "$has_content" ] && [ "${total:-0}" -ge 2 ]; then
        echo "PASS: Happy path (HTTP 200, totalElements=$total)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but expected ≥2 elements, got $total"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $list_status"
    echo "Body: $list_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Filter by status=pending — both invitations should appear
echo ""
echo "--- Test 2: Filter by status=pending ---"
pending_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?status=pending" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
pending_body=$(echo "$pending_resp" | head -n -1)
pending_status=$(echo "$pending_resp" | tail -n 1)

if [ "$pending_status" -eq 200 ]; then
    total=$(echo "$pending_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 2 ]; then
        echo "PASS: Filter status=pending (HTTP 200, totalElements=$total)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter status=pending — expected ≥2, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Filter status=pending — expected HTTP 200, got HTTP $pending_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Filter by status=accepted — should return 0 (none accepted yet)
echo ""
echo "--- Test 3: Filter by status=accepted (expect 0 results) ---"
acc_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?status=accepted" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
acc_body=$(echo "$acc_resp" | head -n -1)
acc_status=$(echo "$acc_resp" | tail -n 1)

if [ "$acc_status" -eq 200 ]; then
    total=$(echo "$acc_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -eq 0 ]; then
        echo "PASS: Filter status=accepted returns 0 results (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter status=accepted — expected 0, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Filter status=accepted — expected HTTP 200, got HTTP $acc_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Search by email — partial match on EMAIL_A
echo ""
echo "--- Test 4: Search by email (partial match) ---"
search_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?email=listinv.a" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
search_body=$(echo "$search_resp" | head -n -1)
search_status=$(echo "$search_resp" | tail -n 1)

if [ "$search_status" -eq 200 ]; then
    total=$(echo "$search_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Email search (HTTP 200, found $total result(s))"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Email search — expected ≥1 result, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Email search — expected HTTP 200, got HTTP $search_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Pagination (size=1 should return exactly 1 item)
echo ""
echo "--- Test 5: Pagination (size=1) ---"
page_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?page=0&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_body=$(echo "$page_resp" | head -n -1)
page_status=$(echo "$page_resp" | tail -n 1)

if [ "$page_status" -eq 200 ]; then
    id_count=$(echo "$page_body" | grep -o '"id":"[^"]*"' | wc -l)
    size_field=$(echo "$page_body" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${id_count:-0}" -eq 1 ] && [ "${size_field:-0}" -eq 1 ]; then
        echo "PASS: Pagination size=1 (HTTP 200, got 1 item)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Pagination size=1 — expected 1 item, got $id_count (size=$size_field)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Pagination — expected HTTP 200, got HTTP $page_status"
    FAIL=$((FAIL + 1))
fi

# Test 6: Non-existent tenant → 404
echo ""
echo "--- Test 6: Non-existent tenant ---"
run_test "Tenant not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 7: Unauthenticated → 401
echo ""
echo "--- Test 7: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X GET "$LIST_URL"

# Test 8: User without employees:read permission → 403
echo ""
echo "--- Test 8: Forbidden (no employees:read permission) ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.listinv.$TS@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"NoPerm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)

if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.listinv.$TS@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi

if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X GET "$LIST_URL" \
        -H "Authorization: Bearer $NO_PERM_TOKEN"
else
    echo "SKIP: Could not obtain unprivileged token"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
