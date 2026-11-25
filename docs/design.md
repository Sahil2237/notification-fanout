# System design: actual implementation and trade-offs

[Visual HLD](hld.md), [separate component LLDs](README.md#separate-low-level-design-documents) and [implemented concepts/metrics](concepts-and-metrics.md) provide the diagram-led companion to these decisions.

Runtime reference: `6474ad3`, verified 24 November 2026. Documentation refresh: 25 November2026 IST. [Assessment coverage](requirements.md), [architecture](architecture.md), [deployment evidence](submission-status.md), [API](api.md), [network/authentication](network-security.md).

## A. Scope, users and acceptance

Java 25/Dropwizard 5 exposes a tenant-authenticated REST API. PostgreSQL 17 is the authoritative event/job/audit store, Kafka 4.3.2 activates delivery work, Redis coordinates admission/retry budgets, and HTTPS receivers perform subscriber business actions. Operators approve webhook origins separately. Development lives under `/workspaces/notification-fanout` in Rancher Ubuntu 24.04, using IntelliJ at the user's request; the original VS Code/Cursor requirement and checked-in Dev Container alternative are recorded in requirements.md.

Four mandatory journeys are implemented: durable structured ingestion; dynamic filtered subscriptions; asynchronous fanout/retries with queryable state; event/subscription delivery audit. Matching uses type/source plus up to20 JSON Pointer equality conditions combined with AND. New subscriptions do not receive old events; deletion does not cancel previously snapshotted jobs. Unsupported filters return validation errors. There is no subscription update API, per-source ordering or replay API.

Acceptance criteria and their evidence are in requirements.md. Examples:202 follows the committed event/jobs/outbox transaction; identical concurrent event identities produce one event and one job per matching subscription; retryable failures allow one initial attempt plus five retries; ordinary read APIs cannot use a primary; all three application instances share the tenant quota. These are correctness/resource bounds, not a measured availability or latency SLA. Full-fanout throughput and p95/p99 production targets remain unagreed.

## B. Boundaries and processing model

This is one deployable Java application per host, not independently deployed API/consumer/worker microservices. The API, outbox publisher, Kafka consumer, delivery workers and DLQ publisher have separate responsibilities/executors inside that process. Three copies run behind a regional managed load balancer and Caddy reverse proxies. Split services only when independent scaling, release cadence or ownership justifies the operational cost.

Synchronous ingestion: authenticate tenant, shared Redis admission, validate body, check identity, read subscription membership, match filters, commit event/jobs/outbox, return202. Asynchronous work: outbox publication, Kafka job activation, delivery claims, HTTP attempts, recovery, retry scheduling and DLQ publication. HTTP network work holds no database transaction/connection. History/list reads use standbys; identity, matching, policy and job-state decisions use the primary.

PostgreSQL, etcd, Kafka and Redis quorum/replication are separate failure boundaries even though colocated on the same three 4-GB/2-vCPU/80-GB Droplets. The load balancer and Spaces are managed dependencies. All compute is BLR1, not a multi-region design. Private encrypted backup storage is SGP1 because new Bangalore Spaces creation was unavailable in the account.

## C. Major decisions

| Recommendation in the implemented design | Why it fits | Trade-off | Reconsider when |
| --- | --- | --- | --- |
| One Java application with bounded internal roles | Small assessment codebase and one delivery contract | Roles share process/host resources | API, Kafka activation or HTTP delivery needs independent scale/ownership |
| PostgreSQL transaction plus outbox | Eliminates an unsafe independent DB-save/Kafka-publish dual write | Extra storage, updates and scans | Measured DB/outbox pressure exceeds agreed latency/backlog targets |
| Match subscriptions inside ingestion | Atomic membership snapshot and complete fanout jobs before acknowledgement | Work grows with subscriptions and matches | High fanout requires asynchronous matching with versioned membership/progress |
| Internal event UUID Kafka key | Single event type spreads across six partitions | No per-source ordering | Business requires ordered delivery, including a policy for failed predecessors |
| Self-managed Kafka RF3/min ISR2 | Real durable backbone on existing hosts; managed Kafka was deferred | Operator owns upgrades, capacity and recovery | Managed-service budget and operational requirements change |
| Redis fixed-minute shared counters | One atomic tenant allowance across app instances; no DB connection per admission | Redis availability is required; asynchronous failover can lose counters; boundary bursts | Exact entitlement accounting or different burst semantics are required |
| Primary for decisions, strict standby-only API reads | Separates reads while preserving consistency-sensitive state transitions | Read-after-write lag or503 without a standby | A concrete API needs primary-consistent reads under a documented contract |
| One active claim per subscription and least-recently-claimed scheduling | Limits one subscription's concurrency across instances | Does not group different subscriptions sharing one endpoint/domain | Endpoint-wide isolation, tenant fairness or weighted service tiers are required |
| Strict synchronous PostgreSQL with required watchdog | Preserves acknowledged writes through the tested safe single-host failure | Writes stop without safe replication/quorum | A different explicitly agreed availability/durability trade-off is accepted |
| Daily encrypted Spaces dump,24-hour retention | Requested low-cost off-host recovery | Roughly a day's disaster exposure; failed/expired snapshots can leave none | Longer retention, WAL/PITR or regional recovery becomes required |
| Static tenant/admin bearer credentials | Simple service-to-service authentication and explicit tenant predicates | No OAuth/OIDC, end-user IAM or per-user quota | Multiple users/tenants need verified claims, lifecycle and granular permissions |

## D. APIs, validation and compatibility

`/v1` and webhook `schemaVersion=1` are explicit contracts. See api.md for every route, response and error. Bodies are bounded to256 KiB; type/source128 characters; nesting32; URLs2048; bounded offset pagination. The proxy also caps request bodies. Secrets authenticate before body parsing. Tenant identity comes from a configured bearer credential, not caller-supplied tenant headers. User/compartment identity is operator-configured; this is not a full identity provider.

`POST /v1/events` supports explicit idempotency keys, generated canonical-content/identity hashes and optional producer event IDs. Identical retries return the original event; conflicting reuse returns409. Without an explicit key/producer occurrence ID, identical content under the same identity deliberately collapses while retained. `POST /v1/subscriptions` does not have this idempotency contract: repeated creates can register multiple subscriptions for the same URL. There is no PUT/PATCH, ETag or If-Match conditional-update contract. HMAC webhook signatures authenticate sender/body; they are not ETags.

Receivers must tolerate additive envelope fields and transactionally deduplicate the stable delivery ID. Changing filter meaning or envelope interpretation requires explicit contract/version decisions. Database migrations are versioned; old migration history is immutable.

## E. Data model, transactions and consistency

| Entity | Identity, relationship and main access pattern |
| --- | --- |
| events | UUID; tenant-scoped idempotency uniqueness and optional tenant/user/compartment/producer-ID uniqueness; immutable accepted content and event lookup |
| subscriptions | UUID and tenant; immutable destination/filter snapshot, soft deletion and last-claim scheduling metadata |
| deliveries | UUID, event/subscription relationships and unique event/subscription pair; pending/delivered/failed, attempt count, due time, lease token/expiry |
| delivery_attempts | Delivery relationship and attempt number; start/finish, nullable HTTP code, outcome and bounded error category |
| event_outbox | Event relationship, availability/publication/dispatch state and ownership lease; safe replay after uncertain publishing/dispatch |
| delivery_dead_letters | Unique delivery relationship, terminal reason/count, failed/published times and independent publisher lease |
| kafka_dead_letters | Malformed-record quarantine by Kafka coordinates; different from delivery exhaustion |
| approved_webhook_origins / webhook_origin_audit | Active/revoked origin policy and append-only operator action audit |

Liquibase also maintains schema history/lock tables. Current backup snapshot metadata verifies nine application tables plus databasechangelog, not every physical database table. Migration005 introduced delivery controls,006 grants runtime rights, and007 removes the unused intermediate PostgreSQL counter table. Redis, not SQL counters, performs production admission.

Unique constraints arbitrate concurrent ingestion; indexed due times and lease fields support short claims/recovery; subscription row locks enforce the shared concurrency cap. Acceptance, attempt completion and terminal DLQ insertion use explicit transaction boundaries. If any required operation fails, its transaction rolls back. Terminal status and DLQ insertion cannot partially commit. Kafka offset commits follow durable activation. No transaction spans PostgreSQL, Kafka, Redis and receiver HTTP.

The writer pool maximum is 8/app and reader pool 4/app: up to24 writer plus 12 reader connections across three apps, excluding proxy/migration/backup/operator clients. PostgreSQL max_connections is 100 per server. Increasing the Droplet shape does not automatically change this self-managed setting; active-query memory/CPU and operational headroom determine a safe budget.

Read APIs check standby roles and return replica/eventual headers. A recent create can briefly be absent; clients can use the committed write response and retry reads. There is no response cache or cache-invalidation layer. Search engines and table sharding are not applicable to the implemented scope; introduce them only after retention/query evidence warrants them. Application data retention is not automated because the business expiry/audit contract is unspecified. Backup retention does not expire events or idempotency identities.

## F. Failure handling and delivery semantics

The contract is duplicate-tolerant processing with bounded HTTP delivery attempts, not unconditional eventual receipt or exactly-once external business effects. A lost202 response requires producer retry with the same identity. Outbox publication and Kafka activation may repeat; uniqueness/idempotent activation prevent duplicate jobs. A receiver can commit before the sender loses its response; retry then repeats the webhook. Delivery ID plus receiver-side transactional deduplication addresses that boundary.

Delivery claims last30 seconds, HTTP cancellation deadline5 seconds, and transient failures allow six total attempts. Persistent jittered backoff uses a1 second base and60 second cap. Permanent policy/HTTP failures stop earlier. Default shared retry budgets are 300/tenant/minute and30/subscription/minute; unavailable/exhausted budgets defer work without spending an HTTP attempt. Unique ownership tokens and expiry checks reject stale delivery/DLQ completions; these tokens cannot fence an already-issued external HTTP action. Poison Kafka records are durably quarantined.

Redis requires a recent replica for writes and the application sends WAIT1 on the same borrowed connection after admission. Missing safe acknowledgement returns503; exhausted allowance 429. Redis replication remains asynchronous, so quota updates can be lost or an overshoot admitted after failover. Do not present this as financial entitlement accounting. Accepted event durability remains a PostgreSQL responsibility.

One application-container failure uses Docker restart policy on its surviving host; the load balancer removes unready backends. There is no controller that provisions a replacement Droplet. Losing two whole hosts removes etcd/Kafka quorum, the required PostgreSQL standby and Redis safety. Readiness fails and normal durable admission stops/fails; the remaining process/health endpoint may still be reachable. Losing only two application containers while infrastructure remains healthy is different: the remaining application can serve traffic at reduced capacity.

## G. Security, privacy and trust boundaries

The service is publicly reachable through HTTPS. Java 8080 and admin/metrics 8081 bind loopback; Caddy fronts the API. There is no dedicated API gateway, WAF or OAuth/OIDC integration. Cloud Firewall plus host UFW/VPC rules protect infrastructure; these are DigitalOcean controls, not OCI NSGs. Public22/80/443 currently accept all sources; this is not a strict load-balancer-only backend design. SSH is key-only with locked root passwords. Outgoing traffic is broadly allowed, while webhook code separately enforces approved public HTTPS origins and connection-time DNS/address checks. Restrict SSH source ranges, backend ingress and network egress as explicit hardening work; see network-security.md.

Threat boundaries include untrusted producers, malicious destinations/DNS, cross-tenant object access, compromised credentials, unsafe release uploads and failing peers. Mitigations include credential-derived tenant predicates, independent admin credentials, runtime DML/migration-owner separation, HMAC/timestamps, TLS identity checks, separate internal authorities, private cluster ACLs and signed-image promotion. Application containers run with constrained resources/permissions. Offline authority keys remain off cloud hosts; internal certificates require operator renewal before 90 days. Secrets remain in protected configuration and are excluded from Git/logs/metrics labels. Root operators and signing/repository access remain trusted.

Backups are age-encrypted and authenticated. Application payloads are not field-encrypted by this service; do not send personal/regulated data to the public demo receiver. No compliance certification has been established. Durable recovery-secret escrow outside both cluster and bucket remains an operator obligation.

## H. Build, validation, deployment and operations

One Maven module contains REST resources/security, Store, RedisRateLimiter, KafkaPipeline, DeliveryWorker, WebhookSender, filters/retry helpers and metrics code. Resources hold local/HA configuration and SQL migrations. deploy/droplet holds signed application release tooling; deploy/ha holds quorum/TLS/Redis/backup renderers and drills; docs holds requirements, diagrams, API, evidence and runbooks.

Implementation milestones were: durable REST-to-HTTP slice; filters/subscriptions/audit and recovery; Kafka outbox/activation; signed CI and cloud deployment; three-node HA/Spaces recovery; shared Redis, generated keys, subscriber isolation, delivery DLQ and strict replica reads. Each milestone's evidence is version-specific in submission-status.md. Local setup is in README; tests must never target production databases.

Runtime CI passed72 Java tests,41 Python tests, actual service startup/privilege checks, failed-startup rollback/restore, synchronous PostgreSQL and Redis failure drills, and fixable HIGH/CRITICAL image gates. These are named scenarios, not exhaustive testing. The latest cloud rollout additionally checked shared quotas, API idempotency/conflict, replica reads, data preservation, public HTTPS, external webhook HMAC and Redis failover/rejoin. The earlier whole-Droplet failure and actual nine-table Spaces restore were not rerun as new ten-table disaster tests.

Build once, verify/sign exact artifacts, publish private images, stage over pinned SSH and promote through a restricted gateway. No CI root SSH/signing private keys are installed for runtime use. Initial cross-contract rollout stopped all old workers before new ones started; compatible releases roll one app at a time. Database schema is not automatically reversed. Binary rollback must preserve the current ownership/idempotency/limit contracts; do not reopen the stale single-host database. Infrastructure and backup-only tools are updated separately; changing backup tools did not restart PostgreSQL.

Local metrics, request/event correlation and audit are implemented. Three metrics bridges emit bounded aggregate OTLP attempts, but the last observed provider 404 blocks Metrics Explorer confirmation. There are no alarms/tickets by explicit request and no complete distributed-tracing backend. Operational signals include readiness, backlog age, delivery failures/stale results, consumer lag/ISR, DB pools, Redis replicas, disk and backup age. They are not an agreed SLO or configured paging policy. See operations.md and metrics.md.

Daily Spaces snapshots have24-hour retention plus timer delay. Earlier actual restore authenticated/decrypted a Spaces archive into isolated PostgreSQL and checked nine historical snapshot counts. New tools check ten-table metadata and backup-role DLQ read access; do not claim a new ten-table Spaces restore. Region/cluster destruction can lose roughly a day or more if no usable daily snapshot exists; there is no continuous WAL/PITR or measured full-service disaster RTO.

## I. Capacity, cost and evolution

These are planning assumptions, not measured supported throughput:10 events/s average,100/s peak,1 KiB event payload and three matching subscriptions/event, with one HTTP attempt per healthy delivery. Assume90% API requests are ingestion and10% are reads for an illustrative9:1 REST write/read ratio. Actual DB traffic has many more writes/reads for matching, outbox, claims and audit; no measured DB read/write ratio is claimed.

| Calculation | Consequence |
| --- | --- |
|10 x3 =30 deliveries/s average;100 x3 =300/s peak | Fanout, not ingestion count alone, sets worker demand |
| Required HTTP concurrency = attempts/s x receiver duration | At200 ms:6average and60peak before headroom; current12 workers cannot serve that assumed peak |
| Current healthy ceiling at 200 ms is 12/0.2 =60 attempts/s | Illustrative only; CPU/DB/TLS/receiver fairness and retries reduce it; one subscription is also capped at one active attempt |
| Twelve workers at 5 second timeout:12/5 =2.4 completed attempts/s | Slow receivers materially reduce capacity; budgets and bulkheads limit damage, not create throughput |
|10 x86,400 =864,000 events/day; x1,024 bytes =0.885 GB raw event payload/day | Jobs, JSON envelopes, indexes, WAL, three DB copies and backups add substantially;80 GB host disks are shared with Kafka |
|864,000 x3 =2,592,000 jobs/day | At one attempt/job the same number of attempt rows; a stress case of three attempts/job creates7,776,000/day and90 attempts/s average, above the illustrative current worker capacity |
| Payload-only bandwidth:10 KiB/s ingest,30 KiB/s webhook average;100/300 KiB/s peak | Excludes HTTP/TLS/envelope overhead, retries and database/Kafka replication; each Kafka record has two additional replica copies |
|6,000 requests/minute =100 requests/s tenant average within a window | Reads also spend this allowance; a boundary burst is possible. Admission limit is not an end-to-end throughput promise |
|Six partitions and three consumers:two partitions/consumer | At most six active consumers in that group without more partitions; delivery workers scale separately |

The historical10,000-event cloud probe offered50/s with no matching subscriptions. The Kafka10,000-record single-type test checks partition distribution. Neither establishes a300 webhook/s fanout SLO or10,000 events/s capacity. Measure payload size, subscriptions, matching ratio, receiver latency, retries, duration, backlog growth and recovery before making a workload commitment. Add headroom from those measurements, not from an untested sizing formula alone.

The selected deployment estimate recorded at provisioning was roughly $101/month base:three $24 Droplets, a $24 two-unit load balancer and$5 Standard Spaces, before taxes/overages/unrelated resources. This is a historical estimate, not a current pricing quote. Redis reused existing hosts. Storage, backup/egress volume, telemetry ingestion and future managed services are cost drivers; no additional paid resource was added for Redis. Independent physical placement and a region outage are not covered merely by having three hosts.

Reconsider component separation, more partitions/workers, retention/partitioning, pooling and managed services when measured backlog/latency, disk runway, connection saturation or failure requirements demand them. Horizontal autoscaling, verified end-user claims/per-user quotas, managed Kafka, endpoint-wide fairness, ordering, replay, ETags for a future update API, general caching, certificate automation and regional recovery remain explicit evolution work, not hidden implemented features.
