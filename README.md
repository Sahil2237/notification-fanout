# Event Notification Fanout

Java 25 / Dropwizard 5 event ingestion and webhook fanout service for DigitalOcean Bangalore. PostgreSQL durably accepts events and matching delivery jobs in one transaction; an outbox publisher sends them to Kafka, and consumers activate retryable webhook deliveries. [Deployment and current evidence](docs/submission-status.md), [architecture](docs/architecture.md), [API](docs/api.md), [design](docs/design.md), [operations](docs/operations.md), [requirements-to-tests](docs/validation.md).

The HA release uses three 4-GB Ubuntu 24.04 Droplets, three Kafka brokers/controllers, strict synchronous PostgreSQL with Patroni/etcd, and three application replicas behind a two-node DigitalOcean load balancer. Encrypted daily backups go to a private Spaces bucket with 24-hour retention. [HA configuration and limits](deploy/ha/README.md) describe the failure boundary; [deployment evidence](docs/submission-status.md) distinguishes completed cloud verification from a rollout in progress. The approximate base cost is $101/month, without managed Kafka or a paid registry.

**Live HA endpoint:** `https://174.138.121.225`. The authorized cutover preserved all 10,011 existing events and verified all nine imported tables by count and content fingerprint. The cloud host-offline test promoted another PostgreSQL primary and delivered a webhook with two Kafka brokers; an actual encrypted Spaces backup restored successfully. See [current evidence and limits](docs/submission-status.md).

## Setup and development

All editing, builds and tests belong in `/workspaces/notification-fanout` inside the Rancher Ubuntu 24.04 container, using IntelliJ Remote Development. Do not save assessment source on the host Desktop or Downloads. See [IDE instructions](docs/intellij.md).

```bash
cd /workspaces/notification-fanout
mvn -B -ntp verify
python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v
java -jar target/notification-fanout.jar db migrate config/local.yml
./scripts/start-local.sh
```

From a fresh clone on a Docker-capable machine, `docker compose --profile dev up --build -d db kafka kafka-init development` prepares the same development services. Open the development container in IntelliJ and execute the commands above. Never point `TEST_DB_URL` at production: tests migrate and truncate their dedicated test database. `mvn test` runs units; `mvn verify` includes real PostgreSQL/Kafka/HTTP tests. Local API defaults to port 8080, metrics/admin to 8081; development receiver is `scripts/receiver.py` on 9090. Do not start development and packaged app services on the same published ports.

For a packaged local run: `docker compose up -d db kafka kafka-init`, `docker compose run --build --rm migrate`, then `docker compose up --build -d app`. Local-only credentials and HTTP destinations are defined in `config/local.yml`; never expose those defaults publicly.

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

Ingestion returns `202` and `event.id` after the database commit. It does not wait for webhook receipt. Query `/v1/events/{id}/deliveries`, `/v1/subscriptions/{id}/deliveries` or `/v1/deliveries/{id}` for status and attempts. List/delete subscriptions through `/v1/subscriptions`; changes apply without restart. Existing queued jobs retain their original destination after subscription deletion. New subscriptions do not receive past events.

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

See [the complete diagrams](docs/architecture.md). Matching happens in the ingestion transaction; PostgreSQL holds event, jobs and outbox. Kafka uses event UUID keys so one event type is spread over partitions. Consumers commit offsets after their dispatch database transaction. Delivery workers claim due jobs using expiring leases, send signed requests outside the database transaction, and atomically save attempt history and the next state/schedule.

## Delivery contract and failure conditions

|Property|Contract|
|---|---|
|Acceptance|202 only after event + matching jobs + outbox commit; storage errors return a failure|
|Processing|Durable, duplicate-tolerant processing while PostgreSQL storage survives|
|Webhook delivery|Up to five total attempts with possible duplicates; permanent failures and exhausted retries become queryable `failed`|
|At-most-once?|No: uncertain outcomes can cause repeated requests|
|Exactly-once?|No: a receiver may commit a side effect and lose the response before the sender records success|
|Unconditional eventual receipt?|No: finite retries, receiver outages, revocation, and loss of all database copies and usable backups can prevent receipt|

The practical guarantee is **at-least-once-style processing with bounded HTTP delivery attempts**, not exactly-once side effects. Receivers must deduplicate by stable `deliveryId`. A Kafka publication or dispatch can repeat after a crash without creating another delivery job. A crash after successful HTTP receipt but before recording it can repeat the webhook. Lease fencing prevents old workers from overwriting a newer attempt.

HTTP 2xx succeeds. Network errors/timeouts, 408, 429 and 5xx retry; redirects and other responses fail permanently. Apache HttpClient automatic retries and redirects are disabled. Each request has a cancellation deadline (default 5 s); claims have a 30 s lease. Backoff is 50–100% jitter on `min(60s, 1s × 2^(attempt-1))`, persisted in PostgreSQL. Unknown/interrupted attempts count toward the total. `Retry-After` is not currently honored. Null HTTP codes mean no response code was durably recorded.

A tenant-scoped idempotency key returns the original event on identical content and 409 on changed content; omitting it allows repeats. Keys last as long as retained events. Payload object property order is canonicalized; numerical representation can affect the content hash. Events with zero matches are retained and traverse Kafka.

Outgoing authentication derives `tenant_key = HMAC-SHA256(master, "tenant:" + tenant_id)` and signs `timestamp + "." + raw_body`. Headers include `X-Delivery-ID`, `X-Event-ID`, `X-Webhook-Timestamp`, and `X-Webhook-Signature: v1=<hex>`. Provision only the derived tenant key to its receiver through a secure operator channel. Receivers verify freshness/signature and persist deduplication IDs. The free Webhook.site sink captures test requests; it is not a production receiver implementation.

## CI/CD and verification

GitHub Actions runs Java units/integrations and signed-bundle security tests on push/PR. It builds one image, gates on fixable HIGH/CRITICAL findings, validates Caddy, starts real containers, checks runtime DB permissions, exercises failed-startup rollback and restores a backup. Only then is that exact image packaged and signed for a trusted default-branch push. Deployment downloads the artifact from that successful run; it does not rebuild it.

A dedicated SSH user has no Docker membership, cannot read application secrets, and can sudo only a root-owned promotion gateway. The gateway validates the signature, checksums, revision, image identity and safe bundle paths before release. Pinned SSH host identity and CI action commits constrain the trust boundary. See [Droplet setup](deploy/droplet/README.md) and [verification matrix](docs/validation.md).

## What we sacrifice for simplicity vs. what we would harden next

|Current choice|Benefit|Limit and next hardening trigger|
|---|---|---|
|Four roles colocated on each of three Droplets|Limits HA infrastructure cost while surviving one tested Droplet failure|CPU/memory and regional failures are shared; separate roles or regions when measured load or failure targets justify the cost|
|Match inside ingestion transaction|Atomic membership snapshot and fanout jobs|O(subscriptions + matches); move matching async with versioned membership if measured latency requires it|
|Kafka dispatch, DB retry scheduler|Durable queue and queryable delayed attempts|Shared DB load; tune publisher/consumer counts using representative measurements|
|Runtime administrator origin approvals|Immediate controlled onboarding/revocation|Origin-level permission, not per-path or tenant-specific approval; outbound network enforcement remains useful|
|Static tenant credentials|Small, explicit authorization model|Operator provisioning/rotation; integrate identity management and tenant-specific quotas for broader service use|
|Mutual TLS and separate internal authorities|Encrypts/authenticates cluster traffic and separates application/control credentials|Internal certificates last 90 days; renewal and expiry monitoring require operator ownership|
|Bounded retries, no ordering/replay|Independent, finite delivery work|Terminal failures need an explicit operator redrive policy; ordering/replay remain optional extensions|
|Per-replica request limit|Simple admission control on each application replica|Three replicas can admit up to three times one replica limit; shared tenant quotas and receiver fairness remain future work|
|Daily encrypted Spaces backup and strict 24-hour retention|Independent storage and an authenticated restore procedure|Total cluster loss can lose up to roughly 24 hours; no continuous WAL/PITR, and expiring an old backup before a failed new one can leave none|
|Explicit retention policy, no automatic deletion|Avoids unapproved data removal|Implement bounded retention after business/audit and idempotency-key expiry requirements are agreed|
|Logs, metrics and health checks|Inspect requests, backlog and errors|Configure external alerts, dashboards and paging; no unmeasured SLO claim|

## Submission

Source, Mermaid diagrams, tests, workflows and runbooks are in the personal GitHub repository. Grant interviewer access to the private repository. Sign out of personal accounts used on the assessment workstation before handing it back; retain authorized server deployment credentials separately. No claim of exhaustive testing, zero data loss after disk destruction, or high availability is made by a green pipeline.
