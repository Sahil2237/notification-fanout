# Verified submission and deployment

## Latest decision: retain the existing live service

On **24 November 2026 IST**, the user selected **“Keep the current service unchanged”** when explicit authorization was required to replicate existing production credentials/data to the two additional Droplets. No production credentials or database contents were replicated, no HA services were started, and no HA cutover was performed. Automatic promotion remains held (`HA_ENABLED=true`, `HA_DEPLOY_READY=false`). The existing HTTPS service remains the original `57b3fbf` release; the historical verification below describes that release.

## Verified HA candidate — not activated in the cloud

Candidate **`507f9896e914d8da00353626da2f8aa503bd31b1`** passed [all CI/release gates](https://github.com/Sahil2237/notification-fanout/actions/runs/45335125767) and [exact-image publication](https://github.com/Sahil2237/notification-fanout/actions/runs/45336595057).

- Local Java: **48 passed** (17 unit, 31 real PostgreSQL/Kafka/HTTP integration).
- Python: **28 passed** (12 deployment security, 16 HA/backup).
- Local full HA drill: **8 checks passed**, **106 acknowledged events preserved**, **28.82-second single-node recovery**; four synthetic webhooks received HTTP 204, including one while a whole simulated node was down.
- Real PostgreSQL snapshot/age encryption/authenticated manifest/decryption/pg_restore preserved all nine tracked table counts. This local test used a filesystem S3 adapter; it is **not** an actual Spaces restore test.
- Quorum loss refused unsafe acknowledgement; recovery preserved acknowledged events; proxy logs passed the database-credential redaction regression.
- Exact CI images for app, PostgreSQL/backup tools, Caddy, Kafka 4.3.2 and etcd passed the fixable HIGH/CRITICAL vulnerability gate. Reports do not claim zero unknown/unfixed vulnerabilities or a fully patched workstation.
- Five full-revision tags were published under `ghcr.io/sahil2237/`: `notification-fanout`, `notification-ha-tools`, `notification-ha-proxy`, `notification-kafka`, `notification-etcd`. The application package was visually confirmed **Private**. Signature/checksum/image-content verification succeeded when installing infrastructure images on all three Droplets.

[Local HA evidence](evidence/local-ha-507f989.json) and [published image identities](evidence/published-images-507f989.json) contain synthetic/public metadata only.

## Provisioned resources and remaining work

Three 4-GB/2-vCPU Bangalore Droplets exist: `64.227.168.29`, `139.59.13.207`, `159.65.153.56`. A two-node regional load balancer (`174.138.121.225`) has three prepared backends. The original service still uses `https://64.227.168.29`; **the new load balancer is not an active HA API endpoint**. Cloud and host firewall rules restrict cluster traffic to private peers. The two idle nodes were patched/rebooted onto Ubuntu kernel `6.8.0-151`; the existing host remains on `6.8.0-149` pending an authorized rollout. Production Docker is `29.10.0`; the older Rancher development engine was not upgraded.

A private Standard Spaces bucket, `notification-fanout-backups-sahil2237`, exists in Singapore because new Bangalore Spaces creation was unavailable. Bucket-scoped backup credentials, node-specific TLS and a protected recovery identity are prepared; offline CA signing keys remain off the Droplets. **The daily backup timers are not activated, and a real Spaces backup/restore and independent cloud host-failure/fencing test have not been performed.** The limited Spaces key cannot install a storage-native lifecycle rule; application-side expiry is configured in the candidate code, not asserted as an active cloud policy.

These provisioned resources incur approximately **$101/month base** ($72 Droplets + $24 load balancer + $5 Spaces), even though HA is not activated. They have not been deleted or silently resized. This is about $77/month above the original $24 Droplet, before taxes/overages; remove unused resources only through an explicitly authorized cleanup.

Further HA deployment requires an authorized credential/database replication plan, safe import of the existing data, cloud failover/fencing tests, daily Spaces backup activation and a real restore drill. Zero-loss scope is acknowledged commits through a tested single-node failure with safe quorum; daily backup disaster RPO is up to roughly 24 hours. Regional loss, continuous WAL/PITR, automatic internal-certificate renewal, shared tenant quotas and external paging remain separate work.

## Existing single-host release — historical evidence

The current deployed source revision is `57b3fbfb81743081b4b76382392e9ed2eed8fc27`. Verification completed on **24 November 2026 IST** (23 November UTC). The final evidence/documentation commit does not change the running application.

- Private personal repository: [Sahil2237/notification-fanout](https://github.com/Sahil2237/notification-fanout).
- [Successful CI and release gates](https://github.com/Sahil2237/notification-fanout/actions/runs/45257513323).
- [Successful signed-artifact deployment](https://github.com/Sahil2237/notification-fanout/actions/runs/45258726373).
- Public REST base: `https://64.227.168.29`; [readiness](https://64.227.168.29/health/ready). Normal certificate validation succeeds; no insecure TLS override is required.
- [Machine-readable synthetic verification evidence](evidence/release-57b3fbf.json), [architecture diagrams](architecture.md), [API contract](api.md), [test matrix](validation.md), [operations](operations.md).

## Functional expectations

|Requirement|Implementation and verified evidence|
|---|---|
|Durable structured event ingestion|REST accepts type, source and arbitrary JSON. PostgreSQL commits event, matching delivery jobs and Kafka outbox atomically before 202. Transaction/storage-failure and duplicate-request tests passed; 10,000 cloud events persisted and survived a host reboot.|
|Dynamic subscriptions and filters|Create/list/delete without restart; type/source and JSON Pointer payload equality. Lifecycle, tenant isolation and filter tests passed. Live origin approval/revocation and subscription registration/deletion required no app restart.|
|Fanout, retries and queryable state|UUID-keyed Kafka dispatch activates unique delivery jobs. Signed HTTPS webhooks use persisted jittered backoff, leases and bounded attempts. Three demo events each received 204 on attempt one. A deliberate 503 receiver produced five recorded attempts and terminal failed.|
|Delivery audit|Event/subscription/delivery queries return status, attempt timestamps, outcomes and nullable HTTP response codes. Live successful and failed histories survived app restart and a full Droplet reboot.|

## Engineering expectations

The repository includes the full ingest/matching/outbox/Kafka/fanout/retry/audit flow and request sequence diagrams, precise delivery guarantees and failure conditions, setup/filter documentation, operational runbooks, and the simplicity-versus-next-hardening decisions.

All **46 Java tests** passed: **15 unit and 31 real PostgreSQL/HTTP/Kafka integration tests**. All **12 Python deployment security tests** passed. CI also passed image scanning with **zero fixable HIGH/CRITICAL findings**, Caddy configuration validation, real Compose startup, runtime database privilege checks, intentionally failed-startup rollback and database restore. The scan uses a dated vulnerability database and is not a claim that no vulnerabilities can exist.

CI builds the application image once. Deployment promotes that signed, checksummed artifact from the exact successful workflow run without rebuilding. A restricted upload user has no Docker membership or access to application secrets; a root-owned gateway verifies signatures, safe paths, release identity and portable image content before promotion. Infra images are pinned to the tested digests in the release bundle. Cloud verification confirmed the running image's layers and runtime configuration against the signed archive's original config digest.

## Live failure and recovery checks

- Unauthenticated tenant requests return 401; tenant credentials cannot administer origins, administrator credentials cannot ingest tenant events, and public ingress rejects administrator routes.
- Approved a temporary public HTTPS origin, registered/deleted a matching subscription, revoked the origin, and confirmed registration was denied without restarting the app. Revocation persisted across restart/reboot.
- Three matching synthetic events traversed public HTTPS, PostgreSQL, Kafka and the external receiver. Captured body/IDs and HMAC signatures were verified. An additional event accepted while Kafka was stopped remained pending with zero HTTP attempts and delivered after broker recovery.
- A separate temporary receiver intentionally returned 503. All five attempt timestamps/codes and terminal failure were audited; the temporary subscription was deleted afterward. A nonmatching event created no jobs.
- A new backup restored into a temporary database on the **same Droplet**, preserving three demo events/deliveries, revocation and its audit. The temporary database was removed. No database backup was exported to the workstation.
- After a **full Droplet reboot**, normal HTTPS readiness recovered, the deployed revision was unchanged, all 10,000 probe events remained persisted/dispatched, and successful/failed delivery audit plus revoked origin state remained intact.

The temporary free receiver is for synthetic data only. Its capability URL is kept out of Git in operator state; it has a seven-day lifetime and a 50-request allowance. It captures requests but does not enforce signatures or deduplicate business effects itself.

## Measured capacity and partitioning

On the existing 2-vCPU/4-GB Droplet, a bounded probe submitted **10,000 events of one type at 50 events/s** in **200.04 seconds**. All received 202; all 10,000 were persisted and Kafka-dispatched, with zero errors and zero matching delivery jobs. Local HTTP acceptance latency was p50 **13.63 ms**, p95 **73.81 ms**, p99 **191.01 ms**.

This measures same-host HTTP ingestion, database durability and Kafka dispatch for small synthetic payloads. It excludes public-network latency, matching fanout and receiver throughput; it is **not** evidence of 10,000 events/s or an end-to-end fanout SLO. Reproduce with `python3 /opt/notification-fanout/validate-cloud.py --events 10000 --rate 50` as the Droplet operator; the script refuses subscriptions that might match its generated test type.

The dedicated integration test sends 10,000 same-type, distinct-UUID-keyed Kafka records across six partitions and verifies distribution. After the cloud probe, cumulative partition end offsets were **1613, 1617, 1655, 1738, 1694, 1694** (10,011 records including 11 earlier records). One event type therefore did not concentrate this workload in one partition. UUID keys do not promise per-source ordering.

## Deployment boundary and remaining work

The selected existing resource is `notification-fanout`, Droplet ID `622664599`, in team `Blitz_team13_Hyd`, **Bangalore/blr1**, Ubuntu 24.04, 2 vCPU / 4 GB RAM / 80 GB disk. The approved base is approximately **$24/month**; no managed Kafka, managed database, App Platform app, registry or additional paid resource was added. Kafka and PostgreSQL are self-hosted with persistent volumes.

The cloud firewall permits 22/80/443. Public API traffic uses Caddy HTTPS; 8080 is loopback-only, administrator operations use an SSH tunnel, and database/broker/metrics ports are not published. Public-IP certificates use the short-lived ACME profile and persistent Caddy state. Renewal is configured automatically; an elapsed renewal cycle has not yet been observed.

This is a hardened **single-host assessment deployment**, with tested durability/recovery within that boundary. It does not provide host/zone failover or survive loss of its sole disk. Before taking production availability or data-loss commitments, implement encrypted off-host backups with independent restore drills, replicas/failover as required, external dashboards/alerts/paging, agreed retention automation, credential lifecycle management and representative fanout/soak tests. No independent RPO/RTO or fanout SLO has been established. Internal database/Kafka traffic is plaintext on one private Docker bridge under this explicit single-host trust model.

Per-source ordering and replay remain optional extensions. [README trade-offs](../README.md#what-we-sacrifice-for-simplicity-vs-what-we-would-harden-next) explains when to revisit each simplicity choice. Source and diagrams are pushed; interviewer access to the private repository must be granted by its owner. Personal-account sign-out remains a workstation handoff step, not evidence of application deployment.
