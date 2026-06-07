#!/usr/bin/env bash
#
# End-to-end proof of the Zelo v1 DELETE loop, with both services running for
# real against Postgres. Builds nothing — run `./gradlew :zelo-server:bootJar
# :zelo-demo:bootJar` first. Needs Docker (for Postgres), Java 21 and curl.
#
#   1. boots Postgres + zelo-server (bootstrapping an api key whose webhook points
#      at the demo) + zelo-demo (configured to call back into Zelo);
#   2. creates a user in the demo, then asks Zelo to delete it;
#   3. Zelo fires the signed webhook -> the demo verifies it, erases the user and
#      auto-fulfills -> Zelo records FULFILLED;
#   4. asserts the user is gone, the request is FULFILLED and the audit chain verifies.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG=$(mktemp -d); SERVER_PID=""; DEMO_PID=""

fail() { echo "E2E FAIL: $1"; echo "--- server.log ---"; tail -15 "$LOG/server.log" 2>/dev/null; echo "--- demo.log ---"; tail -15 "$LOG/demo.log" 2>/dev/null; exit 1; }
teardown() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; [ -n "$DEMO_PID" ] && kill "$DEMO_PID" 2>/dev/null; docker rm -f zelo-e2e-pg >/dev/null 2>&1; }
trap teardown EXIT
jget() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

docker rm -f zelo-e2e-pg >/dev/null 2>&1
docker run -d --name zelo-e2e-pg -e POSTGRES_DB=zelo -e POSTGRES_USER=zelo -e POSTGRES_PASSWORD=zelo \
  -p 5433:5432 postgres:16-alpine >/dev/null
echo "waiting for postgres..."; for i in $(seq 1 30); do docker exec zelo-e2e-pg pg_isready -U zelo >/dev/null 2>&1 && break; sleep 1; done

SERVER_JAR=$(ls "$ROOT"/zelo-server/build/libs/zelo-server-*.jar | grep -v plain)
DEMO_JAR=$(ls "$ROOT"/zelo-demo/build/libs/zelo-demo-*.jar | grep -v plain)

ZELO_DB_URL=jdbc:postgresql://localhost:5433/zelo ZELO_DB_USER=zelo ZELO_DB_PASSWORD=zelo \
  ZELO_BOOTSTRAP_API_KEY=demo-key ZELO_BOOTSTRAP_NAME=demo \
  ZELO_BOOTSTRAP_WEBHOOK_URL=http://localhost:8081/zelo/webhooks \
  ZELO_BOOTSTRAP_WEBHOOK_SECRET=demo-secret ZELO_OUTBOX_POLL_MS=500 \
  java -jar "$SERVER_JAR" > "$LOG/server.log" 2>&1 &
SERVER_PID=$!
echo "waiting for zelo-server..."; for i in $(seq 1 60); do curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && break; sleep 1; done
curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q UP || fail "server not healthy"

ZELO_API_URL=http://localhost:8080 ZELO_API_KEY=demo-key ZELO_WEBHOOK_SECRET=demo-secret DEMO_PORT=8081 \
  java -jar "$DEMO_JAR" > "$LOG/demo.log" 2>&1 &
DEMO_PID=$!
echo "waiting for zelo-demo..."; for i in $(seq 1 60); do curl -sf http://localhost:8081/users >/dev/null 2>&1 && break; sleep 1; done
curl -sf http://localhost:8081/users >/dev/null 2>&1 || fail "demo not healthy"

echo "--- create user alice ---"
curl -sf -X POST http://localhost:8081/users -H 'Content-Type: application/json' \
  -d '{"external_id":"alice","name":"Alice","email":"alice@example.com"}' >/dev/null || fail "create user"
curl -sf http://localhost:8081/users/alice >/dev/null || fail "alice missing after create"

echo "--- trigger deletion ---"
RID=$(curl -sf -X POST http://localhost:8081/users/alice/request-deletion | jget requestId)
[ -n "$RID" ] || fail "no requestId returned"
echo "zelo request id: $RID"

echo "--- await the loop (webhook -> erase -> auto-fulfill) ---"
for i in $(seq 1 20); do [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/users/alice)" = "404" ] && break; sleep 1; done

echo "--- verify ---"
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/users/alice)" = "404" ] || fail "alice still present — erasure did not happen"
STATUS=$(curl -sf "http://localhost:8080/v1/requests/$RID" -H "Authorization: demo-key" | jget status)
[ "$STATUS" = "FULFILLED" ] || fail "request status=$STATUS (expected FULFILLED)"
OK=$(curl -sf "http://localhost:8080/v1/audit/verify" -H "Authorization: demo-key" | jget ok)
[ "$OK" = "True" ] || fail "audit verify ok=$OK"

echo
echo "E2E PASS: alice erased (404), Zelo request $RID = FULFILLED, audit chain verified ok=$OK"
