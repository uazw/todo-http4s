#!/usr/bin/env bash
#
# End-to-end smoke test against a running instance.
#
#   docker compose up -d postgres
#   sbt run            # terminal 1
#   scripts/smoke.sh   # terminal 2
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

check() { # check <description> <expected-status> <method> <path> [body]
  local description="$1" expected="$2" method="$3" path="$4" body="${5:-}"
  local args=(-s -o /tmp/todo-smoke-body -w '%{http_code}' -X "$method" "$BASE$path")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  local status
  status="$(curl "${args[@]}")"
  if [ "$status" = "$expected" ]; then
    PASS=$((PASS + 1))
    printf '  ok    %-52s %s\n' "$description" "$status"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL  %-52s expected %s got %s\n' "$description" "$expected" "$status"
    printf '        %s\n' "$(cat /tmp/todo-smoke-body)"
  fi
}

field() { # field <name> — reads from the last body
  python3 -c "import json,sys;print(json.load(open('/tmp/todo-smoke-body')).get('$1',''))"
}

echo "smoke testing $BASE"

check "health"                       200 GET  "/health"
check "unknown route is 404"         404 GET  "/does-not-exist"

check "create a todo"                201 POST "/api/todos" '{"title":"smoke: buy milk","description":"the corner shop"}'
ID="$(field id)"
TITLE="$(field title)"
[ "$TITLE" = "smoke: buy milk" ] && { PASS=$((PASS+1)); echo "  ok    title is echoed back"; } \
                               || { FAIL=$((FAIL+1)); echo "  FAIL  unexpected title: $TITLE"; }

check "read it back"                 200 GET  "/api/todos/$ID"
check "bad uuid is 400"              400 GET  "/api/todos/not-a-uuid"
check "unknown id is 404"            404 GET  "/api/todos/00000000-0000-4000-8000-000000000000"

check "blank title is rejected"      400 POST "/api/todos" '{"title":"   "}'
check "malformed body is rejected"   400 POST "/api/todos" '{"nope":true}'
check "bad status filter is 400"     400 GET  "/api/todos?status=banana"

check "it shows up in the list"      200 GET  "/api/todos?limit=5&offset=0"
check "free-text search matches"     200 GET  "/api/todos?q=smoke"
check "no matches is still 200"      200 GET  "/api/todos?q=zzz-nothing-matches"

check "patch the status"             200 PATCH "/api/todos/$ID" '{"status":"done","dueAt":null}'
DONE_STATUS="$(python3 -c "import json;print(json.load(open('/tmp/todo-smoke-body'))['status'])")"
[ "$DONE_STATUS" = "done" ] && { PASS=$((PASS+1)); echo "  ok    patch applied"; } \
                            || { FAIL=$((FAIL+1)); echo "  FAIL  patch not applied: $DONE_STATUS"; }
check "filter by the new status"     200 GET  "/api/todos?status=done"

check "delete it"                    204 DELETE "/api/todos/$ID"
check "delete again is 404"          404 DELETE "/api/todos/$ID"
check "and it is gone"               404 GET  "/api/todos/$ID"

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ]
