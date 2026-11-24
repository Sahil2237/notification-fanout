# Current design and implementation contract

## A. Scope and assumptions

Java 25, Dropwizard 5, PostgreSQL 17 and Kafka 4.3.2. Development lives under `/workspaces` in Rancher Ubuntu 24.04/IntelliJ. The HA release uses three $24/month Bangalore Droplets, a $24/month two-node load balancer and $5/month private Spaces backup storage (about $101/month base). Managed Kafka, ordering and replay are excluded. No public production availability or data-loss SLO has been agreed.

## B. Functional acceptance

FR1: POST type/source/arbitrary JSON and acknowledge only after atomic event/jobs/outbox commit. FR2: create/list/delete tenant subscriptions without restart, with type/source/payload equality filters. Administrator origin approvals/revocations are also durable and immediate. FR3: activate matching jobs through Kafka, send signed webhooks, persist bounded retries and expose pending/delivered/failed. FR4: tenant-scoped event/subscription history includes attempt times, actual response code or null, outcome and final state. FR5: duplicates and concurrent processing cannot create duplicate event/subscription jobs; duplicate network delivery remains possible. Verification methods are in validation.md.

## C. Architecture and boundaries

[Architecture and request sequence](architecture.md) are authoritative. Synchronous work: authentication, validation, subscription snapshot, matching, database transaction, 202. Asynchronous work: outbox publication, Kafka dispatch, leased HTTP delivery, retry scheduling and audits. HTTP never holds the job-claim transaction open. One codebase is appropriate for the assessment: separate API/publisher/consumer/worker services only when measured scaling or ownership requires independent boundaries. PostgreSQL is the authoritative durable state; Kafka is the fanout dispatch backbone. No distributed cache is needed. Each Droplet hosts a replica of the application, Kafka, PostgreSQL and etcd. A single-node failure is tolerated with a surviving quorum; the three-node same-region cluster does not provide zero-loss regional disaster recovery.

## D. Decisions and alternatives

|Decision|Reason|Trade-off / reconsideration trigger|
|---|---|---|
|PostgreSQL transaction + outbox|No unsafe DB/broker dual write|More DB writes; scale DB/outbox only after measurements|
|Event UUID Kafka key|One event type does not become one hot partition|No per-source ordering; add explicit serial scheduling only when required|
|Matching at ingestion|Clear subscriber-membership snapshot|Latency grows with subscriptions/fanout; async matching needs versioned membership/progress|
|Persisted origin approval checks|No stale cache or restart after changes|A DB read per validation; optimize only with explicit revocation semantics|
|Separate admin credential|Tenant tokens cannot change outbound destination policy|Operator provisioning rather than a full identity platform|
|Signed build-once release|Deploys the artifact tested/scanned by CI|Signing-key rotation and CI/repository protection require operations|
|Three colocated HA nodes|Quorum replication without separate machines for each role|Shared CPU/disk/region; split components or regions after measured pressure or stronger failure targets|
|Strict synchronous PostgreSQL and watchdog fencing|Acknowledged commits reach the primary and a synchronous standby before success|Availability is sacrificed when safe replication or quorum is unavailable; never force an asynchronous promotion|
|Kafka RF3, min ISR2 and UUID keys|One broker loss can preserve dispatch without event-type hotspots|Quorum loss delays work; PostgreSQL outbox remains authoritative|
|Daily private encrypted Spaces backup|Independent recovery storage at low base cost|24-hour disaster RPO and one-day-only retention; add WAL/PITR and longer retention only with a revised requirement|

## E. APIs and data

See api.md and versioned SQL migrations. Events, subscriptions, delivery attempts, deliveries and Kafka outbox use UUIDs and database constraints. One job per event/subscription is enforced by a unique constraint; idempotency keys are tenant-scoped. Indexed due times/leases support claim and recovery. `approved_webhook_origins` stores active/revoked state; append-only `webhook_origin_audit` records policy commands. The runtime DB role has DML rather than schema ownership; migrations use the owner. Admin token and tenant tokens are independent. API pagination is bounded offset pagination, not snapshot pagination.

## F. Failures and security

See README delivery contract. Storage errors cannot return acceptance; transient Kafka outages retain the outbox; publish/consume duplicates are expected; offset commits follow DB dispatch commits; stale worker leases are fenced. Six total HTTP attempts allow five retries; permanent failures terminate early. Terminal results and a durable delivery DLQ commit together, then a leased publisher forwards them to the Kafka DLQ. Receivers must persist delivery deduplication, verify tenant-derived HMAC and timestamp, and return 2xx only for acceptance. Uncertain HTTP outcomes may duplicate side effects. Poison records are quarantined. Domain DNS is checked again during connection to reject private/metadata addresses. Public HTTPS ingress keeps administrator routes private; cross-host PostgreSQL connections verify server identity, while Kafka and failover control use mutual TLS and separate authorities. Private firewall rules restrict cluster traffic to the three peers. Secrets and API tokens are never logged or stored in Git. Sensitive production payloads must not be sent to the free demo receiver. Compliance certification is not applicable to this assessment and has not been established.

## G. Implementation and release

One Maven module with resources/auth/policy, Store, KafkaPipeline, DeliveryWorker, WebhookSender and retry/filter helpers. Liquibase migrations are append-only. GitHub tests real dependencies, builds/scans once, exercises startup/rollback/restore, then signs the exact release. A restricted upload user invokes a root-owned signed-bundle gateway; source checkout and root SSH credentials are not placed on the Droplet for CI. API and signing secrets remain server-side. The separate operator SSH key remains privileged and must be protected. Additive migrations permit application rollback; destructive schema rollback is not automated.

## H. Validation and operations

See validation.md and operations.md. Verify liveness/readiness, accepted event retrieval, matching fanout, response/timeout audit, duplicate handling and dependency recovery. Follow each cloud release with synthetic events and receiver capture verification. Local and CI tests are evidence for named scenarios, never every possible failure. Define alerts for readiness, backlog age, failed attempts, disk space and backup age before a production availability commitment. The encrypted Spaces restore drill is separate from local/CI restore tests. Application-side 24-hour pruning can be delayed while the entire cluster is unavailable; the scoped key cannot configure provider lifecycle policies.

## I. Capacity, cost and evolution

Planning assumptions: 10 events/s average, 100/s peak, 1 KiB event and 3 matching subscriptions/event. Average fanout=30 deliveries/s; peak=300/s. At 200 ms per healthy receiver request, required HTTP concurrency is 6 average / 60 peak, before headroom. Twelve delivery workers across the three replicas do not satisfy that peak; use measured throughput to set supported traffic. At 5-second receiver timeouts, twelve workers can finish only about 2.4 attempts/s. Event storage is approximately 0.86 GB/day at 10/s; jobs/attempts, indexes, WAL and backup copies add substantially. Three attempts per delivery means approximately 7.8 million attempt rows/day under these assumptions. Never extrapolate a short 10,000-event no-match probe into a full fanout SLO. Six Kafka partitions distribute keys; they do not create more hosts or worker capacity.

Three 4-GB / 2-vCPU / 80-GB Droplets cost $72/month, two load-balancer nodes $24/month, and Standard Spaces $5/month: about $101/month before taxes/overages. New Bangalore Spaces creation was unavailable in this account, so the private backup bucket is Singapore. GitHub Container Registry currently adds no image storage/bandwidth charge. No managed Kafka, managed PostgreSQL, or Kubernetes cluster is provisioned. Scale when measured acceptance latency, DB saturation, backlog age, disk runway or receiver fairness misses agreed targets. Partition tables/search/cache only when actual retention/query pressure justifies them. Identity integration, endpoint-wide/weighted scheduling, full distributed tracing, independent alerting, continuous WAL/PITR and storage-native lifecycle configuration remain evolution work. Physical host placement and region-wide failure isolation are not guaranteed merely by provisioning three Droplets.


The new source enforces tenant quotas through atomic shared PostgreSQL fixed windows, avoiding new Redis infrastructure while retaining fail-closed behavior. Per-subscription concurrency reservations and least-recently-claimed scheduling apply across all application instances. Separate bounded standby-only pools serve history/list reads with explicit eventual consistency and no primary fallback. Generated keys bind canonical event content and optional producer IDs to authenticated tenant/configured user/compartment identity. Details and first-rollout compatibility are in reliability-controls.md.
