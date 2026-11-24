# Verification matrix

The current source suite contains 19 unit and 40 integration tests; release-gateway tests add 12 Python security tests, and HA configuration/backup tests add 19 more Python tests. Deployed release evidence is version-specific in submission-status.md. Run `mvn -B -ntp verify` and `python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v`. Tests use a dedicated disposable PostgreSQL database and real Kafka/HTTP endpoints. Never configure the test database URL to a production/shared database.

|Requirement / material risk|Automated evidence|
|---|---|
|Type/source/payload filters, null/missing, arrays and JSON Pointer|CoreTest filter cases; ServiceIT all JSON kinds|
|Durable transaction and storage error response|ServiceIT event/fanout rollback and simulated SQLSTATE 53100 HTTP 503; KafkaIT atomic outbox rollback|
|Dynamic subscription management and tenant isolation|ServiceIT REST lifecycle, deletion boundary and ownership tests|
|Runtime origin approval / revocation / administrative isolation|ServiceIT approve/list/revoke without restart, fresh Store persistence and revoked seed protection; CoreTest public HTTPS approval|
|Actual DNS rebinding / mixed private answers|CoreTest injected lookup changes between registration and connection resolver|
|Concurrency / duplicate requests / stale workers|ServiceIT concurrent idempotency, distinct claims, lost HTTP-success result and lease fencing|
|Retries / final failure / timeout / audit|ServiceIT real 500/500/204, permanent response, exhaustion, real slow receiver cancellation, retry timing and null HTTP status|
|Backpressure rollback|ServiceIT bounded outbox rejects a new event but preserves a duplicate request|
|Real REST → Kafka → webhook flow|KafkaIT full journey with dispatch gating and recorded HTTP response|
|Kafka recovery / poison data / offset boundaries|KafkaIT broker outage, restarts, duplicate envelopes, quarantine, partition-specific dispatch failure and outbox lease recovery|
|10,000 records with one type|KafkaIT sends 10,000 UUID-keyed records to a real six-partition topic and checks distribution; this is not a full-service throughput SLO|
|Release authenticity / path safety|Python tests for tampered image/signature, symlinks, manifest paths, revision mismatch and immutable release reuse|
|Container/schema/runtime privilege correctness|CI Compose smoke test with distinct migration/runtime roles, DDL denial, ingestion and dispatch|
|Failed startup / rollback / database restore|CI executes the real release script with an image that migrates but fails server startup, checks rollback and restores a pre-release dump|
|HA configuration, certificate boundaries, backup integrity and retention|16 Python cases in deploy/ha/test_ha.py|
|Independent PostgreSQL processes and etcd quorum|CI real three-member TLS cluster preserves 100 acknowledged rows after primary loss and accepts another write|
|Full local HA, fanout, quorum loss and backup restore|Rancher test-local-stack.py: 106 acknowledged events, four HTTP 204 deliveries including one with a whole simulated node down, recovery, all nine restored table counts, and no proxy credential leakage|
|Dependency vulnerabilities|CI scans the application, PostgreSQL/backup tools, proxy, Kafka and etcd; gates on fixable HIGH/CRITICAL findings and uploads reports|

Cloud verification is separate from CI: record the exact deployed revision and image ID, prove live admin changes do not restart the app, post synthetic matching/nonmatching events, verify audit and the external request/HMAC, and test service restart recovery. The existing deployment already passed reboot and same-host restore; each changed release needs its own live evidence. See submission-status.md for historical results and the latest workflow/release reports for new outcomes.

Cloud host-failure and actual Spaces restore evidence are recorded separately from the local eight-check HA drill. Unproven boundaries include region-wide disaster recovery, long-duration saturation, broad receiver-fairness testing, automated paging and agreed production p95/p99 SLOs. A load probe must state whether it includes HTTP ingestion, matching fanout, delivery, payload size, duration and receiver latency. No test suite is described as exhaustive. The 10,000-record test initially exposed a local Kafka sequencing failure with multiple in-flight batches; a single in-flight request and bounded producer deadlines passed the rerun.


The first hosted security gate rejected older transitive versions. The fixes pin Jackson BOM 2.21.7, Jetty BOM 12.1.10, Guava 33.7.2-jre, HTTP Core/H2 5.4.3 and lz4-java 1.11.4. All are same-series fixes verified from Maven Central. Vulnerability findings are not suppressed; the hosted scan passed with zero fixable HIGH/CRITICAL findings before promotion. Public IP certificate preflight succeeded with normal system trust validation; `default_sni` selects the IP certificate for clients that omit SNI.


Cloud promotion revealed that the OCI-backed Docker engine reports a manifest ID where the CI engine reports a config ID. The portable identity verifier checks the signed archive's original config SHA-256, every root filesystem layer digest, architecture/OS and normalized runtime configuration; it never accepts a mismatched image merely because its tag matches. Five dedicated tests cover cross-engine identity and rejection of changed layers, entrypoint, user and archive digest. Release CI also starts with root-only bind-mounted init/config files to exercise the explicit container-read permissions needed on a fresh server.


## Verified cloud release

Revision `57b3fbf` passed the [hosted release gates](https://github.com/Sahil2237/notification-fanout/actions/runs/45257513323), [signed deployment](https://github.com/Sahil2237/notification-fanout/actions/runs/45258726373), public HTTPS/webhook/HMAC checks, real broker outage recovery, five-attempt HTTP 503 failure audit, app restart, same-server backup restore and full Droplet reboot. The 10,000-event no-match probe accepted, persisted and dispatched every event at an offered 50/s (p95 local HTTP acceptance 73.81 ms). See [current results and limits](submission-status.md) and [sanitized evidence](evidence/release-57b3fbf.json); this does not establish fanout throughput or host-loss recovery.

## HA release gates

Revision `507f9896e914d8da00353626da2f8aa503bd31b1` passed [all verification and image gates](https://github.com/Sahil2237/notification-fanout/actions/runs/45335125767) and [exact-image publication](https://github.com/Sahil2237/notification-fanout/actions/runs/45336595057). The local suite passed 48 Java and 28 Python tests. Kafka is 4.3.2 with updated base packages; the proxy uses Go 1.27.4 and a fixed networking module. The scan scope is known, fixable HIGH/CRITICAL findings at scan time, not a guarantee that every tool or future vulnerability is absent. The existing Rancher host engine is older development infrastructure and is not the production Docker engine.


## Activated cloud HA verification — 24 November 2026

Application bd082b6 passed the complete [CI gates](https://github.com/Sahil2237/notification-fanout/actions/runs/45341972631), [image publication](https://github.com/Sahil2237/notification-fanout/actions/runs/45343398780) and [signed three-node rollout](https://github.com/Sahil2237/notification-fanout/actions/runs/45343513088). The cloud checks are recorded in [sanitized evidence](evidence/cloud-ha-bd082b6.json).

| Material risk | Executed cloud verification |
| --- | --- |
| Import omission/corruption | Nine table counts/content fingerprints matched at import; all 10,011 immutable original events matched again after failures/reboots |
| Unsafe primary process | Paused primary triggered watchdog host reboot; required watchdog unit/device checks passed after real boots |
| Host failure | Powered primary Droplet off through DigitalOcean; another PostgreSQL member automatically became primary; accepted and delivered a new event while only two hosts were running |
| Kafka single-broker loss | All six partitions had ISR 2 during the whole-host outage, with successful new webhook delivery; all returned to ISR 3 after rejoin |
| Lost acknowledged work | All 124 acknowledged synthetic event IDs were retrievable after failure/maintenance; total event count 10,135 |
| Receiver/audit mismatch | Four new deliveries returned 204; external capture IDs, body and HMAC verified against audit |
| Unusable off-host backup | Downloaded/authenticated/decrypted actual encrypted Spaces archive; isolated PostgreSQL restore matched all nine snapshot counts; same-day backup repeat returned not_due |
| Reboot/image drift | All three running application images match the signed artifact, APP_IMAGE persists, all hosts run kernel 6.8.0-151/Docker 29.10.0 with zero pending apt upgrades and no reboot requirement |
| Ingress security | Normal HTTPS certificate validation, unauthenticated tenant 401, public administrator endpoint 404 |

The host-offline probe proves another-primary promotion; the earlier watchdog reboot probe did not, because its original leader returned. Requests can time out or receive 503 during failover. Exact response-time SLOs, an elapsed 24-hour Spaces expiry/public-certificate renewal cycle, full-region restore, independent-zone placement, slow-receiver fanout capacity and external paging remain unverified/unimplemented as detailed in the current status.


## New delivery-control verification

The source suite now covers concurrent canonical generated keys and producer-ID conflicts, identity scoping, a shared fixed-window cap across independent Store instances and window reset, subscriber reservations/fairness across instances, six-attempt exhaustion, rollback when the DLQ insert fails, tenant-scoped dead-letter history, real Kafka DLQ publication with event correlation, stale DLQ publisher fencing, request correlation headers, MDC cleanup and rejection of a primary presented as a strict read pool.

The hosted PostgreSQL HA fixture additionally runs the application JDBC driver against actual standby nodes before and after promotion and verifies connection failure when only the primary remains. Those hosted results must be recorded from the successful run before promotion. New snapshots verify the two additional durable-control tables; restore accepts historical snapshot metadata too.
