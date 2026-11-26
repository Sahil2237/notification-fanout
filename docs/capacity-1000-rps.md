# Capacity target: five tenants at 1,000 events/second each

The latest requested target is **5,000 events/second aggregate**, with a **300-second rolling duplicate-identity cache**. That is **1.5 million distinct events**, or **3 million aliases** if each event supplies both a producer event ID and an idempotency key. Expiry does not extend on duplicate reads. PostgreSQL remains authoritative after expiry or a cache miss.

The existing single-tenant operator profile below is **not sufficient for five simultaneous tenants and must not be applied as that capacity**. These figures are sizing targets, not load-test evidence. The existing 32-MiB / 50,000-alias admission budget cannot retain the entire requested working set. Cache saturation falls back to PostgreSQL. No paid infrastructure resize has been performed.

At the earlier conservative accounting of 1,400 bytes/alias, reserve at least 4.2 GB (3.91 GiB) in the application accounting budget before subscription entries and burst headroom. Physical Redis memory, replication buffers, fragmentation and persistence rewrite memory must be measured separately. Each Redis replica needs the full working set; three replicas do not divide it. The five-minute setting halves identity occupancy versus ten minutes at the same arrival rate.

## Historical single-tenant candidate (not applied)


This older reference profile targets **1,000 events/second aggregate** and is superseded by the five-tenant target above. The one-minute 1,000-events/minute test is historical evidence, not proof of this higher rate. This profile reuses the three existing 4-GB hosts without creating/resizing paid Droplets. Final executed observations belong in submission-status.md.

## Configuration and assumptions

| Setting | Reviewed value |
| --- | --- |
| Legacy reference quota | 66,000 authenticated requests/minute; superseded in HA configuration by 1,000 requests/second per tenant across instances |
| Five-minute window (single-tenant reference only) | 300,000 events, up to 600,000 aliases when key and producer ID are both supplied |
| Application cache admission budget | 1.75 GiB conservative accounting; at most 1,500,000 alias/subscription entries |
| Redis maxmemory | 1,088 MiB, noeviction unchanged |
| Redis container | 1,664 MiB hard memory/swap limit, 1 CPU; existing container updated without restart |
| Host guard | At least 384 MiB available after reserving the full new Redis container allowance |
| API/database/worker bounds | Existing admission, JDBC pools, subscriber concurrency and delivery workers remain |

Cache accounting deliberately includes conservative per-entry overhead and differs from actual Redis memory. Profile sizing assumes the current small identity metadata, two aliases/event and small subscription records. Larger configured identities, rule records, bursts, replica buffers, fragmentation and AOF rewrite memory need measurement. Redis headroom is not a throughput guarantee.

The profile changes neither PostgreSQL/Kafka durability nor Redis/Sentinel leadership, credentials, TLS or firewall rules. Quota keys remain outside cache cleanup. At capacity or Redis failure, database deduplication remains authoritative; full-window cache occupancy requires the stated workload to fit.

## Reviewed operator procedure

Source: [profile](../deploy/ha/capacity-1000-rps.json), [validator/applicator](../deploy/ha/capacity.py), [bounded load tool](../deploy/ha/AcceptanceLoad.java).

1. Validate the profile and compare available memory on all three hosts before changing any host.
2. Back up existing protected inventory/Compose files. Increase Redis container allowance before maxmemory, then use CONFIG SET/CONFIG REWRITE. Do not bootstrap over live Redis/Sentinel files or replace election state.
3. Persist numeric application settings in inventory and the app/migration Compose environments. Keep secrets and all other services unchanged.
4. Drain and recreate one app at a time using the already verified image; check readiness, then restore routing. Verify identical quotas/cache settings on every app before testing.
5. Use unique synthetic event types with no candidate subscriptions. Start with a short lower-rate probe. The Java load tool has bounded client threads/queue/concurrency and reads credentials from protected environment only.
6. For a sustained test, watch host available memory, Redis memory/error counters, readiness and matching backlog. Stop the generator if memory drops below the host reserve, dependencies fail or backlog approaches its admission bound. Do not hide errors with retries.
7. Verify HTTP responses, distinct stored producer IDs/outbox records, matching completion and cache TTL/LRU membership. A no-match acceptance test does not certify webhook fanout capacity or receiver throughput.

The load tool is packaged in the signed HA artifact as acceptance-load.jar. It supports at most 1,000 offered requests/second for at most 300 seconds, bounded outstanding requests and a stop-file guard. New quota headroom is for legitimate requests, not a promise that bursts above the target have been benchmarked. Production workload mix and failure recovery still require separate validation.

## Five-minute quota and expiry semantics

The HA application configuration uses a Redis-clock fixed **one-second rate-limit window**, 1,000 authenticated requests per tenant, shared across application replicas. Reads and writes both count. This is independent of the **five-minute duplicate cache TTL**. Retry budgets retain their existing minute windows. The optional protected TENANT_CREDENTIALS JSON maps unique bearer tokens to tenants; a tenant cannot choose its quota identity in an arbitrary request header. The default installation preserves the existing service tenant; adding a quota does not provision five customer credentials or certify sustained throughput. Fixed windows permit boundary bursts.

Cache entries have a non-sliding 300-second maximum lifetime. Repeated duplicates do not extend expiry. Existing entries from a previous release can retain their earlier TTL until they expire; no global Redis flush is part of deployment, because quota and replication state must be preserved.
