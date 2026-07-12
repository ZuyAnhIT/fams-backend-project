#!/usr/bin/env bash
# Comprehensive FAMS demo seed.
# Creates 3 demo tenants with sites, shifts, employees, assignments,
# workspaces, and random-check configs via API; then injects 30 days
# of historical checkins, attendance, violations, and notifications
# directly into PostgreSQL.
#
# Usage:
#   bash scripts/seed.sh
#   BASE_URL=http://localhost:8080 bash scripts/seed.sh
#
# Safe to run multiple times on the same database.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_CONTAINER="${DB_CONTAINER:-fams-postgres}"
DB_USER_ENV="${DB_USER:-fams_user}"
DB_NAME_ENV="${DB_NAME:-fams_db}"

echo "=== FAMS Demo Seed ==="
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

# mk_tenant <name> <slug> [extra_json_fields]
mk_tenant() {
    local name="$1" slug="$2" extra="${3:-}"
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
        # Already has a subscription — update it
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
    local r s b
    local payload
    payload=$(python3 -c "import json; print(json.dumps({'name':'$name','type':'$type','description':'$desc'}))")
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$tid/workspaces" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1); b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        local wid
        wid=$(echo "$b" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))")
        echo "    + Workspace: $name (id=$wid)"
    elif [ "$s" -eq 409 ]; then
        echo "    ~ Workspace exists: $name"
    else
        echo "    ! Workspace error: $name — HTTP $s"
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

# ── Tenant 1: Acme Corp ───────────────────────────────────────────────────────

echo "=== Tenant 1: Acme Corp (Construction) ==="
mk_tenant "Acme Corp" "acme-corp" ',"industry":"Construction","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"'
ACME_ID="$TENANT_ID"

if [ -n "$ACME_ID" ]; then
    [ -n "$PLAN_PRO_ID" ] && mk_subscription "$PLAN_PRO_ID" "pro"

    echo "  Sites:"
    mk_site "$ACME_ID" "Acme Hanoi HQ" "ACME-HN" "15 Le Loi St, Hoan Kiem, Hanoi" "21.0285" "105.8542" "Asia/Ho_Chi_Minh"
    ACME_HN="$SITE_ID"
    mk_site "$ACME_ID" "Acme HCMC Office" "ACME-HCM" "100 Nguyen Hue Blvd, District 1, HCMC" "10.7769" "106.7009" "Asia/Ho_Chi_Minh"
    ACME_HCM="$SITE_ID"
    mk_site "$ACME_ID" "Acme Da Nang Branch" "ACME-DN" "45 Tran Phu St, Hai Chau, Da Nang" "16.0544" "108.2022" "Asia/Ho_Chi_Minh"
    ACME_DN="$SITE_ID"

    echo "  Shifts (Hanoi HQ):"
    ACME_HN_MORN="" ACME_HN_AFTN="" ACME_HN_NIGHT=""
    if [ -n "$ACME_HN" ]; then
        mk_shift "$ACME_ID" "$ACME_HN" "Morning Shift" "07:00" "15:00" "false" "true"; ACME_HN_MORN="$SHIFT_ID"
        mk_shift "$ACME_ID" "$ACME_HN" "Afternoon Shift" "15:00" "23:00" "false" "false"; ACME_HN_AFTN="$SHIFT_ID"
        mk_shift "$ACME_ID" "$ACME_HN" "Night Shift" "23:00" "07:00" "true" "false"; ACME_HN_NIGHT="$SHIFT_ID"
    fi
    echo "  Shifts (HCMC Office):"
    ACME_HCM_MORN="" ACME_HCM_AFTN=""
    if [ -n "$ACME_HCM" ]; then
        mk_shift "$ACME_ID" "$ACME_HCM" "Morning Shift" "08:00" "16:00" "false" "true"; ACME_HCM_MORN="$SHIFT_ID"
        mk_shift "$ACME_ID" "$ACME_HCM" "Afternoon Shift" "14:00" "22:00" "false" "false"; ACME_HCM_AFTN="$SHIFT_ID"
    fi
    echo "  Shifts (Da Nang Branch):"
    ACME_DN_MORN=""
    if [ -n "$ACME_DN" ]; then
        mk_shift "$ACME_ID" "$ACME_DN" "Standard Shift" "08:00" "17:00" "false" "true"; ACME_DN_MORN="$SHIFT_ID"
        mk_shift "$ACME_ID" "$ACME_DN" "Extended Shift" "07:00" "19:00" "false" "true"
    fi

    echo "  Employees:"
    mk_employee '{"firstName":"Alice","lastName":"Walker","email":"alice.walker@acme.com","employeeCode":"ACME-001","position":"Senior Engineer","department":"Engineering","hiredDate":"2023-03-15","phone":"+84901000001"}' "Alice Walker (Senior Engineer)"
    ACME_E1="$EMP_ID"
    mk_employee '{"firstName":"Bob","lastName":"Smith","email":"bob.smith@acme.com","employeeCode":"ACME-002","position":"Site Supervisor","department":"Operations","hiredDate":"2022-07-01","phone":"+84901000002"}' "Bob Smith (Site Supervisor)"
    ACME_E2="$EMP_ID"
    mk_employee '{"firstName":"Charlie","lastName":"Jones","email":"charlie.jones@acme.com","employeeCode":"ACME-003","position":"Technician","department":"Engineering","hiredDate":"2021-09-01","phone":"+84901000003"}' "Charlie Jones (Technician) [inactive]"
    ACME_E3="$EMP_ID"
    mk_status "$ACME_E3" "inactive"
    mk_employee '{"firstName":"Diana","lastName":"Prince","email":"diana.prince@acme.com","employeeCode":"ACME-004","position":"HR Manager","department":"Human Resources","hiredDate":"2020-01-10","phone":"+84901000004"}' "Diana Prince (HR Manager)"
    ACME_E4="$EMP_ID"
    mk_employee '{"firstName":"Grace","lastName":"Kim","email":"grace.kim@acme.com","employeeCode":"ACME-005","position":"Safety Officer","department":"Safety","hiredDate":"2023-06-01","phone":"+84901000005"}' "Grace Kim (Safety Officer)"
    ACME_E5="$EMP_ID"
    mk_employee '{"firstName":"Henry","lastName":"Chen","email":"henry.chen@acme.com","employeeCode":"ACME-006","position":"Construction Lead","department":"Engineering","hiredDate":"2022-02-15","phone":"+84901000006"}' "Henry Chen (Construction Lead)"
    ACME_E6="$EMP_ID"
    mk_employee '{"firstName":"Ivan","lastName":"Rodriguez","email":"ivan.rodriguez@acme.com","employeeCode":"ACME-007","position":"Senior Engineer","department":"Engineering","hiredDate":"2021-11-30","phone":"+84901000007"}' "Ivan Rodriguez (Senior Engineer)"
    ACME_E7="$EMP_ID"
    mk_employee '{"firstName":"Jane","lastName":"Park","email":"jane.park@acme.com","employeeCode":"ACME-008","position":"HR Specialist","department":"Human Resources","hiredDate":"2024-01-15","phone":"+84901000008"}' "Jane Park (HR Specialist)"
    ACME_E8="$EMP_ID"
    mk_employee '{"firstName":"Kevin","lastName":"Lee","email":"kevin.lee@acme.com","employeeCode":"ACME-009","position":"Site Inspector","department":"Quality","hiredDate":"2023-08-01","phone":"+84901000009"}' "Kevin Lee (Site Inspector)"
    ACME_E9="$EMP_ID"
    mk_employee '{"firstName":"Laura","lastName":"Martinez","email":"laura.martinez@acme.com","employeeCode":"ACME-010","position":"Project Coordinator","department":"Operations","hiredDate":"2022-04-20","phone":"+84901000010"}' "Laura Martinez (Project Coordinator)"
    ACME_E10="$EMP_ID"
    mk_employee '{"firstName":"Mike","lastName":"Nguyen","email":"mike.nguyen@acme.com","employeeCode":"ACME-011","position":"Construction Worker","department":"Engineering","hiredDate":"2024-03-01","phone":"+84901000011"}' "Mike Nguyen (Construction Worker)"
    ACME_E11="$EMP_ID"
    mk_employee '{"firstName":"Nancy","lastName":"Tran","email":"nancy.tran@acme.com","employeeCode":"ACME-012","position":"QA Engineer","department":"Quality","hiredDate":"2023-11-15","phone":"+84901000012"}' "Nancy Tran (QA Engineer) [terminated]"
    ACME_E12="$EMP_ID"
    mk_status "$ACME_E12" "terminated"

    echo "  Assignments (Hanoi HQ — Morning):"
    for emp in "$ACME_E1" "$ACME_E5" "$ACME_E6" "$ACME_E7" "$ACME_E9"; do
        [ -n "$emp" ] && mk_assignment "$ACME_ID" "$ACME_HN" "$emp" "$ACME_HN_MORN" "worker" "2026-01-01"
    done
    [ -n "$ACME_E2" ] && mk_assignment "$ACME_ID" "$ACME_HN" "$ACME_E2" "$ACME_HN_MORN" "supervisor" "2026-01-01"

    echo "  Assignments (Hanoi HQ — Afternoon):"
    [ -n "$ACME_E11" ] && [ -n "$ACME_HN_AFTN" ] && mk_assignment "$ACME_ID" "$ACME_HN" "$ACME_E11" "$ACME_HN_AFTN" "worker" "2026-01-01"

    echo "  Assignments (HCMC Office — Morning):"
    for emp in "$ACME_E4" "$ACME_E8" "$ACME_E10"; do
        [ -n "$emp" ] && [ -n "$ACME_HCM_MORN" ] && mk_assignment "$ACME_ID" "$ACME_HCM" "$emp" "$ACME_HCM_MORN" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$ACME_ID" "Engineering" "department" "Engineering and construction teams"
    mk_workspace "$ACME_ID" "Operations" "department" "Site operations and logistics"
    mk_workspace "$ACME_ID" "Human Resources" "department" "HR, people management and compliance"
    mk_workspace "$ACME_ID" "Quality and Safety" "department" "Quality assurance and workplace safety"
    mk_workspace "$ACME_ID" "Hanoi Engineering Team" "team" "Engineers based at the Hanoi HQ"
    mk_workspace "$ACME_ID" "HCMC Admin Team" "team" "Administrative staff at HCMC office"

    echo "  Random check config:"
    mk_rand_config "$ACME_ID" "location_only" "2" "300"
fi
echo ""

# ── Tenant 2: Beta Industries ─────────────────────────────────────────────────

echo "=== Tenant 2: Beta Industries (Manufacturing) ==="
mk_tenant "Beta Industries" "beta-industries" ',"industry":"Manufacturing","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"'
BETA_ID="$TENANT_ID"

if [ -n "$BETA_ID" ]; then
    [ -n "$PLAN_BASIC_ID" ] && mk_subscription "$PLAN_BASIC_ID" "basic"

    echo "  Sites:"
    mk_site "$BETA_ID" "Beta Main Plant" "BETA-MAIN" "KCN Tan Binh, Binh Duong Province" "10.9350" "106.6900" "Asia/Ho_Chi_Minh"
    BETA_PLANT="$SITE_ID"
    mk_site "$BETA_ID" "Beta Warehouse A" "BETA-WH-A" "Lo B12, KCN Song Than 2, Binh Duong" "10.8250" "106.7100" "Asia/Ho_Chi_Minh"
    BETA_WH_A="$SITE_ID"

    echo "  Shifts (Main Plant):"
    BETA_PLANT_DAY="" BETA_PLANT_EVE="" BETA_PLANT_NIGHT=""
    if [ -n "$BETA_PLANT" ]; then
        mk_shift "$BETA_ID" "$BETA_PLANT" "Day Shift" "06:00" "14:00" "false" "true"; BETA_PLANT_DAY="$SHIFT_ID"
        mk_shift "$BETA_ID" "$BETA_PLANT" "Evening Shift" "14:00" "22:00" "false" "false"; BETA_PLANT_EVE="$SHIFT_ID"
        mk_shift "$BETA_ID" "$BETA_PLANT" "Night Shift" "22:00" "06:00" "true" "false"; BETA_PLANT_NIGHT="$SHIFT_ID"
    fi
    echo "  Shifts (Warehouse A):"
    BETA_WH_DAY="" BETA_WH_EVE=""
    if [ -n "$BETA_WH_A" ]; then
        mk_shift "$BETA_ID" "$BETA_WH_A" "Day Shift" "07:00" "15:00" "false" "true"; BETA_WH_DAY="$SHIFT_ID"
        mk_shift "$BETA_ID" "$BETA_WH_A" "Evening Shift" "15:00" "23:00" "false" "false"; BETA_WH_EVE="$SHIFT_ID"
    fi

    echo "  Employees:"
    mk_employee '{"firstName":"Eve","lastName":"Taylor","email":"eve.taylor@beta.com","employeeCode":"BETA-001","position":"Production Lead","department":"Manufacturing","hiredDate":"2024-01-01","phone":"+84902000001"}' "Eve Taylor (Production Lead)"
    BETA_E1="$EMP_ID"
    mk_employee '{"firstName":"Frank","lastName":"Wilson","email":"frank.wilson@beta.com","employeeCode":"BETA-002","position":"Quality Inspector","department":"Quality Control","hiredDate":"2023-06-15","phone":"+84902000002"}' "Frank Wilson (Quality Inspector)"
    BETA_E2="$EMP_ID"
    mk_employee '{"firstName":"Georgia","lastName":"Brown","email":"georgia.brown@beta.com","employeeCode":"BETA-003","position":"Production Manager","department":"Manufacturing","hiredDate":"2022-09-01","phone":"+84902000003"}' "Georgia Brown (Production Manager)"
    BETA_E3="$EMP_ID"
    mk_employee '{"firstName":"Harold","lastName":"Davis","email":"harold.davis@beta.com","employeeCode":"BETA-004","position":"Senior QA Inspector","department":"Quality Control","hiredDate":"2021-04-20","phone":"+84902000004"}' "Harold Davis (Senior QA Inspector)"
    BETA_E4="$EMP_ID"
    mk_employee '{"firstName":"Iris","lastName":"Johnson","email":"iris.johnson@beta.com","employeeCode":"BETA-005","position":"Machine Operator","department":"Manufacturing","hiredDate":"2023-12-01","phone":"+84902000005"}' "Iris Johnson (Machine Operator)"
    BETA_E5="$EMP_ID"
    mk_employee '{"firstName":"James","lastName":"Kim","email":"james.kim@beta.com","employeeCode":"BETA-006","position":"Maintenance Engineer","department":"Maintenance","hiredDate":"2022-07-10","phone":"+84902000006"}' "James Kim (Maintenance Engineer)"
    BETA_E6="$EMP_ID"
    mk_employee '{"firstName":"Karen","lastName":"Smith","email":"karen.smith@beta.com","employeeCode":"BETA-007","position":"Safety Coordinator","department":"Safety","hiredDate":"2023-03-15","phone":"+84902000007"}' "Karen Smith (Safety Coordinator)"
    BETA_E7="$EMP_ID"
    mk_employee '{"firstName":"Leo","lastName":"Wang","email":"leo.wang@beta.com","employeeCode":"BETA-008","position":"Line Supervisor","department":"Manufacturing","hiredDate":"2022-11-01","phone":"+84902000008"}' "Leo Wang (Line Supervisor)"
    BETA_E8="$EMP_ID"
    mk_employee '{"firstName":"Maria","lastName":"Garcia","email":"maria.garcia@beta.com","employeeCode":"BETA-009","position":"Process Engineer","department":"Engineering","hiredDate":"2024-02-15","phone":"+84902000009"}' "Maria Garcia (Process Engineer)"
    BETA_E9="$EMP_ID"
    mk_employee '{"firstName":"Nathan","lastName":"Lee","email":"nathan.lee@beta.com","employeeCode":"BETA-010","position":"Quality Analyst","department":"Quality Control","hiredDate":"2023-09-01","phone":"+84902000010"}' "Nathan Lee (Quality Analyst)"
    BETA_E10="$EMP_ID"

    echo "  Assignments (Main Plant — Day Shift):"
    for emp in "$BETA_E1" "$BETA_E5" "$BETA_E7" "$BETA_E9"; do
        [ -n "$emp" ] && [ -n "$BETA_PLANT_DAY" ] && mk_assignment "$BETA_ID" "$BETA_PLANT" "$emp" "$BETA_PLANT_DAY" "worker" "2026-01-01"
    done
    [ -n "$BETA_E3" ] && [ -n "$BETA_PLANT_DAY" ] && mk_assignment "$BETA_ID" "$BETA_PLANT" "$BETA_E3" "$BETA_PLANT_DAY" "supervisor" "2026-01-01"

    echo "  Assignments (Main Plant — Evening Shift):"
    for emp in "$BETA_E6" "$BETA_E8"; do
        [ -n "$emp" ] && [ -n "$BETA_PLANT_EVE" ] && mk_assignment "$BETA_ID" "$BETA_PLANT" "$emp" "$BETA_PLANT_EVE" "worker" "2026-01-01"
    done

    echo "  Assignments (Warehouse A — Day Shift):"
    for emp in "$BETA_E2" "$BETA_E4" "$BETA_E10"; do
        [ -n "$emp" ] && [ -n "$BETA_WH_DAY" ] && mk_assignment "$BETA_ID" "$BETA_WH_A" "$emp" "$BETA_WH_DAY" "worker" "2026-01-01"
    done

    echo "  Workspaces:"
    mk_workspace "$BETA_ID" "Manufacturing" "department" "Production lines and assembly"
    mk_workspace "$BETA_ID" "Quality Control" "department" "Quality assurance and inspection"
    mk_workspace "$BETA_ID" "Maintenance" "department" "Equipment and facility maintenance"
    mk_workspace "$BETA_ID" "Safety" "department" "Workplace health and safety"
    mk_workspace "$BETA_ID" "Plant Floor Team A" "team" "Day-shift production team at main plant"
    mk_workspace "$BETA_ID" "Warehouse Team" "team" "Warehouse and logistics team"

    echo "  Random check config:"
    mk_rand_config "$BETA_ID" "location_only" "3" "240"
fi
echo ""

# ── Tenant 3: Gamma Logistics ─────────────────────────────────────────────────

echo "=== Tenant 3: Gamma Logistics (Logistics) ==="
mk_tenant "Gamma Logistics" "gamma-logistics" ',"industry":"Logistics","timezone":"Asia/Ho_Chi_Minh","countryCode":"VN"'
GAMMA_ID="$TENANT_ID"

if [ -n "$GAMMA_ID" ]; then
    [ -n "$PLAN_ENTERPRISE_ID" ] && mk_subscription "$PLAN_ENTERPRISE_ID" "enterprise"

    echo "  Sites:"
    mk_site "$GAMMA_ID" "Gamma Depot North" "GAMMA-N" "KCN Noi Bai, Soc Son, Hanoi" "21.2210" "105.7950" "Asia/Ho_Chi_Minh"
    GAMMA_N="$SITE_ID"
    mk_site "$GAMMA_ID" "Gamma Depot South" "GAMMA-S" "KCN Hiep Phuoc, Nha Be, HCMC" "10.6550" "106.7450" "Asia/Ho_Chi_Minh"
    GAMMA_S="$SITE_ID"
    mk_site "$GAMMA_ID" "Gamma Central Hub" "GAMMA-HUB" "27 Truong Chinh, Thanh Khe, Da Nang" "16.0710" "108.1990" "Asia/Ho_Chi_Minh"
    GAMMA_HUB="$SITE_ID"

    echo "  Shifts (Depot North):"
    GAMMA_N_MORN="" GAMMA_N_AFTN="" GAMMA_N_NIGHT=""
    if [ -n "$GAMMA_N" ]; then
        mk_shift "$GAMMA_ID" "$GAMMA_N" "Morning Shift" "06:00" "14:00" "false" "true"; GAMMA_N_MORN="$SHIFT_ID"
        mk_shift "$GAMMA_ID" "$GAMMA_N" "Afternoon Shift" "14:00" "22:00" "false" "false"; GAMMA_N_AFTN="$SHIFT_ID"
        mk_shift "$GAMMA_ID" "$GAMMA_N" "Night Shift" "22:00" "06:00" "true" "false"; GAMMA_N_NIGHT="$SHIFT_ID"
    fi
    echo "  Shifts (Depot South):"
    GAMMA_S_MORN="" GAMMA_S_AFTN=""
    if [ -n "$GAMMA_S" ]; then
        mk_shift "$GAMMA_ID" "$GAMMA_S" "Morning Shift" "06:00" "14:00" "false" "true"; GAMMA_S_MORN="$SHIFT_ID"
        mk_shift "$GAMMA_ID" "$GAMMA_S" "Afternoon Shift" "14:00" "22:00" "false" "false"; GAMMA_S_AFTN="$SHIFT_ID"
    fi
    echo "  Shifts (Central Hub):"
    GAMMA_HUB_STD=""
    if [ -n "$GAMMA_HUB" ]; then
        mk_shift "$GAMMA_ID" "$GAMMA_HUB" "Standard Shift" "08:00" "17:00" "false" "true"; GAMMA_HUB_STD="$SHIFT_ID"
        mk_shift "$GAMMA_ID" "$GAMMA_HUB" "Extended Shift" "06:00" "18:00" "false" "true"
    fi

    echo "  Employees:"
    mk_employee '{"firstName":"Oscar","lastName":"Martinez","email":"oscar.martinez@gamma.com","employeeCode":"GAMMA-001","position":"Logistics Director","department":"Operations","hiredDate":"2020-05-01","phone":"+84903000001"}' "Oscar Martinez (Logistics Director)"
    GAMMA_E1="$EMP_ID"
    mk_employee '{"firstName":"Patricia","lastName":"Chen","email":"patricia.chen@gamma.com","employeeCode":"GAMMA-002","position":"Operations Manager","department":"Operations","hiredDate":"2021-02-15","phone":"+84903000002"}' "Patricia Chen (Operations Manager)"
    GAMMA_E2="$EMP_ID"
    mk_employee '{"firstName":"Quinn","lastName":"Roberts","email":"quinn.roberts@gamma.com","employeeCode":"GAMMA-003","position":"Dispatch Coordinator","department":"Operations","hiredDate":"2022-08-01","phone":"+84903000003"}' "Quinn Roberts (Dispatch Coordinator)"
    GAMMA_E3="$EMP_ID"
    mk_employee '{"firstName":"Rachel","lastName":"Brown","email":"rachel.brown@gamma.com","employeeCode":"GAMMA-004","position":"Fleet Manager","department":"Fleet","hiredDate":"2021-10-20","phone":"+84903000004"}' "Rachel Brown (Fleet Manager)"
    GAMMA_E4="$EMP_ID"
    mk_employee '{"firstName":"Samuel","lastName":"Wilson","email":"samuel.wilson@gamma.com","employeeCode":"GAMMA-005","position":"Senior Driver","department":"Fleet","hiredDate":"2020-11-01","phone":"+84903000005"}' "Samuel Wilson (Senior Driver)"
    GAMMA_E5="$EMP_ID"
    mk_employee '{"firstName":"Teresa","lastName":"Davis","email":"teresa.davis@gamma.com","employeeCode":"GAMMA-006","position":"Driver","department":"Fleet","hiredDate":"2023-01-15","phone":"+84903000006"}' "Teresa Davis (Driver)"
    GAMMA_E6="$EMP_ID"
    mk_employee '{"firstName":"Victor","lastName":"Johnson","email":"victor.johnson@gamma.com","employeeCode":"GAMMA-007","position":"Warehouse Lead","department":"Warehouse","hiredDate":"2022-04-01","phone":"+84903000007"}' "Victor Johnson (Warehouse Lead)"
    GAMMA_E7="$EMP_ID"
    mk_employee '{"firstName":"Wendy","lastName":"Kim","email":"wendy.kim@gamma.com","employeeCode":"GAMMA-008","position":"Quality Checker","department":"Quality","hiredDate":"2023-07-01","phone":"+84903000008"}' "Wendy Kim (Quality Checker)"
    GAMMA_E8="$EMP_ID"
    mk_employee '{"firstName":"Xavier","lastName":"Lee","email":"xavier.lee@gamma.com","employeeCode":"GAMMA-009","position":"Route Planner","department":"Operations","hiredDate":"2024-01-10","phone":"+84903000009"}' "Xavier Lee (Route Planner)"
    GAMMA_E9="$EMP_ID"
    mk_employee '{"firstName":"Yolanda","lastName":"Garcia","email":"yolanda.garcia@gamma.com","employeeCode":"GAMMA-010","position":"HR Manager","department":"Human Resources","hiredDate":"2021-06-01","phone":"+84903000010"}' "Yolanda Garcia (HR Manager)"
    GAMMA_E10="$EMP_ID"
    mk_employee '{"firstName":"Zach","lastName":"Nguyen","email":"zach.nguyen@gamma.com","employeeCode":"GAMMA-011","position":"Forklift Operator","department":"Warehouse","hiredDate":"2023-09-01","phone":"+84903000011"}' "Zach Nguyen (Forklift Operator)"
    GAMMA_E11="$EMP_ID"
    mk_employee '{"firstName":"Amy","lastName":"Tran","email":"amy.tran@gamma.com","employeeCode":"GAMMA-012","position":"Inventory Analyst","department":"Warehouse","hiredDate":"2024-02-01","phone":"+84903000012"}' "Amy Tran (Inventory Analyst)"
    GAMMA_E12="$EMP_ID"

    echo "  Assignments (Depot North — Morning):"
    for emp in "$GAMMA_E1" "$GAMMA_E3" "$GAMMA_E9"; do
        [ -n "$emp" ] && [ -n "$GAMMA_N_MORN" ] && mk_assignment "$GAMMA_ID" "$GAMMA_N" "$emp" "$GAMMA_N_MORN" "supervisor" "2026-01-01"
    done
    [ -n "$GAMMA_E5" ] && [ -n "$GAMMA_N_MORN" ] && mk_assignment "$GAMMA_ID" "$GAMMA_N" "$GAMMA_E5" "$GAMMA_N_MORN" "worker" "2026-01-01"

    echo "  Assignments (Depot North — Afternoon):"
    for emp in "$GAMMA_E6" "$GAMMA_E7"; do
        [ -n "$emp" ] && [ -n "$GAMMA_N_AFTN" ] && mk_assignment "$GAMMA_ID" "$GAMMA_N" "$emp" "$GAMMA_N_AFTN" "worker" "2026-01-01"
    done

    echo "  Assignments (Depot South — Morning):"
    for emp in "$GAMMA_E4" "$GAMMA_E8" "$GAMMA_E11" "$GAMMA_E12"; do
        [ -n "$emp" ] && [ -n "$GAMMA_S_MORN" ] && mk_assignment "$GAMMA_ID" "$GAMMA_S" "$emp" "$GAMMA_S_MORN" "worker" "2026-01-01"
    done
    [ -n "$GAMMA_E2" ] && [ -n "$GAMMA_S_MORN" ] && mk_assignment "$GAMMA_ID" "$GAMMA_S" "$GAMMA_E2" "$GAMMA_S_MORN" "supervisor" "2026-01-01"

    echo "  Assignments (Central Hub):"
    [ -n "$GAMMA_E10" ] && [ -n "$GAMMA_HUB_STD" ] && mk_assignment "$GAMMA_ID" "$GAMMA_HUB" "$GAMMA_E10" "$GAMMA_HUB_STD" "worker" "2026-01-01"

    echo "  Workspaces:"
    mk_workspace "$GAMMA_ID" "Operations" "department" "Logistics operations and dispatch"
    mk_workspace "$GAMMA_ID" "Fleet Management" "department" "Driver and vehicle management"
    mk_workspace "$GAMMA_ID" "Warehouse" "department" "Warehouse and inventory"
    mk_workspace "$GAMMA_ID" "Quality" "department" "Quality control and compliance"
    mk_workspace "$GAMMA_ID" "Human Resources" "department" "HR and employee relations"
    mk_workspace "$GAMMA_ID" "Hanoi Depot Team" "team" "Team at Depot North (Hanoi)"
    mk_workspace "$GAMMA_ID" "HCMC Depot Team" "team" "Team at Depot South (HCMC)"

    echo "  Random check config:"
    mk_rand_config "$GAMMA_ID" "location_only" "2" "360"
fi
echo ""

# ── Historical Data (PostgreSQL direct insert) ────────────────────────────────

echo "--- Injecting historical data via PostgreSQL ---"
SEED_SQL="$(dirname "$0")/seed_historical.sql"
if [ ! -f "$SEED_SQL" ]; then
    echo "  ERROR: $SEED_SQL not found — skipping historical data"
else
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER_ENV" -d "$DB_NAME_ENV" < "$SEED_SQL"
    echo "  Historical data injected."
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== Seed complete ==="
echo ""
echo "  Platform Admin : admin@fams.com / Admin@1234"
echo "  Swagger UI     : $BASE_URL/swagger-ui.html"
echo ""
echo "  Tenants:"
echo "    Acme Corp       (acme-corp)        — 12 employees, 3 sites, Pro plan"
echo "    Beta Industries (beta-industries)  — 10 employees, 2 sites, Basic plan"
echo "    Gamma Logistics (gamma-logistics)  — 12 employees, 3 sites, Enterprise plan"
echo ""
echo "  Historical data: 30 days of checkins, attendance summaries, violations,"
echo "  face profiles, scheduled checks, notifications, and audit logs."
echo ""
