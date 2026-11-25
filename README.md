# Event Notification Fanout

Java 25 / Dropwizard 5 event ingestion and webhook fanout service for DigitalOcean Bangalore. PostgreSQL durably accepts events and matching delivery jobs in one transaction; an outbox publisher sends them to Kafka, and consumers activate retryable webhook deliveries. [Deployment and current evidence](docs/submission-status.md), [visual HLD](docs/hld.md), [component LLDs](docs/README.md#separate-low-level-design-documents), [architecture](docs/architecture.md), [API](docs/api.md), [design](docs/design.md), [operations](docs/operations.md), [requirements-to-tests](docs/validation.md).

The deployed runtime is **6474ad3** on three existing 4-GB Ubuntu 24.04 Droplets in BLR1: three application replicas, self-managed Kafka, synchronous PostgreSQL/Patroni/etcd, and shared Redis with Sentinel failover. A managed regional load balancer fronts Caddy. Encrypted daily backups use private Spaces in SGP1 with 24-hour retention. [Requirements coverage](docs/requirements.md), [network/authentication](docs/network-security.md) and [current evidence](docs/submission-status.md) distinguish verified implementation, historical drills and remaining gaps.

**Live API:** `https://174.138.121.225`. The latest cutover preserved 10,136 existing events and five subscriptions before probes, verified one shared tenant quota across three apps, idempotent event replay/conflict, replica reads, one signed external webhook receipt and Redis failover/rejoin. [CI passed](https://github.com/Sahil2237/notification-fanout/actions/runs/45447905686). Earlier whole-Droplet/PostgreSQL failover and an actual nine-table Spaces restore are separate evidence, not new drills for every release.

**Important limits:** no managed queue, per-source ordering, replay API, per-user quota, horizontal autoscaler or general data cache. POST subscription is not key-idempotent; no PUT/PATCH/ETag contract exists. Metrics emission is installed but DigitalOcean Explorer visibility remains unverified after provider 404. No alarms/tickets were requested. Public backend/SSH ingress and broad egress remain documented hardening gaps. Load distribution is provider-managed (the legacy algorithm selector is deprecated); live checks use HTTP 9080 /health/ready every 5 s, timeout 3 s, thresholds 2 failures/2 successes, and sticky sessions off. [Verified LB settings](docs/network-security.md#load-balancing-and-gateway-boundary).

## Diagram-led design documents

Start with the [visual design guide](docs/README.md) and [high-level design](docs/hld.md). Open separate LLDs for [Notification app](docs/lld/notification-app.md), [Dropwizard runtime](docs/lld/dropwizard.md), [PostgreSQL/Patroni](docs/lld/postgresql.md), [Kafka](docs/lld/kafka.md), [Redis/Sentinel](docs/lld/redis-sentinel.md), [etcd](docs/lld/etcd.md), [Caddy](docs/lld/caddy.md) and [DigitalOcean load balancer](docs/lld/load-balancer.md). Each includes low-level requirements, diagrams, bounds, failure handling, security, metrics and evidence. Dropwizard and the Notification app share one JVM, not separate app containers.

The [system-design concepts and metrics catalogue](docs/concepts-and-metrics.md) maps implemented controls and all explicit custom instrumentation, including local-only metrics, provider-supported telemetry and observability gaps. It does not claim every supported metric is visible in DigitalOcean or every proposed production improvement is deployed.

## Setup and development

All editing, builds and tests belong in `/workspaces/notification-fanout` inside the Rancher Ubuntu 24.04 container, using IntelliJ Remote Development. Do not save assessment source on the host Desktop or Downloads. See [IDE instructions](docs/intellij.md).

```bash
cd /workspaces/notification-fanout
mvn -B -ntp verify
python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v
python3 -m unittest discover -s deploy/ha -p 'test_*.py' -v
java -jar target/notification-fanout.jar db migrate config/local.yml
./scripts/start-local.sh
```

From a fresh clone on a Docker-capable machine, `docker compose --profile dev up --build -d db redis kafka kafka-init development` prepares the same development services. Open the development container in IntelliJ and execute the commands above. Never point `TEST_DB_URL` at production: tests migrate and truncate their dedicated test database. `mvn test` runs units; `mvn verify` includes real PostgreSQL/Kafka/HTTP tests. Local API defaults to port 8080, metrics/admin to 8081; development receiver is `scripts/receiver.py` on 9090. Do not start development and packaged app services on the same published ports.

For a packaged local run: `docker compose up -d db redis kafka kafka-init`, `docker compose run --build --rm migrate`, then `docker compose up --build -d app`. Local-only credentials and HTTP destinations are defined in `config/local.yml`; never expose those defaults publicly.

## REST usage

Tenant `/v1` endpoints require a tenant bearer token; the separate administrator endpoints require the administrator token. The current cloud tenant is `service`; authentication tokens and signing master stay in each node's root-only `/opt/notification-fanout/ha/secrets.json`. The deployed REST base is `https://174.138.121.225`, fronted by Caddy with a publicly trusted certificate; the API/admin ports are not published publicly. An SSH tunnel remains available; see [access instructions](deploy/droplet/README.md).

Create a subscription **before** publishing its events:

```json
{
  "webhookUrl": "https://your-receiver.example/events",
  "filter": {
    "type": "order.created",
    "source": "checkout",
    "payloadConditions": [
      {"path": "/customer/tier", "operator": "eq", "value": "gold"}
    ]
  }
}
```

Send that body to `POST /v1/subscriptions`. Send the following to `POST /v1/events`, optionally with `Idempotency-Key: order-123-created`:

```json
{"type":"order.created","source":"checkout","payload":{"orderId":"123","customer":{"tier":"gold"}}}
```

Ingestion returns `202` and `event.id` after the database commit. It does not wait for webhook receipt. Query `/v1/events/{id}/deliveries`, `/v1/subscriptions/{id}/deliveries` or `/v1/deliveries/{id}` for status and attempts. List/delete subscriptions through `/v1/subscriptions`; changes apply without restart. Existing queued jobs retain their original destination after subscription deletion. New subscriptions do not receive past events. Repeating POST /v1/subscriptions can create a second registration for the same URL: event idempotency does not deduplicate separate subscriptions.

### Filter syntax

Omitted type/source/conditions mean no restriction at that level; supplied conditions combine with AND. Payload paths are JSON Pointer: `/items/0/name`, `~1` escapes `/`, `~0` escapes `~`, and empty path selects the whole payload. Only `eq` is supported. Missing differs from null, strings are case-sensitive, scalar numbers 1 and 1.0 compare equal, and booleans differ from numbers. Objects/arrays use structural JSON equality. Maximum 20 conditions, 256 KiB request body, depth 32, and 128 characters for event type/source.

### Approve webhook origins without restart

An independent `ADMIN_TOKEN` controls `GET/POST/DELETE /v1/admin/webhook-origins`. Tenant tokens cannot call these endpoints; the administrator token cannot ingest tenant events. Public Caddy routes reject administrator endpoints, so operators use the private SSH tunnel.

```bash
curl -fsS -X POST "$BASE_URL/v1/admin/webhook-origins" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"origin":"https://your-receiver.example"}'
```

PostgreSQL stores approvals, revocations and their audit. All instances consult the store at registration and before each delivery attempt; no cache or restart is involved. Startup seeds previously unknown configuration origins but never reactivates a revoked origin. Revocation can stop queued attempts; already in-flight HTTP requests may finish. HTTPS and connection-time public-address checks remain mandatory, including protection against DNS changing to private/metadata addresses. Origins contain scheme, host and optional port, not paths/query strings. Dynamic origin approval is an operator action; subscribers then register their complete receiver URL with their tenant token.

## Architecture

See [the complete diagrams](docs/architecture.md) and [reliability controls](docs/reliability-controls.md). Matching happens in the ingestion transaction; PostgreSQL holds event, jobs and outbox. Kafka uses event UUID keys so one event type is spread over partitions. Consumers commit offsets after their dispatch database transaction. Delivery workers claim due jobs using expiring leases, send signed requests outside the database transaction, and atomically save attempt history and the next state/schedule.

## Delivery contract and failure conditions

|Property|Contract|
|---|---|
|Acceptance|202 only after event + matching jobs + outbox commit; storage errors return a failure|
|Processing|Durable, duplicate-tolerant processing while PostgreSQL storage survives|
|Webhook delivery|Up to six total attempts (initial attempt plus five retries) with possible duplicates; permanent failures and exhausted retries become queryable `failed`|
|At-most-once?|No: uncertain outcomes can cause repeated requests|
|Exactly-once?|No: a receiver may commit a side effect and lose the response before the sender records success|
|Unconditional eventual receipt?|No: finite retries, receiver outages, revocation, and loss of all database copies and usable backups can prevent receipt|

The practical guarantee is **at-least-once-style processing with bounded HTTP delivery attempts**, not exactly-once side effects. Receivers must deduplicate by stable `deliveryId`. A Kafka publication or dispatch can repeat after a crash without creating another delivery job. A crash after successful HTTP receipt but before recording it can repeat the webhook. Lease fencing prevents old workers from overwriting a newer attempt.

HTTP 2xx succeeds. Network errors/timeouts, 408, 429 and 5xx retry; redirects and other responses fail permanently. Apache HttpClient automatic retries and redirects are disabled. Each request has a cancellation deadline (default 5 s); claims have a 30 s lease. Backoff is 50–100% jitter on `min(60s, 1s × 2^(attempt-1))`, persisted in PostgreSQL. Unknown/interrupted attempts count toward the total. `Retry-After` is not currently honored. Null HTTP codes mean no response code was durably recorded.

A tenant-scoped explicit idempotency key returns the original event on identical content and 409 on changed content. When omitted, a versioned SHA-256 key is generated from canonical type/source/payload, optional producer eventId, and authenticated tenant/user/compartment identity. Repeated identical content under that identity returns the original event. Send a new producer eventId or explicit key for a genuinely separate occurrence with identical content. A reused producer eventId with changed content returns 409. Keys last as long as retained events. Payload object property order is canonicalized; numerical representation can affect the content hash. Events with zero matches are retained and traverse Kafka.

Outgoing authentication derives `tenant_key = HMAC-SHA256(master, "tenant:" + tenant_id)` and signs `timestamp + "." + raw_body`. Headers include `X-Delivery-ID`, `X-Event-ID`, `X-Webhook-Timestamp`, and `X-Webhook-Signature: v1=<hex>`. Provision only the derived tenant key to its receiver through a secure operator channel. Receivers verify freshness/signature and persist deduplication IDs. The free Webhook.site sink captures test requests; it is not a production receiver implementation.

## CI/CD and verification

GitHub Actions runs Java units/integrations and signed-bundle security tests on push/PR. It builds one image, gates on fixable HIGH/CRITICAL findings, validates Caddy, starts real containers, checks runtime DB permissions, exercises failed-startup rollback and restores a backup. It also validates the TLS Redis/Sentinel runtime and scans Redis alongside the other infrastructure images. Only then is that exact image packaged and signed for a trusted default-branch push. Deployment downloads the artifact from that successful run; it does not rebuild it.

A dedicated SSH user has no Docker membership, cannot read application secrets, and can sudo only a root-owned promotion gateway. The gateway validates the signature, checksums, revision, image identity and safe bundle paths before release. Pinned SSH host identity and CI action commits constrain the trust boundary. See [Droplet setup](deploy/droplet/README.md) and [verification matrix](docs/validation.md).

## What we sacrifice for simplicity vs. what we would harden next

|Current choice|Benefit|Limit and next hardening trigger|
|---|---|---|
|Application and data roles colocated on three Droplets|Limits HA infrastructure cost while surviving one tested Droplet failure|CPU/memory and regional failures are shared; separate roles or regions when measured load or failure targets justify the cost|
|Match inside ingestion transaction|Atomic membership snapshot and fanout jobs|O(subscriptions + matches); move matching async with versioned membership if measured latency requires it|
|Kafka dispatch, DB retry scheduler|Durable queue and queryable delayed attempts|Shared DB load; tune publisher/consumer counts using representative measurements|
|Runtime administrator origin approvals|Immediate controlled onboarding/revocation|Origin-level permission, not per-path or tenant-specific approval; outbound network enforcement remains useful|
|Static tenant credentials|Small, explicit authorization model with shared tenant quotas|Operator provisioning/rotation; add verified end-user claims, fine-grained permissions and per-user quotas when required|
|Mutual TLS and separate internal authorities|Encrypts/authenticates cluster traffic and separates application/control credentials|Internal certificates last 90 days; renewal and expiry monitoring require operator ownership|
|Five retries and transactional delivery DLQ|Terminal failures are queryable and published to a separate Kafka DLQ without losing audit|Permanent failures go directly to DLQ; publication may repeat, so DLQ consumers deduplicate deliveryId; automatic redrive, ordering and replay remain optional|
|Shared fixed-window request limit|Atomic Redis Lua counters coordinate application replicas before event persistence; retry budgets share the same store|Redis availability is required; asynchronous replication can lose counters on failover, and fixed windows permit a boundary burst. Redis cloud rollout and existing-client failover were verified; no strict zero-overshoot quota claim|
|Daily encrypted Spaces backup and strict 24-hour retention|Independent storage and an authenticated restore procedure|A usable latest daily backup has roughly 24-hour disaster exposure; no continuous WAL/PITR. Missing/expired backups can mean greater loss or no recovery|
|Application history retained without automatic deletion|Avoids unapproved data removal|Implement bounded retention after business/audit and idempotency-key expiry requirements are agreed|
|Logs, metrics and health checks|Inspect requests, backlog and errors locally|Last observed Insights provider 404 blocks Explorer confirmation; alarms/tickets excluded; no measured SLO claim|

## Submission

Source, Mermaid diagrams, tests, workflows and runbooks are in the personal GitHub repository. Grant interviewer access to the private repository. Sign out of personal accounts used on the assessment workstation before handing it back; this remains a final handoff step. Retain authorized server deployment credentials separately. IntelliJ was used at the user's request; the original VS Code/Cursor requirement and checked-in Dev Container option are recorded in docs/requirements.md. No claim of exhaustive testing, zero data loss after disk destruction, or high availability is made by a green pipeline.

Metrics-only DigitalOcean Insights export, configuration, metric names and verification status: [Metrics emission](docs/metrics.md). Alarms and ticket creation are outside the current requested scope.
