# Current design and implementation contract

## A. Scope and assumptions

Java 25, Dropwizard 5, PostgreSQL 17 and Kafka 4.3.1. Development lives under `/workspaces` in Rancher Ubuntu 24.04/IntelliJ. Deployment is the approved $24/month Bangalore Droplet, self-hosting Kafka/PostgreSQL. Managed Kafka, ordering and replay are excluded. No public production availability or data-loss SLO has been agreed.

## B. Functional acceptance

FR1: POST type/source/arbitrary JSON and acknowledge only after atomic event/jobs/outbox commit. FR2: create/list/delete tenant subscriptions without restart, with type/source/payload equality filters. Administrator origin approvals/revocations are also durable and immediate. FR3: activate matching jobs through Kafka, send signed webhooks, persist bounded retries and expose pending/delivered/failed. FR4: tenant-scoped event/subscription history includes attempt times, actual response code or null, outcome and final state. FR5: duplicates and concurrent processing cannot create duplicate event/subscription jobs; duplicate network delivery remains possible. Verification methods are in validation.md.

## C. Architecture and boundaries

[Architecture and request sequence](architecture.md) are authoritative. Synchronous work: authentication, validation, subscription snapshot, matching, database transaction, 202. Asynchronous work: outbox publication, Kafka dispatch, leased HTTP delivery, retry scheduling and audits. HTTP never holds the job-claim transaction open. One codebase is appropriate for the assessment: separate API/publisher/consumer/worker services only when measured scaling or ownership requires independent boundaries. PostgreSQL is the authoritative durable state; Kafka is the fanout dispatch backbone. No distributed cache is needed. A host/disk failure affects all components in the current deployment.

## D. Decisions and alternatives

|Decision|Reason|Trade-off / reconsideration trigger|
|---|---|---|
|PostgreSQL transaction + outbox|No unsafe DB/broker dual write|More DB writes; scale DB/outbox only after measurements|
|Event UUID Kafka key|One event type does not become one hot partition|No per-source ordering; add explicit serial scheduling only when required|
|Matching at ingestion|Clear subscriber-membership snapshot|Latency grows with subscriptions/fanout; async matching needs versioned membership/progress|
|Persisted origin approval checks|No stale cache or restart after changes|A DB read per validation; optimize only with explicit revocation semantics|
|Separate admin credential|Tenant tokens cannot change outbound destination policy|Operator provisioning rather than a full identity platform|
|Signed build-once release|Deploys the artifact tested/scanned by CI|Signing-key rotation and CI/repository protection require operations|
|Budget single host|Predictable small cost|No host/broker/DB failover; independently hosted replicas require a revised budget|

## E. APIs and data

See api.md and versioned SQL migrations. Events, subscriptions, delivery attempts, deliveries and Kafka outbox use UUIDs and database constraints. One job per event/subscription is enforced by a unique constraint; idempotency keys are tenant-scoped. Indexed due times/leases support claim and recovery. `approved_webhook_origins` stores active/revoked state; append-only `webhook_origin_audit` records policy commands. The runtime DB role has DML rather than schema ownership; migrations use the owner. Admin token and tenant tokens are independent. API pagination is bounded offset pagination, not snapshot pagination.

## F. Failures and security

See README delivery contract. Storage errors cannot return acceptance; transient Kafka outages retain the outbox; publish/consume duplicates are expected; offset commits follow DB dispatch commits; stale worker leases are fenced. Five total HTTP attempts bound retry work. Receivers must persist delivery deduplication, verify tenant-derived HMAC and timestamp, and return 2xx only for acceptance. Uncertain HTTP outcomes may duplicate side effects. Poison records are quarantined. Domain DNS is checked again during connection to reject private/metadata addresses. Public HTTPS ingress keeps administrator routes private; single-host internal traffic is plaintext only on the dedicated bridge. Secrets and API tokens are never logged or stored in Git. Sensitive production payloads must not be sent to the free demo receiver. Compliance certification is not applicable to this assessment and has not been established.

## G. Implementation and release

One Maven module with resources/auth/policy, Store, KafkaPipeline, DeliveryWorker, WebhookSender and retry/filter helpers. Liquibase migrations are append-only. GitHub tests real dependencies, builds/scans once, exercises startup/rollback/restore, then signs the exact release. A restricted upload user invokes a root-owned signed-bundle gateway; source checkout and root SSH credentials are not placed on the Droplet for CI. API and signing secrets remain server-side. The separate operator SSH key remains privileged and must be protected. Additive migrations permit application rollback; destructive schema rollback is not automated.

## H. Validation and operations

See validation.md and operations.md. Verify liveness/readiness, accepted event retrieval, matching fanout, response/timeout audit, duplicate handling and dependency recovery. Follow each cloud release with synthetic events and receiver capture verification. Local and CI tests are evidence for named scenarios, never every possible failure. Define alerts for readiness, backlog age, failed attempts, disk space and backup age before a production availability commitment. Backups on the same disk are not disaster recovery.

## I. Capacity, cost and evolution

Planning assumptions: 10 events/s average, 100/s peak, 1 KiB event and 3 matching subscriptions/event. Average fanout=30 deliveries/s; peak=300/s. At 200 ms per healthy receiver request, required HTTP concurrency is 6 average / 60 peak, before headroom. Four delivery workers on the budget host do not satisfy that peak; use measured throughput to set supported traffic. At 5-second receiver timeouts, four workers can finish only about 0.8 attempts/s. Event storage is approximately 0.86 GB/day at 10/s; jobs/attempts, indexes, WAL and backup copies add substantially. Three attempts per delivery means approximately 7.8 million attempt rows/day under these assumptions. Never extrapolate a short 10,000-event no-match probe into a full fanout SLO. Six Kafka partitions distribute keys; they do not create more hosts or worker capacity.

The existing 4 GiB / 2-vCPU / 80-GB Droplet has a $24/month base price; optional backups, tax, transfer overages and additional hosts are separate. No new paid services are provisioned by this hardening. Scale when measured acceptance latency, DB saturation, backlog age, disk runway or receiver fairness misses agreed targets. Partition tables/search/cache only when actual retention/query pressure justifies them. Off-host backups, HA, identity integration, fair scheduling, tracing and lifecycle policies remain explicit operational evolution work.
