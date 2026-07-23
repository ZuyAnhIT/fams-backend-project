#!/usr/bin/env bash
# FAMS Demo Seed — Vietnamese dataset.
# Creates 5 demo tenants with sites, shifts, employees, assignments,
# departments, workspaces (+ members), invitations, IP whitelist, and
# random-check configs via API; then injects 30 days of historical
# checkins, attendance, violations, and notifications directly into
# PostgreSQL (see seed_historical.sql).
#
# Tenant roster:
#   acme-corp          Công ty CP Xây dựng Hoàng Long   (Pro, active)
#   beta-industries     Công ty TNHH Sản xuất Bình Minh (Basic, active)
#   gamma-logistics     Công ty CP Logistics Phương Nam (Enterprise, active)
#   tia-sang-startup    Công ty Khởi nghiệp Tia Sáng    (Trial, sits at the 5-employee limit)
#   dong-a-jsc          Công ty TNHH Đông Á             (Basic, suspended via API at the end)
#
# Two people work at two different tenants under one shared login
# (see the "Multi-tenant person linking" block near the end, run
# directly against Postgres since there is no API for it):
#   - Phạm Thị Dung: HR_MANAGER at Hoàng Long + SITE_SUPERVISOR at Bình Minh
#   - Trương Văn Đạt: EMPLOYEE at Phương Nam + EMPLOYEE at Tia Sáng
#
# Usage:
#   bash scripts/seed.sh
#   BASE_URL=http://localhost:8080 bash scripts/seed.sh
#
# Safe to run multiple times on the same database.

set -uo pipefail

# Load DB credentials from the repo's .env when run from the host (e.g. via
# `make seed`). When run inside the fams-seed container, these are already
# injected directly as environment variables by docker-compose, and no .env
# file is mounted there — the `[ -f ]` check below just skips silently.
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

echo "=== FAMS Demo Seed (Vietnamese dataset) ==="
echo "Target: $BASE_URL"
echo ""

# ── Login ─────────────────────────────────────────────────────────────────────

echo "Logging in as platform admin..."
_r=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
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
_plans=$(curl -s "$BASE_URL/api/v1/plans?size=20&activeOnly=false" \
    -H "Authorization: Bearer $TOKEN")
PLAN_TRIAL_ID=$(echo "$_plans" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='trial'),''))
")
PLAN_BASIC_ID=$(echo "$_plans" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='basic'),''))
")
PLAN_PRO_ID=$(echo "$_plans" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='pro'),''))
")
PLAN_ENTERPRISE_ID=$(echo "$_plans" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print(next((i['id'] for i in items if i['name']=='enterprise'),''))
")
echo "  trial=$PLAN_TRIAL_ID"
echo "  basic=$PLAN_BASIC_ID"
echo "  pro=$PLAN_PRO_ID"
echo "  enterprise=$PLAN_ENTERPRISE_ID"
echo ""

# ── Helper Functions ──────────────────────────────────────────────────────────

TENANT_ID=""
SITE_ID=""
SHIFT_ID=""
EMP_ID=""
DEPT_ID=""
WORKSPACE_ID=""
INVITATION_ID=""

# mk_tenant <name> <slug> [extra_json_fields] [industry]
mk_tenant() {
    local name="$1" slug="$2" extra="${3:-}" industry="${4:-}"
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"name\":\"$name\",\"slug\":\"$slug\"$extra}")
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
            # Tenant already existed (possibly from an older, English-named seed run) —
            # bring its display name/industry up to date so re-runs always converge.
            if [ -n "$TENANT_ID" ]; then
                curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID" \
                    -H "Content-Type: application/json" \
                    -H "Authorization: Bearer $TOKEN" \
                    -d "$(python3 -c "import json; print(json.dumps({'name':'$name','industry':'$industry'}))")"
            fi
        else
            TENANT_ID=""; echo "  Skipped: $name (could not fetch id)"
        fi
    else
        TENANT_ID=""; echo "  Error creating $name — HTTP $s"
    fi
}

# mk_subscription <plan_id> <plan_name>
mk_subscription() {
    local plan_id="$1" plan_name="$2"
    [ -z "$TENANT_ID" ] && return
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"planId\":\"$plan_id\",\"billingCycle\":\"MONTHLY\"}")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ] || [ "$s" -eq 200 ]; then
        echo "  Subscription: $plan_name plan — OK"
    elif [ "$s" -eq 409 ]; then
        r=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d "{\"planId\":\"$plan_id\",\"billingCycle\":\"MONTHLY\"}")
        s=$(echo "$r" | tail -n 1)
        if [ "$s" -eq 200 ]; then
            echo "  Subscription: $plan_name plan — updated"
        else
            echo "  Subscription: $plan_name plan — update HTTP $s"
        fi
    else
        echo "  Subscription: $plan_name plan — HTTP $s"
    fi
}

# mk_site <tenant_id> <name> <code> <address> <lat> <lon> [timezone]
mk_site() {
    local tid="$1" name="$2" code="$3" address="$4" lat="$5" lon="$6" tz="${7:-UTC}"
    SITE_ID=""
    local r s b
    local payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','code':'$code','address':'$address','latitude':$lat,'longitude':$lon,'timezone':'$tz','status':'active'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        SITE_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Site: $name (id=$SITE_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/sites?search=$code&size=10" \
            -H "Authorization: Bearer $TOKEN")
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

# mk_shift <tenant_id> <site_id> <name> <start_HH:mm> <end_HH:mm> <allow_overnight> [allow_overtime]
mk_shift() {
    local tid="$1" sid="$2" name="$3" start="$4" end_t="$5" overnight="${6:-false}" ot="${7:-false}"
    SHIFT_ID=""
    [ -z "$sid" ] && return
    local r s b
    local payload
    payload=$(python3 -c "
import json
print(json.dumps({
  'name': '$name',
  'startTime': '$start',
  'endTime': '$end_t',
  'allowOvernight': '$overnight'.lower() == 'true',
  'allowOvertime': '$ot'.lower() == 'true',
  'earlyCheckinMinutes': 15,
  'lateCheckoutMinutes': 15
}))
")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites/$sid/shifts" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        SHIFT_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "      + Shift: $name $start-$end_t (id=$SHIFT_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/sites/$sid/shifts?size=20" \
            -H "Authorization: Bearer $TOKEN")
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

# mk_department <tenant_id> <name> [description]
mk_department() {
    local tid="$1" name="$2" desc="${3:-}"
    DEPT_ID=""
    [ -z "$tid" ] && return
    local r s b payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','description':'$desc'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/departments" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        DEPT_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Phòng ban: $name (id=$DEPT_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/departments" \
            -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1); lb=$(echo "$lr" | head -n -1)
        DEPT_ID=$(echo "$lb" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',[])
match=[i for i in items if i.get('name')=='$name']
print(match[0]['id'] if match else '')
")
        echo "    ~ Phòng ban đã tồn tại: $name (id=$DEPT_ID)"
    else
        echo "    ! Lỗi tạo phòng ban: $name — HTTP $s"
    fi
}

# mk_employee <json_payload> <label>
mk_employee() {
    local payload="$1" label="$2"
    EMP_ID=""
    [ -z "$TENANT_ID" ] && return
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
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
        echo "    ! $label — HTTP $s"
    fi
}

# mk_status <emp_id> <status>
mk_status() {
    local emp_id="$1" new_status="$2"
    [ -z "$TENANT_ID" ] || [ -z "$emp_id" ] && return
    curl -s -o /dev/null \
        -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$emp_id/status" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"status\":\"$new_status\"}"
    echo "      → status → $new_status"
}

# mk_assignment <tenant_id> <site_id> <emp_id> <shift_id> [role] [start_date]
mk_assignment() {
    local tid="$1" sid="$2" emp_id="$3" shift_id="$4" role="${5:-worker}" start="${6:-2026-01-01}"
    [ -z "$emp_id" ] || [ -z "$sid" ] || [ -z "$shift_id" ] && return
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/sites/$sid/assignments" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"employeeId\":\"$emp_id\",\"shiftId\":\"$shift_id\",\"startDate\":\"$start\",\"role\":\"$role\"}")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        local aid
        aid=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "      → Assigned (role=$role, id=$aid)"
    elif [ "$s" -eq 409 ]; then
        echo "      → Already assigned"
    else
        echo "      ! Assignment error — HTTP $s"
    fi
}

# mk_workspace <tenant_id> <name> [type] [description]
mk_workspace() {
    local tid="$1" name="$2" type="${3:-department}" desc="${4:-}"
    WORKSPACE_ID=""
    local r s b
    local payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','type':'$type','description':'$desc'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/workspaces" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        WORKSPACE_ID=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Workspace: $name (id=$WORKSPACE_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants/$tid/workspaces?size=50" \
            -H "Authorization: Bearer $TOKEN")
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

# mk_workspace_member <tenant_id> <workspace_id> <employee_id> [role]
mk_workspace_member() {
    local tid="$1" wsid="$2" emp_id="$3" role="${4:-member}"
    [ -z "$wsid" ] || [ -z "$emp_id" ] && return
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/workspaces/$wsid/members" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
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

# mk_rand_config <tenant_id> [check_mode] [checks_per_shift] [response_window_s]
mk_rand_config() {
    local tid="$1" mode="${2:-location_only}" cps="${3:-2}" window="${4:-300}"
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/random-check-configs/tenant-default" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
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

# mk_ip_whitelist <tenant_id> <ip> <label> [scope]
# NOTE: the backend's POST /ip-whitelists does not actually reject duplicate
# ipAddress values for the same tenant (its own Swagger doc claims 409, but
# it always returns 201) — so this function checks the existing list itself
# before creating, to keep re-runs of this seed script idempotent regardless.
mk_ip_whitelist() {
    local tid="$1" ip="$2" label="$3" scope="${4:-all}"
    [ -z "$tid" ] && return
    local existing
    existing=$(curl -s "$BASE_URL/api/v1/tenants/$tid/ip-whitelists?size=100" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[])
print('yes' if any(i.get('ipAddress')=='$ip' for i in items) else 'no')
" 2>/dev/null)
    if [ "$existing" = "yes" ]; then
        echo "    ~ IP whitelist đã tồn tại: $ip"
        return
    fi
    local r s payload
    payload=$(python3 -c "import json; print(json.dumps({'ipAddress':'$ip','label':'$label','scope':'$scope'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/ip-whitelists" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 201 ]; then
        echo "    + IP whitelist: $ip ($label)"
    elif [ "$s" -eq 409 ]; then
        echo "    ~ IP whitelist đã tồn tại: $ip"
    else
        echo "    ! Lỗi IP whitelist: $ip — HTTP $s"
    fi
}

# mk_invitation <tenant_id> <email> <first> <last> [phone]
# NOTE: the backend allows re-inviting an email whose previous invitation was
# already cancelled/expired (a real, intentional business rule — not a bug),
# so a plain 409-on-duplicate check isn't enough to keep this idempotent
# across repeated seed runs. Check the tenant's invitation list ourselves
# first and skip entirely if this email has any invitation already.
mk_invitation() {
    local tid="$1" email="$2" first="$3" last="$4" phone="${5:-}"
    INVITATION_ID=""
    [ -z "$tid" ] && return
    local existing
    existing=$(curl -s "$BASE_URL/api/v1/tenants/$tid/invitations?size=100" \
        -H "Authorization: Bearer $TOKEN" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('data',{}).get('content',[]) if isinstance(d.get('data'),dict) else d.get('data',[])
match=[i for i in items if i.get('email')=='$email']
print(match[0]['id'] if match else '')
" 2>/dev/null)
    if [ -n "$existing" ]; then
        INVITATION_ID="$existing"
        echo "    ~ Lời mời đã tồn tại: $email"
        return
    fi
    local r s b payload
    payload=$(python3 -c "import json; print(json.dumps({'email':'$email','firstName':'$first','lastName':'$last','phone':'$phone'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/invitations" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
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

# mk_cancel_invitation <tenant_id> <invitation_id>
mk_cancel_invitation() {
    local tid="$1" iid="$2"
    [ -z "$iid" ] && return
    curl -s -o /dev/null -X DELETE "$BASE_URL/api/v1/tenants/$tid/invitations/$iid" \
        -H "Authorization: Bearer $TOKEN"
    echo "      → Lời mời đã hủy"
}

# mk_suspend_tenant <tenant_id>
mk_suspend_tenant() {
    local tid="$1"
    [ -z "$tid" ] && return
    local r s
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/suspend" \
        -H "Authorization: Bearer $TOKEN")
    s=$(echo "$r" | tail -n 1)
    if [ "$s" -eq 200 ]; then
        echo "  → Tenant đã bị tạm ngưng (suspended)"
    elif [ "$s" -eq 400 ]; then
        echo "  ~ Tenant đã ở trạng thái suspended/cancelled từ trước"
    else
        echo "  ! Lỗi suspend tenant — HTTP $s"
    fi
}

# ── Tenant 1: Công ty CP Xây dựng Hoàng Long ─────────────────────────────────

echo "=== Tenant 1: Công ty CP Xây dựng Hoàng Long (acme-corp) ==="
mk_tenant "Công ty CP Xây dựng Hoàng Long" "acme-corp" ',"industry":"Xây dựng","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Xây dựng"
HL_ID="$TENANT_ID"

if [ -n "$HL_ID" ]; then
    [ -n "$PLAN_PRO_ID" ] && mk_subscription "$PLAN_PRO_ID" "pro"

    echo "  Phòng ban:"
    mk_department "$HL_ID" "Kỹ thuật" "Kỹ sư và công nhân xây dựng"; HL_DEPT_KT="$DEPT_ID"
    mk_department "$HL_ID" "Vận hành" "Vận hành công trường và hậu cần"; HL_DEPT_VH="$DEPT_ID"
    mk_department "$HL_ID" "Nhân sự" "Quản lý nhân sự và tuyển dụng"; HL_DEPT_NS="$DEPT_ID"
    mk_department "$HL_ID" "An toàn & Chất lượng" "An toàn lao động và kiểm định chất lượng"; HL_DEPT_AT="$DEPT_ID"

    echo "  Sites:"
    mk_site "$HL_ID" "Trụ sở Hoàng Long Hà Nội" "HL-HN" "15 Lê Lợi, Hoàn Kiếm, Hà Nội" "21.0285" "105.8542" "Asia/Ho_Chi_Minh"
    HL_HN="$SITE_ID"
    mk_site "$HL_ID" "Văn phòng Hoàng Long TP.HCM" "HL-HCM" "100 Nguyễn Huệ, Quận 1, TP.HCM" "10.7769" "106.7009" "Asia/Ho_Chi_Minh"
    HL_HCM="$SITE_ID"
    mk_site "$HL_ID" "Chi nhánh Hoàng Long Đà Nẵng" "HL-DN" "45 Trần Phú, Hải Châu, Đà Nẵng" "16.0544" "108.2022" "Asia/Ho_Chi_Minh"
    HL_DN="$SITE_ID"

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
    mk_employee "{\"firstName\":\"An\",\"lastName\":\"Nguyễn Văn\",\"email\":\"an.nguyen@hoanglong.vn\",\"employeeCode\":\"HL-001\",\"position\":\"Kỹ sư cao cấp\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_DEPT_KT\",\"hiredDate\":\"2023-03-15\",\"phone\":\"+84901000001\"}" "Nguyễn Văn An (Kỹ sư cao cấp)"
    HL_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Bình\",\"lastName\":\"Trần Thị\",\"email\":\"binh.tran@hoanglong.vn\",\"employeeCode\":\"HL-002\",\"position\":\"Giám sát công trường\",\"department\":\"Vận hành\",\"departmentId\":\"$HL_DEPT_VH\",\"hiredDate\":\"2022-07-01\",\"phone\":\"+84901000002\"}" "Trần Thị Bình (Giám sát công trường)"
    HL_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Cường\",\"lastName\":\"Lê Văn\",\"email\":\"cuong.le@hoanglong.vn\",\"employeeCode\":\"HL-003\",\"position\":\"Kỹ thuật viên\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_DEPT_KT\",\"hiredDate\":\"2021-09-01\",\"phone\":\"+84901000003\"}" "Lê Văn Cường (Kỹ thuật viên) [inactive]"
    HL_E3="$EMP_ID"
    mk_status "$HL_E3" "inactive"
    mk_employee "{\"firstName\":\"Dung\",\"lastName\":\"Phạm Thị\",\"email\":\"dung.pham@hoanglong.vn\",\"employeeCode\":\"HL-004\",\"position\":\"Trưởng phòng Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$HL_DEPT_NS\",\"hiredDate\":\"2020-01-10\",\"phone\":\"+84901000004\"}" "Phạm Thị Dung (Trưởng phòng Nhân sự) [đa công ty]"
    HL_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Giang\",\"lastName\":\"Hoàng Thị\",\"email\":\"giang.hoang@hoanglong.vn\",\"employeeCode\":\"HL-005\",\"position\":\"Cán bộ An toàn\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_DEPT_AT\",\"hiredDate\":\"2023-06-01\",\"phone\":\"+84901000005\"}" "Hoàng Thị Giang (Cán bộ An toàn)"
    HL_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Hùng\",\"lastName\":\"Vũ Văn\",\"email\":\"hung.vu@hoanglong.vn\",\"employeeCode\":\"HL-006\",\"position\":\"Trưởng nhóm Xây dựng\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_DEPT_KT\",\"hiredDate\":\"2022-02-15\",\"phone\":\"+84901000006\"}" "Vũ Văn Hùng (Trưởng nhóm Xây dựng)"
    HL_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Khôi\",\"lastName\":\"Đặng Văn\",\"email\":\"khoi.dang@hoanglong.vn\",\"employeeCode\":\"HL-007\",\"position\":\"Kỹ sư cao cấp\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_DEPT_KT\",\"hiredDate\":\"2021-11-30\",\"phone\":\"+84901000007\"}" "Đặng Văn Khôi (Kỹ sư cao cấp)"
    HL_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Lan\",\"lastName\":\"Bùi Thị\",\"email\":\"lan.bui@hoanglong.vn\",\"employeeCode\":\"HL-008\",\"position\":\"Chuyên viên Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$HL_DEPT_NS\",\"hiredDate\":\"2024-01-15\",\"phone\":\"+84901000008\"}" "Bùi Thị Lan (Chuyên viên Nhân sự)"
    HL_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Minh\",\"lastName\":\"Ngô Văn\",\"email\":\"minh.ngo@hoanglong.vn\",\"employeeCode\":\"HL-009\",\"position\":\"Thanh tra công trường\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_DEPT_AT\",\"hiredDate\":\"2023-08-01\",\"phone\":\"+84901000009\"}" "Ngô Văn Minh (Thanh tra công trường)"
    HL_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Ngọc\",\"lastName\":\"Đỗ Thị\",\"email\":\"ngoc.do@hoanglong.vn\",\"employeeCode\":\"HL-010\",\"position\":\"Điều phối viên dự án\",\"department\":\"Vận hành\",\"departmentId\":\"$HL_DEPT_VH\",\"hiredDate\":\"2022-04-20\",\"phone\":\"+84901000010\"}" "Đỗ Thị Ngọc (Điều phối viên dự án)"
    HL_E10="$EMP_ID"
    mk_employee "{\"firstName\":\"Phúc\",\"lastName\":\"Phan Văn\",\"email\":\"phuc.phan@hoanglong.vn\",\"employeeCode\":\"HL-011\",\"position\":\"Công nhân xây dựng\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$HL_DEPT_KT\",\"hiredDate\":\"2024-03-01\",\"phone\":\"+84901000011\"}" "Phan Văn Phúc (Công nhân xây dựng)"
    HL_E11="$EMP_ID"
    mk_employee "{\"firstName\":\"Quỳnh\",\"lastName\":\"Trịnh Thị\",\"email\":\"quynh.trinh@hoanglong.vn\",\"employeeCode\":\"HL-012\",\"position\":\"Kỹ sư QA\",\"department\":\"An toàn & Chất lượng\",\"departmentId\":\"$HL_DEPT_AT\",\"hiredDate\":\"2023-11-15\",\"phone\":\"+84901000012\"}" "Trịnh Thị Quỳnh (Kỹ sư QA) [terminated]"
    HL_E12="$EMP_ID"
    mk_status "$HL_E12" "terminated"

    echo "  Phân công (Hà Nội — Ca sáng):"
    for emp in "$HL_E1" "$HL_E5" "$HL_E6" "$HL_E7" "$HL_E9"; do
        [ -n "$emp" ] && mk_assignment "$HL_ID" "$HL_HN" "$emp" "$HL_HN_SANG" "worker" "2026-01-01"
    done
    [ -n "$HL_E2" ] && mk_assignment "$HL_ID" "$HL_HN" "$HL_E2" "$HL_HN_SANG" "supervisor" "2026-01-01"

    echo "  Phân công (Hà Nội — Ca chiều):"
    [ -n "$HL_E11" ] && [ -n "$HL_HN_CHIEU" ] && mk_assignment "$HL_ID" "$HL_HN" "$HL_E11" "$HL_HN_CHIEU" "worker" "2026-01-01"

    echo "  Phân công (TP.HCM — Ca sáng):"
    for emp in "$HL_E4" "$HL_E8" "$HL_E10"; do
        [ -n "$emp" ] && [ -n "$HL_HCM_SANG" ] && mk_assignment "$HL_ID" "$HL_HCM" "$emp" "$HL_HCM_SANG" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$HL_ID" "Phòng Kỹ thuật" "department" "Kỹ sư và đội thi công"; HL_WS_KT="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Phòng Vận hành" "department" "Vận hành công trường và hậu cần"; HL_WS_VH="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Phòng Nhân sự" "department" "Nhân sự và tuân thủ"; HL_WS_NS="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Phòng An toàn & Chất lượng" "department" "An toàn lao động và kiểm định chất lượng"; HL_WS_AT="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Kỹ thuật Hà Nội" "team" "Kỹ sư làm việc tại trụ sở Hà Nội"; HL_WS_TEAM_HN="$WORKSPACE_ID"
    mk_workspace "$HL_ID" "Đội Hành chính TP.HCM" "team" "Nhân viên hành chính tại văn phòng TP.HCM"; HL_WS_TEAM_HCM="$WORKSPACE_ID"

    echo "  Thành viên workspace:"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E1" "lead"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E6" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_KT" "$HL_E7" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_NS" "$HL_E4" "manager"
    mk_workspace_member "$HL_ID" "$HL_WS_NS" "$HL_E8" "member"
    mk_workspace_member "$HL_ID" "$HL_WS_TEAM_HN" "$HL_E9" "member"

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
fi
echo ""

# ── Tenant 2: Công ty TNHH Sản xuất Bình Minh ────────────────────────────────

echo "=== Tenant 2: Công ty TNHH Sản xuất Bình Minh (beta-industries) ==="
mk_tenant "Công ty TNHH Sản xuất Bình Minh" "beta-industries" ',"industry":"Sản xuất","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Sản xuất"
BM_ID="$TENANT_ID"

if [ -n "$BM_ID" ]; then
    [ -n "$PLAN_BASIC_ID" ] && mk_subscription "$PLAN_BASIC_ID" "basic"

    echo "  Phòng ban:"
    mk_department "$BM_ID" "Sản xuất" "Dây chuyền sản xuất và lắp ráp"; BM_DEPT_SX="$DEPT_ID"
    mk_department "$BM_ID" "Kiểm soát chất lượng" "Kiểm định và đảm bảo chất lượng"; BM_DEPT_QC="$DEPT_ID"
    mk_department "$BM_ID" "Bảo trì" "Bảo trì thiết bị và nhà xưởng"; BM_DEPT_BT="$DEPT_ID"
    mk_department "$BM_ID" "An toàn" "An toàn lao động tại nhà máy"; BM_DEPT_AT="$DEPT_ID"

    echo "  Sites:"
    mk_site "$BM_ID" "Nhà máy Bình Minh" "BM-MAIN" "KCN Tân Bình, Bình Dương" "10.9350" "106.6900" "Asia/Ho_Chi_Minh"
    BM_PLANT="$SITE_ID"
    mk_site "$BM_ID" "Kho Bình Minh A" "BM-WH-A" "Lô B12, KCN Sóng Thần 2, Bình Dương" "10.8250" "106.7100" "Asia/Ho_Chi_Minh"
    BM_WH_A="$SITE_ID"

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
    mk_employee "{\"firstName\":\"Xuân\",\"lastName\":\"Đỗ Thị\",\"email\":\"xuan.do@binhminh.vn\",\"employeeCode\":\"BM-001\",\"position\":\"Tổ trưởng sản xuất\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_DEPT_SX\",\"hiredDate\":\"2024-01-01\",\"phone\":\"+84902000001\"}" "Đỗ Thị Xuân (Tổ trưởng sản xuất)"
    BM_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Yên\",\"lastName\":\"Nguyễn Văn\",\"email\":\"yen.nguyen@binhminh.vn\",\"employeeCode\":\"BM-002\",\"position\":\"Nhân viên kiểm định chất lượng\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_DEPT_QC\",\"hiredDate\":\"2023-06-15\",\"phone\":\"+84902000002\"}" "Nguyễn Văn Yên (Nhân viên kiểm định chất lượng)"
    BM_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Dung\",\"lastName\":\"Phạm Thị\",\"email\":\"dung.pham@binhminh.vn\",\"employeeCode\":\"BM-003\",\"position\":\"Giám sát công trường\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_DEPT_SX\",\"hiredDate\":\"2022-09-01\",\"phone\":\"+84902000003\"}" "Phạm Thị Dung (Giám sát công trường) [đa công ty]"
    BM_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Lê Văn\",\"email\":\"dat.le@binhminh.vn\",\"employeeCode\":\"BM-004\",\"position\":\"Chuyên viên QA cao cấp\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_DEPT_QC\",\"hiredDate\":\"2021-04-20\",\"phone\":\"+84902000004\"}" "Lê Văn Đạt (Chuyên viên QA cao cấp)"
    BM_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Diễm\",\"lastName\":\"Phạm Thị\",\"email\":\"diem.pham@binhminh.vn\",\"employeeCode\":\"BM-005\",\"position\":\"Công nhân vận hành máy\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_DEPT_SX\",\"hiredDate\":\"2023-12-01\",\"phone\":\"+84902000005\"}" "Phạm Thị Diễm (Công nhân vận hành máy)"
    BM_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Kiên\",\"lastName\":\"Vũ Văn\",\"email\":\"kien.vu@binhminh.vn\",\"employeeCode\":\"BM-006\",\"position\":\"Kỹ sư bảo trì\",\"department\":\"Bảo trì\",\"departmentId\":\"$BM_DEPT_BT\",\"hiredDate\":\"2022-07-10\",\"phone\":\"+84902000006\"}" "Vũ Văn Kiên (Kỹ sư bảo trì)"
    BM_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Loan\",\"lastName\":\"Hoàng Thị\",\"email\":\"loan.hoang@binhminh.vn\",\"employeeCode\":\"BM-007\",\"position\":\"Điều phối An toàn\",\"department\":\"An toàn\",\"departmentId\":\"$BM_DEPT_AT\",\"hiredDate\":\"2023-03-15\",\"phone\":\"+84902000007\"}" "Hoàng Thị Loan (Điều phối An toàn)"
    BM_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Nam\",\"lastName\":\"Bùi Văn\",\"email\":\"nam.bui@binhminh.vn\",\"employeeCode\":\"BM-008\",\"position\":\"Tổ trưởng dây chuyền\",\"department\":\"Sản xuất\",\"departmentId\":\"$BM_DEPT_SX\",\"hiredDate\":\"2022-11-01\",\"phone\":\"+84902000008\"}" "Bùi Văn Nam (Tổ trưởng dây chuyền)"
    BM_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Oanh\",\"lastName\":\"Ngô Thị\",\"email\":\"oanh.ngo@binhminh.vn\",\"employeeCode\":\"BM-009\",\"position\":\"Kỹ sư quy trình\",\"department\":\"Bảo trì\",\"departmentId\":\"$BM_DEPT_BT\",\"hiredDate\":\"2024-02-15\",\"phone\":\"+84902000009\"}" "Ngô Thị Oanh (Kỹ sư quy trình)"
    BM_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Phát\",\"lastName\":\"Đặng Văn\",\"email\":\"phat.dang@binhminh.vn\",\"employeeCode\":\"BM-010\",\"position\":\"Chuyên viên phân tích chất lượng\",\"department\":\"Kiểm soát chất lượng\",\"departmentId\":\"$BM_DEPT_QC\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84902000010\"}" "Đặng Văn Phát (Chuyên viên phân tích chất lượng)"
    BM_E10="$EMP_ID"

    echo "  Phân công (Nhà máy — Ca ngày):"
    for emp in "$BM_E1" "$BM_E5" "$BM_E7" "$BM_E9"; do
        [ -n "$emp" ] && [ -n "$BM_PLANT_NGAY" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$emp" "$BM_PLANT_NGAY" "worker" "2026-01-01"
    done
    [ -n "$BM_E3" ] && [ -n "$BM_PLANT_NGAY" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$BM_E3" "$BM_PLANT_NGAY" "supervisor" "2026-01-01"

    echo "  Phân công (Nhà máy — Ca tối):"
    for emp in "$BM_E6" "$BM_E8"; do
        [ -n "$emp" ] && [ -n "$BM_PLANT_TOI" ] && mk_assignment "$BM_ID" "$BM_PLANT" "$emp" "$BM_PLANT_TOI" "worker" "2026-01-01"
    done

    echo "  Phân công (Kho A — Ca ngày):"
    for emp in "$BM_E2" "$BM_E4" "$BM_E10"; do
        [ -n "$emp" ] && [ -n "$BM_WH_NGAY" ] && mk_assignment "$BM_ID" "$BM_WH_A" "$emp" "$BM_WH_NGAY" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$BM_ID" "Sản xuất" "department" "Dây chuyền và lắp ráp"; BM_WS_SX="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Kiểm soát chất lượng" "department" "Đảm bảo và kiểm định chất lượng"; BM_WS_QC="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Bảo trì" "department" "Bảo trì thiết bị và nhà xưởng"; BM_WS_BT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "An toàn" "department" "An toàn và sức khỏe lao động"; BM_WS_AT="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Đội Sản xuất Ca ngày" "team" "Đội sản xuất ca ngày tại nhà máy chính"; BM_WS_TEAM_A="$WORKSPACE_ID"
    mk_workspace "$BM_ID" "Đội Kho vận" "team" "Đội kho vận và hậu cần"; BM_WS_TEAM_KHO="$WORKSPACE_ID"

    echo "  Thành viên workspace:"
    mk_workspace_member "$BM_ID" "$BM_WS_SX" "$BM_E1" "lead"
    mk_workspace_member "$BM_ID" "$BM_WS_SX" "$BM_E3" "manager"
    mk_workspace_member "$BM_ID" "$BM_WS_QC" "$BM_E2" "member"
    mk_workspace_member "$BM_ID" "$BM_WS_QC" "$BM_E4" "lead"
    mk_workspace_member "$BM_ID" "$BM_WS_TEAM_KHO" "$BM_E10" "member"

    echo "  Lời mời nhân viên:"
    mk_invitation "$BM_ID" "yen.pham.moi@binhminh.vn" "Yến" "Phạm Thị" "+84906222221"
    mk_invitation "$BM_ID" "hung.le.moi@binhminh.vn" "Hùng" "Lê Văn" "+84906222222"
    HUNG_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$BM_ID" "$HUNG_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$BM_ID" "location_only" "3" "240"
fi
echo ""

# ── Tenant 3: Công ty CP Logistics Phương Nam ────────────────────────────────

echo "=== Tenant 3: Công ty CP Logistics Phương Nam (gamma-logistics) ==="
mk_tenant "Công ty CP Logistics Phương Nam" "gamma-logistics" ',"industry":"Logistics","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Logistics"
PN_ID="$TENANT_ID"

if [ -n "$PN_ID" ]; then
    [ -n "$PLAN_ENTERPRISE_ID" ] && mk_subscription "$PLAN_ENTERPRISE_ID" "enterprise"

    echo "  Phòng ban:"
    mk_department "$PN_ID" "Điều hành" "Điều hành và điều phối logistics"; PN_DEPT_DH="$DEPT_ID"
    mk_department "$PN_ID" "Đội xe" "Quản lý lái xe và phương tiện"; PN_DEPT_XE="$DEPT_ID"
    mk_department "$PN_ID" "Kho vận" "Kho bãi và tồn kho"; PN_DEPT_KHO="$DEPT_ID"
    mk_department "$PN_ID" "Nhân sự" "Nhân sự và quan hệ lao động"; PN_DEPT_NS="$DEPT_ID"

    echo "  Sites:"
    mk_site "$PN_ID" "Kho vận Phương Nam - Bắc" "PN-N" "KCN Nội Bài, Sóc Sơn, Hà Nội" "21.2210" "105.7950" "Asia/Ho_Chi_Minh"
    PN_N="$SITE_ID"
    mk_site "$PN_ID" "Kho vận Phương Nam - Nam" "PN-S" "KCN Hiệp Phước, Nhà Bè, TP.HCM" "10.6550" "106.7450" "Asia/Ho_Chi_Minh"
    PN_S="$SITE_ID"
    mk_site "$PN_ID" "Trung tâm điều phối Phương Nam" "PN-HUB" "27 Trường Chinh, Thanh Khê, Đà Nẵng" "16.0710" "108.1990" "Asia/Ho_Chi_Minh"
    PN_HUB="$SITE_ID"

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
    mk_employee "{\"firstName\":\"Quang\",\"lastName\":\"Trịnh Văn\",\"email\":\"quang.trinh@phuongnam.vn\",\"employeeCode\":\"PN-001\",\"position\":\"Giám đốc Logistics\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_DEPT_DH\",\"hiredDate\":\"2020-05-01\",\"phone\":\"+84903000001\"}" "Trịnh Văn Quang (Giám đốc Logistics)"
    PN_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Hồng\",\"lastName\":\"Lý Thị\",\"email\":\"hong.ly@phuongnam.vn\",\"employeeCode\":\"PN-002\",\"position\":\"Trưởng phòng Vận hành\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_DEPT_DH\",\"hiredDate\":\"2021-02-15\",\"phone\":\"+84903000002\"}" "Lý Thị Hồng (Trưởng phòng Vận hành)"
    PN_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Sơn\",\"lastName\":\"Mai Văn\",\"email\":\"son.mai@phuongnam.vn\",\"employeeCode\":\"PN-003\",\"position\":\"Điều phối viên\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_DEPT_DH\",\"hiredDate\":\"2022-08-01\",\"phone\":\"+84903000003\"}" "Mai Văn Sơn (Điều phối viên)"
    PN_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Thảo\",\"lastName\":\"Đinh Thị\",\"email\":\"thao.dinh@phuongnam.vn\",\"employeeCode\":\"PN-004\",\"position\":\"Trưởng đội xe\",\"department\":\"Đội xe\",\"departmentId\":\"$PN_DEPT_XE\",\"hiredDate\":\"2021-10-20\",\"phone\":\"+84903000004\"}" "Đinh Thị Thảo (Trưởng đội xe)"
    PN_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Tùng\",\"lastName\":\"Cao Văn\",\"email\":\"tung.cao@phuongnam.vn\",\"employeeCode\":\"PN-005\",\"position\":\"Lái xe cao cấp\",\"department\":\"Đội xe\",\"departmentId\":\"$PN_DEPT_XE\",\"hiredDate\":\"2020-11-01\",\"phone\":\"+84903000005\"}" "Cao Văn Tùng (Lái xe cao cấp)"
    PN_E5="$EMP_ID"
    mk_employee "{\"firstName\":\"Uyên\",\"lastName\":\"Lương Thị\",\"email\":\"uyen.luong@phuongnam.vn\",\"employeeCode\":\"PN-006\",\"position\":\"Lái xe\",\"department\":\"Đội xe\",\"departmentId\":\"$PN_DEPT_XE\",\"hiredDate\":\"2023-01-15\",\"phone\":\"+84903000006\"}" "Lương Thị Uyên (Lái xe)"
    PN_E6="$EMP_ID"
    mk_employee "{\"firstName\":\"Vinh\",\"lastName\":\"Huỳnh Văn\",\"email\":\"vinh.huynh@phuongnam.vn\",\"employeeCode\":\"PN-007\",\"position\":\"Tổ trưởng kho\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_DEPT_KHO\",\"hiredDate\":\"2022-04-01\",\"phone\":\"+84903000007\"}" "Huỳnh Văn Vinh (Tổ trưởng kho)"
    PN_E7="$EMP_ID"
    mk_employee "{\"firstName\":\"Xuân\",\"lastName\":\"Tô Thị\",\"email\":\"xuan.to@phuongnam.vn\",\"employeeCode\":\"PN-008\",\"position\":\"Nhân viên kiểm tra chất lượng\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_DEPT_KHO\",\"hiredDate\":\"2023-07-01\",\"phone\":\"+84903000008\"}" "Tô Thị Xuân (Nhân viên kiểm tra chất lượng)"
    PN_E8="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Trương Văn\",\"email\":\"dat.truong@phuongnam.vn\",\"employeeCode\":\"PN-009\",\"position\":\"Nhân viên điều phối tuyến\",\"department\":\"Điều hành\",\"departmentId\":\"$PN_DEPT_DH\",\"hiredDate\":\"2024-01-10\",\"phone\":\"+84903000009\"}" "Trương Văn Đạt (Nhân viên điều phối tuyến) [đa công ty]"
    PN_E9="$EMP_ID"
    mk_employee "{\"firstName\":\"Yến\",\"lastName\":\"Đào Thị\",\"email\":\"yen.dao@phuongnam.vn\",\"employeeCode\":\"PN-010\",\"position\":\"Trưởng phòng Nhân sự\",\"department\":\"Nhân sự\",\"departmentId\":\"$PN_DEPT_NS\",\"hiredDate\":\"2021-06-01\",\"phone\":\"+84903000010\"}" "Đào Thị Yến (Trưởng phòng Nhân sự)"
    PN_E10="$EMP_ID"
    mk_employee "{\"firstName\":\"Bảo\",\"lastName\":\"Vương Văn\",\"email\":\"bao.vuong@phuongnam.vn\",\"employeeCode\":\"PN-011\",\"position\":\"Nhân viên vận hành xe nâng\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_DEPT_KHO\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84903000011\"}" "Vương Văn Bảo (Nhân viên vận hành xe nâng)"
    PN_E11="$EMP_ID"
    mk_employee "{\"firstName\":\"Cẩm\",\"lastName\":\"Chu Thị\",\"email\":\"cam.chu@phuongnam.vn\",\"employeeCode\":\"PN-012\",\"position\":\"Chuyên viên phân tích tồn kho\",\"department\":\"Kho vận\",\"departmentId\":\"$PN_DEPT_KHO\",\"hiredDate\":\"2024-02-01\",\"phone\":\"+84903000012\"}" "Chu Thị Cẩm (Chuyên viên phân tích tồn kho)"
    PN_E12="$EMP_ID"

    echo "  Phân công (Kho Bắc — Ca sáng):"
    for emp in "$PN_E1" "$PN_E3" "$PN_E9"; do
        [ -n "$emp" ] && [ -n "$PN_N_SANG" ] && mk_assignment "$PN_ID" "$PN_N" "$emp" "$PN_N_SANG" "supervisor" "2026-01-01"
    done
    [ -n "$PN_E5" ] && [ -n "$PN_N_SANG" ] && mk_assignment "$PN_ID" "$PN_N" "$PN_E5" "$PN_N_SANG" "worker" "2026-01-01"

    echo "  Phân công (Kho Bắc — Ca chiều):"
    for emp in "$PN_E6" "$PN_E7"; do
        [ -n "$emp" ] && [ -n "$PN_N_CHIEU" ] && mk_assignment "$PN_ID" "$PN_N" "$emp" "$PN_N_CHIEU" "worker" "2026-01-01"
    done

    echo "  Phân công (Kho Nam — Ca sáng):"
    for emp in "$PN_E4" "$PN_E8" "$PN_E11" "$PN_E12"; do
        [ -n "$emp" ] && [ -n "$PN_S_SANG" ] && mk_assignment "$PN_ID" "$PN_S" "$emp" "$PN_S_SANG" "worker" "2026-01-01"
    done
    [ -n "$PN_E2" ] && [ -n "$PN_S_SANG" ] && mk_assignment "$PN_ID" "$PN_S" "$PN_E2" "$PN_S_SANG" "supervisor" "2026-01-01"

    echo "  Phân công (Trung tâm điều phối):"
    [ -n "$PN_E10" ] && [ -n "$PN_HUB_TC" ] && mk_assignment "$PN_ID" "$PN_HUB" "$PN_E10" "$PN_HUB_TC" "worker" "2026-01-01"

    echo "  Workspaces:"
    mk_workspace "$PN_ID" "Điều hành" "department" "Điều hành và điều phối logistics"; PN_WS_DH="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Quản lý Đội xe" "department" "Quản lý lái xe và phương tiện"; PN_WS_XE="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Kho vận" "department" "Kho bãi và tồn kho"; PN_WS_KHO="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Chất lượng" "department" "Kiểm soát chất lượng và tuân thủ"; PN_WS_CL="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Nhân sự" "department" "Nhân sự và quan hệ lao động"; PN_WS_NS="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội Kho Hà Nội" "team" "Đội làm việc tại Kho vận Phương Nam - Bắc"; PN_WS_TEAM_HN="$WORKSPACE_ID"
    mk_workspace "$PN_ID" "Đội Kho TP.HCM" "team" "Đội làm việc tại Kho vận Phương Nam - Nam"; PN_WS_TEAM_HCM="$WORKSPACE_ID"

    echo "  Thành viên workspace:"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E1" "manager"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E2" "lead"
    mk_workspace_member "$PN_ID" "$PN_WS_DH" "$PN_E9" "member"
    mk_workspace_member "$PN_ID" "$PN_WS_XE" "$PN_E4" "lead"
    mk_workspace_member "$PN_ID" "$PN_WS_XE" "$PN_E5" "member"
    mk_workspace_member "$PN_ID" "$PN_WS_KHO" "$PN_E7" "lead"

    echo "  Lời mời nhân viên:"
    mk_invitation "$PN_ID" "linh.nguyen.moi@phuongnam.vn" "Linh" "Nguyễn Thị" "+84906333331"
    mk_invitation "$PN_ID" "phong.vu.moi@phuongnam.vn" "Phong" "Vũ Văn" "+84906333332"
    PHONG_INVITATION_ID="$INVITATION_ID"
    mk_cancel_invitation "$PN_ID" "$PHONG_INVITATION_ID"

    echo "  Random check config:"
    mk_rand_config "$PN_ID" "location_only" "2" "360"
fi
echo ""

# ── Tenant 4: Công ty Khởi nghiệp Tia Sáng (trial, sát giới hạn gói) ─────────

echo "=== Tenant 4: Công ty Khởi nghiệp Tia Sáng (tia-sang-startup, TRIAL) ==="
mk_tenant "Công ty Khởi nghiệp Tia Sáng" "tia-sang-startup" ',"industry":"Công nghệ","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Công nghệ"
TS_ID="$TENANT_ID"

if [ -n "$TS_ID" ]; then
    [ -n "$PLAN_TRIAL_ID" ] && mk_subscription "$PLAN_TRIAL_ID" "trial"

    echo "  Phòng ban:"
    mk_department "$TS_ID" "Kỹ thuật" "Phát triển sản phẩm"; TS_DEPT_KT="$DEPT_ID"
    mk_department "$TS_ID" "Marketing" "Marketing và tăng trưởng"; TS_DEPT_MKT="$DEPT_ID"
    mk_department "$TS_ID" "Điều hành" "Ban lãnh đạo"; TS_DEPT_DH="$DEPT_ID"

    echo "  Sites:"
    mk_site "$TS_ID" "Văn phòng Tia Sáng" "TS-HN" "25 Cầu Giấy, Cầu Giấy, Hà Nội" "21.0333" "105.7926" "Asia/Ho_Chi_Minh"
    TS_HN="$SITE_ID"

    echo "  Ca làm việc:"
    TS_HANHCHINH=""
    if [ -n "$TS_HN" ]; then
        mk_shift "$TS_ID" "$TS_HN" "Ca hành chính" "08:30" "17:30" "false" "false"; TS_HANHCHINH="$SHIFT_ID"
    fi

    echo "  Nhân viên (5/5 — chạm giới hạn gói Trial):"
    mk_employee "{\"firstName\":\"Ngân\",\"lastName\":\"Nguyễn Thị Kim\",\"email\":\"ngan.nguyen@tiasang.vn\",\"employeeCode\":\"TS-001\",\"position\":\"Nhà sáng lập kiêm CEO\",\"department\":\"Điều hành\",\"departmentId\":\"$TS_DEPT_DH\",\"hiredDate\":\"2024-06-01\",\"phone\":\"+84904000001\"}" "Nguyễn Thị Kim Ngân (Nhà sáng lập kiêm CEO)"
    TS_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Long\",\"lastName\":\"Lê Văn Bảo\",\"email\":\"long.le@tiasang.vn\",\"employeeCode\":\"TS-002\",\"position\":\"Trưởng nhóm Kỹ thuật\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_DEPT_KT\",\"hiredDate\":\"2024-06-15\",\"phone\":\"+84904000002\"}" "Lê Văn Bảo Long (Trưởng nhóm Kỹ thuật)"
    TS_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Duyên\",\"lastName\":\"Phan Thị Mỹ\",\"email\":\"duyen.phan@tiasang.vn\",\"employeeCode\":\"TS-003\",\"position\":\"Chuyên viên Marketing\",\"department\":\"Marketing\",\"departmentId\":\"$TS_DEPT_MKT\",\"hiredDate\":\"2024-07-01\",\"phone\":\"+84904000003\"}" "Phan Thị Mỹ Duyên (Chuyên viên Marketing)"
    TS_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Đạt\",\"lastName\":\"Trương Văn\",\"email\":\"dat.truong@tiasang.vn\",\"employeeCode\":\"TS-004\",\"position\":\"Nhân viên Kỹ thuật (bán thời gian)\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_DEPT_KT\",\"hiredDate\":\"2024-08-01\",\"phone\":\"+84904000004\"}" "Trương Văn Đạt (Nhân viên Kỹ thuật bán thời gian) [đa công ty]"
    TS_E4="$EMP_ID"
    mk_employee "{\"firstName\":\"Khang\",\"lastName\":\"Đỗ Văn\",\"email\":\"khang.do@tiasang.vn\",\"employeeCode\":\"TS-005\",\"position\":\"Thực tập sinh Kỹ thuật\",\"department\":\"Kỹ thuật\",\"departmentId\":\"$TS_DEPT_KT\",\"hiredDate\":\"2024-09-01\",\"phone\":\"+84904000005\"}" "Đỗ Văn Khang (Thực tập sinh Kỹ thuật)"
    TS_E5="$EMP_ID"

    echo "  Phân công (Ca hành chính):"
    for emp in "$TS_E1" "$TS_E2" "$TS_E3" "$TS_E4" "$TS_E5"; do
        [ -n "$emp" ] && [ -n "$TS_HANHCHINH" ] && mk_assignment "$TS_ID" "$TS_HN" "$emp" "$TS_HANHCHINH" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$TS_ID" "Kỹ thuật" "department" "Đội phát triển sản phẩm"; TS_WS_KT="$WORKSPACE_ID"
    mk_workspace "$TS_ID" "Marketing" "department" "Đội marketing và tăng trưởng"; TS_WS_MKT="$WORKSPACE_ID"

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
mk_tenant "Công ty TNHH Đông Á" "dong-a-jsc" ',"industry":"Dịch vụ","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"' "Dịch vụ"
DA_ID="$TENANT_ID"

if [ -n "$DA_ID" ]; then
    [ -n "$PLAN_BASIC_ID" ] && mk_subscription "$PLAN_BASIC_ID" "basic"

    echo "  Phòng ban:"
    mk_department "$DA_ID" "Hành chính" "Hành chính văn phòng"; DA_DEPT_HC="$DEPT_ID"
    mk_department "$DA_ID" "Kế toán" "Kế toán và tài chính"; DA_DEPT_KT="$DEPT_ID"
    mk_department "$DA_ID" "Chăm sóc khách hàng" "Hỗ trợ và chăm sóc khách hàng"; DA_DEPT_CSKH="$DEPT_ID"
    mk_department "$DA_ID" "IT" "Công nghệ thông tin nội bộ"; DA_DEPT_IT="$DEPT_ID"

    echo "  Sites:"
    mk_site "$DA_ID" "Văn phòng Đông Á" "DA-HCM" "88 Lý Tự Trọng, Quận 1, TP.HCM" "10.7756" "106.7019" "Asia/Ho_Chi_Minh"
    DA_HCM="$SITE_ID"

    echo "  Ca làm việc:"
    DA_HANHCHINH=""
    if [ -n "$DA_HCM" ]; then
        mk_shift "$DA_ID" "$DA_HCM" "Ca hành chính" "08:00" "17:00" "false" "false"; DA_HANHCHINH="$SHIFT_ID"
    fi

    echo "  Nhân viên:"
    mk_employee "{\"firstName\":\"Hạnh\",\"lastName\":\"Bạch Thị\",\"email\":\"hanh.bach@donga.vn\",\"employeeCode\":\"DA-001\",\"position\":\"Trưởng phòng Hành chính\",\"department\":\"Hành chính\",\"departmentId\":\"$DA_DEPT_HC\",\"hiredDate\":\"2023-05-01\",\"phone\":\"+84905000001\"}" "Bạch Thị Hạnh (Trưởng phòng Hành chính)"
    DA_E1="$EMP_ID"
    mk_employee "{\"firstName\":\"Long\",\"lastName\":\"Kiều Văn\",\"email\":\"long.kieu@donga.vn\",\"employeeCode\":\"DA-002\",\"position\":\"Nhân viên Kế toán\",\"department\":\"Kế toán\",\"departmentId\":\"$DA_DEPT_KT\",\"hiredDate\":\"2023-06-01\",\"phone\":\"+84905000002\"}" "Kiều Văn Long (Nhân viên Kế toán)"
    DA_E2="$EMP_ID"
    mk_employee "{\"firstName\":\"Mai\",\"lastName\":\"Lâm Thị\",\"email\":\"mai.lam@donga.vn\",\"employeeCode\":\"DA-003\",\"position\":\"Nhân viên Chăm sóc khách hàng\",\"department\":\"Chăm sóc khách hàng\",\"departmentId\":\"$DA_DEPT_CSKH\",\"hiredDate\":\"2023-08-01\",\"phone\":\"+84905000003\"}" "Lâm Thị Mai (Nhân viên Chăm sóc khách hàng)"
    DA_E3="$EMP_ID"
    mk_employee "{\"firstName\":\"Nghĩa\",\"lastName\":\"Vũ Văn\",\"email\":\"nghia.vu@donga.vn\",\"employeeCode\":\"DA-004\",\"position\":\"Nhân viên IT\",\"department\":\"IT\",\"departmentId\":\"$DA_DEPT_IT\",\"hiredDate\":\"2023-09-01\",\"phone\":\"+84905000004\"}" "Vũ Văn Nghĩa (Nhân viên IT)"
    DA_E4="$EMP_ID"

    echo "  Phân công (Ca hành chính):"
    for emp in "$DA_E1" "$DA_E2" "$DA_E3" "$DA_E4"; do
        [ -n "$emp" ] && [ -n "$DA_HANHCHINH" ] && mk_assignment "$DA_ID" "$DA_HCM" "$emp" "$DA_HANHCHINH" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$DA_ID" "Hành chính - Kế toán" "department" "Hành chính văn phòng và kế toán"; DA_WS_HC="$WORKSPACE_ID"
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

# ── Historical Data (PostgreSQL direct insert) ────────────────────────────────

echo "--- Injecting historical data via PostgreSQL ---"
SEED_SQL="$(dirname "$0")/seed_historical.sql"
if [ ! -f "$SEED_SQL" ]; then
    echo "  ERROR: $SEED_SQL not found — skipping historical data"
elif command -v docker >/dev/null 2>&1 && docker exec "$DB_CONTAINER" true >/dev/null 2>&1; then
    # Host run (or anywhere with access to the Docker socket): delegate to
    # the container's own psql, same as before — no local psql client needed.
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" < "$SEED_SQL"
    echo "  Historical data injected (via docker exec)."
else
    # No Docker socket available here (e.g. running inside the fams-seed
    # container) — connect directly over the network instead.
    PGPASSWORD="${DB_PASSWORD:-}" psql -h "${DB_HOST:-localhost}" -p "${DB_PORT:-5433}" \
        -U "$DB_USER_ENV" -d "$DB_NAME_ENV" -v ON_ERROR_STOP=1 -f "$SEED_SQL"
    echo "  Historical data injected (via psql network connection)."
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== Seed complete ==="
echo ""
echo "  Platform Admin : admin@fams.com / Admin@1234"
echo "  Swagger UI     : $BASE_URL/swagger-ui.html"
echo ""
echo "  Tenants:"
echo "    Hoàng Long (acme-corp)        — 12 nhân viên, 3 site, gói Pro"
echo "    Bình Minh  (beta-industries)  — 10 nhân viên, 2 site, gói Basic"
echo "    Phương Nam (gamma-logistics)  — 12 nhân viên, 3 site, gói Enterprise"
echo "    Tia Sáng   (tia-sang-startup) — 5 nhân viên (chạm giới hạn), gói Trial"
echo "    Đông Á     (dong-a-jsc)       — 4 nhân viên, gói Basic, SUSPENDED"
echo ""
echo "  Người đa công ty (cùng 1 tài khoản đăng nhập, 2 tenant khác nhau):"
echo "    Phạm Thị Dung   — HR_MANAGER tại Hoàng Long + SITE_SUPERVISOR tại Bình Minh"
echo "    Trương Văn Đạt  — EMPLOYEE tại Phương Nam + EMPLOYEE tại Tia Sáng"
echo ""
echo "  Historical data: 30 days of checkins, attendance summaries, violations,"
echo "  face profiles, scheduled checks, notifications, audit logs, and more"
echo "  (see seed_historical.sql) across all 5 tenants."
echo ""
