#!/usr/bin/env bash
# FAMS Demo Seed — Vietnamese dataset (v2 — 15 tenants + platform staff).
#
# Creates 13 owner accounts (2 of them own 2 tenants each) + 15 tenants with
# sites, shifts, employees, assignments, workspaces (+ members), invitations,
# IP whitelist, and random-check configs via API; ~12 platform staff accounts
# with platform-level roles; then injects 30 days of historical checkins,
# attendance, violations, face profiles, and notifications directly into
# PostgreSQL for the 5 richest tenants (see seed_historical.sql).
#
# 3 "main" tenants get full-depth data (>=12-15 rows per entity — employees,
# workspaces, sites, shifts, assignments, face profiles via historical SQL):
#   acme-corp          Công ty CP Xây dựng Hoàng Long   (Pro)
#   beta-industries     Công ty TNHH Sản xuất Bình Minh (Pro)
#   gamma-logistics     Công ty CP Logistics Phương Nam (Enterprise)
#
# 2 existing edge-case tenants (kept lightweight on purpose — each tests a
# specific business rule):
#   tia-sang-startup    Công ty Khởi nghiệp Tia Sáng    (Trial, sits at the 5-employee limit)
#   dong-a-jsc          Công ty TNHH Đông Á             (Basic, suspended via API at the end)
#
# 10 new lightweight tenants (realistic but minimal — diverse industries,
# diverse plan/billing/status combinations, 2 owner accounts each holding 2
# companies to exercise multi-tenant ownership):
#   viet-phat-retail, hoang-gia-fnb (same owner as viet-phat-retail),
#   minh-chau-security, thanh-cong-real-estate, viet-nam-cleaning,
#   sao-mai-electric, phu-quy-mining, dai-duong-fishery (same owner as
#   phu-quy-mining), tan-phat-agriculture, viet-tin-events (CANCELLED sub)
#
# All sample accounts (tenant owners, employees with logins, platform staff)
# share the SAME default password as the platform admin: Admin@1234
#
# Two people already work at two different tenants under one shared login
# (kept from the original dataset):
#   - Phạm Thị Dung: HR_MANAGER at Hoàng Long + SITE_SUPERVISOR at Bình Minh
#   - Trương Văn Đạt: EMPLOYEE at Phương Nam + EMPLOYEE at Tia Sáng
#
# Usage:
#   bash scripts/seed.sh
#   BASE_URL=http://localhost:8080 bash scripts/seed.sh
#
# Safe to run multiple times on the same database (idempotent upserts).

set -uo pipefail

ENV_FILE="$(dirname "$0")/../.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_CONTAINER="${DB_CONTAINER:-fams-postgres}"
DB_USER_ENV="${DB_USER:-fams_user}"
DB_NAME_ENV="${DB_NAME:-fams_db}"
DEFAULT_PASSWORD="Admin@1234"

echo "=== FAMS Demo Seed v2 (15 tenants, Vietnamese dataset) ==="
echo "Target: $BASE_URL"
echo ""

# ── Login as platform admin ──────────────────────────────────────────────────

echo "Logging in as platform admin..."
_r=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
_body=$(echo "$_r" | head -n -1)
_status=$(echo "$_r" | tail -n 1)
if [ "$_status" -ne 200 ]; then
    echo "ERROR: Cannot login as admin (HTTP $_status)."
    echo "Is the backend running? Try: make dev-d, wait ~30s, then re-run."
    exit 1
fi
TOKEN=$(echo "$_body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
echo "OK"
echo ""

# ── Fetch Plan IDs ────────────────────────────────────────────────────────────

echo "Fetching plan IDs..."
_plans=$(curl -s "$BASE_URL/api/v1/plans?size=20&activeOnly=false" -H "Authorization: Bearer $TOKEN")
plan_id() {
    echo "$_plans" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='$1'),''))
"
}
PLAN_TRIAL_ID=$(plan_id trial)
PLAN_BASIC_ID=$(plan_id basic)
PLAN_PRO_ID=$(plan_id pro)
PLAN_ENTERPRISE_ID=$(plan_id enterprise)
echo "  trial=$PLAN_TRIAL_ID basic=$PLAN_BASIC_ID pro=$PLAN_PRO_ID enterprise=$PLAN_ENTERPRISE_ID"
echo ""

# ── Legacy plan (will be deactivated near the end, after 1 tenant subscribes to it) ──
# Spec mục 1.2: "1 gói bị deactivate nhưng vẫn có tenant cũ đang dùng". Thực tế theo đúng
# nghiệp vụ hệ thống này (Issue #8, PlanService.migrateTenantsOffPlan — KHÔNG cho phép tắt
# gói còn tenant mà không chỉ định migrateToPlanId): deactivate legacy_basic sẽ tự động
# chuyển Đại Dương sang gói basic, còn legacy_basic tự nó ở trạng thái isActive=false, 0
# tenant — đây chính là luồng cần test (không phải "tenant vẫn kẹt ở gói cũ", hệ thống chủ
# động migrate). Created fresh each run; 409 handles reruns.
echo "Creating legacy plan (to be deactivated after 1 tenant is on it)..."
_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/plans" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"name":"legacy_basic","displayName":"Legacy Basic (ngừng bán)","description":"Gói cũ đã ngừng bán cho khách hàng mới — chỉ còn áp dụng cho tenant đã đăng ký từ trước.","priceMonthly":9.99,"priceYearly":99.99,"sortOrder":99}')
_s=$(echo "$_r" | tail -n 1); _b=$(echo "$_r" | head -n -1)
if [ "$_s" -eq 201 ]; then
    PLAN_LEGACY_ID=$(echo "$_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
    echo "  Created: legacy_basic (id=$PLAN_LEGACY_ID)"
elif [ "$_s" -eq 409 ]; then
    _plans2=$(curl -s "$BASE_URL/api/v1/plans?size=20&activeOnly=false" -H "Authorization: Bearer $TOKEN")
    PLAN_LEGACY_ID=$(echo "$_plans2" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='legacy_basic'),''))
")
    echo "  Exists: legacy_basic (id=$PLAN_LEGACY_ID)"
else
    PLAN_LEGACY_ID=""
    echo "  ! Error creating legacy_basic plan — HTTP $_s ($_b)"
fi
echo ""

# Fetch permission IDs grouped by resource once, up front — used by both the tenant-level
# and platform-level custom-role helpers further down.
_perms=$(curl -s "$BASE_URL/api/v1/permissions" -H "Authorization: Bearer $TOKEN")
perm_ids_for() {
    echo "$_perms" | python3 -c "
import json,sys
d=json.load(sys.stdin)
groups=d.get('data',[])
ids=[]
for g in groups:
    if g.get('resource') in $1:
        ids.extend([p['id'] for p in g.get('permissions',[])])
print(json.dumps(ids))
"
}

# ── Helper: register + verify an owner/staff account ─────────────────────────
# mk_account <email> <displayName> [phone]
# Registers via the public /auth/register endpoint (real signup path), then
# flips email_verified=true directly in Postgres so it can log in immediately
# — registration itself always requires email verification first, and there
# is no email inbox to click a link from in a seed script.
mk_account() {
    local email="$1" name="$2" phone="${3:-}"
    local existing
    existing=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM users WHERE email='$email';" 2>/dev/null | tr -d ' \n')
    if [ -n "$existing" ]; then
        echo "  ~ Account exists: $email"
        return
    fi
    local payload r s
    payload=$(python3 -c "import json; print(json.dumps({'email':'$email','password':'$DEFAULT_PASSWORD','displayName':'$name'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/register" \
        -H "Content-Type: application/json" -d "$payload")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c \
            "UPDATE users SET email_verified = TRUE WHERE email = '$email';" >/dev/null 2>&1
        echo "  + Account: $email ($name)"
    else
        echo "  ! Account error: $email — HTTP $s"
    fi
}

# ── 13 owner accounts (2 own two companies each) ─────────────────────────────

echo "=== Registering 13 tenant-owner accounts ==="
mk_account "chu.hoanglong@gmail.com"    "Nguyễn Văn An"
mk_account "giamdoc.binhminh@gmail.com" "Đỗ Thị Xuân"
mk_account "quang.phuongnam@gmail.com"  "Trịnh Văn Quang"
mk_account "kimngan.tiasang@gmail.com"  "Nguyễn Thị Kim Ngân"
mk_account "hanh.donga@gmail.com"       "Bạch Thị Hạnh"
mk_account "owner.vietphat@gmail.com"   "Lương Văn Phát"
mk_account "owner.minhchau@gmail.com"   "Đinh Văn Châu"
mk_account "owner.thanhcong@gmail.com"  "Trần Thị Thành"
mk_account "owner.vncleaning@gmail.com" "Phạm Văn Sạch"
mk_account "owner.saomai@gmail.com"     "Vũ Thị Mai"
mk_account "owner.phuquy@gmail.com"     "Hoàng Văn Quý"
mk_account "owner.tanphat@gmail.com"    "Ngô Thị Tân"
mk_account "owner.viettin@gmail.com"    "Đặng Văn Tín"
# 3 chủ sở hữu bổ sung — cho tenant rỗng / sắp hết hạn trial / đã hết hạn trial (spec mục 2.1)
mk_account "owner.rongvang@gmail.com"   "Lê Văn Rồng"
mk_account "owner.hoaphuong@gmail.com"  "Trần Thị Phượng"
mk_account "owner.namviet@gmail.com"    "Nguyễn Văn Nam"
echo ""

# ── Tenant/Site/Shift/Employee/Workspace helpers ─────────────────────────────

TENANT_ID=""; SITE_ID=""; SHIFT_ID=""; EMP_ID=""; WORKSPACE_ID=""; INVITATION_ID=""

# mk_tenant <name> <slug> <owner_email> [extra_json_fields] [industry]
mk_tenant() {
    local name="$1" slug="$2" owner_email="$3" extra="${4:-}" industry="${5:-}"
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"name\":\"$name\",\"slug\":\"$slug\",\"ownerEmail\":\"$owner_email\"$extra}")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        TENANT_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "  Created: $name (id=$TENANT_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants?search=$slug&size=5" \
            -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
        if [ "$ls" -eq 200 ]; then
            TENANT_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('slug')=='$slug']
print(match[0]['id'] if match else (items[0]['id'] if items else ''))
")
            echo "  Exists: $name (id=$TENANT_ID)"
        else
            TENANT_ID=""; echo "  Skipped: $name (could not fetch id)"
        fi
    else
        TENANT_ID=""; echo "  Error creating $name — HTTP $s ($b)"
    fi
}


# mk_subscription <plan_id> <plan_name> [billing_cycle] [status]
# status defaults to ACTIVE (a paying/upgraded tenant) — pass "TRIAL" explicitly for tenants
# that are genuinely still on the trial plan. Every tenant auto-gets a TRIAL subscription at
# creation; without explicitly setting status here, a tenant upgraded to Pro/Basic/Enterprise
# would keep showing subscription status=TRIAL forever (inconsistent — a paying company should
# never show as "still on trial").
mk_subscription() {
    local plan_id="$1" plan_name="$2" cycle="${3:-MONTHLY}" status="${4:-ACTIVE}"
    [ -z "$TENANT_ID" ] && return
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"planId\":\"$plan_id\",\"billingCycle\":\"$cycle\",\"status\":\"$status\"}")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ] || [ "$s" -eq 200 ]; then
        echo "  Subscription: $plan_name ($cycle, $status) — OK"
    elif [ "$s" -eq 409 ]; then
        r=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
            -d "{\"planId\":\"$plan_id\",\"billingCycle\":\"$cycle\",\"status\":\"$status\"}")
        s=$(echo "$r" | tail -n 1)
        [ "$s" -eq 200 ] && echo "  Subscription: $plan_name ($cycle, $status) — updated" || echo "  Subscription update HTTP $s"
    else
        echo "  Subscription: $plan_name — HTTP $s"
    fi
}

# mk_cancel_subscription — used for the 1 tenant we want in CANCELLED state for status diversity
mk_cancel_subscription() {
    [ -z "$TENANT_ID" ] && return
    curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d '{"status":"CANCELLED"}'
    echo "  Subscription: CANCELLED"
}

mk_site() {
    local tid="$1" name="$2" code="$3" address="$4" lat="$5" lon="$6" tz="${7:-Asia/Ho_Chi_Minh}"
    SITE_ID=""
    local r s b payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','code':'$code','address':'$address','latitude':$lat,'longitude':$lon,'timezone':'$tz','status':'active'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        SITE_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Site: $name (id=$SITE_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/sites?search=$code&size=10" -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
        SITE_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('name')=='$name' or i.get('code')=='$code']
print(match[0]['id'] if match else (items[0]['id'] if items else ''))
")
        echo "    ~ Site exists: $name (id=$SITE_ID)"
    else
        echo "    ! Site error: $name — HTTP $s"
    fi
}

mk_shift() {
    local tid="$1" sid="$2" name="$3" start="$4" end_t="$5" overnight="${6:-false}" ot="${7:-false}"
    SHIFT_ID=""
    [ -z "$sid" ] && return
    local r s b payload
    payload=$(python3 -c "
import json
print(json.dumps({
  'name': '$name', 'startTime': '$start', 'endTime': '$end_t',
  'allowOvernight': '$overnight'.lower() == 'true',
}))
")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites/$sid/shifts" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        SHIFT_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "      + Shift: $name $start-$end_t (id=$SHIFT_ID)"
        if [ "$ot" = "true" ]; then
            curl -s -o /dev/null -X PUT "$BASE_URL/api/v1/tenants/$tid/sites/$sid/shifts/$SHIFT_ID/ot-config" \
                -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
                -d '{"allowOvertime":true,"earlyCheckinMinutes":15,"lateCheckoutMinutes":30}'
        fi
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/sites/$sid/shifts?size=20" -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
        SHIFT_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
")
        echo "      ~ Shift exists: $name (id=$SHIFT_ID)"
    else
        echo "      ! Shift error: $name — HTTP $s"
    fi
}

# mk_workspace <tenant_id> <name> [type] [description] [parentId] — "department" replaces
# the old standalone Department entity (consolidated into Workspace, see
# docs/api/workspace-management-api.md section 4). Optional [parentId] lets callers build a
# 3rd nesting level (Công ty → Khối → Đội) instead of the flat 1-level parent/child used
# elsewhere — see the "workspace tree" block per deep tenant below.
mk_workspace() {
    local tid="$1" name="$2" type="${3:-department}" desc="${4:-}" parent="${5:-}"
    WORKSPACE_ID=""
    local r s b payload
    if [ -n "$parent" ]; then
        payload=$(python3 -c "import json; print(json.dumps({'name':'$name','type':'$type','description':'$desc','parentId':'$parent'}))")
    else
        payload=$(python3 -c "import json; print(json.dumps({'name':'$name','type':'$type','description':'$desc'}))")
    fi
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/workspaces" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        WORKSPACE_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Workspace: $name (id=$WORKSPACE_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/workspaces?size=50" -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
        WORKSPACE_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[]) if isinstance(d.get('data'),dict) else d.get('data',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
")
        echo "    ~ Workspace exists: $name (id=$WORKSPACE_ID)"
    else
        echo "    ! Workspace error: $name — HTTP $s"
    fi
}

mk_workspace_member() {
    local tid="$1" wsid="$2" emp_id="$3" role="${4:-member}"
    [ -z "$wsid" ] || [ -z "$emp_id" ] && return
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/workspaces/$wsid/members" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"employeeId\":\"$emp_id\",\"role\":\"$role\"}")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "      → Thành viên workspace (role=$role)"
    elif [ "$s" -eq 409 ]; then
        echo "      → Đã là thành viên workspace"
    else
        echo "      ! Lỗi thêm thành viên workspace — HTTP $s"
    fi
}

# mk_employee <json_payload> <label>
mk_employee() {
    local payload="$1" label="$2"
    EMP_ID=""
    [ -z "$TENANT_ID" ] && return
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        EMP_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + $label"
    elif [ "$s" -eq 409 ]; then
        local email
        email=$(echo "$payload" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('email',''))")
        if [ -n "$email" ]; then
            local lr ls lb
            lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$TENANT_ID/employees?search=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$email'))")&size=5" \
                -H "Authorization: Bearer $TOKEN")
            ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
            EMP_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('email')=='$email']
print(match[0]['id'] if match else (items[0]['id'] if items else ''))
")
        fi
        echo "    ~ $label (exists, id=$EMP_ID)"
    else
        echo "    ! $label — HTTP $s ($b)"
    fi
}

mk_status() {
    local emp_id="$1" new_status="$2"
    [ -z "$TENANT_ID" ] || [ -z "$emp_id" ] && return
    curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$emp_id/status" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "{\"status\":\"$new_status\"}"
    echo "      → status → $new_status"
}

mk_assignment() {
    local tid="$1" sid="$2" emp_id="$3" shift_id="$4" role="${5:-worker}" start="${6:-2026-01-01}" end="${7:-}"
    [ -z "$emp_id" ] || [ -z "$sid" ] && return
    local shift_field="" end_field=""
    [ -n "$shift_id" ] && shift_field="\"shiftId\":\"$shift_id\","
    [ -n "$end" ] && end_field="\"endDate\":\"$end\","
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites/$sid/assignments" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"employeeId\":\"$emp_id\",${shift_field}${end_field}\"startDate\":\"$start\",\"role\":\"$role\"}")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "      → Assigned (role=$role)"
    elif [ "$s" -eq 409 ]; then
        echo "      → Đã phân công / xung đột lịch (bỏ qua)"
    else
        echo "      ! Assignment error — HTTP $s"
    fi
}

mk_rand_config() {
    local tid="$1" mode="${2:-location_only}" cps="${3:-2}" window="${4:-300}"
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/random-check-configs/tenant-default" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"checksPerShift\":$cps,\"minIntervalMinutes\":60,\"allowedStartTime\":\"07:00\",\"allowedEndTime\":\"22:00\",\"checkMode\":\"$mode\",\"applicableRoles\":[\"worker\",\"supervisor\"],\"responseWindowSeconds\":$window}")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "    + Random check config: mode=$mode, $cps checks/shift"
    elif [ "$s" -eq 409 ]; then
        echo "    ~ Random check config exists"
    else
        echo "    ! Random check config error — HTTP $s"
    fi
}

mk_ip_whitelist() {
    local tid="$1" ip="$2" label="$3" scope="${4:-all}"
    [ -z "$tid" ] && return
    local existing
    existing=$(curl -s "$BASE_URL/api/v1/tenants/$tid/ip-whitelists?size=100" -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print('yes' if any(i.get('ipAddress')=='$ip' for i in items) else 'no')
" 2>/dev/null)
    [ "$existing" = "yes" ] && { echo "    ~ IP whitelist đã tồn tại: $ip"; return; }
    local r s payload
    payload=$(python3 -c "import json; print(json.dumps({'ipAddress':'$ip','label':'$label','scope':'$scope'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/ip-whitelists" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "    + IP whitelist: $ip ($label)"
    elif [ "$s" -eq 409 ]; then
        echo "    ~ IP whitelist đã tồn tại: $ip"
    else
        echo "    ! Lỗi IP whitelist: $ip — HTTP $s"
    fi
}

# mk_tenant_custom_role <tenant_id> <name> <desc> <resources_py_list> — moved up here (was
# previously defined only in the "diversity" section further down, AFTER the 3 deep-tenant
# blocks that need to call it — bash requires a function to be defined before first use, so
# calling it from inside the Tenant 1/2/3 blocks silently failed with "command not found").
mk_tenant_custom_role() {
    local tenant_id="$1" name="$2" desc="$3" resources_py="$4"
    [ -z "$tenant_id" ] && return
    local ids payload r s b
    ids=$(perm_ids_for "$resources_py")
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','description':'$desc','tenantId':'$tenant_id','permissionIds':json.loads('$ids')}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/roles" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        echo "  + Role tùy chỉnh (tenant): $name"
    elif [ "$s" -eq 409 ]; then
        echo "  ~ Role tùy chỉnh đã tồn tại: $name"
    else
        echo "  ! Lỗi tạo role tùy chỉnh: $name — HTTP $s ($b)"
    fi
}

mk_invitation() {
    local tid="$1" email="$2" first="$3" last="$4" phone="${5:-}"
    INVITATION_ID=""
    [ -z "$tid" ] && return
    local existing
    existing=$(curl -s "$BASE_URL/api/v1/tenants/$tid/invitations?size=100" -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[]) if isinstance(d.get('data'),dict) else d.get('data',[])
match=[i for i in items if i.get('email')=='$email']
print(match[0]['id'] if match else '')
" 2>/dev/null)
    if [ -n "$existing" ]; then
        INVITATION_ID="$existing"; echo "    ~ Lời mời đã tồn tại: $email"; return
    fi
    local r s b payload
    payload=$(python3 -c "import json; print(json.dumps({'email':'$email','firstName':'$first','lastName':'$last','phone':'$phone'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/invitations" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        INVITATION_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Lời mời: $email (id=$INVITATION_ID)"
    elif [ "$s" -eq 409 ]; then
        echo "    ~ Lời mời đã tồn tại: $email"
    else
        echo "    ! Lỗi tạo lời mời: $email — HTTP $s"
    fi
}

mk_cancel_invitation() {
    local tid="$1" iid="$2"
    [ -z "$iid" ] && return
    curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/tenants/$tid/invitations/$iid" -H "Authorization: Bearer $TOKEN"
    echo "      → Lời mời đã hủy"
}

mk_suspend_tenant() {
    local tid="$1"
    [ -z "$tid" ] && return
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/suspend" -H "Authorization: Bearer $TOKEN")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 200 ]; then
        echo "  → Tenant đã bị tạm ngưng (suspended)"
    elif [ "$s" -eq 400 ]; then
        echo "  ~ Tenant đã ở trạng thái suspended/cancelled từ trước"
    else
        echo "  ! Lỗi suspend tenant — HTTP $s"
    fi
}

# Name pools used to generate extra employees for the 3 deep tenants and the
# 10 lightweight tenants without hand-writing every single record.
VN_HO=(Nguyễn Trần Lê Phạm Hoàng Huỳnh Phan Vũ Võ Đặng Bùi Đỗ Hồ Ngô Dương Đinh Lý Trịnh Mai Cao)
VN_TEN=(An Bình Cường Dung Giang Hùng Khôi Lan Minh Ngọc Phúc Quỳnh Sơn Thảo Tùng Uyên Vinh Xuân Yến Đạt Bảo Châu Duy Hải Kiên Loan Nam Oanh Phát Trang)

# mk_extra_employees <tenant_id> <workspace_id> <department_name> <site_id> <shift_id>
#                     <code_prefix> <start_index> <count>
# Generates <count> additional employees (positions cycling through a small
# pool) using the shared name arrays, assigns each to <site_id>/<shift_id>.
mk_extra_employees() {
    local tid="$1" wsid="$2" dept_name="$3" sid="$4" shid="$5" prefix="$6" start_idx="$7" count="$8"
    local positions=("Nhân viên" "Kỹ thuật viên" "Chuyên viên" "Tổ trưởng" "Nhân viên vận hành")
    local i idx ho ten pos code email
    for i in $(seq 0 $((count - 1))); do
        idx=$((start_idx + i))
        ho="${VN_HO[$((RANDOM % ${#VN_HO[@]}))]}"
        ten="${VN_TEN[$((RANDOM % ${#VN_TEN[@]}))]}"
        pos="${positions[$((i % ${#positions[@]}))]}"
        code=$(printf "%s-%03d" "$prefix" "$idx")
        email=$(echo "${ten}.${ho}${idx}@$(echo "$prefix" | tr '[:upper:]' '[:lower:]').vn" | tr -d ' ')
        mk_employee "{\"firstName\":\"$ten\",\"lastName\":\"$ho\",\"email\":\"$email\",\"employeeCode\":\"$code\",\"position\":\"$pos\",\"department\":\"$dept_name\",\"departmentId\":\"$wsid\",\"hiredDate\":\"2024-0$(( (i % 9) + 1 ))-01\",\"phone\":\"+8490${idx}${idx}0000\"}" "$ten $ho ($pos) [auto]"
        [ -n "$EMP_ID" ] && [ -n "$sid" ] && mk_assignment "$tid" "$sid" "$EMP_ID" "$shid" "worker" "2026-01-01"
    done
}

# mk_employee_group <tenant_id> <workspace_id> <dept_name> <site_id> <shift_id> <code_prefix>
#                    <start_index> <count> <position_label> <assignment_role> <hired_pattern>
# Like mk_extra_employees but with a FIXED position label + assignment role + hire-date pattern
# per call, so callers can build up the "10 nhóm nghiệp vụ" mix required by
# docs/testing/sample-data-requirements-v2.md mục 6.1 by calling this once per nhóm, instead of
# 1 generic pool that can't express "quản lý công trình" vs "nhân viên thời vụ" as distinct
# groups. hired_pattern: "established" (2021-2023, lâu năm) | "probation" (thử việc, hired
# trong ~45 ngày gần nhất) | "seasonal" (thời vụ, hired gần đây + assignment có endDate ngắn
# hạn ~60-90 ngày, KHÔNG phải 1 status riêng — hệ thống không có status "thời vụ", chỉ mô
# phỏng qua position + hạn phân công ngắn, xem sample-data-requirements-v2.md mục 6.1).
mk_employee_group() {
    local tid="$1" wsid="$2" dept_name="$3" sid="$4" shid="$5" prefix="$6" start_idx="$7" count="$8"
    local position="$9" arole="${10}" hired_pattern="${11}"
    local i idx ho ten code email hired end_field=""
    for i in $(seq 0 $((count - 1))); do
        idx=$((start_idx + i))
        ho="${VN_HO[$((RANDOM % ${#VN_HO[@]}))]}"
        ten="${VN_TEN[$((RANDOM % ${#VN_TEN[@]}))]}"
        code=$(printf "%s-%03d" "$prefix" "$idx")
        email=$(echo "${ten}.${ho}${idx}@$(echo "$prefix" | tr '[:upper:]' '[:lower:]').vn" | tr -d ' ')
        case "$hired_pattern" in
            probation) hired=$(date -u -d "-$(( (idx % 40) + 5 )) days" +%F 2>/dev/null || date -u -v-$(( (idx % 40) + 5 ))d +%F) ;;
            seasonal)  hired=$(date -u -d "-$(( (idx % 20) + 3 )) days" +%F 2>/dev/null || date -u -v-$(( (idx % 20) + 3 ))d +%F) ;;
            *)         hired="2022-0$(( (i % 9) + 1 ))-01" ;;
        esac
        mk_employee "{\"firstName\":\"$ten\",\"lastName\":\"$ho\",\"email\":\"$email\",\"employeeCode\":\"$code\",\"position\":\"$position\",\"department\":\"$dept_name\",\"departmentId\":\"$wsid\",\"hiredDate\":\"$hired\",\"phone\":\"+8490${idx}${idx}0000\"}" "$ten $ho ($position) [auto]"
        if [ -n "$EMP_ID" ] && [ -n "$sid" ]; then
            if [ "$hired_pattern" = "seasonal" ]; then
                local end_date
                end_date=$(date -u -d "+$(( (idx % 30) + 60 )) days" +%F 2>/dev/null || date -u -v+$(( (idx % 30) + 60 ))d +%F)
                mk_assignment "$tid" "$sid" "$EMP_ID" "$shid" "$arole" "$hired" "$end_date"
            else
                mk_assignment "$tid" "$sid" "$EMP_ID" "$shid" "$arole" "2026-01-01"
            fi
        fi
    done
}

# mk_extra_sites <tenant_id> <name_prefix> <code_prefix> <city_list_csv> <start_index> <count>
# Generates <count> extra sites cycling through a small city/coordinate pool,
# each with 1 standard day shift, returning nothing (callers re-query if they
# need the last SITE_ID/SHIFT_ID — used only to pad row-counts realistically).
mk_extra_sites() {
    local tid="$1" name_prefix="$2" code_prefix="$3" start_idx="$4" count="$5"
    # city;lat;lon pool — reused cyclically, distinct enough to look realistic
    local cities=(
        "Hải Phòng;20.8449;106.6881" "Cần Thơ;10.0452;105.7469" "Nha Trang;12.2388;109.1967"
        "Vũng Tàu;10.4113;107.1362" "Biên Hòa;10.9574;106.8426" "Huế;16.4637;107.5909"
        "Buôn Ma Thuột;12.6667;108.0500" "Quy Nhơn;13.7563;109.2297" "Thái Nguyên;21.5928;105.8442"
        "Nam Định;20.4341;106.1675" "Vinh;18.6796;105.6813" "Hạ Long;20.9500;107.0833"
    )
    local i idx entry city lat lon name code
    for i in $(seq 0 $((count - 1))); do
        idx=$((start_idx + i))
        entry="${cities[$((i % ${#cities[@]}))]}"
        city=$(echo "$entry" | cut -d';' -f1); lat=$(echo "$entry" | cut -d';' -f2); lon=$(echo "$entry" | cut -d';' -f3)
        name="$name_prefix - $city"
        code=$(printf "%s-%02d" "$code_prefix" "$idx")
        mk_site "$tid" "$name" "$code" "Khu vực $city" "$lat" "$lon" "Asia/Ho_Chi_Minh"
        [ -n "$SITE_ID" ] && mk_shift "$tid" "$SITE_ID" "Ca tiêu chuẩn" "08:00" "17:00" "false" "true"
    done
}

# ── Tenant 1: Công ty CP Xây dựng Hoàng Long (DEEP — 15 employees, 13 sites, 13 workspaces) ──

echo "=== Tenant 1: Công ty CP Xây dựng Hoàng Long (acme-corp) ==="
mk_tenant "Công ty CP Xây dựng Hoàng Long" "acme-corp" "chu.hoanglong@gmail.com" \
    ',"industry":"Xây dựng","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Xây dựng"
HL_ID="$TENANT_ID"

if [ -n "$HL_ID" ]; then
    [ -n "$PLAN_PRO_ID" ] && mk_subscription "$PLAN_PRO_ID" "pro"

    echo "  Workspaces (phòng ban):"
    mk_workspace "$HL_ID" "Kỹ thuật" "department" "Kỹ sư và công nhân xây dựng"; HL_WS_KT="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Vận hành" "department" "Vận hành công trường và hậu cần"; HL_WS_VH="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Nhân sự" "department" "Quản lý nhân sự và tuyển dụng"; HL_WS_NS="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "An toàn & Chất lượng" "department" "An toàn lao động và kiểm định chất lượng"; HL_WS_AT="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Thiết kế" "department" "Thiết kế kiến trúc và kết cấu"; HL_WS_TK="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Kỹ thuật Hà Nội" "team" "Kỹ sư làm việc tại trụ sở Hà Nội"; HL_WS_TEAM_HN="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Hành chính TP.HCM" "team" "Nhân viên hành chính tại văn phòng TP.HCM"; HL_WS_TEAM_HCM="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Công trình Đà Nẵng" "team" "Đội thi công tại chi nhánh Đà Nẵng"; HL_WS_TEAM_DN="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Ban Giám đốc" "department" "Ban lãnh đạo công ty"; HL_WS_BGD="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Tài chính - Kế toán" "department" "Kế toán và tài chính dự án"; HL_WS_TC="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Mua hàng - Vật tư" "department" "Thu mua và quản lý vật tư công trình"; HL_WS_MH="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Cơ điện" "team" "Đội thi công cơ điện (M&E)"; HL_WS_TEAM_CD="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Pháp chế" "department" "Pháp lý hợp đồng và tuân thủ"; HL_WS_PC="$WORKSPACE_ID"

    echo "  Sites (công trình):"
    mk_site "$HL_ID" "Trụ sở Hoàng Long Hà Nội" "HL-HN" "15 Lê Lợi, Hoàn Kiếm, Hà Nội" "21.0285" "105.8542" "Asia/Ho_Chi_Minh"
    HL_HN="$SITE_ID"
    mk_site "$HL_ID" "Văn phòng Hoàng Long TP.HCM" "HL-HCM" "100 Nguyễn Huệ, Quận 1, TP.HCM" "10.7769" "106.7009" "Asia/Ho_Chi_Minh"
    HL_HCM="$SITE_ID"
    mk_site "$HL_ID" "Chi nhánh Hoàng Long Đà Nẵng" "HL-DN" "45 Trần Phú, Hải Châu, Đà Nẵng" "16.0544" "108.2022" "Asia/Ho_Chi_Minh"
    HL_DN="$SITE_ID"
    mk_extra_sites "$HL_ID" "Công trình Hoàng Long" "HL" 4 10
    HL_EXTRA_SITE="$SITE_ID"

    echo "  Ca làm việc (Hà Nội):"
    HL_HN_SANG="" HL_HN_CHIEU="" HL_HN_DEM=""
    if [ -n "$HL_HN" ]; then
        mk_shift "$HL_ID" "$HL_HN" "Ca sáng" "07:00" "15:00" "false" "true"; HL_HN_SANG="$SHIFT_ID"
        mk_shift "$HL_ID" "$HL_HN" "Ca chiều" "15:00" "23:00" "false" "false"; HL_HN_CHIEU="$SHIFT_ID"
        mk_shift "$HL_ID" "$HL_HN" "Ca đêm" "23:00" "07:00" "true" "false"; HL_HN_DEM="$SHIFT_ID"
    fi
    echo "  Ca làm việc (TP.HCM):"
    HL_HCM_SANG="" HL_HCM_CHIEU=""
    if [ -n "$HL_HCM" ]; then
        mk_shift "$HL_ID" "$HL_HCM" "Ca sáng" "08:00" "16:00" "false" "true"; HL_HCM_SANG="$SHIFT_ID"
        mk_shift "$HL_ID" "$HL_HCM" "Ca chiều" "14:00" "22:00" "false" "false"; HL_HCM_CHIEU="$SHIFT_ID"
    fi
    echo "  Ca làm việc (Đà Nẵng):"
    HL_DN_TC=""
    if [ -n "$HL_DN" ]; then
        mk_shift "$HL_ID" "$HL_DN" "Ca tiêu chuẩn" "08:00" "17:00" "false" "true"; HL_DN_TC="$SHIFT_ID"
        mk_shift "$HL_ID" "$HL_DN" "Ca mở rộng" "07:00" "19:00" "false" "true"
    fi

    echo "  Nhân viên:"
    mk_employee "{\"firstName\":\"An\",\"lastName\":\"Nguyễn Văn\",\"email\":\"an.nguyen@hoanglong.vn\",\"employeeCode\":\"HL-001\",\"position\":\"Kỹ sư cao cấp\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_WS_KT\",\"hiredDate\":\"2023-03-15\",\"phone\":\"+84901000001\"}" "Nguyễn Văn An (Kỹ sư cao cấp)"
    HL_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Bình\",\"lastName\":\"Trần Thị\",\"email\":\"binh.tran@hoanglong.vn\",\"employeeCode\":\"HL-002\",\"position\":\"Giám sát công trường\",\"department\":\"Vận hành\",\"departmentId\":\"$HL_WS_VH\",\"hiredDate\":\"2022-07-01\",\"phone\":\"+84901000002\"}" "Trần Thị Bình (Giám sát công trường)"
    HL_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Cường\",\"lastName\":\"Lê Văn\",\"email\":\"cuong.le@hoanglong.vn\",\"employeeCode\":\"HL-003\",\"position\":\"Kỹ thuật viên\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_WS_KT\",\"hiredDate\":\"2021-09-01\",\"phone\":\"+84901000003\"}" "Lê Văn Cường (Kỹ thuật viên) [inactive]"
    HL_E3="$EMP_ID"
    mk_status "$HL_E3" "inactive"
    mk_employee "{\"firstName\":\"Dung\",\"lastName\":\"Phạm Thị\",\"email\":\"dung.pham@hoanglong.vn\",\"employeeCode\":\"HL-004\",\"position\":\"Trưởng phòng Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$HL_WS_NS\",\"hiredDate\":\"2020-01-10\",\"phone\":\"+84901000004\"}" "Phạm Thị Dung (Trưởng phòng Nhân sự) [đa công ty]"
    HL_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Giang\",\"lastName\":\"Hoàng Thị\",\"email\":\"giang.hoang@hoanglong.vn\",\"employeeCode\":\"HL-005\",\"position\":\"Cán bộ An toàn\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_WS_AT\",\"hiredDate\":\"2023-06-01\",\"phone\":\"+84901000005\"}" "Hoàng Thị Giang (Cán bộ An toàn)"
    HL_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Hùng\",\"lastName\":\"Vũ Văn\",\"email\":\"hung.vu@hoanglong.vn\",\"employeeCode\":\"HL-006\",\"position\":\"Trưởng nhóm Xây dựng\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_WS_KT\",\"hiredDate\":\"2022-02-15\",\"phone\":\"+84901000006\"}" "Vũ Văn Hùng (Trưởng nhóm Xây dựng)"
    HL_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Khôi\",\"lastName\":\"Đặng Văn\",\"email\":\"khoi.dang@hoanglong.vn\",\"employeeCode\":\"HL-007\",\"position\":\"Kỹ sư cao cấp\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_WS_KT\",\"hiredDate\":\"2021-11-30\",\"phone\":\"+84901000007\"}" "Đặng Văn Khôi (Kỹ sư cao cấp)"
    HL_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Lan\",\"lastName\":\"Bùi Thị\",\"email\":\"lan.bui@hoanglong.vn\",\"employeeCode\":\"HL-008\",\"position\":\"Chuyên viên Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$HL_WS_NS\",\"hiredDate\":\"2024-01-15\",\"phone\":\"+84901000008\"}" "Bùi Thị Lan (Chuyên viên Nhân sự)"
    HL_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Minh\",\"lastName\":\"Ngô Văn\",\"email\":\"minh.ngo@hoanglong.vn\",\"employeeCode\":\"HL-009\",\"position\":\"Thanh tra công trường\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_WS_AT\",\"hiredDate\":\"2023-08-01\",\"phone\":\"+84901000009\"}" "Ngô Văn Minh (Thanh tra công trường)"
    HL_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Ngọc\",\"lastName\":\"Đỗ Thị\",\"email\":\"ngoc.do@hoanglong.vn\",\"employeeCode\":\"HL-010\",\"position\":\"Điều phối viên dự án\",\"department\":\"Vận hành\",\"departmentId\":\"$HL_WS_VH\",\"hiredDate\":\"2022-04-20\",\"phone\":\"+84901000010\"}" "Đỗ Thị Ngọc (Điều phối viên dự án)"
    HL_E10="$EMP_ID"
    mk_employee "{\"firstName\":\"Phúc\",\"lastName\":\"Phan Văn\",\"email\":\"phuc.phan@hoanglong.vn\",\"employeeCode\":\"HL-011\",\"position\":\"Công nhân xây dựng\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_WS_KT\",\"hiredDate\":\"2024-03-01\",\"phone\":\"+84901000011\"}" "Phan Văn Phúc (Công nhân xây dựng)"
    HL_E11="$EMP_ID"
    mk_employee "{\"firstName\":\"Quỳnh\",\"lastName\":\"Trịnh Thị\",\"email\":\"quynh.trinh@hoanglong.vn\",\"employeeCode\":\"HL-012\",\"position\":\"Kỹ sư QA\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_WS_AT\",\"hiredDate\":\"2023-11-15\",\"phone\":\"+84901000012\"}" "Trịnh Thị Quỳnh (Kỹ sư QA) [terminated]"
    HL_E12="$EMP_ID"
    mk_status "$HL_E12" "terminated"
    mk_employee "{\"firstName\":\"Bảo\",\"lastName\":\"Cao Văn\",\"email\":\"bao.cao@hoanglong.vn\",\"employeeCode\":\"HL-013\",\"position\":\"Kiến trúc sư\",\"department\":\"Thiết kế\",\"departmentId\":\"$HL_WS_TK\",\"hiredDate\":\"2023-05-01\",\"phone\":\"+84901000013\"}" "Cao Văn Bảo (Kiến trúc sư)"
    HL_E13="$EMP_ID"
    mk_employee "{\"firstName\":\"Châu\",\"lastName\":\"Lý Thị\",\"email\":\"chau.ly@hoanglong.vn\",\"employeeCode\":\"HL-014\",\"position\":\"Kế toán dự án\",\"department\":\"Tài chính - Kế toán\",\"departmentId\":\"$HL_WS_TC\",\"hiredDate\":\"2022-10-01\",\"phone\":\"+84901000014\"}" "Lý Thị Châu (Kế toán dự án)"
    HL_E14="$EMP_ID"
    mk_employee "{\"firstName\":\"Duy\",\"lastName\":\"Mai Văn\",\"email\":\"duy.mai@hoanglong.vn\",\"employeeCode\":\"HL-015\",\"position\":\"Nhân viên Mua hàng\",\"department\":\"Mua hàng - Vật tư\",\"departmentId\":\"$HL_WS_MH\",\"hiredDate\":\"2024-05-01\",\"phone\":\"+84901000015\"}" "Mai Văn Duy (Nhân viên Mua hàng)"
    HL_E15="$EMP_ID"

    echo "  Phân công (Hà Nội — Ca sáng):"
    for emp in "$HL_E1" "$HL_E5" "$HL_E6" "$HL_E7" "$HL_E9" "$HL_E13"; do
        [ -n "$emp" ] && mk_assignment "$HL_ID" "$HL_HN" "$emp" "$HL_HN_SANG" "worker" "2026-01-01"
    done
    [ -n "$HL_E2" ] && mk_assignment "$HL_ID" "$HL_HN" "$HL_E2" "$HL_HN_SANG" "supervisor" "2026-01-01"

    echo "  Phân công (Hà Nội — Ca chiều):"
    [ -n "$HL_E11" ] && [ -n "$HL_HN_CHIEU" ] && mk_assignment "$HL_ID" "$HL_HN" "$HL_E11" "$HL_HN_CHIEU" "worker" "2026-01-01"

    echo "  Phân công (TP.HCM — Ca sáng):"
    for emp in "$HL_E4" "$HL_E8" "$HL_E10" "$HL_E14" "$HL_E15"; do
        [ -n "$emp" ] && [ -n "$HL_HCM_SANG" ] && mk_assignment "$HL_ID" "$HL_HCM" "$emp" "$HL_HCM_SANG" "worker" "2026-01-01"
    done

    echo "  Thành viên workspace:"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E1" "lead"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E6" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E7" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_NS" "$HL_E4" "manager"
    mk_workspace_member "$HL_ID" "$HL_WS_NS" "$HL_E8" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_TEAM_HN" "$HL_E9" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_TK" "$HL_E13" "lead"
    mk_workspace_member "$HL_ID" "$HL_WS_TC" "$HL_E14" "lead"
    mk_workspace_member "$HL_ID" "$HL_WS_MH" "$HL_E15" "member"

    echo "  IP whitelist:"
    mk_ip_whitelist "$HL_ID" "203.113.128.0/24" "Văn phòng Hà Nội" "all"
    mk_ip_whitelist "$HL_ID" "14.161.0.0/16" "VPN nội bộ" "api"

    echo "  Lời mời nhân viên:"
    mk_invitation "$HL_ID" "hoa.nguyen.moi@hoanglong.vn" "Hoa" "Nguyễn Thị" "+84906111111"
    mk_invitation "$HL_ID" "kiet.tran.moi@hoanglong.vn" "Kiệt" "Trần Văn" "+84906111112"
    KIET_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$HL_ID" "$KIET_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$HL_ID" "location_only" "2" "300"

    # Bổ sung độ sâu (spec dữ liệu mẫu mục 2.3): thêm 15 nhân viên nữa để đủ 10-15 mẫu/vai
    # trò khi cộng với 15 người ở trên (tổng ~30), 1 nhóm con cấp 3 trong workspace tree,
    # và 1 lời mời còn "pending" thật (2 lời mời phía trên đã bị hủy ngay — thiếu case còn
    # hiệu lực để tester tự bấm accept).
    echo "  Nhân viên bổ sung (đủ độ sâu 10-15 mẫu/vai trò):"
    # Đủ 10 nhóm nghiệp vụ (sample-data-requirements-v2.md mục 6.1) — thay cho pool vị trí
    # chung chung trước đây. 15 người đặt tên tay ở trên đã phủ Admin/Nhân sự/Trưởng phòng/Kỹ
    # thuật/An toàn, phần dưới bổ sung đúng các nhóm còn thiếu.
    mk_employee_group "$HL_ID" "$HL_WS_VH" "Vận hành" "$HL_HN" "$HL_HN_SANG" "HL-QLCT" 1 2 "Quản lý công trình" "supervisor" "established"
    mk_employee_group "$HL_ID" "$HL_WS_KT" "Kỹ thuật" "$HL_HCM" "$HL_HCM_SANG" "HL-TBP" 1 2 "Trưởng bộ phận thi công" "supervisor" "established"
    mk_employee_group "$HL_ID" "$HL_WS_AT" "An toàn & Chất lượng" "$HL_DN" "$HL_DN_TC" "HL-GS" 1 3 "Giám sát công trình" "supervisor" "established"
    mk_employee_group "$HL_ID" "$HL_WS_KT" "Kỹ thuật" "$HL_HN" "$HL_HN_SANG" "HL-CT" 1 8 "Công nhân xây dựng" "worker" "established"
    HL_MULTISITE_E="$EMP_ID"
    mk_employee_group "$HL_ID" "$HL_WS_TC" "Tài chính - Kế toán" "$HL_HCM" "$HL_HCM_SANG" "HL-VP" 1 4 "Nhân viên văn phòng" "worker" "established"
    mk_employee_group "$HL_ID" "$HL_WS_VH" "Vận hành" "$HL_HN" "$HL_HN_CHIEU" "HL-TV" 1 3 "Nhân viên thời vụ" "worker" "seasonal"
    mk_employee_group "$HL_ID" "$HL_WS_KT" "Kỹ thuật" "$HL_HN" "$HL_HN_SANG" "HL-TT" 1 3 "Nhân viên thử việc" "worker" "probation"

    echo "  Workspace lồng 3 cấp (Công ty → Đội → Nhóm) — 3 nhánh:"
    mk_workspace "$HL_ID" "Nhóm Kỹ thuật Ca sáng" "team" "Nhóm nhỏ trong Đội Kỹ thuật Hà Nội, trực ca sáng" "$HL_WS_TEAM_HN"
    mk_workspace "$HL_ID" "Nhóm Hành chính Lễ tân" "team" "Nhóm nhỏ trong Đội Hành chính TP.HCM" "$HL_WS_TEAM_HCM"
    mk_workspace "$HL_ID" "Nhóm Cơ điện M&E" "team" "Nhóm nhỏ trong Đội Cơ điện" "$HL_WS_TEAM_CD"

    echo "  Nhân viên làm việc tại NHIỀU công trình cùng lúc (spec v2 mục 6.3):"
    [ -n "$HL_MULTISITE_E" ] && [ -n "$HL_HCM" ] && [ -n "$HL_HCM_SANG" ] && \
        mk_assignment "$HL_ID" "$HL_HCM" "$HL_MULTISITE_E" "$HL_HCM_SANG" "worker" "2026-01-01"

    echo "  Lời mời còn hiệu lực (chưa accept/hủy):"
    mk_invitation "$HL_ID" "phuong.dang.choloi@hoanglong.vn" "Phượng" "Đặng Thị" "+84906111115"
fi
echo ""

# ── Tenant 2: Công ty TNHH Sản xuất Bình Minh (DEEP — 15 employees, 12 sites, 12 workspaces) ──

echo "=== Tenant 2: Công ty TNHH Sản xuất Bình Minh (beta-industries) ==="
mk_tenant "Công ty TNHH Sản xuất Bình Minh" "beta-industries" "giamdoc.binhminh@gmail.com" \
    ',"industry":"Sản xuất","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Sản xuất"
BM_ID="$TENANT_ID"

if [ -n "$BM_ID" ]; then
    # Pro (not Basic) — Basic caps at 5 sites, and this tenant is deliberately one of
    # the 3 "deep" demo companies with 12+ sites; a 15-employee multi-site manufacturer
    # is realistically past Basic tier anyway.
    [ -n "$PLAN_PRO_ID" ] && mk_subscription "$PLAN_PRO_ID" "pro"

    echo "  Workspaces (phòng ban):"
    mk_workspace "$BM_ID" "Sản xuất" "department" "Dây chuyền sản xuất và lắp ráp"; BM_WS_SX="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Kiểm soát chất lượng" "department" "Đảm bảo và kiểm định chất lượng"; BM_WS_QC="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Bảo trì" "department" "Bảo trì thiết bị và nhà xưởng"; BM_WS_BT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "An toàn" "department" "An toàn và sức khỏe lao động"; BM_WS_AT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Kho vận" "department" "Nhập/xuất kho và tồn kho"; BM_WS_KHO="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Đội Sản xuất Ca ngày" "team" "Đội sản xuất ca ngày tại nhà máy chính"; BM_WS_TEAM_A="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Đội Kho vận" "team" "Đội kho vận và hậu cần"; BM_WS_TEAM_KHO="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Ban Giám đốc" "department" "Ban lãnh đạo nhà máy"; BM_WS_BGD="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Nhân sự" "department" "Tuyển dụng và chính sách nhân sự"; BM_WS_NS="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Kỹ thuật quy trình" "department" "Cải tiến quy trình sản xuất"; BM_WS_KTQT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Đội Bảo trì Cơ điện" "team" "Đội bảo trì máy móc và điện"; BM_WS_TEAM_BT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Xuất nhập khẩu" "department" "Thủ tục xuất nhập khẩu nguyên liệu"; BM_WS_XNK="$WORKSPACE_ID"

    echo "  Sites (nhà máy/kho):"
    mk_site "$BM_ID" "Nhà máy Bình Minh" "BM-MAIN" "KCN Tân Bình, Bình Dương" "10.9350" "106.6900" "Asia/Ho_Chi_Minh"
    BM_PLANT="$SITE_ID"
    mk_site "$BM_ID" "Kho Bình Minh A" "BM-WH-A" "Lô B12, KCN Sóng Thần 2, Bình Dương" "10.8250" "106.7100" "Asia/Ho_Chi_Minh"
    BM_WH_A="$SITE_ID"
    mk_extra_sites "$BM_ID" "Nhà máy/Kho Bình Minh" "BM" 3 10

    echo "  Ca làm việc (Nhà máy):"
    BM_PLANT_NGAY="" BM_PLANT_TOI="" BM_PLANT_DEM=""
    if [ -n "$BM_PLANT" ]; then
        mk_shift "$BM_ID" "$BM_PLANT" "Ca ngày" "06:00" "14:00" "false" "true"; BM_PLANT_NGAY="$SHIFT_ID"
        mk_shift "$BM_ID" "$BM_PLANT" "Ca tối" "14:00" "22:00" "false" "false"; BM_PLANT_TOI="$SHIFT_ID"
        mk_shift "$BM_ID" "$BM_PLANT" "Ca đêm" "22:00" "06:00" "true" "false"; BM_PLANT_DEM="$SHIFT_ID"
    fi
    echo "  Ca làm việc (Kho A):"
    BM_WH_NGAY="" BM_WH_TOI=""
    if [ -n "$BM_WH_A" ]; then
        mk_shift "$BM_ID" "$BM_WH_A" "Ca ngày" "07:00" "15:00" "false" "true"; BM_WH_NGAY="$SHIFT_ID"
        mk_shift "$BM_ID" "$BM_WH_A" "Ca tối" "15:00" "23:00" "false" "false"; BM_WH_TOI="$SHIFT_ID"
    fi

    echo "  Nhân viên:"
    mk_employee "{\"firstName\":\"Xuân\",\"lastName\":\"Đỗ Thị\",\"email\":\"xuan.do@binhminh.vn\",\"employeeCode\":\"BM-001\",\"position\":\"Tổ trưởng sản xuất\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_WS_SX\",\"hiredDate\":\"2024-01-01\",\"phone\":\"+84902000001\"}" "Đỗ Thị Xuân (Tổ trưởng sản xuất)"
    BM_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Yên\",\"lastName\":\"Nguyễn Văn\",\"email\":\"yen.nguyen@binhminh.vn\",\"employeeCode\":\"BM-002\",\"position\":\"Nhân viên kiểm định chất lượng\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_WS_QC\",\"hiredDate\":\"2023-06-15\",\"phone\":\"+84902000002\"}" "Nguyễn Văn Yên (Nhân viên kiểm định chất lượng)"
    BM_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Dung\",\"lastName\":\"Phạm Thị\",\"email\":\"dung.pham@binhminh.vn\",\"employeeCode\":\"BM-003\",\"position\":\"Giám sát công trường\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_WS_SX\",\"hiredDate\":\"2022-09-01\",\"phone\":\"+84902000003\"}" "Phạm Thị Dung (Giám sát công trường) [đa công ty]"
    BM_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Lê Văn\",\"email\":\"dat.le@binhminh.vn\",\"employeeCode\":\"BM-004\",\"position\":\"Chuyên viên QA cao cấp\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_WS_QC\",\"hiredDate\":\"2021-04-20\",\"phone\":\"+84902000004\"}" "Lê Văn Đạt (Chuyên viên QA cao cấp)"
    BM_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Diễm\",\"lastName\":\"Phạm Thị\",\"email\":\"diem.pham@binhminh.vn\",\"employeeCode\":\"BM-005\",\"position\":\"Công nhân vận hành máy\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_WS_SX\",\"hiredDate\":\"2023-12-01\",\"phone\":\"+84902000005\"}" "Phạm Thị Diễm (Công nhân vận hành máy)"
    BM_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Kiên\",\"lastName\":\"Vũ Văn\",\"email\":\"kien.vu@binhminh.vn\",\"employeeCode\":\"BM-006\",\"position\":\"Kỹ sư bảo trì\",\"department\":\"Bảo trì\",\"departmentId\":\"$BM_WS_BT\",\"hiredDate\":\"2022-07-10\",\"phone\":\"+84902000006\"}" "Vũ Văn Kiên (Kỹ sư bảo trì)"
    BM_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Loan\",\"lastName\":\"Hoàng Thị\",\"email\":\"loan.hoang@binhminh.vn\",\"employeeCode\":\"BM-007\",\"position\":\"Điều phối An toàn\",\"department\":\"An toàn\",\"departmentId\":\"$BM_WS_AT\",\"hiredDate\":\"2023-03-15\",\"phone\":\"+84902000007\"}" "Hoàng Thị Loan (Điều phối An toàn)"
    BM_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Nam\",\"lastName\":\"Bùi Văn\",\"email\":\"nam.bui@binhminh.vn\",\"employeeCode\":\"BM-008\",\"position\":\"Tổ trưởng dây chuyền\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_WS_SX\",\"hiredDate\":\"2022-11-01\",\"phone\":\"+84902000008\"}" "Bùi Văn Nam (Tổ trưởng dây chuyền)"
    BM_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Oanh\",\"lastName\":\"Ngô Thị\",\"email\":\"oanh.ngo@binhminh.vn\",\"employeeCode\":\"BM-009\",\"position\":\"Kỹ sư quy trình\",\"department\":\"Kỹ thuật quy trình\",\"departmentId\":\"$BM_WS_KTQT\",\"hiredDate\":\"2024-02-15\",\"phone\":\"+84902000009\"}" "Ngô Thị Oanh (Kỹ sư quy trình)"
    BM_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Phát\",\"lastName\":\"Đặng Văn\",\"email\":\"phat.dang@binhminh.vn\",\"employeeCode\":\"BM-010\",\"position\":\"Chuyên viên phân tích chất lượng\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_WS_QC\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84902000010\"}" "Đặng Văn Phát (Chuyên viên phân tích chất lượng)"
    BM_E10="$EMP_ID"
    mk_employee "{\"firstName\":\"Quốc\",\"lastName\":\"Hồ Văn\",\"email\":\"quoc.ho@binhminh.vn\",\"employeeCode\":\"BM-011\",\"position\":\"Thủ kho\",\"department\":\"Kho vận\",\"departmentId\":\"$BM_WS_KHO\",\"hiredDate\":\"2022-05-01\",\"phone\":\"+84902000011\"}" "Hồ Văn Quốc (Thủ kho)"
    BM_E11="$EMP_ID"
    mk_employee "{\"firstName\":\"Reo\",\"lastName\":\"Dương Văn\",\"email\":\"reo.duong@binhminh.vn\",\"employeeCode\":\"BM-012\",\"position\":\"Nhân viên xuất nhập khẩu\",\"department\":\"Xuất nhập khẩu\",\"departmentId\":\"$BM_WS_XNK\",\"hiredDate\":\"2023-10-01\",\"phone\":\"+84902000012\"}" "Dương Văn Reo (Nhân viên xuất nhập khẩu)"
    BM_E12="$EMP_ID"
    mk_employee "{\"firstName\":\"Sương\",\"lastName\":\"Đinh Thị\",\"email\":\"suong.dinh@binhminh.vn\",\"employeeCode\":\"BM-013\",\"position\":\"Giám đốc Nhà máy\",\"department\":\"Ban Giám đốc\",\"departmentId\":\"$BM_WS_BGD\",\"hiredDate\":\"2019-03-01\",\"phone\":\"+84902000013\"}" "Đinh Thị Sương (Giám đốc Nhà máy)"
    BM_E13="$EMP_ID"
    mk_employee "{\"firstName\":\"Toàn\",\"lastName\":\"Lý Văn\",\"email\":\"toan.ly@binhminh.vn\",\"employeeCode\":\"BM-014\",\"position\":\"Chuyên viên Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$BM_WS_NS\",\"hiredDate\":\"2024-04-01\",\"phone\":\"+84902000014\"}" "Lý Văn Toàn (Chuyên viên Nhân sự) [terminated]"
    BM_E14="$EMP_ID"
    mk_status "$BM_E14" "terminated"
    mk_employee "{\"firstName\":\"Uyên\",\"lastName\":\"Trịnh Thị\",\"email\":\"uyen.trinh@binhminh.vn\",\"employeeCode\":\"BM-015\",\"position\":\"Kỹ thuật viên bảo trì\",\"department\":\"Bảo trì\",\"departmentId\":\"$BM_WS_BT\",\"hiredDate\":\"2023-07-15\",\"phone\":\"+84902000015\"}" "Trịnh Thị Uyên (Kỹ thuật viên bảo trì) [inactive]"
    BM_E15="$EMP_ID"
    mk_status "$BM_E15" "inactive"

    echo "  Phân công (Nhà máy — Ca ngày):"
    for emp in "$BM_E1" "$BM_E5" "$BM_E7" "$BM_E9" "$BM_E13"; do
        [ -n "$emp" ] && [ -n "$BM_PLANT_NGAY" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$emp" "$BM_PLANT_NGAY" "worker" "2026-01-01"
    done
    [ -n "$BM_E3" ] && [ -n "$BM_PLANT_NGAY" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$BM_E3" "$BM_PLANT_NGAY" "supervisor" "2026-01-01"

    echo "  Phân công (Nhà máy — Ca tối):"
    for emp in "$BM_E6" "$BM_E8"; do
        [ -n "$emp" ] && [ -n "$BM_PLANT_TOI" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$emp" "$BM_PLANT_TOI" "worker" "2026-01-01"
    done

    echo "  Phân công (Kho A — Ca ngày):"
    for emp in "$BM_E2" "$BM_E4" "$BM_E10" "$BM_E11" "$BM_E12"; do
        [ -n "$emp" ] && [ -n "$BM_WH_NGAY" ] && mk_assignment "$BM_ID" "$BM_WH_A" "$emp" "$BM_WH_NGAY" "worker" "2026-01-01"
    done

    echo "  Thành viên workspace:"
    mk_workspace_member "$BM_ID" "$BM_WS_SX" "$BM_E1" "lead"
    mk_workspace_member "$BM_ID" "$BM_WS_SX" "$BM_E3" "manager"
    mk_workspace_member "$BM_ID" "$BM_WS_QC" "$BM_E2" "member"
    mk_workspace_member "$BM_ID" "$BM_WS_QC" "$BM_E4" "lead"
    mk_workspace_member "$BM_ID" "$BM_WS_TEAM_KHO" "$BM_E11" "member"
    mk_workspace_member "$BM_ID" "$BM_WS_BGD" "$BM_E13" "manager"
    mk_workspace_member "$BM_ID" "$BM_WS_XNK" "$BM_E12" "lead"

    echo "  Lời mời nhân viên:"
    mk_invitation "$BM_ID" "yen.pham.moi@binhminh.vn" "Yến" "Phạm Thị" "+84906222221"
    mk_invitation "$BM_ID" "hung.le.moi@binhminh.vn" "Hùng" "Lê Văn" "+84906222222"
    HUNG_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$BM_ID" "$HUNG_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$BM_ID" "location_face" "3" "240"

    echo "  Nhân viên bổ sung (đủ độ sâu 10-15 mẫu/vai trò):"
    mk_employee_group "$BM_ID" "$BM_WS_SX" "Sản xuất" "$BM_PLANT" "$BM_PLANT_NGAY" "BM-QLCT" 1 2 "Quản lý công trình" "supervisor" "established"
    mk_employee_group "$BM_ID" "$BM_WS_BT" "Bảo trì" "$BM_PLANT" "$BM_PLANT_NGAY" "BM-TBP" 1 2 "Trưởng bộ phận sản xuất" "supervisor" "established"
    mk_employee_group "$BM_ID" "$BM_WS_AT" "An toàn" "$BM_WH_A" "$BM_WH_NGAY" "BM-GS" 1 3 "Giám sát công trình" "supervisor" "established"
    mk_employee_group "$BM_ID" "$BM_WS_SX" "Sản xuất" "$BM_PLANT" "$BM_PLANT_NGAY" "BM-CT" 1 8 "Công nhân vận hành máy" "worker" "established"
    BM_MULTISITE_E="$EMP_ID"
    mk_employee_group "$BM_ID" "$BM_WS_KHO" "Kho vận" "$BM_WH_A" "$BM_WH_NGAY" "BM-VP" 1 4 "Nhân viên văn phòng" "worker" "established"
    mk_employee_group "$BM_ID" "$BM_WS_SX" "Sản xuất" "$BM_PLANT" "$BM_PLANT_TOI" "BM-TV" 1 3 "Nhân viên thời vụ" "worker" "seasonal"
    mk_employee_group "$BM_ID" "$BM_WS_SX" "Sản xuất" "$BM_PLANT" "$BM_PLANT_NGAY" "BM-TT" 1 3 "Nhân viên thử việc" "worker" "probation"

    echo "  Workspace lồng 3 cấp (Công ty → Đội → Nhóm) — 3 nhánh:"
    mk_workspace "$BM_ID" "Nhóm Vận hành Máy CNC" "team" "Nhóm nhỏ trong Đội Sản xuất Ca ngày" "$BM_WS_TEAM_A"
    mk_workspace "$BM_ID" "Nhóm Xuất kho" "team" "Nhóm nhỏ trong Đội Kho vận" "$BM_WS_TEAM_KHO"
    mk_workspace "$BM_ID" "Nhóm Bảo trì Điện" "team" "Nhóm nhỏ trong Đội Bảo trì Cơ điện" "$BM_WS_TEAM_BT"

    echo "  Role tùy chỉnh cấp tenant:"
    mk_tenant_custom_role "$BM_ID" "Trưởng ca đêm" \
        "Vai trò tùy chỉnh của Bình Minh — chỉ xem chấm công và duyệt Face ID ca đêm, không có quyền xóa nhân viên/site" \
        "['checkins','face_id']"

    echo "  Nhân viên làm việc tại NHIỀU công trình cùng lúc (spec v2 mục 6.3):"
    [ -n "$BM_MULTISITE_E" ] && [ -n "$BM_WH_A" ] && [ -n "$BM_WH_NGAY" ] && \
        mk_assignment "$BM_ID" "$BM_WH_A" "$BM_MULTISITE_E" "$BM_WH_NGAY" "worker" "2026-01-01"

    echo "  Lời mời còn hiệu lực (chưa accept/hủy):"
    mk_invitation "$BM_ID" "khoi.trinh.choloi@binhminh.vn" "Khôi" "Trịnh Văn" "+84906222223"
fi
echo ""

# ── Tenant 3: Công ty CP Logistics Phương Nam (DEEP — 15 employees, 13 sites, 13 workspaces) ──

echo "=== Tenant 3: Công ty CP Logistics Phương Nam (gamma-logistics) ==="
mk_tenant "Công ty CP Logistics Phương Nam" "gamma-logistics" "quang.phuongnam@gmail.com" \
    ',"industry":"Logistics","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Logistics"
PN_ID="$TENANT_ID"

if [ -n "$PN_ID" ]; then
    [ -n "$PLAN_ENTERPRISE_ID" ] && mk_subscription "$PLAN_ENTERPRISE_ID" "enterprise" "YEARLY"

    echo "  Workspaces (phòng ban):"
    mk_workspace "$PN_ID" "Điều hành" "department" "Điều hành và điều phối logistics"; PN_WS_DH="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Quản lý Đội xe" "department" "Quản lý lái xe và phương tiện"; PN_WS_XE="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Kho vận" "department" "Kho bãi và tồn kho"; PN_WS_KHO="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Chất lượng" "department" "Kiểm soát chất lượng và tuân thủ"; PN_WS_CL="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Nhân sự" "department" "Nhân sự và quan hệ lao động"; PN_WS_NS="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội Kho Hà Nội" "team" "Đội làm việc tại Kho vận Phương Nam - Bắc"; PN_WS_TEAM_HN="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội Kho TP.HCM" "team" "Đội làm việc tại Kho vận Phương Nam - Nam"; PN_WS_TEAM_HCM="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Ban Giám đốc" "department" "Ban lãnh đạo công ty"; PN_WS_BGD="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Kinh doanh" "department" "Phát triển khách hàng và hợp đồng vận tải"; PN_WS_KD="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Công nghệ thông tin" "department" "Hệ thống quản lý vận tải (TMS)"; PN_WS_IT="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội xe đường dài" "team" "Đội lái xe tuyến liên tỉnh"; PN_WS_TEAM_XE="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Tài chính" "department" "Tài chính và công nợ vận tải"; PN_WS_TC="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội Trung tâm điều phối" "team" "Đội điều phối tuyến tại trung tâm Đà Nẵng"; PN_WS_TEAM_HUB="$WORKSPACE_ID"

    echo "  Sites (kho/trung tâm):"
    mk_site "$PN_ID" "Kho vận Phương Nam - Bắc" "PN-N" "KCN Nội Bài, Sóc Sơn, Hà Nội" "21.2210" "105.7950" "Asia/Ho_Chi_Minh"
    PN_N="$SITE_ID"
    mk_site "$PN_ID" "Kho vận Phương Nam - Nam" "PN-S" "KCN Hiệp Phước, Nhà Bè, TP.HCM" "10.6550" "106.7450" "Asia/Ho_Chi_Minh"
    PN_S="$SITE_ID"
    mk_site "$PN_ID" "Trung tâm điều phối Phương Nam" "PN-HUB" "27 Trường Chinh, Thanh Khê, Đà Nẵng" "16.0710" "108.1990" "Asia/Ho_Chi_Minh"
    PN_HUB="$SITE_ID"
    mk_extra_sites "$PN_ID" "Kho vận Phương Nam" "PN" 4 10

    echo "  Ca làm việc (Kho Bắc):"
    PN_N_SANG="" PN_N_CHIEU="" PN_N_DEM=""
    if [ -n "$PN_N" ]; then
        mk_shift "$PN_ID" "$PN_N" "Ca sáng" "06:00" "14:00" "false" "true"; PN_N_SANG="$SHIFT_ID"
        mk_shift "$PN_ID" "$PN_N" "Ca chiều" "14:00" "22:00" "false" "false"; PN_N_CHIEU="$SHIFT_ID"
        mk_shift "$PN_ID" "$PN_N" "Ca đêm" "22:00" "06:00" "true" "false"; PN_N_DEM="$SHIFT_ID"
    fi
    echo "  Ca làm việc (Kho Nam):"
    PN_S_SANG="" PN_S_CHIEU=""
    if [ -n "$PN_S" ]; then
        mk_shift "$PN_ID" "$PN_S" "Ca sáng" "06:00" "14:00" "false" "true"; PN_S_SANG="$SHIFT_ID"
        mk_shift "$PN_ID" "$PN_S" "Ca chiều" "14:00" "22:00" "false" "false"; PN_S_CHIEU="$SHIFT_ID"
    fi
    echo "  Ca làm việc (Trung tâm điều phối):"
    PN_HUB_TC=""
    if [ -n "$PN_HUB" ]; then
        mk_shift "$PN_ID" "$PN_HUB" "Ca tiêu chuẩn" "08:00" "17:00" "false" "true"; PN_HUB_TC="$SHIFT_ID"
        mk_shift "$PN_ID" "$PN_HUB" "Ca mở rộng" "06:00" "18:00" "false" "true"
    fi

    echo "  Nhân viên:"
    mk_employee "{\"firstName\":\"Quang\",\"lastName\":\"Trịnh Văn\",\"email\":\"quang.trinh@phuongnam.vn\",\"employeeCode\":\"PN-001\",\"position\":\"Giám đốc Logistics\",\"department\":\"Ban Giám đốc\",\"departmentId\":\"$PN_WS_BGD\",\"hiredDate\":\"2020-05-01\",\"phone\":\"+84903000001\"}" "Trịnh Văn Quang (Giám đốc Logistics)"
    PN_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Hồng\",\"lastName\":\"Lý Thị\",\"email\":\"hong.ly@phuongnam.vn\",\"employeeCode\":\"PN-002\",\"position\":\"Trưởng phòng Vận hành\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_WS_DH\",\"hiredDate\":\"2021-02-15\",\"phone\":\"+84903000002\"}" "Lý Thị Hồng (Trưởng phòng Vận hành)"
    PN_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Sơn\",\"lastName\":\"Mai Văn\",\"email\":\"son.mai@phuongnam.vn\",\"employeeCode\":\"PN-003\",\"position\":\"Điều phối viên\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_WS_DH\",\"hiredDate\":\"2022-08-01\",\"phone\":\"+84903000003\"}" "Mai Văn Sơn (Điều phối viên)"
    PN_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Thảo\",\"lastName\":\"Đinh Thị\",\"email\":\"thao.dinh@phuongnam.vn\",\"employeeCode\":\"PN-004\",\"position\":\"Trưởng đội xe\",\"department\":\"Quản lý Đội xe\",\"departmentId\":\"$PN_WS_XE\",\"hiredDate\":\"2021-10-20\",\"phone\":\"+84903000004\"}" "Đinh Thị Thảo (Trưởng đội xe)"
    PN_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Tùng\",\"lastName\":\"Cao Văn\",\"email\":\"tung.cao@phuongnam.vn\",\"employeeCode\":\"PN-005\",\"position\":\"Lái xe cao cấp\",\"department\":\"Quản lý Đội xe\",\"departmentId\":\"$PN_WS_XE\",\"hiredDate\":\"2020-11-01\",\"phone\":\"+84903000005\"}" "Cao Văn Tùng (Lái xe cao cấp)"
    PN_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Uyên\",\"lastName\":\"Lương Thị\",\"email\":\"uyen.luong@phuongnam.vn\",\"employeeCode\":\"PN-006\",\"position\":\"Lái xe\",\"department\":\"Quản lý Đội xe\",\"departmentId\":\"$PN_WS_XE\",\"hiredDate\":\"2023-01-15\",\"phone\":\"+84903000006\"}" "Lương Thị Uyên (Lái xe)"
    PN_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Vinh\",\"lastName\":\"Huỳnh Văn\",\"email\":\"vinh.huynh@phuongnam.vn\",\"employeeCode\":\"PN-007\",\"position\":\"Tổ trưởng kho\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_WS_KHO\",\"hiredDate\":\"2022-04-01\",\"phone\":\"+84903000007\"}" "Huỳnh Văn Vinh (Tổ trưởng kho)"
    PN_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Xuân\",\"lastName\":\"Tô Thị\",\"email\":\"xuan.to@phuongnam.vn\",\"employeeCode\":\"PN-008\",\"position\":\"Nhân viên kiểm tra chất lượng\",\"department\":\"Chất lượng\",\"departmentId\":\"$PN_WS_CL\",\"hiredDate\":\"2023-07-01\",\"phone\":\"+84903000008\"}" "Tô Thị Xuân (Nhân viên kiểm tra chất lượng)"
    PN_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Trương Văn\",\"email\":\"dat.truong@phuongnam.vn\",\"employeeCode\":\"PN-009\",\"position\":\"Nhân viên điều phối tuyến\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_WS_DH\",\"hiredDate\":\"2024-01-10\",\"phone\":\"+84903000009\"}" "Trương Văn Đạt (Nhân viên điều phối tuyến) [đa công ty]"
    PN_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Yến\",\"lastName\":\"Đào Thị\",\"email\":\"yen.dao@phuongnam.vn\",\"employeeCode\":\"PN-010\",\"position\":\"Trưởng phòng Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$PN_WS_NS\",\"hiredDate\":\"2021-06-01\",\"phone\":\"+84903000010\"}" "Đào Thị Yến (Trưởng phòng Nhân sự)"
    PN_E10="$EMP_ID"
    mk_employee "{\"firstName\":\"Bảo\",\"lastName\":\"Vương Văn\",\"email\":\"bao.vuong@phuongnam.vn\",\"employeeCode\":\"PN-011\",\"position\":\"Nhân viên vận hành xe nâng\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_WS_KHO\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84903000011\"}" "Vương Văn Bảo (Nhân viên vận hành xe nâng)"
    PN_E11="$EMP_ID"
    mk_employee "{\"firstName\":\"Cẩm\",\"lastName\":\"Chu Thị\",\"email\":\"cam.chu@phuongnam.vn\",\"employeeCode\":\"PN-012\",\"position\":\"Chuyên viên phân tích tồn kho\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_WS_KHO\",\"hiredDate\":\"2024-02-01\",\"phone\":\"+84903000012\"}" "Chu Thị Cẩm (Chuyên viên phân tích tồn kho)"
    PN_E12="$EMP_ID"
    mk_employee "{\"firstName\":\"Danh\",\"lastName\":\"Phan Văn\",\"email\":\"danh.phan@phuongnam.vn\",\"employeeCode\":\"PN-013\",\"position\":\"Nhân viên Kinh doanh\",\"department\":\"Kinh doanh\",\"departmentId\":\"$PN_WS_KD\",\"hiredDate\":\"2022-12-01\",\"phone\":\"+84903000013\"}" "Phan Văn Danh (Nhân viên Kinh doanh)"
    PN_E13="$EMP_ID"
    mk_employee "{\"firstName\":\"Én\",\"lastName\":\"Bùi Thị\",\"email\":\"en.bui@phuongnam.vn\",\"employeeCode\":\"PN-014\",\"position\":\"Kỹ sư phần mềm TMS\",\"department\":\"Công nghệ thông tin\",\"departmentId\":\"$PN_WS_IT\",\"hiredDate\":\"2023-04-01\",\"phone\":\"+84903000014\"}" "Bùi Thị Én (Kỹ sư phần mềm TMS) [inactive]"
    PN_E14="$EMP_ID"
    mk_status "$PN_E14" "inactive"
    mk_employee "{\"firstName\":\"Phong\",\"lastName\":\"Đỗ Văn\",\"email\":\"phong.do@phuongnam.vn\",\"employeeCode\":\"PN-015\",\"position\":\"Kế toán công nợ\",\"department\":\"Tài chính\",\"departmentId\":\"$PN_WS_TC\",\"hiredDate\":\"2021-08-15\",\"phone\":\"+84903000015\"}" "Đỗ Văn Phong (Kế toán công nợ)"
    PN_E15="$EMP_ID"

    echo "  Phân công (Kho Bắc — Ca sáng):"
    for emp in "$PN_E1" "$PN_E3" "$PN_E9" "$PN_E13"; do
        [ -n "$emp" ] && [ -n "$PN_N_SANG" ] && mk_assignment "$PN_ID" "$PN_N" "$emp" "$PN_N_SANG" "supervisor" "2026-01-01"
    done
    [ -n "$PN_E5" ] && [ -n "$PN_N_SANG" ] && mk_assignment "$PN_ID" "$PN_N" "$PN_E5" "$PN_N_SANG" "worker" "2026-01-01"

    echo "  Phân công (Kho Bắc — Ca chiều):"
    for emp in "$PN_E6" "$PN_E7"; do
        [ -n "$emp" ] && [ -n "$PN_N_CHIEU" ] && mk_assignment "$PN_ID" "$PN_N" "$emp" "$PN_N_CHIEU" "worker" "2026-01-01"
    done

    echo "  Phân công (Kho Nam — Ca sáng):"
    for emp in "$PN_E4" "$PN_E8" "$PN_E11" "$PN_E12" "$PN_E15"; do
        [ -n "$emp" ] && [ -n "$PN_S_SANG" ] && mk_assignment "$PN_ID" "$PN_S" "$emp" "$PN_S_SANG" "worker" "2026-01-01"
    done
    [ -n "$PN_E2" ] && [ -n "$PN_S_SANG" ] && mk_assignment "$PN_ID" "$PN_S" "$PN_E2" "$PN_S_SANG" "supervisor" "2026-01-01"

    echo "  Phân công (Trung tâm điều phối):"
    [ -n "$PN_E10" ] && [ -n "$PN_HUB_TC" ] && mk_assignment "$PN_ID" "$PN_HUB" "$PN_E10" "$PN_HUB_TC" "worker" "2026-01-01"

    echo "  Thành viên workspace:"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E1" "manager"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E2" "lead"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E9" "member"
    mk_workspace_member "$PN_ID" "$PN_WS_XE" "$PN_E4" "lead"
    mk_workspace_member "$PN_ID" "$PN_WS_XE" "$PN_E5" "member"
    mk_workspace_member "$PN_ID" "$PN_WS_KHO" "$PN_E7" "lead"
    mk_workspace_member "$PN_ID" "$PN_WS_KD" "$PN_E13" "member"
    mk_workspace_member "$PN_ID" "$PN_WS_IT" "$PN_E14" "lead"

    echo "  Lời mời nhân viên:"
    mk_invitation "$PN_ID" "linh.nguyen.moi@phuongnam.vn" "Linh" "Nguyễn Thị" "+84906333331"
    mk_invitation "$PN_ID" "phong.vu.moi@phuongnam.vn" "Phong" "Vũ Văn" "+84906333332"
    PHONG_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$PN_ID" "$PHONG_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$PN_ID" "location_face_liveness" "2" "360"

    echo "  Nhân viên bổ sung (đủ độ sâu 10-15 mẫu/vai trò):"
    mk_employee_group "$PN_ID" "$PN_WS_DH" "Điều hành" "$PN_N" "$PN_N_SANG" "PN-QLCT" 1 2 "Quản lý công trình" "supervisor" "established"
    mk_employee_group "$PN_ID" "$PN_WS_XE" "Quản lý Đội xe" "$PN_S" "$PN_S_SANG" "PN-TBP" 1 2 "Trưởng bộ phận vận chuyển" "supervisor" "established"
    mk_employee_group "$PN_ID" "$PN_WS_KHO" "Kho vận" "$PN_HUB" "$PN_HUB_TC" "PN-GS" 1 3 "Giám sát công trình" "supervisor" "established"
    mk_employee_group "$PN_ID" "$PN_WS_KHO" "Kho vận" "$PN_N" "$PN_N_SANG" "PN-CT" 1 8 "Nhân viên kho" "worker" "established"
    PN_MULTISITE_E="$EMP_ID"
    mk_employee_group "$PN_ID" "$PN_WS_NS" "Nhân sự" "$PN_S" "$PN_S_SANG" "PN-VP" 1 4 "Nhân viên văn phòng" "worker" "established"
    mk_employee_group "$PN_ID" "$PN_WS_KHO" "Kho vận" "$PN_N" "$PN_N_CHIEU" "PN-TV" 1 3 "Nhân viên thời vụ" "worker" "seasonal"
    mk_employee_group "$PN_ID" "$PN_WS_XE" "Quản lý Đội xe" "$PN_S" "$PN_S_SANG" "PN-TT" 1 3 "Nhân viên thử việc" "worker" "probation"

    echo "  Workspace lồng 3 cấp (Công ty → Đội → Nhóm) — 3 nhánh:"
    mk_workspace "$PN_ID" "Nhóm Điều phối Tuyến Bắc" "team" "Nhóm nhỏ trong Đội Kho Hà Nội" "$PN_WS_TEAM_HN"
    mk_workspace "$PN_ID" "Nhóm Điều phối Tuyến Nam" "team" "Nhóm nhỏ trong Đội Kho TP.HCM" "$PN_WS_TEAM_HCM"
    mk_workspace "$PN_ID" "Nhóm Lái xe đường dài Bắc-Nam" "team" "Nhóm nhỏ trong Đội xe đường dài" "$PN_WS_TEAM_XE"

    echo "  Nhân viên làm việc tại NHIỀU công trình cùng lúc (spec v2 mục 6.3):"
    [ -n "$PN_MULTISITE_E" ] && [ -n "$PN_S" ] && [ -n "$PN_S_SANG" ] && \
        mk_assignment "$PN_ID" "$PN_S" "$PN_MULTISITE_E" "$PN_S_SANG" "worker" "2026-01-01"

    echo "  Lời mời còn hiệu lực (chưa accept/hủy):"
    mk_invitation "$PN_ID" "hoa.le.choloi@phuongnam.vn" "Hoa" "Lê Thị" "+84906333333"
fi
echo ""

# ── Business-scenario diversity: site-scoped roles, inactive/cancelled lifecycle states,
#    tenant-level custom role. Every feature these exercise was built/reviewed this session
#    (RBAC site-scope, Workspace/Shift/Site deactivation, Assignment cancel, custom roles) —
#    without this section the seed data only ever showed the "happy path" create flow, with
#    zero rows demonstrating any of these states.

echo "=== Đa dạng hóa: role giới hạn site, trạng thái inactive/cancelled, custom role ==="

mk_site_scoped_role() {
    local user_email="$1" role_name="$2" tenant_id="$3" site_id="$4" label="$5"
    [ -z "$tenant_id" ] || [ -z "$site_id" ] && return
    local user_id role_id user_role_id
    user_id=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM users WHERE email='$user_email';" 2>/dev/null | tr -d ' \n')
    [ -z "$user_id" ] && { echo "  ~ Bỏ qua site-scope cho $user_email (chưa có tài khoản — chạy sau seed_historical.sql)"; return; }
    role_id=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM roles WHERE tenant_id IS NULL AND name='$role_name';" 2>/dev/null | tr -d ' \n')
    user_role_id=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM user_roles WHERE user_id='$user_id' AND role_id='$role_id' AND tenant_id='$tenant_id';" 2>/dev/null | tr -d ' \n')
    if [ -z "$user_role_id" ]; then
        echo "  ~ Bỏ qua site-scope cho $user_email (chưa có role $role_name — chạy sau seed_historical.sql)"
        return
    fi
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c \
        "INSERT INTO user_role_sites (user_role_id, site_id) VALUES ('$user_role_id','$site_id') ON CONFLICT DO NOTHING;" >/dev/null 2>&1
    echo "  + $label"
}

mk_deactivate_workspace() {
    local tenant_id="$1" name="$2"
    [ -z "$tenant_id" ] && return
    local id
    id=$(curl -s "$BASE_URL/api/v1/tenants/$tenant_id/workspaces?search=$(python3 -c "import urllib.parse;print(urllib.parse.quote('$name'))")&size=5" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
" 2>/dev/null)
    [ -z "$id" ] && { echo "  ! Không tìm thấy workspace '$name' để deactivate"; return; }
    curl -s -o /dev/null -X PUT "$BASE_URL/api/v1/tenants/$tenant_id/workspaces/$id" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"status":"inactive"}'
    echo "  + Workspace '$name' → inactive"
}

mk_deactivate_shift() {
    local tenant_id="$1" site_id="$2" name="$3"
    [ -z "$tenant_id" ] || [ -z "$site_id" ] && return
    local id
    id=$(curl -s "$BASE_URL/api/v1/tenants/$tenant_id/sites/$site_id/shifts?size=20" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
" 2>/dev/null)
    [ -z "$id" ] && { echo "  ! Không tìm thấy ca '$name' để deactivate"; return; }
    curl -s -o /dev/null -X PUT "$BASE_URL/api/v1/tenants/$tenant_id/sites/$site_id/shifts/$id" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"status":"inactive"}'
    echo "  + Ca '$name' → inactive"
}

mk_deactivate_site() {
    local tenant_id="$1" name="$2"
    [ -z "$tenant_id" ] && return
    local id
    id=$(curl -s "$BASE_URL/api/v1/tenants/$tenant_id/sites?search=$(python3 -c "import urllib.parse;print(urllib.parse.quote('$name'))")&size=5" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
" 2>/dev/null)
    [ -z "$id" ] && { echo "  ! Không tìm thấy site '$name' để deactivate"; return; }
    curl -s -o /dev/null -X PUT "$BASE_URL/api/v1/tenants/$tenant_id/sites/$id" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"status":"inactive"}'
    echo "  + Site '$name' → inactive"
}

mk_cancel_one_assignment() {
    local tenant_id="$1" site_id="$2" label="$3"
    [ -z "$tenant_id" ] || [ -z "$site_id" ] && return
    local id
    id=$(curl -s "$BASE_URL/api/v1/tenants/$tenant_id/sites/$site_id/assignments?status=active&size=1" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(items[0]['id'] if items else '')
" 2>/dev/null)
    [ -z "$id" ] && { echo "  ! Không tìm thấy phân công để hủy ($label)"; return; }
    curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/tenants/$tenant_id/sites/$site_id/assignments/$id" \
        -H "Authorization: Bearer $TOKEN"
    echo "  + Hủy 1 phân công ($label)"
}

echo "  -- Trạng thái inactive/cancelled (mỗi tenant chính 1 workspace + 1 ca + 1 site + 1 phân công hủy) --"
mk_deactivate_workspace "$HL_ID" "Pháp chế"
mk_deactivate_shift "$HL_ID" "$HL_DN" "Ca mở rộng"
mk_deactivate_site "$HL_ID" "Công trình Hoàng Long - Nam Định"
mk_cancel_one_assignment "$HL_ID" "$HL_HCM" "Hoàng Long / TP.HCM"

mk_deactivate_workspace "$BM_ID" "Xuất nhập khẩu"
mk_deactivate_shift "$BM_ID" "$BM_WH_A" "Ca tối"
mk_deactivate_site "$BM_ID" "Nhà máy/Kho Bình Minh - Nam Định"
mk_cancel_one_assignment "$BM_ID" "$BM_WH_A" "Bình Minh / Kho A"

mk_deactivate_workspace "$PN_ID" "Tài chính"
mk_deactivate_shift "$PN_ID" "$PN_HUB" "Ca mở rộng"
mk_deactivate_site "$PN_ID" "Kho vận Phương Nam - Nam Định"
mk_cancel_one_assignment "$PN_ID" "$PN_S" "Phương Nam / Kho Nam"

echo "  -- Ca làm việc bổ sung (đa dạng loại ca — spec mục 2.4) --"
# Ca xoay ngắn 6h — khác biệt với các ca 7-9h hiện có, mô phỏng site vận hành 3 ca/ngày.
mk_shift "$BM_ID" "$BM_PLANT" "Ca ngắn (6h)" "10:00" "16:00" "false" "false"
# Dung sai check-in sớm/check-out muộn RỘNG hẳn (so với 15/30 phút mặc định ở các ca có OT) —
# test rõ 2 thái cực dung sai khác nhau, không chỉ có/không có OT.
if [ -n "$SHIFT_ID" ]; then
    curl -s -o /dev/null -X PUT "$BASE_URL/api/v1/tenants/$BM_ID/sites/$BM_PLANT/shifts/$SHIFT_ID/ot-config" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d '{"allowOvertime":false,"earlyCheckinMinutes":45,"lateCheckoutMinutes":5}'
    echo "  + Ca ngắn (6h) — dung sai vào sớm rộng (45p) / ra muộn hẹp (5p)"
fi

echo "  -- Phân công đã hết hạn TỰ NHIÊN (endDate đã qua, KHÔNG chủ động hủy — spec mục 2.4) --"
# Khác về nghiệp vụ với mk_cancel_one_assignment ở trên (status=cancelled): đây là status vẫn
# 'active' trong DB nhưng endDate đã qua — test đúng logic "chỉ tính assignment còn hiệu lực
# tại thời điểm chấm công", không lẫn với case bị hủy chủ động. Dùng site KHÁC với site hiện
# tại của nhân viên (constraint hệ thống là 1 assignment active/nhân viên/site — cùng site sẽ
# bị 409 vì nhân viên đã có assignment hiện tại ở đó rồi).
[ -n "$HL_E11" ] && [ -n "$HL_DN" ] && [ -n "$HL_DN_TC" ] && \
    mk_assignment "$HL_ID" "$HL_DN" "$HL_E11" "$HL_DN_TC" "worker" "2025-06-01" "2025-12-31"
[ -n "$BM_E6" ] && [ -n "$BM_WH_A" ] && [ -n "$BM_WH_NGAY" ] && \
    mk_assignment "$BM_ID" "$BM_WH_A" "$BM_E6" "$BM_WH_NGAY" "worker" "2025-06-01" "2025-12-31"
[ -n "$PN_E6" ] && [ -n "$PN_S" ] && [ -n "$PN_S_SANG" ] && \
    mk_assignment "$PN_ID" "$PN_S" "$PN_E6" "$PN_S_SANG" "worker" "2025-06-01" "2025-12-31"

echo "  -- Role tùy chỉnh cấp tenant (khác role hệ thống mặc định) --"
mk_tenant_custom_role "$HL_ID" "Kế toán trưởng" \
    "Vai trò tùy chỉnh của Hoàng Long — chỉ xem nhân sự và workspace, không có quyền sửa" \
    "['employees','workspaces']"
mk_tenant_custom_role "$HL_ID" "Giám sát an toàn công trường" \
    "Vai trò tùy chỉnh của Hoàng Long — xem chấm công và xử lý vi phạm, không cấu hình hệ thống" \
    "['checkins','violations']"
mk_tenant_custom_role "$HL_ID" "Quản lý kho vật tư" \
    "Vai trò tùy chỉnh của Hoàng Long — quản lý công trình/vùng làm việc, không có quyền nhân sự" \
    "['sites','geofences']"
mk_tenant_custom_role "$PN_ID" "Điều phối viên Kho" \
    "Vai trò tùy chỉnh của Phương Nam — xem/sửa phân công và ca làm việc tại kho" \
    "['assignments','shifts']"
mk_tenant_custom_role "$PN_ID" "Quản lý đội xe" \
    "Vai trò tùy chỉnh của Phương Nam — quản lý phân công và công trình cho đội xe" \
    "['assignments','sites']"
mk_tenant_custom_role "$PN_ID" "Cán bộ báo cáo" \
    "Vai trò tùy chỉnh của Phương Nam — chỉ xem báo cáo, không có quyền vận hành khác" \
    "['reports']"
mk_tenant_custom_role "$BM_ID" "Trưởng ca sản xuất" \
    "Vai trò tùy chỉnh của Bình Minh — xem phân công và bảng công, không cấu hình hệ thống" \
    "['assignments','attendance']"
mk_tenant_custom_role "$BM_ID" "Kiểm soát viên chất lượng" \
    "Vai trò tùy chỉnh của Bình Minh — xem chấm công và xử lý vi phạm chất lượng" \
    "['checkins','violations']"

echo "  -- Role giới hạn theo site (site-scope RBAC — cần chạy sau khi seed_historical.sql đã tạo login) --"
echo "     (Sẽ áp dụng ở bước cuối, sau khi seed_historical.sql tạo tài khoản đăng nhập cho nhân viên)"
echo ""

# ── Tenant 4: Công ty Khởi nghiệp Tia Sáng (trial, sát giới hạn gói) ─────────

echo "=== Tenant 4: Công ty Khởi nghiệp Tia Sáng (tia-sang-startup, TRIAL) ==="
mk_tenant "Công ty Khởi nghiệp Tia Sáng" "tia-sang-startup" "kimngan.tiasang@gmail.com" \
    ',"industry":"Công nghệ","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Công nghệ"
TS_ID="$TENANT_ID"

if [ -n "$TS_ID" ]; then
    [ -n "$PLAN_TRIAL_ID" ] && mk_subscription "$PLAN_TRIAL_ID" "trial" "MONTHLY" "TRIAL"

    echo "  Workspaces (phòng ban):"
    mk_workspace "$TS_ID" "Kỹ thuật" "department" "Đội phát triển sản phẩm"; TS_WS_KT="$WORKSPACE_ID"
    mk_workspace "$TS_ID" "Marketing" "department" "Đội marketing và tăng trưởng"; TS_WS_MKT="$WORKSPACE_ID"
    mk_workspace "$TS_ID" "Điều hành" "department" "Ban lãnh đạo"; TS_WS_DH="$WORKSPACE_ID"

    echo "  Sites:"
    mk_site "$TS_ID" "Văn phòng Tia Sáng" "TS-HN" "25 Cầu Giấy, Cầu Giấy, Hà Nội" "21.0333" "105.7926" "Asia/Ho_Chi_Minh"
    TS_HN="$SITE_ID"

    echo "  Ca làm việc:"
    TS_HANHCHINH=""
    if [ -n "$TS_HN" ]; then
        mk_shift "$TS_ID" "$TS_HN" "Ca hành chính" "08:30" "17:30" "false" "false"; TS_HANHCHINH="$SHIFT_ID"
    fi

    echo "  Nhân viên (5/5 — chạm giới hạn gói Trial):"
    mk_employee "{\"firstName\":\"Ngân\",\"lastName\":\"Nguyễn Thị Kim\",\"email\":\"ngan.nguyen@tiasang.vn\",\"employeeCode\":\"TS-001\",\"position\":\"Nhà sáng lập kiêm CEO\",\"department\":\"Điều hành\",\"departmentId\":\"$TS_WS_DH\",\"hiredDate\":\"2024-06-01\",\"phone\":\"+84904000001\"}" "Nguyễn Thị Kim Ngân (Nhà sáng lập kiêm CEO)"
    TS_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Long\",\"lastName\":\"Lê Văn Bảo\",\"email\":\"long.le@tiasang.vn\",\"employeeCode\":\"TS-002\",\"position\":\"Trưởng nhóm Kỹ thuật\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_WS_KT\",\"hiredDate\":\"2024-06-15\",\"phone\":\"+84904000002\"}" "Lê Văn Bảo Long (Trưởng nhóm Kỹ thuật)"
    TS_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Duyên\",\"lastName\":\"Phan Thị Mỹ\",\"email\":\"duyen.phan@tiasang.vn\",\"employeeCode\":\"TS-003\",\"position\":\"Chuyên viên Marketing\",\"department\":\"Marketing\",\"departmentId\":\"$TS_WS_MKT\",\"hiredDate\":\"2024-07-01\",\"phone\":\"+84904000003\"}" "Phan Thị Mỹ Duyên (Chuyên viên Marketing)"
    TS_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Trương Văn\",\"email\":\"dat.truong@tiasang.vn\",\"employeeCode\":\"TS-004\",\"position\":\"Nhân viên Kỹ thuật (bán thời gian)\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_WS_KT\",\"hiredDate\":\"2024-08-01\",\"phone\":\"+84904000004\"}" "Trương Văn Đạt (Nhân viên Kỹ thuật bán thời gian) [đa công ty]"
    TS_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Khang\",\"lastName\":\"Đỗ Văn\",\"email\":\"khang.do@tiasang.vn\",\"employeeCode\":\"TS-005\",\"position\":\"Thực tập sinh Kỹ thuật\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_WS_KT\",\"hiredDate\":\"2024-09-01\",\"phone\":\"+84904000005\"}" "Đỗ Văn Khang (Thực tập sinh Kỹ thuật)"
    TS_E5="$EMP_ID"

    echo "  Phân công (Ca hành chính):"
    for emp in "$TS_E1" "$TS_E2" "$TS_E3" "$TS_E4" "$TS_E5"; do
        [ -n "$emp" ] && [ -n "$TS_HANHCHINH" ] && mk_assignment "$TS_ID" "$TS_HN" "$emp" "$TS_HANHCHINH" "worker" "2026-01-01"
    done

    echo "  Thành viên workspace:"
    mk_workspace_member "$TS_ID" "$TS_WS_KT" "$TS_E2" "lead"
    mk_workspace_member "$TS_ID" "$TS_WS_KT" "$TS_E4" "member"
    mk_workspace_member "$TS_ID" "$TS_WS_KT" "$TS_E5" "member"
    mk_workspace_member "$TS_ID" "$TS_WS_MKT" "$TS_E3" "lead"

    echo "  Lời mời nhân viên:"
    mk_invitation "$TS_ID" "an.le.moi@tiasang.vn" "An" "Lê Thị" "+84906444441"

    echo "  Random check config:"
    mk_rand_config "$TS_ID" "location_only" "1" "300"

    echo "  ⚠ Tenant này đang ở đúng giới hạn 5/5 nhân viên của gói Trial —"
    echo "    tạo thêm 1 nhân viên nữa sẽ trả về lỗi PLAN_LIMIT_EXCEEDED (mong muốn, dùng để test)."
fi
echo ""

# ── Tenant 5: Công ty TNHH Đông Á (sẽ bị suspend ở cuối) ─────────────────────

echo "=== Tenant 5: Công ty TNHH Đông Á (dong-a-jsc, sẽ bị SUSPENDED) ==="
mk_tenant "Công ty TNHH Đông Á" "dong-a-jsc" "hanh.donga@gmail.com" \
    ',"industry":"Dịch vụ","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Dịch vụ"
DA_ID="$TENANT_ID"

if [ -n "$DA_ID" ]; then
    [ -n "$PLAN_BASIC_ID" ] && mk_subscription "$PLAN_BASIC_ID" "basic"

    echo "  Workspaces (phòng ban):"
    mk_workspace "$DA_ID" "Hành chính" "department" "Hành chính văn phòng"; DA_WS_HC="$WORKSPACE_ID"
    mk_workspace "$DA_ID" "Kế toán" "department" "Kế toán và tài chính"; DA_WS_KT="$WORKSPACE_ID"
    mk_workspace "$DA_ID" "Chăm sóc khách hàng" "department" "Hỗ trợ và chăm sóc khách hàng"; DA_WS_CSKH="$WORKSPACE_ID"
    mk_workspace "$DA_ID" "IT" "department" "Công nghệ thông tin nội bộ"; DA_WS_IT="$WORKSPACE_ID"

    echo "  Sites:"
    mk_site "$DA_ID" "Văn phòng Đông Á" "DA-HCM" "88 Lý Tự Trọng, Quận 1, TP.HCM" "10.7756" "106.7019" "Asia/Ho_Chi_Minh"
    DA_HCM="$SITE_ID"

    echo "  Ca làm việc:"
    DA_HANHCHINH=""
    if [ -n "$DA_HCM" ]; then
        mk_shift "$DA_ID" "$DA_HCM" "Ca hành chính" "08:00" "17:00" "false" "false"; DA_HANHCHINH="$SHIFT_ID"
    fi

    echo "  Nhân viên:"
    mk_employee "{\"firstName\":\"Hạnh\",\"lastName\":\"Bạch Thị\",\"email\":\"hanh.bach@donga.vn\",\"employeeCode\":\"DA-001\",\"position\":\"Trưởng phòng Hành chính\",\"department\":\"Hành chính\",\"departmentId\":\"$DA_WS_HC\",\"hiredDate\":\"2023-05-01\",\"phone\":\"+84905000001\"}" "Bạch Thị Hạnh (Trưởng phòng Hành chính)"
    DA_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Long\",\"lastName\":\"Kiều Văn\",\"email\":\"long.kieu@donga.vn\",\"employeeCode\":\"DA-002\",\"position\":\"Nhân viên Kế toán\",\"department\":\"Kế toán\",\"departmentId\":\"$DA_WS_KT\",\"hiredDate\":\"2023-06-01\",\"phone\":\"+84905000002\"}" "Kiều Văn Long (Nhân viên Kế toán)"
    DA_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Mai\",\"lastName\":\"Lâm Thị\",\"email\":\"mai.lam@donga.vn\",\"employeeCode\":\"DA-003\",\"position\":\"Nhân viên Chăm sóc khách hàng\",\"department\":\"Chăm sóc khách hàng\",\"departmentId\":\"$DA_WS_CSKH\",\"hiredDate\":\"2023-08-01\",\"phone\":\"+84905000003\"}" "Lâm Thị Mai (Nhân viên Chăm sóc khách hàng)"
    DA_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Nghĩa\",\"lastName\":\"Vũ Văn\",\"email\":\"nghia.vu@donga.vn\",\"employeeCode\":\"DA-004\",\"position\":\"Nhân viên IT\",\"department\":\"IT\",\"departmentId\":\"$DA_WS_IT\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84905000004\"}" "Vũ Văn Nghĩa (Nhân viên IT)"
    DA_E4="$EMP_ID"

    echo "  Phân công (Ca hành chính):"
    for emp in "$DA_E1" "$DA_E2" "$DA_E3" "$DA_E4"; do
        [ -n "$emp" ] && [ -n "$DA_HANHCHINH" ] && mk_assignment "$DA_ID" "$DA_HCM" "$emp" "$DA_HANHCHINH" "worker" "2026-01-01"
    done

    echo "  Thành viên workspace:"
    mk_workspace_member "$DA_ID" "$DA_WS_HC" "$DA_E1" "lead"
    mk_workspace_member "$DA_ID" "$DA_WS_HC" "$DA_E2" "member"

    echo "  Lời mời nhân viên:"
    mk_invitation "$DA_ID" "thu.tran.moi@donga.vn" "Thu" "Trần Thị" "+84906555551"
    THU_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$DA_ID" "$THU_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$DA_ID" "location_only" "1" "300"

    echo "  Tạm ngưng tenant (test tenant-suspended path):"
    mk_suspend_tenant "$DA_ID"
fi
echo ""

# ── Tenants 6-15: 10 new lightweight companies (diverse industries/plans) ───
# mk_light_tenant <name> <slug> <owner_email> <industry> <plan_id> <plan_name>
#                 <site_name> <site_code> <address> <lat> <lon>
#                 <ws1_name> <ws2_name> <emp_count> <code_prefix>
mk_light_tenant() {
    local name="$1" slug="$2" owner="$3" industry="$4" plan_id="$5" plan_name="$6"
    local site_name="$7" site_code="$8" address="$9" lat="${10}" lon="${11}"
    local ws1="${12}" ws2="${13}" emp_count="${14}" prefix="${15}"

    echo "=== Tenant: $name ($slug) ==="
    mk_tenant "$name" "$slug" "$owner" ",\"industry\":\"$industry\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"countryCode\":\"VN\"" "$industry"
    local tid="$TENANT_ID"
    if [ -n "$tid" ]; then
        local sub_status="ACTIVE"
        [ "$plan_name" = "trial" ] && sub_status="TRIAL"
        [ -n "$plan_id" ] && mk_subscription "$plan_id" "$plan_name" "MONTHLY" "$sub_status"

        echo "  Workspaces:"
        mk_workspace "$tid" "$ws1" "department" "Phòng $ws1"; local ws1_id="$WORKSPACE_ID"
        mk_workspace "$tid" "$ws2" "department" "Phòng $ws2"; local ws2_id="$WORKSPACE_ID"
        mk_workspace "$tid" "Điều hành" "department" "Ban lãnh đạo"; local ws3_id="$WORKSPACE_ID"

        echo "  Site:"
        mk_site "$tid" "$site_name" "$site_code" "$address" "$lat" "$lon" "Asia/Ho_Chi_Minh"
        local sid="$SITE_ID"

        echo "  Ca làm việc:"
        mk_shift "$tid" "$sid" "Ca hành chính" "08:00" "17:00" "false" "false"
        local shid="$SHIFT_ID"

        echo "  Nhân viên ($emp_count):"
        mk_extra_employees "$tid" "$ws1_id" "$ws1" "$sid" "$shid" "$prefix" 1 "$emp_count"

        echo "  Random check config:"
        mk_rand_config "$tid" "location_only" "1" "300"
    fi
    echo ""
}

mk_light_tenant "Công ty CP Bán lẻ Việt Phát" "viet-phat-retail" "owner.vietphat@gmail.com" \
    "Bán lẻ" "$PLAN_BASIC_ID" "basic" \
    "Cửa hàng Việt Phát Q1" "VP-Q1" "200 Đồng Khởi, Quận 1, TP.HCM" "10.7770" "106.7030" \
    "Bán hàng" "Kho" 4 "VP"

mk_light_tenant "Công ty TNHH F&B Hoàng Gia" "hoang-gia-fnb" "owner.vietphat@gmail.com" \
    "Nhà hàng - Ẩm thực" "$PLAN_TRIAL_ID" "trial" \
    "Nhà hàng Hoàng Gia Q3" "HG-Q3" "12 Võ Văn Tần, Quận 3, TP.HCM" "10.7830" "106.6900" \
    "Bếp" "Phục vụ" 3 "HG"

mk_light_tenant "Công ty Bảo vệ Minh Châu" "minh-chau-security" "owner.minhchau@gmail.com" \
    "Bảo vệ - An ninh" "$PLAN_BASIC_ID" "basic" \
    "Trụ sở Minh Châu" "MC-HN" "18 Kim Mã, Ba Đình, Hà Nội" "21.0320" "105.8140" \
    "Nghiệp vụ Bảo vệ" "Đào tạo" 5 "MC"

mk_light_tenant "Công ty CP BĐS Thành Công" "thanh-cong-real-estate" "owner.thanhcong@gmail.com" \
    "Bất động sản" "$PLAN_PRO_ID" "pro" \
    "Sàn giao dịch Thành Công" "TC-HCM" "68 Nguyễn Du, Quận 1, TP.HCM" "10.7790" "106.6990" \
    "Kinh doanh" "Pháp lý" 6 "TC"

mk_light_tenant "Công ty Vệ sinh Công nghiệp Việt Nam" "viet-nam-cleaning" "owner.vncleaning@gmail.com" \
    "Dịch vụ vệ sinh" "$PLAN_TRIAL_ID" "trial" \
    "Văn phòng điều phối VSCN" "VS-HCM" "45 Cách Mạng Tháng 8, Quận 10, TP.HCM" "10.7720" "106.6690" \
    "Vận hành" "Nhân sự" 4 "VS"

mk_light_tenant "Công ty Điện lực Sao Mai" "sao-mai-electric" "owner.saomai@gmail.com" \
    "Điện - Cơ khí" "$PLAN_BASIC_ID" "basic" \
    "Xưởng Sao Mai" "SM-BD" "Lô C3, KCN VSIP, Bình Dương" "10.9820" "106.6540" \
    "Kỹ thuật điện" "Cơ khí" 5 "SM"

mk_light_tenant "Công ty Khai khoáng Phú Quý" "phu-quy-mining" "owner.phuquy@gmail.com" \
    "Khai khoáng" "$PLAN_ENTERPRISE_ID" "enterprise" \
    "Mỏ Phú Quý" "PQ-QN" "Xã Đại Sơn, Quảng Ninh" "21.0060" "107.2920" \
    "Khai thác" "An toàn Mỏ" 6 "PQ"

# Trên gói legacy_basic (sắp bị deactivate ở cuối script) — test "tenant cũ vẫn chạy bình
# thường sau khi gói ngừng bán cho khách hàng mới" (mục 1.2 của spec dữ liệu mẫu).
mk_light_tenant "Công ty Thủy sản Đại Dương" "dai-duong-fishery" "owner.phuquy@gmail.com" \
    "Thủy sản" "$PLAN_LEGACY_ID" "legacy_basic" \
    "Xưởng chế biến Đại Dương" "DD-CT" "KCN Trà Nóc, Cần Thơ" "10.0490" "105.7520" \
    "Chế biến" "Kiểm định" 5 "DD"

mk_light_tenant "Công ty Nông nghiệp Tân Phát" "tan-phat-agriculture" "owner.tanphat@gmail.com" \
    "Nông nghiệp" "$PLAN_TRIAL_ID" "trial" \
    "Trang trại Tân Phát" "TP-LA" "Xã Tân Lập, Long An" "10.6960" "106.2280" \
    "Trồng trọt" "Kho vận" 4 "TP"

mk_light_tenant "Công ty Tổ chức Sự kiện Việt Tín" "viet-tin-events" "owner.viettin@gmail.com" \
    "Sự kiện - Truyền thông" "$PLAN_BASIC_ID" "basic" \
    "Văn phòng Việt Tín" "VT-HN" "9 Xã Đàn, Đống Đa, Hà Nội" "21.0140" "105.8280" \
    "Sản xuất sự kiện" "Kinh doanh" 5 "VT"

# Việt Tín gets cancelled subscription status, for status diversity
_r=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants?search=viet-tin-events&size=5" -H "Authorization: Bearer $TOKEN")
_b=$(echo "$_r" | head -n -1)
VT_ID=$(echo "$_b" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
match=[i for i in items if i.get('slug')=='viet-tin-events']
print(match[0]['id'] if match else '')
" 2>/dev/null)
if [ -n "$VT_ID" ]; then
    TENANT_ID="$VT_ID"
    echo "=== Việt Tín: đặt subscription CANCELLED (đa dạng trạng thái) ==="
    mk_cancel_subscription
    echo ""
fi

# ── 3 tenant bổ sung: rỗng / trial sắp hết hạn / trial đã hết hạn (spec mục 2.1) ──

echo "=== Tenant: Công ty CP Rồng Vàng (rong-vang-holdings, MỚI TẠO — rỗng hoàn toàn) ==="
mk_tenant "Công ty CP Rồng Vàng" "rong-vang-holdings" "owner.rongvang@gmail.com" \
    ',"industry":"Đầu tư","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Đầu tư"
echo "  (Cố ý KHÔNG tạo thêm site/shift/workspace/employee nào — test empty-state của mọi màn danh sách)"
echo ""

echo "=== Tenant: Công ty TNHH Hoa Phượng (hoa-phuong-trading, TRIAL sắp hết hạn) ==="
mk_tenant "Công ty TNHH Hoa Phượng" "hoa-phuong-trading" "owner.hoaphuong@gmail.com" \
    ',"industry":"Thương mại","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Thương mại"
HP_ID="$TENANT_ID"
if [ -n "$HP_ID" ]; then
    [ -n "$PLAN_TRIAL_ID" ] && mk_subscription "$PLAN_TRIAL_ID" "trial" "MONTHLY" "TRIAL"
    mk_workspace "$HP_ID" "Kinh doanh" "department" "Phòng kinh doanh"
    mk_site "$HP_ID" "Văn phòng Hoa Phượng" "HP-HCM" "5 Lê Duẩn, Quận 1, TP.HCM" "10.7820" "106.6950" "Asia/Ho_Chi_Minh"
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c \
        "UPDATE tenant_subscriptions SET expires_at = NOW() + INTERVAL '2 days' WHERE tenant_id = '$HP_ID';" >/dev/null 2>&1
    echo "  → Trial hết hạn sau 2 ngày nữa (test cảnh báo sắp hết hạn)"
fi
echo ""

echo "=== Tenant: Công ty TNHH Nam Việt (nam-viet-services, TRIAL ĐÃ hết hạn, chưa nâng cấp) ==="
mk_tenant "Công ty TNHH Nam Việt" "nam-viet-services" "owner.namviet@gmail.com" \
    ',"industry":"Dịch vụ","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Dịch vụ"
NV_ID="$TENANT_ID"
if [ -n "$NV_ID" ]; then
    [ -n "$PLAN_TRIAL_ID" ] && mk_subscription "$PLAN_TRIAL_ID" "trial" "MONTHLY" "TRIAL"
    mk_workspace "$NV_ID" "Vận hành" "department" "Phòng vận hành"
    mk_site "$NV_ID" "Văn phòng Nam Việt" "NV-HCM" "20 Nguyễn Trãi, Quận 5, TP.HCM" "10.7550" "106.6720" "Asia/Ho_Chi_Minh"
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c \
        "UPDATE tenant_subscriptions SET expires_at = NOW() - INTERVAL '5 days' WHERE tenant_id = '$NV_ID';" >/dev/null 2>&1
    echo "  → Trial đã hết hạn 5 ngày trước, chưa nâng cấp gói (test khóa tính năng đúng lúc)"
fi
echo ""

# ── Deactivate the legacy plan (Đại Dương was just put on it above) ─────────
if [ -n "$PLAN_LEGACY_ID" ] && [ -n "$PLAN_BASIC_ID" ]; then
    echo "=== Deactivate legacy_basic (auto-migrates Đại Dương → basic, Issue #8) ==="
    _r=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/plans/$PLAN_LEGACY_ID" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"isActive\":false,\"migrateToPlanId\":\"$PLAN_BASIC_ID\"}")
    _s=$(echo "$_r" | tail -n 1)
    if [ "$_s" -eq 200 ]; then
        echo "  legacy_basic deactivated, tenant(s) migrated to basic — OK"
    else
        echo "  ~ Deactivate legacy_basic — HTTP $_s (đã tắt từ lần chạy trước hoặc không có tenant nào cần chuyển)"
    fi
    echo ""
fi

# ── Platform side: custom platform-level roles + ~12 platform staff ─────────

echo "=== Vai trò nền tảng tùy chỉnh (custom platform roles) ==="

mk_custom_platform_role() {
    local name="$1" desc="$2" resources_py="$3"
    local ids r s b
    ids=$(perm_ids_for "$resources_py")
    local payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','description':'$desc','permissionIds':json.loads('$ids')}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/roles" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        echo "  + Role nền tảng: $name"
    elif [ "$s" -eq 409 ]; then
        echo "  ~ Role nền tảng đã tồn tại: $name"
    else
        echo "  ! Lỗi tạo role nền tảng: $name — HTTP $s ($b)"
    fi
}

mk_custom_platform_role "PLATFORM_SUPPORT_LEAD" \
    "Trưởng nhóm hỗ trợ khách hàng nền tảng — xem toàn bộ tenant/user, không sửa billing" \
    "['tenants','users']"
mk_custom_platform_role "PLATFORM_BILLING_OPS" \
    "Vận hành billing nền tảng — quản lý gói dịch vụ và subscription của tenant" \
    "['plans','subscriptions']"
mk_custom_platform_role "PLATFORM_SECURITY_AUDITOR" \
    "Kiểm toán bảo mật nền tảng — chỉ xem role/permission và audit log, không chỉnh sửa" \
    "['roles','permissions']"
mk_custom_platform_role "PLATFORM_ONBOARDING_SPECIALIST" \
    "Chuyên viên onboarding — hỗ trợ tenant mới thiết lập workspace/site/nhân viên ban đầu" \
    "['tenants','workspaces','sites']"
mk_custom_platform_role "PLATFORM_QA_REVIEWER" \
    "Kiểm định chất lượng nền tảng — xem báo cáo/chấm công để rà soát chất lượng dữ liệu" \
    "['reports','checkins']"
mk_custom_platform_role "PLATFORM_NOTIFICATION_MANAGER" \
    "Quản lý thông báo nền tảng — cấu hình template và theo dõi gửi thông báo toàn hệ thống" \
    "['notifications']"
mk_custom_platform_role "PLATFORM_COMPLIANCE_OFFICER" \
    "Cán bộ tuân thủ nền tảng — xem audit log và cấu hình bảo mật của tenant" \
    "['audit','tenants']"
mk_custom_platform_role "PLATFORM_PARTNER_MANAGER" \
    "Quản lý đối tác/khách hàng lớn — xem thông tin tenant Enterprise, không chỉnh sửa billing" \
    "['tenants']"
echo ""

echo "=== Đăng ký ~12 tài khoản nhân viên nền tảng (platform staff) ==="

mk_platform_staff() {
    local email="$1" first="$2" last="$3" role_name="${4:-PLATFORM_STAFF}"
    local existing
    existing=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM users WHERE email='$email';" 2>/dev/null | tr -d ' \n')
    if [ -n "$existing" ]; then
        echo "  ~ Nhân viên nền tảng đã tồn tại: $email"
        return
    fi
    local role_id
    role_id=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM roles WHERE tenant_id IS NULL AND name='$role_name';" 2>/dev/null | tr -d ' \n')
    local payload r s b
    payload=$(python3 -c "import json; print(json.dumps({'email':'$email','firstName':'$first','lastName':'$last','roleId':'$role_id' if '$role_id' else None}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/platform/invitations" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -ne 201 ]; then
        echo "  ! Lỗi mời nhân viên nền tảng: $email — HTTP $s ($b)"
        return
    fi
    local inv_token
    inv_token=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT token FROM platform_invitations WHERE email='$email' AND status='pending' LIMIT 1;" 2>/dev/null | tr -d ' \n')
    if [ -z "$inv_token" ]; then
        echo "  ! Không lấy được token lời mời: $email"
        return
    fi
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/platform-invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$inv_token\",\"password\":\"$DEFAULT_PASSWORD\",\"displayName\":\"$first $last\"}")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 200 ]; then
        echo "  + Nhân viên nền tảng: $email ($first $last, role=$role_name)"
    else
        echo "  ! Lỗi chấp nhận lời mời nền tảng: $email — HTTP $s"
    fi
}

mk_platform_staff "hotro1.nentang@fams.com"   "Thảo"  "Nguyễn Thị"    "PLATFORM_STAFF"
mk_platform_staff "hotro2.nentang@fams.com"   "Vinh"  "Trần Văn"      "PLATFORM_STAFF"
mk_platform_staff "hotro3.nentang@fams.com"   "Hà"    "Lê Thị"        "PLATFORM_SUPPORT_LEAD"
mk_platform_staff "kinhdoanh1.nentang@fams.com" "Đức" "Phạm Văn"      "PLATFORM_STAFF"
mk_platform_staff "kinhdoanh2.nentang@fams.com" "Linh" "Hoàng Thị"    "PLATFORM_STAFF"
mk_platform_staff "vanhanh1.nentang@fams.com" "Khánh" "Vũ Văn"        "PLATFORM_STAFF"
mk_platform_staff "vanhanh2.nentang@fams.com" "Trâm"  "Đặng Thị"      "PLATFORM_STAFF"
mk_platform_staff "billing1.nentang@fams.com" "Tuấn"  "Bùi Văn"       "PLATFORM_BILLING_OPS"
mk_platform_staff "baomat1.nentang@fams.com"  "Nga"   "Đỗ Thị"        "PLATFORM_SECURITY_AUDITOR"
mk_platform_staff "kythuat1.nentang@fams.com" "Hiếu"  "Ngô Văn"       "PLATFORM_STAFF"
mk_platform_staff "kythuat2.nentang@fams.com" "Diệu"  "Dương Thị"     "PLATFORM_STAFF"
mk_platform_staff "qlsp1.nentang@fams.com"    "Việt"  "Cao Văn"       "PLATFORM_STAFF"
# Bổ sung (spec dữ liệu mẫu mục 1.1): nâng >15 người, đảm bảo mỗi platform-role tùy chỉnh
# có >=2 người (trước đó chỉ có 1/role — không đủ để test "thu hồi role của 1 người không
# ảnh hưởng người còn lại cùng role").
mk_platform_staff "hotro4.nentang@fams.com"   "Phượng" "Lý Thị"       "PLATFORM_SUPPORT_LEAD"
mk_platform_staff "billing2.nentang@fams.com" "Toàn"   "Đinh Văn"     "PLATFORM_BILLING_OPS"
mk_platform_staff "baomat2.nentang@fams.com"  "Ánh"    "Trịnh Thị"    "PLATFORM_SECURITY_AUDITOR"
mk_platform_staff "kythuat3.nentang@fams.com" "Sang"   "Huỳnh Văn"    "PLATFORM_STAFF"
echo ""

echo "=== Vô hiệu hóa 1 tài khoản nhân viên nền tảng (test mất quyền truy cập giữa chừng) ==="
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c \
    "UPDATE users SET is_active = FALSE WHERE email = 'kythuat3.nentang@fams.com';" >/dev/null 2>&1
echo "  kythuat3.nentang@fams.com → is_active=false"
echo ""

# ── Ma trận trạng thái xác thực tài khoản (spec dữ liệu mẫu mục 1.5) ─────────
# 7 case cụ thể, mỗi case phục vụ 1 mục đích test riêng. Tất cả đứng độc lập với
# tenant/employee — không phụ thuộc dữ liệu lịch sử phía dưới.

echo "=== Ma trận xác thực tài khoản (email/phone chưa xác thực, khóa, 2FA, Google, session cũ) ==="

# 1) Chưa xác thực email — đăng ký nhưng KHÔNG tự flip email_verified (khác mk_account).
mk_account_unverified_email() {
    local email="$1" name="$2"
    local existing
    existing=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT id FROM users WHERE email='$email';" 2>/dev/null | tr -d ' \n')
    if [ -n "$existing" ]; then echo "  ~ Đã tồn tại (email chưa xác thực): $email"; return; fi
    local payload r s
    payload=$(python3 -c "import json; print(json.dumps({'email':'$email','password':'$DEFAULT_PASSWORD','displayName':'$name'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/register" \
        -H "Content-Type: application/json" -d "$payload")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "  + Email CHƯA xác thực: $email (đăng nhập sẽ bị chặn — đúng nghiệp vụ)"
    else
        echo "  ! Lỗi tạo tài khoản chưa xác thực email: $email — HTTP $s"
    fi
}
mk_account_unverified_email "chuaxacthucmail1@gmail.com" "Đặng Thị Chưa Xác Thực"
mk_account_unverified_email "chuaxacthucmail2@gmail.com" "Vũ Văn Chưa Xác Thực"

# 2) Chưa xác thực số điện thoại — tài khoản email đã verify bình thường, có gắn phone
#    nhưng phone_verified=false (đăng nhập email/password vẫn hoạt động bình thường).
mk_account "chuaxacthucphone1@gmail.com" "Bùi Thị Chưa Xác Thực SĐT"
mk_account "chuaxacthucphone2@gmail.com" "Ngô Văn Chưa Xác Thực SĐT"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c "
UPDATE users SET phone = '+84907000001', phone_verified = FALSE WHERE email = 'chuaxacthucphone1@gmail.com';
UPDATE users SET phone = '+84907000002', phone_verified = FALSE WHERE email = 'chuaxacthucphone2@gmail.com';
" >/dev/null 2>&1
echo "  + SĐT CHƯA xác thực: chuaxacthucphone1@gmail.com, chuaxacthucphone2@gmail.com"

# 3) Tài khoản bị khóa do đăng nhập sai quá 5 lần (checklist #14).
mk_account "taikhoanbikhoa@gmail.com" "Hồ Thị Bị Khóa"
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c "
UPDATE users SET failed_login_attempts = 5, locked_until = NOW() + INTERVAL '60 minutes'
WHERE email = 'taikhoanbikhoa@gmail.com';
" >/dev/null 2>&1
echo "  + Tài khoản ĐANG BỊ KHÓA (mở lại sau 60 phút, hoặc qua reset-password): taikhoanbikhoa@gmail.com"

# 4) Đăng nhập qua Google — không có mật khẩu thật, chỉ có google_id.
docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -c "
INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, google_id, created_at, updated_at)
SELECT gen_random_uuid(), 'dangnhapgoogle@gmail.com', NULL, 'Mai Văn Đăng Nhập Google', TRUE, TRUE, FALSE,
       'demo-google-sub-id-0001', NOW() - '90 days'::INTERVAL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'dangnhapgoogle@gmail.com');
" >/dev/null 2>&1
echo "  + Tài khoản Google-only (không có mật khẩu): dangnhapgoogle@gmail.com (google_id giả — không login thật qua Google được, chỉ để test dữ liệu/hiển thị)"

# 5) 2 tài khoản đã bật TOTP 2FA thật — dùng chính flow API + pyotp để sinh mã hợp lệ,
#    nên bí danh (manualEntryKey) in ra dưới đây NẠP ĐƯỢC vào Google Authenticator/Authy
#    thật để tự test đăng nhập có 2FA (checklist #12, #13) — không phải dữ liệu giả tĩnh.
mk_totp_account() {
    local email="$1" name="$2"
    mk_account "$email" "$name"
    # Idempotency: once TOTP is enabled, plain /auth/login no longer returns a full accessToken
    # (it returns a pendingToken instead, correctly gating on the 2FA code) — so re-running this
    # helper on a rerun would otherwise misread that as a login failure. Check the DB directly
    # first and skip if already enabled, same pattern as mk_account's own pre-check.
    local already_enabled
    already_enabled=$(docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -t -c \
        "SELECT totp_enabled FROM users WHERE email='$email';" 2>/dev/null | tr -d ' \n')
    if [ "$already_enabled" = "t" ]; then
        echo "  ~ TOTP đã bật từ trước: $email"
        return
    fi
    local login_r login_s login_b own_token
    login_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" -d "{\"identifier\":\"$email\",\"password\":\"$DEFAULT_PASSWORD\"}")
    login_s=$(echo "$login_r" | tail -n 1); login_b=$(echo "$login_r" | head -n -1)
    [ "$login_s" -ne 200 ] && { echo "  ! Không đăng nhập được để bật TOTP: $email"; return; }
    own_token=$(echo "$login_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
    local setup_r setup_s setup_b setup_token secret
    setup_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $own_token")
    setup_s=$(echo "$setup_r" | tail -n 1); setup_b=$(echo "$setup_r" | head -n -1)
    if [ "$setup_s" -eq 409 ]; then echo "  ~ TOTP đã bật từ trước: $email"; return; fi
    [ "$setup_s" -ne 200 ] && { echo "  ! Lỗi TOTP setup: $email — HTTP $setup_s"; return; }
    setup_token=$(echo "$setup_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('setupToken',''))")
    secret=$(echo "$setup_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('manualEntryKey',''))")
    local code
    code=$(python3 -c "import pyotp; print(pyotp.TOTP('$secret').now())")
    local verify_r verify_s verify_b
    verify_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/totp/verify" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $own_token" \
        -d "{\"setupToken\":\"$setup_token\",\"code\":\"$code\"}")
    verify_s=$(echo "$verify_r" | tail -n 1); verify_b=$(echo "$verify_r" | head -n -1)
    if [ "$verify_s" -eq 200 ]; then
        local backup_codes
        backup_codes=$(echo "$verify_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(', '.join(d.get('data',{}).get('backupCodes',[])))")
        echo "  + TOTP 2FA đã bật thật: $email — secret (nạp vào Authenticator app): $secret"
        echo "      backup codes (dùng 1 lần, lưu lại nếu cần test /login/totp bằng backup code): $backup_codes"
    else
        echo "  ! Lỗi TOTP verify: $email — HTTP $verify_s ($verify_b)"
    fi
}
mk_totp_account "bat2fa1@gmail.com" "Đinh Văn Bật 2FA"
mk_totp_account "bat2fa2@gmail.com" "Lương Thị Bật 2FA"

# 6) Refresh token đã bị thu hồi — login riêng bằng 1 tài khoản KHÁC $TOKEN (không đụng
#    session platform-admin đang dùng cho toàn bộ script) rồi tự logout ngay, để lại 1
#    dòng refresh_tokens với revoked_at đã set (test logout-all/logout thực sự chặn token cũ).
_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" -d '{"identifier":"hotro1.nentang@fams.com","password":"Admin@1234"}')
_s=$(echo "$_r" | tail -n 1); _b=$(echo "$_r" | head -n -1)
if [ "$_s" -eq 200 ]; then
    _own_token=$(echo "$_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
    _own_refresh=$(echo "$_b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('refreshToken',''))")
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/logout" -H "Authorization: Bearer $_own_token" \
        -H "Content-Type: application/json" -d "{\"refreshToken\":\"$_own_refresh\"}"
    echo "  + Refresh token đã bị thu hồi (revoked_at set) cho hotro1.nentang@fams.com — token cũ này không dùng lại được"
else
    echo "  ! Không đăng nhập được hotro1.nentang@fams.com để tạo refresh token đã thu hồi (HTTP $_s)"
fi
echo ""

# ── Historical Data (PostgreSQL direct insert) ────────────────────────────────

echo "--- Injecting historical data via PostgreSQL ---"
SEED_SQL="$(dirname "$0")/seed_historical.sql"
if [ ! -f "$SEED_SQL" ]; then
    echo "  ERROR: $SEED_SQL not found — skipping historical data"
elif command -v docker >/dev/null 2>&1 && docker exec "$DB_CONTAINER" true >/dev/null 2>&1; then
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" < "$SEED_SQL"
    echo "  Historical data injected (via docker exec)."
else
    PGPASSWORD="${DB_PASSWORD:-}" psql -h "${DB_HOST:-localhost}" -p "${DB_PORT:-5433}" \
        -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -v ON_ERROR_STOP=1 -f "$SEED_SQL"
    echo "  Historical data injected (via psql network connection)."
fi
echo ""

# ── Site-scoped role assignment (must run AFTER historical data — that's what creates the
#    SITE_SUPERVISOR logins for these two people) ────────────────────────────────────────
echo "=== Role giới hạn theo site (site-scope RBAC demo) ==="
mk_site_scoped_role "binh.tran@hoanglong.vn" "SITE_SUPERVISOR" "$HL_ID" "$HL_HN" \
    "Trần Thị Bình: SITE_SUPERVISOR giới hạn tại 'Trụ sở Hoàng Long Hà Nội' (không thấy site khác)"
mk_site_scoped_role "xuan.do@binhminh.vn" "SITE_SUPERVISOR" "$BM_ID" "$BM_PLANT" \
    "Đỗ Thị Xuân: SITE_SUPERVISOR giới hạn tại 'Nhà máy Bình Minh' (không thấy Kho A)"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== Seed complete ==="
echo ""
echo "  Mật khẩu mặc định cho MỌI tài khoản mẫu (platform admin, owner, nhân viên nền tảng,"
echo "  nhân viên có tài khoản đăng nhập): Admin@1234"
echo ""
echo "  Platform Admin : admin@fams.com"
echo "  Swagger UI     : $BASE_URL/swagger-ui.html"
echo ""
echo "  3 tenant chính (dữ liệu đầy đủ — 15 nhân viên, 12-13 site, 12-13 workspace mỗi công ty):"
echo "    Hoàng Long (acme-corp)        — chu.hoanglong@gmail.com — gói Pro"
echo "    Bình Minh  (beta-industries)  — giamdoc.binhminh@gmail.com — gói Pro"
echo "    Phương Nam (gamma-logistics)  — quang.phuongnam@gmail.com — gói Enterprise (yearly)"
echo ""
echo "  2 tenant biên (edge-case, giữ nguyên nhỏ gọn có chủ đích):"
echo "    Tia Sáng   (tia-sang-startup) — kimngan.tiasang@gmail.com — 5/5 nhân viên, gói Trial (chạm giới hạn)"
echo "    Đông Á     (dong-a-jsc)       — hanh.donga@gmail.com — gói Basic, SUSPENDED"
echo ""
echo "  10 tenant mới (nhẹ, đa dạng ngành/gói, 2 chủ sở hữu mỗi người 2 công ty):"
echo "    Việt Phát (viet-phat-retail) + Hoàng Gia F&B (hoang-gia-fnb) — owner.vietphat@gmail.com"
echo "    Phú Quý (phu-quy-mining) + Đại Dương (dai-duong-fishery)     — owner.phuquy@gmail.com"
echo "    Minh Châu, Thành Công, Vệ sinh VN, Sao Mai, Tân Phát, Việt Tín (CANCELLED sub) — 7 chủ riêng"
echo ""
echo "  Người đa công ty (cùng 1 tài khoản đăng nhập, 2 tenant khác nhau):"
echo "    Phạm Thị Dung   — HR_MANAGER tại Hoàng Long + SITE_SUPERVISOR tại Bình Minh (dung.pham.hr@gmail.com)"
echo "    Trương Văn Đạt  — EMPLOYEE tại Phương Nam + EMPLOYEE tại Tia Sáng (truong.van.dat@gmail.com)"
echo ""
echo "  Nền tảng: 1 platform admin + 12 nhân viên nền tảng (9x PLATFORM_STAFF,"
echo "  1x PLATFORM_SUPPORT_LEAD, 1x PLATFORM_BILLING_OPS, 1x PLATFORM_SECURITY_AUDITOR"
echo "  — 3 role nền tảng tùy chỉnh mới tạo)."
echo ""
echo "  Historical data: 30 ngày checkin, attendance summary, vi phạm, hồ sơ khuôn mặt,"
echo "  scheduled checks, thông báo, audit log... cho 5 tenant giàu dữ liệu nhất"
echo "  (3 tenant chính + Tia Sáng + Đông Á) — xem seed_historical.sql."
echo ""
