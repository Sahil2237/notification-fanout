#!/usr/bin/env bash
# CI only: creates and destroys an isolated Compose project, including its volumes.
set -euo pipefail
cd "$(dirname "$0")"
export APP_IMAGE=${APP_IMAGE:-notification-fanout:smoke}
export PG_OWNER_PASSWORD=smoke-owner-credential-32-characters
export DB_PASSWORD=smoke-runtime-credential-32-characters
export ADMIN_TOKEN=smoke-administrator-token-at-least-32-characters
export API_TOKEN=smoke-api-token-at-least-32-characters
export WEBHOOK_SIGNING_SECRET=smoke-signing-secret-at-least-32-characters
export WEBHOOK_ORIGINS='["https://example.com"]'
export TLS_DOMAIN=
compose=(docker compose --project-name notification-droplet-smoke -f compose.yaml)
cleanup() {
  "${compose[@]}" logs --tail 100 || true
  "${compose[@]}" down --volumes || true
}
trap cleanup EXIT
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait --wait-timeout 300 db kafka
"${compose[@]}" run --rm --no-deps kafka-init
"${compose[@]}" run --rm --no-deps migrate
"${compose[@]}" run --rm --no-deps grants
"${compose[@]}" up -d app
for attempt in $(seq 1 90); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/health/ready
status=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/subscriptions)
[[ "$status" == 401 ]]
curl -fsS -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json'   -d '{"type":"smoke","source":"droplet-ci","payload":{"check":true}}'   http://127.0.0.1:8080/v1/events > /tmp/droplet-smoke-event.json
# The runtime can use application data but cannot modify the schema.
"${compose[@]}" exec -T db psql -U notification_runtime -d notifications -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM events'
if "${compose[@]}" exec -T db psql -U notification_runtime -d notifications -v ON_ERROR_STOP=1 -c 'CREATE TABLE forbidden(id int)'; then
  echo 'Runtime unexpectedly has DDL privileges' >&2; exit 1
fi
for attempt in $(seq 1 30); do
  remaining=$("${compose[@]}" exec -T db psql -U notification_owner -d notifications -Atc 'SELECT count(*) FROM event_outbox WHERE dispatched_at IS NULL')
  [[ "$remaining" == 0 ]] && break
  sleep 1
done
[[ "$remaining" == 0 ]]
echo 'Droplet stack: migration, runtime privileges, authenticated ingestion and Kafka dispatch passed.'
