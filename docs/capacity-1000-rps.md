# 1,000 events/second capacity profile

The current target is **1,000 events/second**, superseding the earlier 10,000/s planning target. The one-minute 1,000-events/minute test is historical evidence, not proof of this higher rate. This profile reuses the three existing 4-GB hosts without creating/resizing paid Droplets. Final executed observations belong in submission-status.md.

## Configuration and assumptions

| Setting | Reviewed value |
| --- | --- |
| Shared tenant request quota | 66,000 authenticated requests/minute across all app instances; reads/writes both count |
| Ten-minute window | 600,000 events, up to 1,200,000 aliases when key and producer ID are both supplied |
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

The load tool is packaged in the signed HA artifact as acceptance-load.jar. It supports at most 1,000 offered requests/second for at most 600 seconds, bounded outstanding requests and a stop-file guard. New quota headroom is for legitimate requests, not a promise that bursts above the target have been benchmarked. Production workload mix and failure recovery still require separate validation.
