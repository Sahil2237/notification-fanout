# Operations and recovery

Operational authority for the existing deployment. [Design](system-design.md), [API](api.md), [fresh interview demo](interviewer-demo.md), [verification/evidence](validation.md). This is a runbook, not an instruction to reprovision or reboot production during a demonstration.

## Inventory and resources

Team **Blitz_team13_Hyd**, region **BLR1**, Ubuntu24.04, three 2 vCPU/4 GB Droplets with 80 GB local disks. Each runs app, PostgreSQL/Patroni, Kafka, Redis, Sentinel, etcd, Caddy and a metrics bridge. Components compete for the same host CPU/disk/memory.

| Node | Public SSH/operator IP | Private service IP |
| --- | --- | --- |
| 1 | 64.227.168.29 | 10.122.0.2 |
| 2 | 139.59.13.207 | 10.122.0.6 |
| 3 | 159.65.153.56 | 10.122.0.5 |

Public API: https://174.138.121.225. PostgreSQL/Redis leader roles are dynamic; do not hard-code a permanent primary. Root-owned HA configuration is /opt/notification-fanout/ha. Compose projects are notification-ha, notification-rate and notification-metrics. The restricted CI upload identity is not a general operator shell.

| Service per node | Memory ceiling | CPU/other bounds |
| --- | --- | --- |
| App | 896 MiB | Xms128m/Xmx512m; MaxMetaspaceSize192m; write/read pools 8/4; four webhook workers |
| Kafka | 1152 MiB | Heap256–512 MiB; one broker/controller per node |
| PostgreSQL/Patroni | 640 MiB | max_connections 100, shared_buffers 128 MiB, work_mem 4 MiB |
| etcd | 256 MiB | Three voters, two required for majority |
| Caddy | 160 MiB | Three proxies behind managed LB |
| Redis | 128 MiB | 0.5 CPU; dataset 64 MiB; noeviction; AOF appendfsync always |
| Sentinel | 64 MiB | 0.15 CPU |
| Metrics bridge | 256 MiB | 0.25 CPU; separate JVM heap 96 MiB |

Other services have no separate hard CPU quota; all share two host vCPUs. Container memory ceilings total3552 MiB but are not preallocated/reserved RAM. Host swap is disabled. A512 MiB heap is not a 512 MiB total JVM process. All app operations share their pool budgets; maximum 36 app connections across the fleet excludes Caddy/Patroni/replication/migration/backup/operator sessions.

The saved operational inventory is a point-in-time snapshot, not a throughput benchmark. Inspect current memory, CPU, disk and pool saturation before changing any limit; resizing a host does not automatically resize Java heaps, pools, Redis maxmemory or PostgreSQL max_connections.

## Operator access and tenant token

Open DigitalOcean in team Blitz_team13_Hyd, choose a Droplet by the inventory IP and use its authorized Web Console, or connect from your local terminal with your existing operator SSH identity. Do not use the restricted CI identity as an interactive shell. SSH requires keys; passwords are disabled.

Example only: replace the key path and username with your entitled operator identity.
```bash
ssh -i ~/.ssh/your_operator_key OPERATOR_USER@64.227.168.29
```

The protected file **/opt/notification-fanout/ha/secrets.json is inside the cloud Droplet**, not your Mac or Rancher development container. Before screen sharing, an authorized operator can display only the existing service-tenant token:
```bash
sudo jq -er '.api_token' /opt/notification-fanout/ha/secrets.json
```

Paste that value into the demo's hidden Tenant API token prompt and press Enter. Never share it in chat/Git or print the entire secrets file. This is the notification-service token, not a GitHub/DigitalOcean/Webhook.site credential. There is no tenant token self-service endpoint. Keep the token out of saved run files and unset the shell variable afterward.

## Network and ports

Both Cloud Firewall and host UFW are enforced. Host defaults deny incoming/outgoing/routed traffic; stateful responses and loopback traffic retain their normal framework allowances.

| TCP port | Service | Effective inbound access |
| --- | --- | --- |
| 22 | SSH | Public IPv4/IPv6 sources retained by explicit user decision; key-only authentication |
| 80 /443 | Caddy HTTP redirect /HTTPS | Only LB resource identity at Cloud Firewall; host requires private interface/source/destination |
| 9080 | Caddy drain-aware /health/ready | LB health probes only |
| 8080 | Dropwizard application/admin REST routes | Loopback; administrator route paths additionally blocked by public Caddy |
| 8081 | Dropwizard metrics/admin health | Loopback only |
| 2019 | Caddy administration | Loopback only |
| 5432 | PostgreSQL | Exact three private peers only |
| 8008 | Patroni | Exact three private peers only |
| 9092 /9093 | Kafka broker /KRaft controller | Exact three private peers only |
| 6379 /26379 | Redis /Sentinel TLS | Exact private peers and local loopback |
| 2379 /2380 | etcd client /peer TLS | Exact three private peers only |

LB UID:15a568f0-1172-4b6f-9ddb-6a4fad34ee1f. Firewall UID:9ebfb734-0687-4487-babd-8853bc73bb97, attached to all three hosts. [Reviewed provider rule reference](../deploy/ha/firewall-policy.json) is not an automatic Terraform state/API export. Do not overwrite newer provider changes blindly.

Private UFW web rules use eth1, VPC10.122.0.0/20 and each node's private destination. The narrower provider LB identity rule also excludes non-LB VPC web clients. Caddy still has wildcard listeners, including UDP443; no inbound UDP443 allowance exposes QUIC. Kafka also has an auxiliary dynamic JVM listener with no inbound allowance. No public application8080/8081 or general dynamic-port rule exists.

| Outbound destination | Effective permitted traffic |
| --- | --- |
| General IPv4/IPv6 destinations | TCP53/80/443 and UDP53/67/123 for DNS, web, DHCP and time |
| Exact three private peer/32 addresses | Provider permits peer TCP; host narrows it to 22,2379–2380,5432,6379,8008,9092–9093,26379 on eth1 |
| Control/response traffic | Existing cloud ICMP allowance and host framework loopback/established/essential-control handling |

Port-restricted outbound web access is not a destination-host allowlist. Application HTTPS-origin approval, DNS/address validation, TLS checks and redirect refusal remain required for webhook protection. Review both network layers before approving a receiver on a custom non-443 port. No new firewall token, VPN, static runner or SSH source narrowing was installed.

LB health settings: HTTP 9080 /health/ready, interval 5s, timeout 3s, thresholds 2/2, sticky sessions off. Provider-managed distribution is recorded; do not claim a specific internal round-robin/least-connections algorithm or exact ten-second failover. [LB settings](evidence/load-balancer-2026-11-25.json), [provider firewall verification](evidence/provider-network-hardening-2026-11-27.json), [external protocol tests](evidence/network-hardening-final-2026-11-27.json). Workstation corporate gateways can invalidate a simple destination-override/TCP-handshake test; preserve TLS identity and inspect the source of unexpected responses.

## Health and diagnosis

From the development container or local API client:
```bash
curl --fail-with-body --silent --show-error --max-time 10 https://174.138.121.225/health/live
curl --fail-with-body --silent --show-error --max-time 10 https://174.138.121.225/health/ready
```

From an authorized shell **inside each Droplet**, repeat this block on all three nodes:
```bash
hostname
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8080/health/ready
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8081/healthcheck
curl --fail-with-body --silent --show-error --max-time 5 http://127.0.0.1:8081/metrics | jq '{accepted:.counters["events.accepted"],duplicates:.counters["events.duplicates"],delivered:.counters["delivery.succeeded"],pending:.gauges["delivery.pending"],oldestPending:.gauges["delivery.oldest_pending_seconds"]}'
sudo docker stats --no-stream
df -h /
free -m
```

127.0.0.1 means the machine executing curl. Running those metrics URLs on your Mac/container does not inspect a Droplet. Browser/API routes cannot supply a bearer token merely by opening a URL. Never print full secrets, Docker environments, ACL files, DSNs, signing keys or payloads to shared logs.

| Symptom | Investigation |
| --- | --- |
| 401/403 | Correct tenant/admin token and permitted route; do not rotate production credentials to fix a pasted URL |
| 409 | Same key with changed content or subscription cap; preserve saved request/identity |
| 429 | Tenant fixed-second quota, or a separate receiver-provider quota; inspect responding host/Retry-After |
| 503 | Admission/backlog, shared Redis TLS/replica acknowledgement, database/read-replica or worker/Kafka readiness |
| Growing unpublished outbox | Publisher errors, Kafka quorum/ISR, SQL pool/latency and available disk |
| Growing Kafka lag/matching age | Consumer progress, primary database work and subscription fanout; local outbox_pending is not Kafka broker lag |
| Growing pending deliveries | Receiver latency/status, retry budgets, per-subscription reservations and worker headroom |
| Direct backend HTTPS timeout | Expected isolation; use the LB, not a firewall bypass |
| No automatic recovery after reboot | Docker/container state, manual-stop policy, watchdog device dependency, quorum and drain flags |

For PostgreSQL, inspect Patroni roles/replication and etcd majority. For Kafka, inspect leaders/ISR/consumer progress. For Redis, inspect Sentinel leadership, connected replicas, TLS/auth and WAIT failures. Never restore availability by forcing unsafe PostgreSQL promotion, disabling synchronous commits/Kafka ISR safety, allowing public cluster ports or bypassing quota Redis with local counters.

## Metrics and logs

Loopback8081 exposes Dropwizard JSON: counters/gauges/timers and JVM/pool instruments. Common event_id correlation is propagated through accepted responses, structured logs, Kafka, webhooks and delivery DLQ; request IDs cover requests without an accepted event. No central log-search/tracing platform is deployed. Docker logs rotate; they are not the durable delivery audit.

Counters are per-process and reset on restart. Shared database backlog gauges describe the same work on each host: use max, not sum. Per-process p95 values cannot be summed/averaged into an accurate cluster percentile. Some instruments appear only after first use.

A separate bounded MetricsBridge scrapes each app every 60 seconds and exports an explicit aggregate OTLP allowlist. It has one queued HTTP task,64 KiB bodies,2-second connect/3-second request timeout and one attempt; source/export outages can lose telemetry without changing event durability. In-process export remains disabled to avoid duplicate metrics. Team/Droplet/region and one service label bound cardinality; tenant/event/user/payload/URL/credential values are excluded. cache.* and some detailed worker/matching metrics are local-only.

| Local instrument | Exported name |
| --- | --- |
| http.success | notification.http.success |
| http.client.errors | notification.http.client.errors |
| http.server.errors | notification.http.server.errors |
| http.rate.limited | notification.http.rate.limited |
| events.accepted | notification.events.accepted |
| events.duplicates | notification.events.duplicates |
| delivery.attempts | notification.delivery.attempts |
| delivery.succeeded | notification.delivery.succeeded |
| delivery.unsuccessful_attempts | notification.delivery.unsuccessful.attempts |
| delivery.retry_budget_deferred | notification.delivery.retry.budget.deferred |
| delivery.stale_results | notification.delivery.stale.results |
| delivery.worker_errors | notification.delivery.worker.errors |
| delivery.recovered | notification.delivery.recovered |
| kafka.published | notification.kafka.published |
| kafka.publish_errors | notification.kafka.publish.errors |
| kafka.dispatched | notification.kafka.dispatched |
| kafka.consumer_errors | notification.kafka.consumer.errors |
| kafka.quarantined | notification.kafka.quarantined |
| kafka.dead_letters_published | notification.kafka.dead.letters.published |
| kafka.dead_letter_publish_errors | notification.kafka.dead.letter.publish.errors |
| metrics.scrape.success | notification.metrics.scrape.success |
| events.acceptance_latency_p95_seconds | notification.events.acceptance.latency.p95 |
| delivery.pending | notification.delivery.pending |
| delivery.oldest_pending_seconds | notification.delivery.oldest.pending.seconds |
| kafka.outbox_pending | notification.kafka.outbox.pending |
| kafka.oldest_undispatched_seconds | notification.kafka.oldest.undispatched.seconds |
| redis.replicas | notification.redis.replicas |
| http.admission.waiting | notification.http.admission.waiting |
| http.admission.rejected | notification.http.admission.rejected |


The source mapping is [MetricsExporter.java](../src/main/java/com/example/notifications/MetricsExporter.java); [MetricsBridge.java](../src/main/java/com/example/notifications/MetricsBridge.java) controls scrape/baselining. Local cache.hits/misses/errors/capacity_rejected and delivery.archived are useful even though they are not in that export allowlist.

The bridge is installed, but the last recorded provider OTLP response was404 and custom Metrics Explorer visibility is **unverified**. Do not present local metrics 200 as proof of external ingestion. No alarms, incident tickets or new dashboards were deployed by user request. Backup freshness, pending age, pool exhaustion, disk headroom, dependency quorum and certificate expiry should be monitored; proposed thresholds are not active alarms or agreed SLOs.

## Deployment, migrations and rollback

The workflows under [.github/workflows](../.github/workflows) configure tests, image/security/HA checks, signed packaging and three-node rolling promotion. Large artifacts request one-day retention; documentation-only pushes skip full image packaging. Latest code verification passed Java/Python/config steps but failed artifact upload because Actions storage quota is exhausted; release/HA jobs were skipped. Do not claim an automatic successful latest pipeline.

The latest application was packaged from the verified JAR into an AMD64 image using the pinned Java base/runtime instructions, scanned with Trivy 0.76.2/current databases, signed with the existing trusted release key and promoted through [the root gateway](../deploy/droplet/promote.py). Only the operator signing environment holds the private signing key. The gateway verifies paths/signatures/checksums/revision and portable filesystem/execution identity before running release scripts.

Legacy Docker Config.Image parent metadata is normalized by [verify-image.py](../deploy/droplet/verify-image.py); the signed archive digest, every filesystem layer, OS/architecture, user, environment and entrypoint remain protected. [Regression tests](../deploy/droplet/test_image_identity.py) reject execution-setting/layer changes.

For a routine compatible release:
1. Inspect current SHA, pool/resource headroom, backups and migration compatibility; preserve the prior image/configuration.
2. Complete required verification/security checks, package/sign exact immutable artifacts and stage all nodes over verified SSH.
3. Prevent an overlapping operator/CI rollout; if holding HA_DEPLOY_READY, record its prior value.
4. Promote one app via the gateway: drain its LB readiness, wait20s, migrate, recreate app, verify ready, persist image/SHA and reopen. Other data services remain running.
5. Verify all nodes, public HTTPS, tenant idempotency/audit and a controlled external signed webhook; restore the prior deployment gate.

The release failure path halts further promotions and attempts the previous app image. It does not reverse schema migrations. Additive migration 011 is compatible with old row readers, but old applications do not enforce subscription keys: finish the fleet upgrade before clients rely on the new contract. Incompatible ownership/filter/reservation changes require a separately planned coordinated cutover, not mixed workers.

Data-service/infrastructure upgrades are separate operations: move one member, wait for safe PostgreSQL sync/etcd quorum/Kafka ISR and app readiness, then continue. Do not bootstrap over an existing cluster or Sentinel state. [HA render/bootstrap](../deploy/ha/render.py), [Redis provisioning](../deploy/ha/redis-cluster.py), [HA release helper](../deploy/droplet/ha-release.sh) are maintained source tools, not invitations to rerun initialization on production.

GitHub storage cleanup removed only 37 explicitly approved superseded artifacts; about 7.66 GiB was freed and 2.59 GiB retained at that observation. This did not clear current artifact quota or change paid usage. [Historical cleanup record](evidence/github-artifact-cleanup-2026-11-27.json). Deleting duplicate Markdown does not materially solve Actions artifact billing. Do not delete additional recovery bundles or increase spending without the appropriate authorization.

## Reboot and failover

Docker/containerd and notification-backup.timer are enabled. All eight existing containers per node have restart:unless-stopped. A normal reboot starts them through Docker; dependencies/replication must recover before LB readiness succeeds. A manually stopped container stays stopped. Deleted containers/hosts are not automatically recreated.

PostgreSQL primary failure is coordinated by Patroni+etcd; Kafka elects eligible partition leaders through KRaft; Redis Sentinel handles Redis primary discovery/failover. Disk-backed volumes survive normal container/host restart. A one-host outage can trigger temporary503s/retries, with the surviving safe quorum carrying work. Two whole-host failures can remove quorum. Do not reboot all three together.

Bootstrap installs the watchdog dependency before Docker startup. Verify [test-watchdog-boot.sh](../deploy/ha/test-watchdog-boot.sh) after planned reboot, with maintenance safeguards. A prior whole-host drill passed; the latest application release did not repeat that destructive test. Drains/coordinated-cutover flags must be absent after successful reopening; do not remove them blindly during another operator's active rollout.

## Storage and retention

Each Docker volume is local to that Droplet's80 GB disk; identical names on different nodes do not mean shared storage.

| Store | Active path/persistence |
| --- | --- |
| PostgreSQL | notification-ha_postgres mounted at /var/lib/postgresql/ha, including database/WAL |
| Kafka | notification-ha_kafka at /var/lib/kafka/data |
| etcd | notification-ha_etcd at /data |
| Redis /Sentinel | notification-rate_redis-data and notification-rate_sentinel-data at /data; writable host config also persists election state |
| Caddy | notification-ha_caddy and notification-ha_caddy-config plus PostgreSQL-backed certificate storage |
| Host bind mounts | Protected /opt/notification-fanout/ha configuration, certificates, scripts and health flags |
| Metrics tmpfs | Temporary16 MiB /tmp; not durable |
| Spaces | Private off-host encrypted notification database backups |

Images also create auxiliary anonymous Kafka/PostgreSQL volumes; active PostgreSQL data uses the configured /ha volume, not the image's default unused data path. No separate DigitalOcean Block Storage volume or shared filesystem is attached. Volume deletion/disk destruction is different from a normal reboot. No whole-Droplet automated backup is configured.

Terminal deliveries move after 24 hours into history, preserving IDs/attempts. Events/audits/history/identities have no automatic deletion. Kafka's bounded log retention is not application audit retention. Do not clear shared Redis or drop business tables for a demo.

## Daily Spaces backup and restore

Private bucket notification-fanout-backups-sahil2237 is in SGP1. Backup tools on all three nodes are pinned to notification-ha-tools:f0c4d332cbe5dbeaa70142edc31f563c576c7b3c. Timer checks run every 5minutes with up to 20 seconds jitter and persistent scheduling. A primary check/session advisory lock permits one successful backup per UTC day, not an exact rolling24-hour cadence.

A consistent exported PostgreSQL snapshot supplies pg_dump and thirteen verification-table counts. The dump/archive is age-encrypted; SHA-256, encrypted-object metadata and a separately authenticated completion manifest are checked. Manifest publication is last, so partial upload is not advertised as a completed backup. Bucket credentials are scoped; encryption/signing identities never belong in Git.

The notifications database backup includes event/outbox/subscriptions, creation keys, delivery identities/history/attempts/DLQ, rule generations, origin policy/audit and migration history. It does **not** include cluster login roles, all host configuration, the separate Caddy certificate database, Kafka/etcd/Redis disks or whole-Droplet images.

Requested retention is 24 hours. Healthy cleanup runs on the five-minute-plus-jitter schedule and only recognizes strict filenames under postgres/daily/v1. There is no installed provider-native lifecycle rule. Outage can delay pruning; deleting an expired backup before a failed replacement can leave no usable snapshot.

From an authorized root/operator shell, inspect scheduling and job output:
```bash
sudo systemctl is-enabled notification-backup.timer
sudo systemctl list-timers notification-backup.timer --all
sudo systemctl status notification-backup.service --no-pager
sudo journalctl -u notification-backup.service -n 30 --no-pager
```

An entitled operator can request a due check with sudo systemctl start notification-backup.service; not_due is expected after that day's successful snapshot. Do not infer successful backup contents merely from an active timer.

On the node holding the protected age identity, test an actual completed Spaces manifest:
```bash
read -r -p 'Completed Spaces manifest key: ' MANIFEST_KEY
sudo bash /opt/notification-fanout/ha/restore-test.sh "$MANIFEST_KEY"
```

The helper authenticates/downloads/decrypts, verifies dump integrity, restores disposable network-isolated PostgreSQL 17 and compares recorded table counts, then removes disposable plaintext/container state. It **does not overwrite production**. Both installed helper paths were synchronized and support current 13-table metadata plus historical formats; an old drift warning is resolved.

A real Spaces restore of a nine-table24 November snapshot passed historically. A fresh13-table restore/application recovery has not been verified. Latest backup jobs recorded successful creation on 27 November at 07:30 IST; that is separate from a restore drill. [Evidence boundary](validation.md#backup-and-recovery-evidence).

For complete recovery: retain trusted deployment/configuration and keys outside both cluster and bucket; provision roles/TLS/control state, restore the database, grant runtime/backup access, rebuild a safe synchronous cluster and Kafka topics/ACLs, clear only the optional notification:cache:v2:* identities that may refer to a newer database, redrive unfinished outbox work, validate audit/synthetic callbacks, then reopen traffic. Never flush quota keys or reopen stale legacy single-host volumes as authoritative. Missing certificate storage/roles and expired keys can extend recovery.

Daily backups imply up to roughly 24 hours of lost changes after complete cluster destruction; continuous WAL/PITR, zero-loss regional disaster recovery, automatic disaster restoration, durable off-cluster secret escrow and a measured full-service RTO are not established.

## Network-policy maintenance and credentials

Review [network-policy.py](../deploy/ha/network-policy.py) and [egress-policy.py](../deploy/ha/egress-policy.py) in the workspace before copying reviewed helpers to a host. Planning does not mutate the firewall:
```bash
python3 deploy/ha/network-policy.py --private-ip 10.122.0.2
python3 deploy/ha/egress-policy.py --peers 10.122.0.2 10.122.0.5 10.122.0.6
```

Use the node's own private IP. Applying requires active UFW, explicit --apply and a unique protected snapshot path. Each helper arms an independent180-second rollback. Egress snapshots both UFW user rules and /etc/default/ufw and refuses unreviewed existing outbound rules. Preserve fresh SSH access while testing LB-only ingress, denied direct access, cluster/HTTPS dependencies and local readiness/metrics.

Cancel the matching rollback timer only after successful checks. In steady state both timers should be inactive:
```bash
sudo systemctl is-active notification-network-rollback.timer
sudo systemctl is-active notification-egress-rollback.timer
```

Emergency restoration uses the actual snapshot directory from that application:
```bash
sudo python3 /opt/notification-fanout/ha/network-stage/network-policy.py --restore '/opt/notification-fanout/ha/network-policy-backup-<actual-timestamp>'
sudo python3 /opt/notification-fanout/ha/egress-stage/egress-policy.py --restore '/opt/notification-fanout/ha/egress-backup-<actual-timestamp>'
```

Restoring one host layer does not restore provider rules and can reintroduce broader historical allowances. Do not disable firewalls as routine diagnosis or change SSH source rules to fix a demo. SSH sources were deliberately retained.

Tenant/admin/signing/database/Spaces/metrics/SSH/release keys stay in protected configuration. Operators may retrieve only their required field, never publish the entire secrets.json/backup.env/ACL files. Renew internal90-day certificates and the authorized90-day Insights token before expiry. Coordinate credential rotation with actual database/Redis roles and receivers; no automatic rotation/central secret manager is claimed. Metrics-only credentials have Insights ingest plus the provider-required read dependency; no new firewall credential was created.
