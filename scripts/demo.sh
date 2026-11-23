#!/usr/bin/env bash
set -euo pipefail
base="${API_BASE:-http://localhost:8080}"
token="${API_TOKEN:-local-demo-token-change-me}"
auth="Authorization: Bearer $token"
subscription=$(curl -fsS "$base/v1/subscriptions" -H "$auth" -H 'Content-Type: application/json' \
  -d '{"webhookUrl":"http://127.0.0.1:9090/hook","filter":{"type":"order.created","source":"checkout","payloadConditions":[{"path":"/customer/tier","operator":"eq","value":"gold"}]}}')
subscription_id=$(jq -r .id <<< "$subscription")
trap 'curl -fsS -X DELETE "$base/v1/subscriptions/$subscription_id" -H "$auth" >/dev/null || true' EXIT
accepted=$(curl -fsS "$base/v1/events" -H "$auth" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)-$RANDOM" \
  -d '{"type":"order.created","source":"checkout","payload":{"order_id":"o-123","customer":{"tier":"gold"}}}')
event_id=$(jq -r .event.id <<< "$accepted")
printf 'Accepted event %s\n' "$event_id"
for attempt in $(seq 1 40); do
  history=$(curl -fsS "$base/v1/events/$event_id/deliveries" -H "$auth")
  state=$(jq -r '.items[0].status' <<< "$history")
  if [[ "$state" != pending && "$state" != null ]]; then
    jq . <<< "$history"
    [[ "$state" == delivered ]]
    exit
  fi
  sleep 1
done
echo 'Delivery did not finish within demo deadline' >&2
exit 1
