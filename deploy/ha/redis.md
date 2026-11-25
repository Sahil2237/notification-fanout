# Shared Redis quota deployment

**Current status:** the three-host Redis/Sentinel infrastructure and application 6474ad3 controls are deployed. Shared quota, automatic cloud promotion, existing-client recovery and replica rejoin passed; see [version-pinned evidence](../../docs/submission-status.md). The procedure below describes bootstrap/release safeguards, not a pending rollout.

Three existing Bangalore Droplets host one Redis process and one Sentinel each. The primary has two replicas; three Sentinels use quorum2 to elect a replacement. No additional paid machine or managed database is required. This store controls API admission and retry budgets only. PostgreSQL remains the durable event, idempotency and delivery-audit store.

## Security and resource boundary

Redis and Sentinel expose TLS-only ports6379 and26379 on each host's private address and loopback. The cloud and host firewalls must admit only the three approved private peer IPs. Neither port is public. The application validates a separate Redis CA and server names; control-plane and PostgreSQL signing keys are not reused. The offline Redis CA private key stays outside the Droplets. Node certificates expire after90 days and need operator renewal.

Application `limiter` credentials can run the quota script only on `notification:limits:v1:*`; they cannot flush data, alter configuration, administer identities or publish Sentinel control messages. The independent `discovery` identity can find primaries and subscribe to primary-switch notifications, but cannot force failover. Replication, Sentinel data control, Sentinel peer administration and operator administration have independent credentials. Only the two application credentials and public Redis CA enter application containers.

Processes run as UID999, drop capabilities, disallow privilege escalation and use read-only root filesystems. Redis has128 MiB/0.5CPU and Sentinel64 MiB/0.15CPU caps. Redis limits clients to128 and data to64 MiB with `noeviction`. AOF uses `appendfsync always`. Configuration/state directories remain writable for automatic role/configuration changes; bootstrapping refuses to overwrite them on a restart.

## Admission, replication and failure behavior

All application instances run the same Redis TIME-based atomic fixed-window script before starting an event transaction. The authenticated tenant selects the key. The limit is shared across instances, not multiplied by the number of application processes. A limit rejection returns429; a Redis connection, permission or replication-acknowledgement failure returns503. There is no local-counter or PostgreSQL-counter fallback. Health checks are exempt.

Redis requires at least one recent replica for writes. The application additionally sends WAIT1 on the same borrowed connection after the quota script. Three Sentinel endpoints allow an existing Jedis client to find a promoted primary. Pool size, connection wait and network timeouts are bounded. Independent per-user quotas are not added by this deployment.

Redis replication is asynchronous. WAIT reduces uncertain counter loss but does not make Sentinel a linearizable quota system: failover can lose recent quota updates or admit an overshoot. Fixed minute boundaries also permit a boundary burst. Those limits are acceptable for abuse/load protection, not financial entitlement accounting. They do not change PostgreSQL event durability. During an unsafe Redis state, the application rejects admission rather than silently bypassing limits.

## Provisioning and release sequence

1. Verify the signed CI image/runtime bundles; use the Redis image identity recorded in `images.json`.
2. Generate six independent credentials and a separate CA using `redis-cluster.py`, inside `/workspaces`. Transfer only the correct node certificate folder, protected configuration and application credentials to the three approved nodes over pinned-host SSH. Never deploy `offline-ca` private keys.
3. Allow the two TLS ports only between the existing private peers, bootstrap each host once, start `notification-rate`, and verify a primary, two replicas and three mutually authenticated Sentinels.
4. Merge only the new application/migration service configuration into the current HA Compose file. Preserve existing PostgreSQL/Kafka/etcd/proxy services and data volumes. Add the public Redis CA to application TLS material.
5. Stage signed application artifacts everywhere. For the first cross-version upgrade, create the root-owned `coordinated-cutover` flag and drain/stop all old application workers before migrations and new application promotion. The promotion script keeps flagged nodes drained; it must not expose a mixed old/new worker population.
6. Verify all new instances, replica-only read headers, shared admission, webhook delivery and retry/DLQ behavior before removing the cutover flags/drains. Redis/API failure must leave accepted PostgreSQL events intact.
7. Update the backup-only tools image so snapshot metadata also verifies the delivery-DLQ table. PostgreSQL need not be restarted to update backup tooling. Re-enable normal verified rolling application deployment only after the initial cutover succeeds.

For a low-impact shared-limit check, keep all API backends drained, seed the newly created service quota counter near its configured ceiling, then send reads through different application instances. The remaining allowance must be shared and every instance must return429 once exhausted. Remove only that synthetic admission state before opening ingress; never manipulate a live tenant counter while public traffic is admitted.

## Verification

The real TLS/Sentinel fixture uses the application's packaged Jedis implementation. Local testing verified three independent clients sharing one quota, TLS hostname rejection, restricted application ACLs, automatic failover after the primary Redis/Sentinel pair stops, recovery of an already-connected client, fail-closed behavior when no replica can acknowledge writes, and rejoining stopped nodes. `test-redis-cluster.py` repeats this drill in CI against the patched/scanned Redis image. `test_redis.py` and `test_ha.py` cover configuration, role separation, topology and restart safeguards.

The Rancher fixture uses disposable containers/data and a private Docker network, with source and results under `/workspaces/notification-fanout`. Its Ubuntu24 runner needs access to the local Docker socket; do not point it at a cloud Docker daemon. The local runner's Docker CLI/interpreter are read-only binds from Rancher's VM, and its Java process uses the existing Java 25 image.

At preparation time,72 Java tests,41 Python tests and the real Redis failover fixture passed locally. Production rollout and cloud evidence must be recorded separately; local tests do not establish that cloud Redis is active.
