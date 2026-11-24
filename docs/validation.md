# Verification matrix

The current runtime suite contains 17 unit and 31 integration tests; release-gateway tests add 12 Python security tests, and HA configuration/backup tests add 16 more Python tests. Run `mvn -B -ntp verify` and `python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v`. Tests use a dedicated disposable PostgreSQL database and real Kafka/HTTP endpoints. Never configure the test database URL to a production/shared database.

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
