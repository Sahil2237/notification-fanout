# Verified submission and deployment

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
