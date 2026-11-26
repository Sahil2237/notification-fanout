# Manual test: one event, two subscriptions, two webhook deliveries

## Open these health URLs directly in your browser

| Check | Full clickable URL | Expected response |
| --- | --- | --- |
| **Process health — liveness** | [https://174.138.121.225/health/live](https://174.138.121.225/health/live) | HTTP 200, `{"status":"alive"}` |
| **Service health — readiness** | [https://174.138.121.225/health/ready](https://174.138.121.225/health/ready) | HTTP 200, `{"status":"ready"}`; HTTP 503 when unavailable |

Neither URL requires a bearer token. Use HTTPS on the standard port 443; do not append `:8080` or `:8081`. The service is an API, so its base URL is not a web dashboard. A health response is from the backend selected by the load balancer; it does not individually verify all three Droplets. All `/v1` API requests below require your tenant token.

This is a repeatable smoke test for the deployed application at **https://174.138.121.225**. Run the commands in order in the **same Bash session**. It creates two subscriptions under your token's tenant, sends one new event, checks its two deliveries, and optionally verifies idempotency and removes the test subscriptions. It does not require an application redeployment.

The examples use synthetic data and two distinct Webhook.site inboxes. Both subscriptions belong to one authenticated tenant; they are not two new tenant accounts. The tenant is determined by the bearer token. Keep your token and inbox IDs private; do not commit run output. Only the two subscription POSTs and the single event POST create application resources. Subscription POST is not idempotent: do not rerun it blindly after a lost response; inspect the subscription list first.

## 1. Prepare the local terminal

Use the IntelliJ terminal in the Rancher Ubuntu development container. Keep development/test files under `/workspaces`; do not use the host Desktop or Downloads. The terminal needs Bash, curl, jq and Python 3.

```bash
bash
cd /workspaces/notification-fanout
set -euo pipefail
umask 077
BASE_URL='https://174.138.121.225'
read -r -s -p 'Tenant API token: ' API_TOKEN
printf '\n'
mkdir -p .deploy/manual-tests
RUN_DIR=$(mktemp -d "$PWD/.deploy/manual-tests/two-subs.XXXXXX")
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
EVENT_TYPE="assessment.demo.$RUN_ID"
SOURCE='manual-interview-test'
PRODUCER_EVENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
IDEMPOTENCY_KEY="manual-$RUN_ID"
printf 'Run files: %s\nEvent type: %s\n' "$RUN_DIR" "$EVENT_TYPE"
```

Use your existing tenant token, not the administrator token or a DigitalOcean token. An authorized operator can obtain the existing service-tenant token from the protected `api_token` field in `/opt/notification-fanout/ha/secrets.json` on a Droplet, or from the team's secure credential store. Do not print the full secrets file or paste credentials into GitHub/chat. No real token is embedded in this document. Keep this session open because subsequent commands use these variables.

## 2. Check public health

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  -w '\nHTTP %{http_code}\n' "$BASE_URL/health/live"
curl --fail-with-body --silent --show-error --max-time 10 \
  -w '\nHTTP %{http_code}\n' "$BASE_URL/health/ready"
```

Expected: `200 {"status":"alive"}` and `200 {"status":"ready"}` (body appears before the HTTP line). No authentication is required for these two routes. Readiness checks the responding application's dependencies/workers; a healthy public response alone does not prove every backend is healthy.

Browser links: [liveness](https://174.138.121.225/health/live), [readiness](https://174.138.121.225/health/ready). Use standard HTTPS port 443. Public `:8080` and `:8081` are not application entry points. Do not disable certificate verification with `-k`.

## 3. Create two independent external inboxes

The active destination allowlist included `https://webhook.site:443` when checked on 26 November 2026. The paths below share this approved origin. You do not need to allowlist each inbox path. A different host/subdomain requires a private administrator operation; tenant API calls cannot approve it.

Create inbox 1:

```bash
curl --fail-with-body --silent --show-error --max-time 15 \
  -X POST 'https://webhook.site/token' \
  -H 'Content-Type: application/json' \
  --data '{"default_status":200,"default_content":"ok","default_content_type":"text/plain","timeout":0,"expiry":86400}' \
  > "$RUN_DIR/inbox-1.json"
INBOX_1=$(jq -er '.uuid' "$RUN_DIR/inbox-1.json")
```

**Wait at least 65 seconds before the next inbox-creation request.** Webhook.site documents a free-tier creation limit of one per minute. If another creation already consumed the quota, wait longer after HTTP 429. Do not use a paid plan for this smoke test. If account authentication is required, use two inboxes you create through your entitled browser session and assign their IDs to `INBOX_1` and `INBOX_2` instead.

Create inbox 2:

```bash
curl --fail-with-body --silent --show-error --max-time 15 \
  -X POST 'https://webhook.site/token' \
  -H 'Content-Type: application/json' \
  --data '{"default_status":200,"default_content":"ok","default_content_type":"text/plain","timeout":0,"expiry":86400}' \
  > "$RUN_DIR/inbox-2.json"
INBOX_2=$(jq -er '.uuid' "$RUN_DIR/inbox-2.json")
test "$INBOX_1" != "$INBOX_2"
WEBHOOK_1="https://webhook.site/$INBOX_1"
WEBHOOK_2="https://webhook.site/$INBOX_2"
printf 'Viewer 1: https://webhook.site/#!/view/%s\nViewer 2: https://webhook.site/#!/view/%s\n' "$INBOX_1" "$INBOX_2"
```

Open the two printed viewer URLs. Do **not** open the receiving URL itself to inspect history: doing that generates an extra captured GET request. Webhook.site is a third-party request inspector; send only this synthetic payload. The inbox identifier is different from your notification-service bearer token. Never send `API_TOKEN` to Webhook.site.

Provider contracts: [create inbox](https://docs.webhook.site/api/tokens.html), [capture and inspect requests](https://docs.webhook.site/api/requests.html). Inbox creation, quotas and expiry are provider-controlled and can change independently of our service.

## 4. Register two matching subscriptions before emitting the event

Subscription 1 matches this run's event type/source and `customer.tier = gold`:

```bash
jq -n --arg url "$WEBHOOK_1" --arg type "$EVENT_TYPE" --arg source "$SOURCE" \
  '{webhookUrl:$url,filter:{type:$type,source:$source,payloadConditions:[{path:"/customer/tier",operator:"eq",value:"gold"}]}}' \
  > "$RUN_DIR/subscription-1-request.json"
curl --fail-with-body --silent --show-error --max-time 15 \
  -D "$RUN_DIR/subscription-1-headers.txt" \
  -X POST "$BASE_URL/v1/subscriptions" \
  -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$RUN_DIR/subscription-1-request.json" \
  > "$RUN_DIR/subscription-1.json"
SUB_1=$(jq -er '.id' "$RUN_DIR/subscription-1.json")
jq . "$RUN_DIR/subscription-1.json"
```

Subscription 2 matches the same type/source and `region = blr1`:

```bash
jq -n --arg url "$WEBHOOK_2" --arg type "$EVENT_TYPE" --arg source "$SOURCE" \
  '{webhookUrl:$url,filter:{type:$type,source:$source,payloadConditions:[{path:"/region",operator:"eq",value:"blr1"}]}}' \
  > "$RUN_DIR/subscription-2-request.json"
curl --fail-with-body --silent --show-error --max-time 15 \
  -D "$RUN_DIR/subscription-2-headers.txt" \
  -X POST "$BASE_URL/v1/subscriptions" \
  -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$RUN_DIR/subscription-2-request.json" \
  > "$RUN_DIR/subscription-2.json"
SUB_2=$(jq -er '.id' "$RUN_DIR/subscription-2.json")
test "$SUB_1" != "$SUB_2"
jq . "$RUN_DIR/subscription-2.json"
```

Each POST should return **201 Created**, a distinct `id` and a `Location` header. Filters use JSON Pointer paths, only `eq`, and AND semantics. Both filters match the event below. Subscription creation is committed before 201; waiting for a read replica is not required before sending the event.

Confirm active subscriptions using the list endpoint:

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" \
  "$BASE_URL/v1/subscriptions?limit=100&offset=0" \
  | jq --arg a "$SUB_1" --arg b "$SUB_2" '.items[] | select(.id==$a or .id==$b)'
```

Reads use replicas, so retry briefly if a new record has not appeared. The list is paginated; if `hasMore=true` and your records are not on this page, fetch offsets 100, 200, etc. The deployed API has **no GET `/v1/subscriptions/{id}`** endpoint; use the list or the saved creation response.

## 5. Send exactly one new structured event

```bash
jq -n --arg id "$PRODUCER_EVENT_ID" --arg type "$EVENT_TYPE" --arg source "$SOURCE" --arg run "$RUN_ID" \
  '{eventId:$id,type:$type,source:$source,payload:{synthetic:true,testRun:$run,orderId:"demo-order-001",customer:{tier:"gold"},region:"blr1",amount:125}}' \
  > "$RUN_DIR/event-request.json"
curl --fail-with-body --silent --show-error --max-time 15 \
  -D "$RUN_DIR/event-headers.txt" \
  -X POST "$BASE_URL/v1/events" \
  -H "Authorization: Bearer $API_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data-binary "@$RUN_DIR/event-request.json" \
  > "$RUN_DIR/event-response.json"
EVENT_ID=$(jq -er '.event.id' "$RUN_DIR/event-response.json")
jq -e '.duplicate == false' "$RUN_DIR/event-response.json"
jq . "$RUN_DIR/event-response.json"
printf 'Internal event ID: %s\nProducer event ID: %s\n' "$EVENT_ID" "$PRODUCER_EVENT_ID"
```

Expected: **202 Accepted**, `duplicate:false`, `event.id`, `event.acceptedAt`, and `Location: /v1/events/<internal-event-id>`. The system commits the event and transactional outbox before 202. Matching and delivery are asynchronous; 202 does not mean the webhooks have completed.

Use **EVENT_ID from the response** in GET paths and webhook comparisons. The producer-supplied `PRODUCER_EVENT_ID` is a separate duplicate identity, not the REST lookup ID. Preserve the same body, producer ID and key if retrying an uncertain POST. Do not generate a new run when retrying the same logical event.

## 6. Confirm the committed event

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" \
  "$BASE_URL/v1/events/$EVENT_ID" | jq .
```

Expected: **200** and the same `id`, payload, type and source. A fresh write can briefly return 404 on a lagging read replica; retry the GET rather than emitting a second new event. The saved 202 response already confirms the primary commit. A read-replica outage can return 503.

## 7. Wait for both deliveries and inspect attempts

```bash
for attempt in $(seq 1 60); do
  curl --fail-with-body --silent --show-error --max-time 10 \
    -H "Authorization: Bearer $API_TOKEN" \
    "$BASE_URL/v1/events/$EVENT_ID/deliveries?limit=100&offset=0" \
    > "$RUN_DIR/deliveries.json"
  if jq -e --arg a "$SUB_1" --arg b "$SUB_2" \
    '[.items[] | select(.subscriptionId==$a or .subscriptionId==$b)] | length==2 and all(.[]; .status=="delivered")' \
    "$RUN_DIR/deliveries.json" > /dev/null; then
    break
  fi
  sleep 2
done
jq -e --arg a "$SUB_1" --arg b "$SUB_2" \
  '[.items[] | select(.subscriptionId==$a or .subscriptionId==$b)] | length==2 and all(.[]; .status=="delivered")' \
  "$RUN_DIR/deliveries.json"
jq --arg a "$SUB_1" --arg b "$SUB_2" \
  '.items[] | select(.subscriptionId==$a or .subscriptionId==$b) | {id,eventId,subscriptionId,status,attemptCount,deliveredAt,attempts}' \
  "$RUN_DIR/deliveries.json"
DELIVERY_1=$(jq -er --arg sub "$SUB_1" '.items[] | select(.subscriptionId==$sub) | .id' "$RUN_DIR/deliveries.json")
DELIVERY_2=$(jq -er --arg sub "$SUB_2" '.items[] | select(.subscriptionId==$sub) | .id' "$RUN_DIR/deliveries.json")
```

Expected: two distinct delivery IDs, one per test subscription, the same event ID, `status:"delivered"`, and a completed attempt with `httpStatus:200` from each configured inbox. The polling loop is bounded; failed HTTP reads stop the commands rather than hiding errors. After diagnosing a transient GET failure, rerun this read-only section. After a delivery timeout/failure, inspect the saved statuses and attempts rather than recreating resources.

An initially empty delivery list can mean matching is not complete. `pending` can mean queued, leased or waiting to retry. `failed` is terminal; inspect `attempts` and `/v1/dead-letters`. Other broad subscriptions in this tenant may also match: the checks above select only the two IDs created by this test. With more than 100 matching deliveries, follow delivery pagination until both test IDs are found.

Individual delivery and per-subscription audit:

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" "$BASE_URL/v1/deliveries/$DELIVERY_1" | jq .
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" "$BASE_URL/v1/deliveries/$DELIVERY_2" | jq .
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" "$BASE_URL/v1/subscriptions/$SUB_1/deliveries?limit=100&offset=0" | jq .
curl --fail-with-body --silent --show-error --max-time 10 \
  -H "Authorization: Bearer $API_TOKEN" "$BASE_URL/v1/subscriptions/$SUB_2/deliveries?limit=100&offset=0" | jq .
```

## 8. Independently verify the webhook receivers

In each printed Webhook.site viewer, find the **POST** containing your `EVENT_ID`. Check `X-Event-ID`, `X-Delivery-ID`, `subscriptionId` and the payload. The event ID must match across both inboxes; delivery IDs and subscription IDs must differ. This proves receipt independently of our delivery audit. A 2xx delivery audit proves receiver acceptance, not an arbitrary downstream business action. Public inspection inboxes do not implement production subscriber-side delivery-ID deduplication or HMAC validation.

You can also retrieve captures without opening the receiving URL:

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  "https://webhook.site/token/$INBOX_1/requests?sorting=newest&per_page=100" \
  > "$RUN_DIR/inbox-1-requests.json"
curl --fail-with-body --silent --show-error --max-time 10 \
  "https://webhook.site/token/$INBOX_2/requests?sorting=newest&per_page=100" \
  > "$RUN_DIR/inbox-2-requests.json"
for inbox in 1 2; do
  jq --arg id "$EVENT_ID" \
    '[.data[] | select(.method=="POST") | {headers,body:(.content | fromjson?)} | select(.body.event.id==$id)]' \
    "$RUN_DIR/inbox-$inbox-requests.json"
done
```

Each resulting array should contain a matching notification. For an inbox requiring provider authentication, use its authorized UI/API access; do not substitute the notification-service bearer token. Seeing `X-Webhook-Signature` is not cryptographic verification: signature validation requires the receiver's separately provisioned signing key. Raw HTTP attempts can exceed one after ambiguous failures; a stable delivery ID supports receiver deduplication.

## 9. Optional idempotency replay

This submits the same logical event again; skip it if you want only one ingestion HTTP request. It should not create another event or additional delivery jobs.

```bash
curl --fail-with-body --silent --show-error --max-time 15 \
  -X POST "$BASE_URL/v1/events" \
  -H "Authorization: Bearer $API_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data-binary "@$RUN_DIR/event-request.json" \
  > "$RUN_DIR/event-replay.json"
jq -e --arg id "$EVENT_ID" '.duplicate==true and .event.id==$id' "$RUN_DIR/event-replay.json"
```

Expected: **202**, original internal ID, `duplicate:true`. Reusing an identity with changed content produces **409**. Redis accelerates duplicates for five minutes; PostgreSQL protects identities after cache expiry. The five-minute lifetime does not expire database identities. Existing delivery attempts can still retry independently of replay; this is at-least-once HTTP delivery, not exactly-once execution at the receiver.

## 10. Metrics and operator health

There is **no public application `/metrics` URL**. Java admin port 8081 is loopback-only on each Droplet; do not open a firewall rule for this test. Public `/health/ready` reports the selected backend, not a three-node inventory.

As an authorized operator, open a Droplet console or an entitled SSH shell and run these commands **on that Droplet**. The restricted CI deployment account is not a general operator shell. Repeat on `64.227.168.29`, `139.59.13.207` and `159.65.153.56` when checking every instance.

```bash
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8080/health/ready | jq .
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8081/healthcheck | jq .
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8081/metrics \
  | jq '{counters:(.counters | with_entries(select(.key | test("^(events\\.|delivery\\.|kafka\\.|http\\.(success|client\\.errors|server\\.errors|rate\\.limited)|cache\\.)")))),gauges:(.gauges | with_entries(select(.key | test("^(delivery\\.|kafka\\.|redis\\.|http\\.admission|events\\.acceptance)")))),acceptanceTimer:.timers["events.acceptance_latency"]}'
```

Useful local names: `events.accepted`, `events.duplicates`, `delivery.succeeded`, `delivery.unsuccessful_attempts`, `delivery.pending`, `delivery.oldest_pending_seconds`, `kafka.published`, `kafka.dispatched`, `kafka.outbox_pending`, `kafka.oldest_undispatched_seconds`, `redis.replicas`, and `cache.*`. Some counters appear only after their first occurrence. Local metric names differ from the exported `notification.*` names; see [metrics contract](metrics.md).

One event increments acceptance on the instance handling the POST; two successful deliveries may be processed on different instances. Do not expect each instance's counters to increase by the global totals. Counters can reset on process restart. Shared database backlog gauges describe the same work and must not be summed across replicas. Per-instance latency p95 is not a cluster-wide percentile.

DigitalOcean resource monitoring: open [DigitalOcean](https://cloud.digitalocean.com/), select team **Blitz_team13_Hyd**, then the Droplet or load balancer monitoring page. These show available infrastructure metrics, not proof of an individual event's delivery. A custom metrics bridge is configured, but the last verified Insights export returned HTTP 404 and Metrics Explorer visibility is unproven. Do not rely on Explorer to validate this smoke test. No alarms/ticket creation is configured.

## 11. URLs to keep handy

Replace UUID placeholders and send `Authorization: Bearer <tenant-token>` on every `/v1` request. A normal browser address bar does not supply this header; use curl or an API client. Only health links work anonymously.

| Purpose | Method and URL | Expected evidence |
| --- | --- | --- |
| Process alive | GET `https://174.138.121.225/health/live` | 200, alive |
| Backend ready | GET `https://174.138.121.225/health/ready` | 200, ready |
| Register webhook/filter | POST `https://174.138.121.225/v1/subscriptions` | 201, subscription ID |
| Find active subscriptions | GET `https://174.138.121.225/v1/subscriptions?limit=100&offset=0` | Items containing your two IDs; follow pagination |
| Commit event | POST `https://174.138.121.225/v1/events` | 202, event ID, acceptedAt, Location |
| Retrieve committed event | GET `https://174.138.121.225/v1/events/<EVENT_ID>` | Same internal ID and payload |
| Event delivery audit | GET `https://174.138.121.225/v1/events/<EVENT_ID>/deliveries?limit=100&offset=0` | Both test subscription IDs, statuses and attempts |
| Individual delivery | GET `https://174.138.121.225/v1/deliveries/<DELIVERY_ID>` | delivered, attempt time and HTTP response |
| Subscription audit | GET `https://174.138.121.225/v1/subscriptions/<SUBSCRIPTION_ID>/deliveries` | Delivery history for that subscription |
| Terminal failures | GET `https://174.138.121.225/v1/dead-letters?limit=100&offset=0` | Delivery dead letters; tenant-filtered |
| Receiver history | GET `https://webhook.site/token/<INBOX_ID>/requests?sorting=newest` | Captured notification POST |
| Local admin health | GET `http://127.0.0.1:8081/healthcheck` **on each Droplet** | Registered dependency checks |
| Local application metrics | GET `http://127.0.0.1:8081/metrics` **on each Droplet** | Dropwizard JSON metrics |

## 12. Clean up only these two subscriptions

Do this after both deliveries finish. Deleting a subscription prevents future matching; it does not cancel work already created or remove the event/delivery audit.

```bash
curl --fail-with-body --silent --show-error --max-time 10 \
  -X DELETE -H "Authorization: Bearer $API_TOKEN" \
  -w 'subscription 1 delete: HTTP %{http_code}\n' "$BASE_URL/v1/subscriptions/$SUB_1"
curl --fail-with-body --silent --show-error --max-time 10 \
  -X DELETE -H "Authorization: Bearer $API_TOKEN" \
  -w 'subscription 2 delete: HTTP %{http_code}\n' "$BASE_URL/v1/subscriptions/$SUB_2"
unset API_TOKEN
```

Expected: **204** for each. Inbox expiry was requested as 24 hours; provider policy controls actual expiry. Do not delete unrelated subscriptions, clear Redis, or remove database records to reset a demo. API audit remains available after terminal deliveries move into history.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| 401 | Missing/wrong tenant bearer token; a browser alone does not attach it |
| 403 or destination-policy error on registration | HTTPS hostname/allowlist/DNS policy; use the approved receiving URL, not the viewer URL |
| 400 / 422 | Exact JSON field names, JSON Pointer path, `eq`, type/source and payload values |
| 404 immediately after create | Read-replica lag; retry GET; confirm the internal ID and correct tenant |
| 409 on event POST | Same identity used with different content; preserve the original file/key for retries |
| 429 | Shared tenant fixed-second quota, or Webhook.site's separate provider quota; identify which host returned it |
| 503 | Shared limiter, persistence/read replica, readiness or admission/backlog dependency; inspect health and logs |
| No delivery yet | Asynchronous matching, filter mismatch, pagination, replica lag, backlog |
| Failed delivery | Read attempts: HTTP status, bounded error category and timestamps; check `/v1/dead-letters` |
| Receiver shows GET as well as POST | Receiving URL was opened in a browser; use viewer/history URLs instead |
| Signature header present | Not proof of HMAC validation; the receiver must verify it using the appropriate key |

Verification on 26 November 2026: public liveness/readiness returned 200; Droplet 1 loopback metrics and administrator healthcheck returned 200; active Webhook.site origin was confirmed. All 15 Bash blocks passed syntax checks. Both generated filter examples matched the generated event payload, and the delivery completion predicate passed success/pending/incomplete fixtures. The requests were checked against source contracts. This documentation task did not create inboxes/subscriptions or emit a new event. Run it with your own tenant token and inboxes to obtain end-to-end evidence for your demo. Earlier cloud delivery validations remain separate historical evidence.
