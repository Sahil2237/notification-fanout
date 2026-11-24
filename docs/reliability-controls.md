# Shared limits, identity, isolation and replica reads

This document describes the new source implementation. The deployed revision and exact verification evidence belong in submission-status.md; a source commit is not proof that a cloud rollout has finished.

## Fixed-window shared request limit

Every authenticated API instance updates a single PostgreSQL row per tenant atomically. The primary database clock selects the minute. An INSERT/ON CONFLICT update either starts the new window, increments a counter below the limit, or returns no row and produces HTTP429 with Retry-After. Concurrent requests across instances cannot independently consume the full configured allowance. Counters survive application restarts and synchronous PostgreSQL failover. A dependency failure returns503; the limiter never falls back to a permissive local counter. Unknown credentials get401 and cannot allocate counter rows. Health probes are exempt; authenticated GETs, POSTs and administrative requests count. Counters store no event payload.

This has the same shared fixed-window semantics requested for a Redis limiter, implemented in existing HA PostgreSQL to avoid another paid service or quorum. There is no new Redis dependency. A strict sliding-window/burst-free guarantee is not claimed: fixed windows can admit two windows' allowance around a boundary. Consider HA Redis with an atomic Lua operation if measured counter write volume materially affects event transactions; define fail-closed behavior and the durability implications of Redis failover before moving the quota there.

## Event identity and generated keys

An event request accepts optional producer eventId (UUID) alongside type, source and arbitrary JSON payload. eventId identifies a producer occurrence; the canonical accepted event.id is generated once by the service and returned for subsequent tracing. The service must not put a newly random server ID into the automatic key, because each retry would then generate a different key.

Without Idempotency-Key, the versioned SHA-256 key includes authenticated tenant, configured userId, configured compartmentId and a canonical content hash including the supplied producer eventId when present. JSON object order is canonicalized. Array order, value types and number representation remain significant. Same identity/content returns the original event. The unique producer identity also rejects changed content with409 even though its generated content hash changes. Explicit keys remain tenant-scoped for backward compatibility; changed content or conflicting authenticated user/compartment returns409. Existing explicit-key records without the new identity columns retain their original content-hash compatibility.

Tenant comes from the bearer credential. In this service-to-service authentication model, userIds and compartmentIds are configured by the operator for each credential's tenant. Defaults are the tenant name and default compartment. Body fields or arbitrary X-User-ID/X-Compartment-ID headers cannot impersonate another identity. Multiple users in one tenant need a future verified identity-provider/token-claims integration; this change does not invent an IAM service.

If neither a producer eventId nor an explicit key is supplied, identical type/source/payload and identity are deliberately the same occurrence for as long as the event is retained. Separate identical occurrences require distinct producer IDs or explicit keys. Keys and their deduplication records are not held in a short-lived cache.

## Correlation and fencing

The canonical event.id is preserved in the accepted response/X-Event-ID header, structured event_id logging context, Kafka envelope and X-Event-ID record header, delivery audit, webhook event body/X-Event-ID header and Kafka delivery DLQ. The producer ID is additionally logged at acceptance. Thread-local logging context is restored on every exit, including exceptions, so executor reuse does not leak identities. Startup/health/invalid requests without an accepted event use requestId; third-party connection-management logs are not falsely attributed to one event.

Existing database uniqueness, idempotent activation and offset-after-database-commit prevent a repeated Kafka record from creating another delivery job. Delivery and DLQ publishers use unique expiring ownership tokens. A stale result cannot complete a replacement claim. The token protects database state, not an already-issued HTTP effect: a receiver can accept a webhook before the sender loses its response. Receivers must deduplicate stable deliveryId and verify HMAC/timestamp. Exactly-once HTTP effects are not claimed.

## Subscriber bulkheads and fair scheduling

subscriberConcurrency defaults to1 per registered subscription across all application instances. A transaction locks the subscription reservation row, rechecks live lease count, claims one due delivery with SKIP LOCKED, and updates last_claimed_at. Other instances cannot independently allocate another slot beyond that subscription's cap. Network calls hold neither row locks nor database connections. Due subscriptions are selected by oldest last claim, preventing a continuously busy subscription from remaining first forever. Expired claims are recovered with new tokens; stale completions remain fenced.

The bulkhead identity is a subscription, not a grouping of all URLs on one receiver domain. A tenant can have multiple subscriptions. Endpoint-wide/tenant-wide fairness and weighted priorities are separate evolution work. Bounded HTTP deadlines and a fixed global worker count remain in force.

## Five retries and delivery dead letters

maxAttempts=6 means one initial attempt plus five retries for transient/network failures. Permanent HTTP/policy failures terminate immediately. Backoff/jitter remains persistent. When a delivery becomes failed, its terminal status, completed attempt and unique delivery_dead_letters row commit together. Expired final attempts also enter this store. Historical failed deliveries are backfilled with reason legacy_failed without silently retrying them.

A separate leased publisher sends schemaVersion1 envelopes to notification-deliveries-dlq-v1, keyed by deliveryId. The accepted event, tenant, attempt count and terminal reason are included; original attempt history remains in PostgreSQL. Topic replication is3, minimum ISR2, and unclean election is disabled in HA. If Kafka is unavailable, the database row remains unpublished and retryable. Publishing and saving its acknowledgement are not a distributed transaction; duplicate DLQ records are possible and consumers must deduplicate deliveryId. Malformed input Kafka records continue to use the distinct kafka_dead_letters quarantine.

GET /v1/dead-letters returns tenant-scoped paginated terminal records including publication time. It does not automatically redrive failures or reset attempts. DLQ retention is currently the same as retained application audit; Kafka's configured bounded retention is a transport copy.

## Replica-only read APIs and connection budget

HA has separate write and read pools. Write pool default maximum8 per application instance; replica read pool default maximum4. Across three replicas, budgets are24 write connections plus12 read connections, excluding Caddy, replication, migration, backup and operator sessions. Deployment inventory application settings DB_WRITE_POOL_MAX, DB_READ_POOL_MAX, SUBSCRIBER_CONCURRENCY and REQUESTS_PER_MINUTE are bounded and rendered into the application environment. Do not treat PostgreSQL max_connections as an application target; retain operational headroom.

The read pool uses targetServerType=secondary, loadBalanceHosts=true, TLS identity checks, read-only connections and standby validation on every borrow. Public event/subscription/delivery/DLQ history queries use repeatable-read, read-only transactions and check pg_is_in_recovery before and after the query. A promoted connection or unavailable replicas returns503, with no fallback to the primary. Responses advertise X-Read-Source and X-Read-Consistency. The application uses the existing two standby nodes, not new database machines.

Replica results are eventually consistent: a new event can briefly return404, a just-created subscription can be absent, or recent audit can lag. The write response includes the committed representation; clients should retry reads. Transactions, idempotency lookups, shared rate-counter writes, origin approval checks, subscription matching, claims, offset activation, outbox and DLQ scheduling stay on the primary. Sending those consistency-sensitive reads to a standby would compromise correctness. Local/single-host configurations without a replica pool retain primary reads for development.

## Deployment sequencing

Provision the new DLQ topic/least-privilege write ACL before running its publisher. Apply additive migrations005/006 with the owner role; the second migration grants the runtime role access to the two new tables. During the first rollout, old binaries do not enforce generated-key deduplication, shared quotas or subscriber reservations. Stage signed artifacts first, then use a coordinated application cutover so old and new workers do not overlap. PostgreSQL/Kafka/etcd keep running. Routine rolling releases can resume once all applications implement the same reservation contract. Reopening an older binary would weaken these contracts and must be an explicit rollback decision.

Backup dumps include the new tables; the updated snapshot verifier checks11 tables. The restore tool also accepts historical nine-table snapshot metadata. Application data and DLQ are not deleted by the one-day Spaces backup retention policy. See operations.md for recovery-secret escrow, actual Spaces restore and failure boundaries.
