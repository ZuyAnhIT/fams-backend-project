#!/usr/bin/env bash
# Decision-support analytics: platform revenue/health + tenant workforce/risk.
# Uses the canonical demo seed from scripts/seed.sh.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

login() {
    local identifier="$1"
    curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
        -d "{\"identifier\":\"$identifier\",\"password\":\"Admin@1234\"}" \
        | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4
}

assert_status() {
    local name="$1" expected="$2" url="$3" token="${4:-}"
    local args=(-s -o /tmp/fams-analytics-response.json -w "%{http_code}" "$url")
    if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
    local actual
    actual=$(curl "${args[@]}")
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected $expected, got $actual"
        sed -n '1,4p' /tmp/fams-analytics-response.json
        FAIL=$((FAIL + 1))
    fi
}

require_fields() {
    local name="$1" url="$2" token="$3"; shift 3
    curl -s "$url" -H "Authorization: Bearer $token" > /tmp/fams-analytics-response.json
    local missing=0
    for field in "$@"; do
        if ! grep -q "\"$field\"" /tmp/fams-analytics-response.json; then
            echo "  missing field: $field"
            missing=1
        fi
    done
    if [ "$missing" -eq 0 ]; then echo "PASS: $name"; PASS=$((PASS + 1));
    else echo "FAIL: $name"; FAIL=$((FAIL + 1)); fi
}

require_value() {
    local name="$1" url="$2" token="$3" expected="$4"
    curl -s "$url" -H "Authorization: Bearer $token" > /tmp/fams-analytics-response.json
    if grep -q "$expected" /tmp/fams-analytics-response.json; then
        echo "PASS: $name"; PASS=$((PASS + 1))
    else
        echo "FAIL: $name — missing value: $expected"
        sed -n '1,4p' /tmp/fams-analytics-response.json
        FAIL=$((FAIL + 1))
    fi
}

ADMIN_TOKEN=$(login "admin@fams.com")
HR_TOKEN=$(login "hr.anphat@fams.test")
EMPLOYEE_TOKEN=$(login "duy.anh@fams.test")
TENANT_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM tenants WHERE slug='demo-an-phat' AND deleted_at IS NULL")
SITE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM sites WHERE tenant_id='$TENANT_ID' AND code='AP-TH' AND deleted_at IS NULL")
WORKSPACE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM workspaces WHERE tenant_id='$TENANT_ID' AND name='Kỹ thuật' AND deleted_at IS NULL LIMIT 1")
EMPLOYEE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM employees WHERE tenant_id='$TENANT_ID' AND employee_code='AP008' AND deleted_at IS NULL")
SHIFT_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM shifts WHERE tenant_id='$TENANT_ID' AND site_id='$SITE_ID' AND deleted_at IS NULL ORDER BY start_time LIMIT 1")
PLAN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT plan_id FROM tenant_subscriptions WHERE tenant_id='$TENANT_ID' LIMIT 1")
IFS='|' read -r CHECKIN_ID CHECKIN_SITE_ID CHECKIN_WORKSPACE_ID CHECKIN_SHIFT_ID CHECKIN_EMPLOYEE_ID < <(
    docker exec fams-postgres psql -U fams_user -d fams_db -AtF'|' -c \
        "SELECT c.id,c.site_id,e.department_id,c.shift_id,c.employee_id FROM checkins c JOIN employees e ON e.id=c.employee_id WHERE c.tenant_id='$TENANT_ID' AND c.deleted_at IS NULL AND c.shift_id IS NOT NULL AND e.department_id IS NOT NULL ORDER BY c.check_in_at DESC LIMIT 1"
)
OTHER_WORKSPACE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -Atqc \
    "SELECT id FROM workspaces WHERE tenant_id='$TENANT_ID' AND id<>'$CHECKIN_WORKSPACE_ID' AND deleted_at IS NULL LIMIT 1")

FROM=2026-09-01
TO=2026-09-06
PLATFORM_REVENUE="$BASE_URL/api/v1/platform/reports/revenue?from=$FROM&to=$TO"
PLATFORM_HEALTH="$BASE_URL/api/v1/platform/reports/customer-health?from=$FROM&to=$TO"
WORKFORCE="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/workforce-effectiveness?from=$FROM&to=$TO"
RISK="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/risk-compliance?from=$FROM&to=$TO"

assert_status "Platform Admin xem doanh thu" 200 "$PLATFORM_REVENUE" "$ADMIN_TOKEN"
require_fields "Doanh thu tách thực thu/MRR, gói, funnel, hết hạn" "$PLATFORM_REVENUE" "$ADMIN_TOKEN" \
    collectedRevenue currentMrr byPlan funnel expiringSubscriptions paymentSuccessRate
assert_status "Tenant Admin không đọc được doanh thu toàn nền tảng" 403 "$PLATFORM_REVENUE" "$HR_TOKEN"
assert_status "Platform Admin xem sức khỏe khách hàng" 200 "$PLATFORM_HEALTH" "$ADMIN_TOKEN"
require_fields "Sức khỏe có tăng trưởng, module, rủi ro và giới hạn" "$PLATFORM_HEALTH" "$ADMIN_TOKEN" \
    growth moduleUsage tenantsAtRisk tenantsNearPlanLimit maxPlanUsagePercent
assert_status "Nền tảng lọc doanh thu theo công ty/gói/trạng thái thuê bao" 200 \
    "$PLATFORM_REVENUE&tenantId=$TENANT_ID&planId=$PLAN_ID&subscriptionStatus=ACTIVE" "$ADMIN_TOKEN"
assert_status "Nền tảng lọc sức khỏe theo công ty/gói/trạng thái thuê bao" 200 \
    "$PLATFORM_HEALTH&tenantId=$TENANT_ID&planId=$PLAN_ID&subscriptionStatus=ACTIVE" "$ADMIN_TOKEN"
assert_status "Trạng thái thuê bao không hỗ trợ bị từ chối" 400 \
    "$BASE_URL/api/v1/platform/reports/revenue?subscriptionStatus=UNKNOWN" "$ADMIN_TOKEN"

assert_status "HR xem hiệu quả nhân sự" 200 "$WORKFORCE" "$HR_TOKEN"
require_fields "Hiệu quả có KPI, so kỳ, xu hướng và công trình" "$WORKFORCE" "$HR_TOKEN" \
    attendanceRate absenceRate totalOtMinutes comparison dailyTrend bySite shortageByWeekday
assert_status "HR lọc theo công trình/workspace/nhân viên" 200 \
    "$WORKFORCE&siteId=$SITE_ID&workspaceId=$WORKSPACE_ID&employeeId=$EMPLOYEE_ID" "$HR_TOKEN"
assert_status "HR lọc hiệu quả theo ca" 200 "$WORKFORCE&siteId=$SITE_ID&shiftId=$SHIFT_ID" "$HR_TOKEN"
assert_status "HR xem rủi ro tuân thủ" 200 "$RISK" "$HR_TOKEN"
require_fields "Rủi ro chuẩn hóa trên 100 lượt và có aging/funnel" "$RISK" "$HR_TOKEN" \
    violationsPer100Checkins aging funnel siteRisk repeatOffenders randomCheckPassRate faceEnrollmentRate
assert_status "HR lọc rủi ro/random check theo ca" 200 "$RISK&siteId=$SITE_ID&shiftId=$SHIFT_ID" "$HR_TOKEN"
CHECKIN_DRILLDOWN="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin?siteId=$CHECKIN_SITE_ID&workspaceId=$CHECKIN_WORKSPACE_ID&shiftId=$CHECKIN_SHIFT_ID&employeeId=$CHECKIN_EMPLOYEE_ID&from=2026-08-31T17:00:00Z&to=2026-09-07T16:59:59Z"
require_value "Drill-down chấm công giữ đủ công trình/workspace/ca/nhân viên" \
    "$CHECKIN_DRILLDOWN" "$HR_TOKEN" "$CHECKIN_ID"
require_value "Workspace khác không làm rò bản ghi chấm công" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin?siteId=$CHECKIN_SITE_ID&workspaceId=$OTHER_WORKSPACE_ID&shiftId=$CHECKIN_SHIFT_ID&employeeId=$CHECKIN_EMPLOYEE_ID&from=2026-08-31T17:00:00Z&to=2026-09-07T16:59:59Z" \
    "$HR_TOKEN" '"totalElements":0'
assert_status "Nhân viên thường không xem báo cáo quản trị" 403 "$WORKFORCE" "$EMPLOYEE_TOKEN"
assert_status "Khoảng ngày đảo ngược bị từ chối" 400 \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/reports/workforce-effectiveness?from=2026-09-06&to=2026-09-01" "$HR_TOKEN"

echo "Analytics report tests: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
