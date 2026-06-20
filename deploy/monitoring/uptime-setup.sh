#!/usr/bin/env bash
#
# External uptime monitoring + alerting for the Zelo API, via OCI Health Checks (free,
# off-box vantage points — so it detects the box being down, which a box-side check
# cannot) + an OCI Monitoring alarm -> Notifications email.
#
# Run from a machine with the OCI CLI configured (the workstation), NOT the box —
# there are no OCI creds on the box by design. Probes the PUBLIC, unauthenticated
# GET /v1/audit/verify/demo (always 200, no key, cheap) as the liveness signal.
#
# Not fully idempotent: re-running creates duplicates. Run once; manage in the console
# after. The alarm metric/query may need tweaking per current OCI docs — verify in the
# console (Monitoring > Alarms) after creation.
set -euo pipefail

: "${COMPARTMENT_OCID:?set COMPARTMENT_OCID to your compartment OCID}"
: "${ALERT_EMAIL:=thgrcarvalho@gmail.com}"
: "${API_HOST:=api.zelocompliance.com}"
: "${HEALTH_PATH:=/v1/audit/verify/demo}"
OCI="${OCI:-oci}"

echo "1/3 External HTTPS health monitor (60s, OCI vantage points) on https://${API_HOST}${HEALTH_PATH}"
MON_ID=$("$OCI" health-checks http-monitor create \
  --compartment-id "$COMPARTMENT_OCID" \
  --display-name "zelo-api-uptime" \
  --targets "[\"${API_HOST}\"]" \
  --protocol HTTPS --port 443 --path "$HEALTH_PATH" \
  --interval-in-seconds 60 --timeout-in-seconds 10 --is-enabled true \
  --query 'data.id' --raw-output)
echo "   monitor: $MON_ID"

echo "2/3 Notifications topic + email subscription"
TOPIC_ID=$("$OCI" ons topic create --compartment-id "$COMPARTMENT_OCID" --name "zelo-alerts" \
  --query 'data."topic-id"' --raw-output)
"$OCI" ons subscription create --compartment-id "$COMPARTMENT_OCID" --topic-id "$TOPIC_ID" \
  --protocol EMAIL --subscription-endpoint "$ALERT_EMAIL" --query 'data.id' --raw-output >/dev/null
echo "   topic: $TOPIC_ID  -> CONFIRM the subscription via the email OCI sends to ${ALERT_EMAIL}."

echo "3/3 Alarm: API health check failing for 5m -> email the topic"
"$OCI" monitoring alarm create --compartment-id "$COMPARTMENT_OCID" \
  --display-name "zelo-api-down" \
  --metric-compartment-id "$COMPARTMENT_OCID" \
  --namespace "oci_healthchecks" \
  --query-text 'HTTP.isHealthy[1m]{target = "'"${API_HOST}"'"}.mean() < 1' \
  --severity CRITICAL --is-enabled true \
  --destinations "[\"$TOPIC_ID\"]" \
  --pending-duration "PT5M" \
  --body "Zelo API uptime check is failing: https://${API_HOST}${HEALTH_PATH}" \
  --query 'data.id' --raw-output
echo "Done. Verify in the OCI console: Observability > Health Checks, and Monitoring > Alarms."
