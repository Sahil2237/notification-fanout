# Verified deployment and submission status

**Post-firewall end-to-end validation:** synthetic event `f570ab22-d69b-40e5-a3e2-55c12ed49fb9` returned202; delivery `34cdd74f-2424-490c-aa5f-2a9878e46088` succeeded with HTTP204 on its first attempt. Receiver capture and HMAC signature verified, temporary subscription deleted. [Evidence](evidence/network-webhook-2026-11-27.json). No new DigitalOcean credential was created; SSH source policy remains unchanged by user decision.

**Provider network hardening completed 27 November:** LB-only Cloud Firewall ingress on 80/443/9080, explicit cloud egress allowances and host outbound default-deny are deployed. All three provider backends are Active and the LB Healthy. Post-change private-cluster, readiness, metrics and outbound service checks passed. SSH sources remain unchanged by explicit user decision; no new DigitalOcean token/GitHub firewall secret was created. [Current policy and evidence](network-hardening.md).

**Latest application deployment: 70f0df1, 27 November.** [Verification CI](https://github.com/Sahil2237/notification-fanout/actions/runs/45927971103) and [three-node deployment](https://github.com/Sahil2237/notification-fanout/actions/runs/45929375771) succeeded. Fresh checks confirm all three apps ready/undrained and direct public backend HTTPS still blocked after rolling promotion. [Post-deployment evidence](evidence/current-network-release-70f0df1.json). Documentation-only commits and artifact-retention configuration changes do not require another application rollout. Provider LB-only rules and restricted egress are deployed; SSH source scope is retained by user decision.

Host network policy deployed on 27 November: direct public backend 80/443 blocked on all three nodes; private proxy/health, SSH, metrics and authenticated LB API checks passed. Provider LB-only rules and outbound restrictions are deployed; SSH source scope remains unchanged by user decision. See [scope, tests and evidence](network-hardening.md).

**27 November network update:** [Provider LB-only backend ingress and restricted egress are deployed; SSH sources remain unchanged by user decision](network-hardening.md). Historical observations below remain version-pinned.

## Previous functional application release: 27ecd2a (November 26)

The five-minute event identity TTL and 1,000 requests/second per-tenant shared Redis quota are deployed on all three existing nodes. All report readiness 200 and are undrained. A fresh synthetic public request returned 202; its identical replay returned the same event. Both cache aliases had 297 seconds remaining when inspected and neither participated in LRU. Replaying the earlier event 94b53336-a2d7-45fa-bd11-d5e9d20d93d3, older than the cache window, returned its original database identity. Events increased by exactly one on every database member; delivery counts remained unchanged.

Local verification passed 91 Java tests and 48 Python deployment tests. The complete GitHub verification pipeline and rolling deployment succeeded. See [machine-readable release evidence](evidence/five-minute-cloud-2026-11-26.json).

The five-tenant target is 5,000 events/second aggregate: 1.5 million events or up to three million aliases per five-minute window. Existing cache admission remains bounded at 32 MiB / 50,000 entries; it is **not sized for this full working set**, and sustained aggregate 5,000/s cloud performance is **not verified**. The optional tenant-credential map supports provisioning distinct identities; this release preserves the existing service credential and does not itself create five customer tenants. Cache saturation falls back to PostgreSQL. No paid resize was performed. [Capacity assumptions and remaining work](capacity-1000-rps.md).

Earlier revision-specific statements and tests below are historical.

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

## Latest deployment: f0c4d33, November 26

Application revision `f0c4d332cbe5dbeaa70142edc31f563c576c7b3c` is running and ready on all three existing Droplets. [Sanitized evidence](evidence/cloud-async-cache-history-f0c4d33.json), [passing CI](https://github.com/Sahil2237/notification-fanout/actions/runs/45839195205), [current design](async-cache-history.md).

| Check | Observed result |
| --- | --- |
| Local validation | 27 unit + 59 integration Java tests; 42 Python tests; 42 Mermaid diagrams and valid local links |
| Hosted CI | Verification, tested-release and HA-runtime jobs passed; signed artifacts verified on every host |
| Cutover | All backends drained, old apps stopped, migrations applied, cache-only ACL scope added, new apps verified, ingress reopened |
| Preserved baseline | 10,138 events, five subscription records, 15 delivery identities; 14 older terminal records moved to history before probes |
| Duplicate/cache path | Same event across three instances returned duplicate flags false/true/true; changed content 409; forced expiry of only synthetic aliases still returned original event from PostgreSQL |
| External webhook | Event e43ce95d-5483-42cd-a5cc-60fa3db82688, delivery c07dbf43-0a0a-4d8c-8db2-c2a44b516f90; one HTTP 204 attempt/capture; HMAC and IDs verified |
| History | Only synthetic completed_at was aged to exercise 24-hour archival; same Delivery ID/status/attempt remained queryable through a replica |
| Subscription cache | Temporary filtered registrations exercised generation changes and a one-record 20% cache ceiling; all temporary registrations were soft-deleted afterward |
| Public HTTPS | Ingestion 202, duplicate original ID, conflict 409, invalid credential 401, replica read header and readiness 200 |
| Final data | 10,140 events (two synthetic probes added), ten subscription records (five temporary records soft-deleted), 16 delivery identities, one hot plus 15 historical delivery records |
| Operations | Daily backup timers active; both restore-helper paths synchronized to verified 13-table-compatible tools; HA_DEPLOY_READY restored true |

The regular automatic rolling workflow was intentionally gated/skipped for this incompatible change; the operator performed the signed-artifact coordinated deployment. Docs-only follow-up commits do not require a runtime redeploy. Data services/proxy were not replaced by the app cutover. The backup tools image alone was refreshed.

**Capacity limit:** 10,000 events/second is a target with a checked-in k6 scenario, not an achieved SLO. Existing hardware, tenant quota, finite cache, primary write capacity and outbound workers are not sized/proven for sustained target fanout. No new actual thirteen-table Spaces restore or whole-Droplet cloud failure drill is claimed. The earlier release record below is historical.

## Historical release record


Documentation refreshed25 November 2026 IST. Runtime evidence below is pinned to the 24 November rollout of **6474ad3b0c043677066c539f3523c075b7358af0**; a documentation-only Git commit does not establish a new runtime deployment. No service, firewall or credential change is part of this documentation refresh.

## Current deployed system

- Public API: **https://174.138.121.225**; [readiness](https://174.138.121.225/health/ready). All three apps and private proxy-readiness endpoints returned 200, with no drain/cutover flags, in the latest runtime checks.
- Private personal [repository](https://github.com/Sahil2237/notification-fanout), default branch `codex/notification-fanout`. Source, Mermaid diagrams, workflows and runbooks are pushed; interviewer access is a separate handoff requirement.
- Runtime [verification 45447905686](https://github.com/Sahil2237/notification-fanout/actions/runs/45447905686) and [exact-image publication45450924076](https://github.com/Sahil2237/notification-fanout/actions/runs/45450924076) passed. The trusted signed application artifact was staged and promoted on all three hosts through the root-owned gateway during a coordinated cutover, not a mixed old/new rollout.
- Three existing BLR1 Droplets host Caddy, Java, Kafka, PostgreSQL/Patroni, etcd, Redis/Sentinel and independent metrics bridges. The managed regional load balancer has two units; the last overview showed all three backends Active/Healthy. There is no extra Redis Droplet or managed Kafka/Redis service.
- Application and Redis image/runtime bundle use6474ad3. Backup-only tools were also updated to6474ad3. Existing PostgreSQL/etcd/Kafka/proxy infrastructure remains the separately verified507f989 runtime; those running services were not restarted to perform this app/Redis rollout.
- Redis has one primary/two replicas and three Sentinels on private TLS ports6379/26379. The final tested primary was node3; roles can change. Default tenant allowance is 6,000 requests/minute, shared across apps. `HA_DEPLOY_READY=true` was restored after validation.
- Live PostgreSQL inspection showed primary node3, synchronous standby node2 and streaming replica node1, `remote_apply`, max_connections100. Application pool maxima are 8 write/4 read per instance; tenant read APIs use standbys only.
- Kafka inspection showed six event partitions with all three replicas in sync and three consumer-group members assigned two partitions each. RF3/min ISR2 and unclean election disabled. One consumer/app; four delivery workers/app.

[Sanitized current rollout evidence](evidence/cloud-redis-6474ad3.json), [assessment matrix](requirements.md), [architecture](architecture.md), [design](design.md), [API](api.md), [network/security](network-security.md), [tests](validation.md), [operations](operations.md).

## Mandatory assessment scope

| Requirement | Implemented and deployed behavior | Evidence boundary |
| --- | --- | --- |
| Durable structured ingestion |202 follows synchronous PostgreSQL event/outbox acceptance transaction followed by asynchronous matching; Kafka outbox retries publication | Latest public HTTPS ingestion/replay and transaction/Kafka recovery integrations; no universal zero-loss disaster claim |
| Dynamic subscriptions | Tenant create/list/delete and type/source/JSON Pointer equality filters without restart; separate durable origin policy | Lifecycle/filter/ownership/deletion tests; historical public cloud registration. POST subscription is not key-idempotent |
| Fanout/retry/state | Synchronous membership snapshot, Kafka activation, bounded/fair workers, persisted backoff, pending/delivered/failed, transient six-total-attempt limit and delivery DLQ | Real dependency integrations; latest cloud one successful webhook attempt. Exhaustion tests are automated; no new live six-failure webhook probe in this rollout |
| Delivery audit | Event/subscription/delivery history and tenant DLQ query endpoints with attempt times/codes/outcomes | Latest external capture/HMAC agrees with delivery audit; unavailable response codes can be null; standby reads can lag |
| Engineering artifacts | Mermaid full flows, README setup/filters/guarantees/trade-offs, tests and functional on-push CI | Current docs reconcile the previously stale Redis/SQL-counter/old-release descriptions; no exhaustive-testing claim |

## Latest runtime validation

| Check executed for6474ad3 | Observed result |
| --- | --- |
| Local and CI test suite |72 Java tests:27 unit/45 integration;41 Python:12 deployment/29 HA-config-backup; zero Java failures/errors/skips in the downloaded CI reports |
| Release/security gates | Actual service startup/privileges, failed-start rollback/restore, real synchronous PostgreSQL failover/JDBC standby reads, real Redis TLS/Sentinel/shared-client drill, image scans and signed packaging passed |
| Image authenticity | Signed archive checksums/config digests and portable filesystem/execution identity verified; cloud Docker manifest IDs were not confused with CI config IDs |
| Data preservation | Original10,136 events and five subscriptions matched count/selected-content fingerprints before synthetic probes; record remains on the original cloud host; no production dump was downloaded |
| Shared quota | With two synthetic slots left, node1/node2 returned 200, node3/node1/node2 returned 429; observed shared count 6000; synthetic admission state removed before reopening public ingress |
| Missing-key duplicate requests | The same synthetic body through all three apps returned one original event, duplicate flags false/true/true and one delivery job |
| Explicit event key | Replay returned original event; conflicting content returned 409 |
| Public HTTPS and replica reads | Normal certificate validation passed; health200; event replay202; history reported replica/eventual |
| External delivery | Event `c9264b6f-1e5a-4a9c-8eea-f6ee519edf94`, delivery `7a403f50-4aa8-44d9-9e82-6ec26ff7e7e5`: one attempt, HTTP 204; one external capture `8a1455e7-f451-4aa9-872c-62beba2e490d`, matching IDs and valid HMAC, checked again after failover |
| Redis primary/Sentinel failure | Stopped node1's Redis/Sentinel pair; existing clients observed503,503,200; node3 promoted; all three application clients recovered; restarted member rejoined and primary again had two replicas |
| Backup compatibility | Backup-only tools updated on all three hosts; backup role can read delivery_dead_letters. The previous release verified ten-table metadata; this redesign extends it to thirteen; no new ten-table Spaces restore was executed |

Local Redis real-client results are in the release CI drill and workspace log; the current JSON evidence contains the cloud checks. Old single-host containers/volumes remain stopped/stale and are not a rollback database.

## Historical HA and actual off-host restore evidence

The earlier [bd082b6 cloud evidence](evidence/cloud-ha-bd082b6.json) records separate whole-Droplet failover, watchdog reboot, Kafka operation with two brokers, retrieval of 124 acknowledged probe IDs and external webhook/HMAC checks. Its10,135 event count and then-current image/kernel/patch observations are historical, not a fresh claim about the latest fleet or database count. The initial migration preserved 10,011 original events and nine imported snapshot-table contents.

An actual encrypted/authenticated Spaces backup created at `2026-11-24T06:51:26.609559Z` was downloaded/decrypted on the cloud host, restored into disposable network-isolated PostgreSQL 17 and checked against all nine historical snapshot counts. Plaintext/disposable resources were removed; a same-day repeat returned not_due. This was not a workstation download and is distinct from CI/local restore tests. The latest snapshot verifier supports ten-table metadata plus historical formats; a historical nine-table restore must not be described as a new ten-table restore.

All three backup timers are configured. A database advisory lock permits one snapshot per UTC day. Private Spaces bucket `notification-fanout-backups-sahil2237` is in SGP1; backup retention is 24 hours with pruning on the five-minute plus jitter timer while healthy. No provider-native lifecycle expiry or elapsed cloud expiry cycle has been proven. Total outage can delay pruning; a failed new snapshot after old expiry can leave no usable backup. A working next backup/restore requires protected keys/configuration outside both cluster and bucket, not only the development VM.

## Metrics and security observations

Independent metrics bridges from verified `cf92a03` remain deployed; in-process telemetry stays disabled to avoid duplicates. Aggregate metrics and local loopback metrics exist. The last observed BLR1 OTLP endpoint returned 404/maintenance and Metrics Explorer showed no custom series; successful cloud visibility is still unverified. [Historical provider/export evidence](evidence/metrics-cloud-cf92a03.json) and [metrics contract](metrics.md). No alarms, tickets or new dashboards were created.

Authentication is static tenant/admin bearer credentials, not OAuth/OIDC. Java/admin ports bind loopback; administrator routes are blocked at public Caddy. Root passwords are locked and SSH password login disabled. At that historical inspection Cloud/UFW allowed public 22/80/443 and broad outgoing traffic. The 27 November host update now blocks direct public backend 80/443. Provider LB-UID-only ingress and port-restricted outbound policy are now deployed; SSH source scope is retained by user decision. Private cluster ports are restricted to the three approved peers. A 25 November read-only Settings follow-up verified HTTP 9080 /health/ready,5-second probe interval,3-second timeout,2 failed/2 successful thresholds and sticky sessions off. Current provider documentation establishes provider-managed distribution and deprecates round-robin/least-connections selection; [follow-up evidence](evidence/load-balancer-2026-11-25.json) supersedes the earlier unknown-setting note. See network-security.md for all current rules and boundaries.

## Remaining capabilities and release limits

- Managed queue, per-source ordered delivery, time-range replay API, independent per-user quotas, horizontal autoscaling and general response cache are not implemented.
- Subscription creation lacks an Idempotency-Key contract; no resource update/ETag/If-Match API exists. Duplicate subscription registrations can intentionally yield multiple deliveries to one URL.
- One-host HA with a surviving safe quorum is the tested durability boundary. Same-region deployment does not prove independent zones/hosts, regional RPO0 or zero interruption. Two whole-host failures remove safety quorum; no automatic Droplet replacement controller exists.
- Redis asynchronous replication permits uncertain/lost recent quota updates and fixed windows allow boundary bursts. This does not turn Redis into event storage or exactly-once entitlement accounting.
- Representative slow-receiver/fanout soak, agreed p95/p99/SLOs, full-service disaster RTO, continuous WAL/PITR, application retention, automatic internal-certificate renewal and durable secret escrow remain open. Alarms/tickets are deliberately excluded, not silently claimed complete.
- The original named-editor requirement was VS Code/Cursor; IntelliJ was used at the user's request and the Dev Container alternative is checked in. Interviewer repository access and personal-account sign-out remain final handoff obligations.

The provisioning estimate was approximately $101/month base for the three Droplets, two LB units and Standard Spaces; it is historical, excludes taxes/overages/unrelated resources and is not a current pricing quotation. Redis added no paid compute node. The historical10,000-event no-match 50/s probe is [separate evidence](evidence/release-57b3fbf.json), not a fanout capacity or10,000 events/s claim.

## Documentation review: restore-helper installation gap

Read-only inspection on the first HA host found the legacy `/usr/local/sbin/notification-restore-test` copy lacks the current delivery-DLQ metadata support, while `/opt/notification-fanout/ha/restore-test.sh` contains it. [Operations](operations.md#restore-command-version-caveat-25-november-documentation-review) now uses the current HA path. Legacy-helper synchronization across hosts and a fresh ten-table actual Spaces restore remain unverified; this documentation release does not claim to fix or retest them.
