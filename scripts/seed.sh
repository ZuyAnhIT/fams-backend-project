#!/usr/bin/env bash
# Create demo tenants and employees for local development.
#
# Usage:
#   bash scripts/seed.sh
#   BASE_URL=http://localhost:8080 bash scripts/seed.sh
#
# Safe to run on a fresh database. To reset and re-seed:
#   make stop-v && make dev-d
#   (wait for startup) then re-run this script.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

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
TOKEN=$(echo "$_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "OK"
echo ""

# ── Helpers ───────────────────────────────────────────────────────────────────

TENANT_ID=""

mk_tenant() {
    local name="$1" slug="$2" extra="${3:-}"
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"name\":\"$name\",\"slug\":\"$slug\"$extra}")
    s=$(echo "$r" | tail -n 1)
    b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        TENANT_ID=$(echo "$b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "  Created: $name (id=$TENANT_ID)"
    elif [ "$s" -eq 409 ]; then
        local lr ls lb
        lr=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/tenants?search=$slug&size=1" \
            -H "Authorization: Bearer $TOKEN")
        ls=$(echo "$lr" | tail -n 1)
        lb=$(echo "$lr" | head -n -1)
        if [ "$ls" -eq 200 ]; then
            TENANT_ID=$(echo "$lb" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
            echo "  Exists: $name (id=$TENANT_ID) — seeding employees"
        else
            TENANT_ID=""
            echo "  Skipped: $name already exists but could not fetch its ID (HTTP $ls)"
        fi
    else
        TENANT_ID=""
        echo "  Error: $name — HTTP $s"
    fi
}

EMP_ID=""

mk_employee() {
    local payload="$1" label="$2"
    EMP_ID=""
    [ -z "$TENANT_ID" ] && return
    local r s b
    r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "$payload")
    s=$(echo "$r" | tail -n 1)
    b=$(echo "$r" | head -n -1)
    if [ "$s" -eq 201 ]; then
        EMP_ID=$(echo "$b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "    + $label"
    else
        echo "    ! $label — HTTP $s"
    fi
}

mk_status() {
    local emp_id="$1" new_status="$2"
    [ -z "$TENANT_ID" ] || [ -z "$emp_id" ] && return
    curl -s -o /dev/null \
        -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$emp_id/status" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"status\":\"$new_status\"}"
}

# ── Tenant 1: Acme Corp ───────────────────────────────────────────────────────

echo "--- Acme Corp (Construction) ---"
mk_tenant "Acme Corp" "acme-corp" ',"industry":"Construction","timezone":"Asia/Ho_Chi_Minh"'
mk_employee \
    '{"firstName":"Alice","lastName":"Walker","email":"alice.walker@acme.com","employeeCode":"ACME-001","position":"Senior Engineer","department":"Engineering","hiredDate":"2023-03-15"}' \
    "Alice Walker — Engineering (active)"
mk_employee \
    '{"firstName":"Bob","lastName":"Smith","email":"bob.smith@acme.com","employeeCode":"ACME-002","position":"Site Supervisor","department":"Operations","hiredDate":"2022-07-01"}' \
    "Bob Smith — Operations (active)"
mk_employee \
    '{"firstName":"Charlie","lastName":"Jones","email":"charlie.jones@acme.com","employeeCode":"ACME-003","position":"Technician","department":"Engineering","hiredDate":"2021-09-01"}' \
    "Charlie Jones — Engineering"
CHARLIE_EMP_ID="$EMP_ID"
mk_status "$CHARLIE_EMP_ID" "inactive"
[ -n "$CHARLIE_EMP_ID" ] && echo "      → status set to inactive"
mk_employee \
    '{"firstName":"Diana","lastName":"Prince","email":"diana.prince@acme.com","employeeCode":"ACME-004","position":"HR Manager","department":"Human Resources","hiredDate":"2020-01-10"}' \
    "Diana Prince — HR (active)"
echo ""

# ── Tenant 2: Beta Industries ─────────────────────────────────────────────────

echo "--- Beta Industries (Manufacturing) ---"
mk_tenant "Beta Industries" "beta-industries" ',"industry":"Manufacturing","timezone":"UTC"'
mk_employee \
    '{"firstName":"Eve","lastName":"Taylor","email":"eve.taylor@beta.com","employeeCode":"BETA-001","position":"Production Lead","department":"Manufacturing","hiredDate":"2024-01-01"}' \
    "Eve Taylor — Manufacturing (active)"
mk_employee \
    '{"firstName":"Frank","lastName":"Wilson","email":"frank.wilson@beta.com","employeeCode":"BETA-002","position":"Quality Inspector","department":"Quality Control","hiredDate":"2023-06-15"}' \
    "Frank Wilson — Quality Control (active)"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────

echo "=== Seed complete ==="
echo ""
echo "  Admin      : admin@fams.com / Admin@1234"
echo "  Swagger UI : $BASE_URL/swagger-ui.html"
echo "  Tenants    : Acme Corp (acme-corp), Beta Industries (beta-industries)"
echo ""
