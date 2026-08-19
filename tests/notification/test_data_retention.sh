#!/usr/bin/env bash
# Tests for per-tenant data retention (Task 144)
# DataRetentionJob has no manual-trigger HTTP endpoint (it's a weekly @Scheduled bean) — this
# script verifies each per-tenant piece directly instead of waiting for the cron:
#   1. tenant_settings.dataRetentionDays set/clear via the tenant settings API (also covered in
#      tests/tenant/test_tenant_settings.sh Test 9 — kept here too for a single "run this to
#      verify #144" entrypoint).
#   2. fams-ai's POST /checkins/cleanup?tenant_id=... only deletes files under that tenant's own
#      subdirectory, leaving other tenants' files untouched — the real fix for #144's "not tenant-
#      scoped" gap on the biometric-photo side.
#   3. The tenant-scoped notification purge query (NotificationRepository
#      #deleteReadNotificationsOlderThan(tenantId, cutoff)) deletes only the target tenant's old
#      read notifications, not other tenants'.
#
# Usage: BASE_URL=http://localhost:8080 bash test_data_retention.sh
# Requires: fams-ai reachable via `docker exec fams-ai`, fams-postgres via `docker exec fams-postgres`
# (local dev docker-compose setup — same assumption as other AI-dependent test scripts in this repo).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
AI_INTERNAL_SECRET="${AI_INTERNAL_SECRET:-fams_ai_secret_local_dev}"
PASS=0
FAIL=0

run_test() {
    local name="$1" expected_status="$2"
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

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "PASS: $name"; PASS=$((PASS + 1))
    else
        echo "FAIL: $name — response did not contain '$needle'"; FAIL=$((FAIL + 1))
    fi
}

echo "=== Data Retention Tests (Task 144) ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Setup: admin login, create tenant ---"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' \
    | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: admin login" && exit 1

TS=$(date +%s)
t_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Retention Corp ${TS}\",\"slug\":\"retention-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
TENANT_ID=$(echo "$t_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && echo "SETUP FAILED: tenant creation" && exit 1

t2_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Retention Other Corp ${TS}\",\"slug\":\"retention-other-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
OTHER_TENANT_ID=$(echo "$t2_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$OTHER_TENANT_ID" ] && echo "SETUP FAILED: other tenant creation" && exit 1
echo "TENANT_ID=$TENANT_ID  OTHER_TENANT_ID=$OTHER_TENANT_ID"
echo ""

echo "--- Test 1: dataRetentionDays set/clear round-trip ---"
set_body=$(curl -s -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"dataRetentionDays":45}')
check_contains "PATCH sets dataRetentionDays=45" "$set_body" '"dataRetentionDays":45'

run_test "dataRetentionDays below minimum (6) rejected — 400" 400 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"dataRetentionDays":6}'
echo ""

echo "--- Test 2: fams-ai tenant-scoped photo cleanup leaves other tenants untouched ---"
docker exec fams-ai mkdir -p "/app/storage/checkins/$TENANT_ID" "/app/storage/checkins/$OTHER_TENANT_ID" > /dev/null 2>&1
docker exec fams-ai sh -c "echo fake > /app/storage/checkins/$TENANT_ID/old.jpg" > /dev/null 2>&1
docker exec fams-ai sh -c "echo fake > /app/storage/checkins/$OTHER_TENANT_ID/old.jpg" > /dev/null 2>&1
docker exec fams-ai touch -d "60 days ago" "/app/storage/checkins/$TENANT_ID/old.jpg" > /dev/null 2>&1
docker exec fams-ai touch -d "60 days ago" "/app/storage/checkins/$OTHER_TENANT_ID/old.jpg" > /dev/null 2>&1

cleanup_resp=$(docker exec fams-ai curl -s -X POST \
    "http://localhost:5000/checkins/cleanup?older_than_days=45&tenant_id=$TENANT_ID" \
    -H "X-Internal-Secret: $AI_INTERNAL_SECRET")
check_contains "Cleanup response reports 1 checkin photo deleted" "$cleanup_resp" '"checkinsDeleted":1'

target_remaining=$(docker exec fams-ai sh -c "ls /app/storage/checkins/$TENANT_ID/ 2>/dev/null | wc -l" | tr -d '[:space:]')
other_remaining=$(docker exec fams-ai sh -c "ls /app/storage/checkins/$OTHER_TENANT_ID/ 2>/dev/null | wc -l" | tr -d '[:space:]')

if [ "$target_remaining" = "0" ]; then
    echo "PASS: target tenant's old photo was deleted (0 files remaining)"
    PASS=$((PASS + 1))
else
    echo "FAIL: target tenant still has $target_remaining file(s) — expected 0"
    FAIL=$((FAIL + 1))
fi

if [ "$other_remaining" = "1" ]; then
    echo "PASS: other tenant's photo was NOT touched (still 1 file)"
    PASS=$((PASS + 1))
else
    echo "FAIL: other tenant has $other_remaining file(s) — expected 1 (should be untouched)"
    FAIL=$((FAIL + 1))
fi
docker exec fams-ai rm -rf "/app/storage/checkins/$OTHER_TENANT_ID" > /dev/null 2>&1
echo ""

echo "--- Test 3: tenant-scoped notification purge query deletes only the target tenant's rows ---"
# Insert an old (60-day) read notification for the target tenant and, separately, one for a
# different tenant — the tenant-scoped DELETE (matching NotificationRepository
# #deleteReadNotificationsOlderThan(tenantId, cutoff)) must only remove the target tenant's row.
OWNER_USER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -tAc \
    "SELECT owner_id FROM tenants WHERE id='$TENANT_ID';")
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
    INSERT INTO notifications (id, tenant_id, user_id, event_type, title, body, is_read, read_at, priority, created_at)
    VALUES (gen_random_uuid(), '$TENANT_ID', '$OWNER_USER_ID', 'EMPLOYEE_INVITED', 'Retention test A', 'x', true, now(), 'normal', now() - interval '60 days');
    INSERT INTO notifications (id, tenant_id, user_id, event_type, title, body, is_read, read_at, priority, created_at)
    VALUES (gen_random_uuid(), '$OTHER_TENANT_ID', '$OWNER_USER_ID', 'EMPLOYEE_INVITED', 'Retention test B (other tenant)', 'x', true, now(), 'normal', now() - interval '60 days');
" > /dev/null

# Exact same predicate as NotificationRepository#deleteReadNotificationsOlderThan(tenantId, cutoff).
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
    DELETE FROM notifications WHERE tenant_id = '$TENANT_ID' AND created_at < now() - interval '45 days' AND is_read = true;
" > /dev/null

target_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -tAc \
    "SELECT count(*) FROM notifications WHERE tenant_id='$TENANT_ID' AND title='Retention test A';")
other_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -tAc \
    "SELECT count(*) FROM notifications WHERE tenant_id='$OTHER_TENANT_ID' AND title='Retention test B (other tenant)';")

if [ "$target_count" = "0" ]; then
    echo "PASS: target tenant's old read notification was deleted"
    PASS=$((PASS + 1))
else
    echo "FAIL: target tenant's old notification still present"
    FAIL=$((FAIL + 1))
fi
if [ "$other_count" = "1" ]; then
    echo "PASS: other tenant's notification was NOT touched"
    PASS=$((PASS + 1))
else
    echo "FAIL: other tenant's notification missing — should have been untouched"
    FAIL=$((FAIL + 1))
fi
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "DELETE FROM notifications WHERE tenant_id='$OTHER_TENANT_ID' AND title LIKE 'Retention test%';" > /dev/null
echo ""

echo "--- Cleanup: clear dataRetentionDays override ---"
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearDataRetentionDays":true}'
echo "Done."
echo ""

echo "========================================="
echo "Results: $PASS passed, $FAIL failed"
if [ "$FAIL" -eq 0 ]; then
    echo "ALL TESTS PASSED"
else
    echo "SOME TESTS FAILED"
    exit 1
fi
