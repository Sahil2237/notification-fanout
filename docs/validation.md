# Verification matrix

The current runtime suite contains 15 unit and 31 integration tests; release-gateway tests add 12 Python security tests. Run `mvn -B -ntp verify` and `python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v`. Tests use a dedicated disposable PostgreSQL database and real Kafka/HTTP endpoints. Never configure the test database URL to a production/shared database.

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
|Dependency vulnerabilities|CI image scan gates on fixable HIGH/CRITICAL vulnerabilities and uploads its report|

Cloud verification is separate from CI: record the exact deployed revision and image ID, prove live admin changes do not restart the app, post synthetic matching/nonmatching events, verify audit and the external request/HMAC, and test service restart recovery. The existing deployment already passed reboot and same-host restore; each changed release needs its own live evidence. See submission-status.md for historical results and the latest workflow/release reports for new outcomes.

Unproven boundaries include full host loss, independent replica failover, encrypted off-host restore, long-duration saturation, broad receiver-fairness testing, automated paging and agreed production p95/p99 SLOs. A load probe must state whether it includes HTTP ingestion, matching fanout, delivery, payload size, duration and receiver latency. No test suite is described as exhaustive. The 10,000-record test initially exposed a local Kafka sequencing failure with multiple in-flight batches; a single in-flight request and bounded producer deadlines passed the rerun.


The first hosted security gate rejected older transitive versions. The fixes pin Jackson BOM 2.21.7, Jetty BOM 12.1.10, Guava 33.7.2-jre, HTTP Core/H2 5.4.3 and lz4-java 1.11.4. All are same-series fixes verified from Maven Central. Vulnerability findings are not suppressed; the next hosted scan must pass before promotion. Public IP certificate preflight succeeded with normal system trust validation; `default_sni` selects the IP certificate for clients that omit SNI.


Cloud promotion revealed that the OCI-backed Docker engine reports a manifest ID where the CI engine reports a config ID. The portable identity verifier checks the signed archive's original config SHA-256, every root filesystem layer digest, architecture/OS and normalized runtime configuration; it never accepts a mismatched image merely because its tag matches. Five dedicated tests cover cross-engine identity and rejection of changed layers, entrypoint, user and archive digest. Release CI also starts with root-only bind-mounted init/config files to exercise the explicit container-read permissions needed on a fresh server.
