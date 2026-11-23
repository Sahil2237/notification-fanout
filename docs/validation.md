# Executed validation and remaining gaps

> Current Kafka implementation and verified scope: see [submission-status.md](submission-status.md) and [architecture.md](architecture.md). Earlier milestone descriptions below are retained as historical design context.

Executed on November 23, 2026, inside Rancher Desktop's Java 25 / Ubuntu 24.04 development container. Project persisted at /workspaces/notification-fanout. Host PostgreSQL checkout was not modified.

|Check|Observed result|
|---|---|
|Runtime|Ubuntu 24.04; Temurin 25.0.5.1; Maven 3.8.7; PostgreSQL 17|
|mvn verify|7 unit + 17 real PostgreSQL/HTTP integration tests passed, zero skips|
|Signed webhook demo|500, 500, 204; three audited attempts; final delivered|
|Actual Java process restart|Queued work accepted with worker disabled; Java process killed; after restart delivered in approximately 4.1 seconds, three attempts|
|Database outage|Liveness 200; readiness 503; ingestion 503 without acknowledgment; readiness recovered after DB restart|
|Runtime Docker image|Built from clean image using Dockerfile; non-root runtime started; readiness and authenticated subscription list passed|
|Acceptance-only probe|100 requests, concurrency 4, zero matching subscriptions; 100 accepted, no failures, about 209 accepted/s and p95 42ms|
|Fanout probe|100 requests, concurrency 4, three matches each; 100 accepted, 300 delivered, zero failed; about 240 accepted/s and p95 acceptance 31ms; queue drained about 10.9s after ingestion|
|Fanout attempt breakdown|300 HTTP 204, 600 HTTP 500, 29 recorded transport errors; total 929 application attempts|
|IDE|IntelliJ 2025.3.6.2 backend and matching client connected to Rancher; saved Maven Verify configuration executed on /opt/java/openjdk (Java 25), bundled Maven 3.9.11; UI visibly showed BUILD SUCCESS; 7 unit + 17 integration tests passed.|
|GitHub|Repository initialized locally; no remote, no account authenticated, no hosted CI run yet|

These are short local smoke probes, not sustained load/capacity tests, percentile SLO proof, or a production benchmark. Fanout receiver deliberately returns two failures per delivery; extra transport errors occurred during the local concurrent probe. Results reflect the whole local setup, including a busy developer workstation and the receiver, not an isolated sender ceiling. No 1000-subscription, slow-receiver fairness or long-duration saturation benchmark has been executed. The initial acceptance probe while an in-use JAR was being overwritten failed; start-local.sh now copies the JAR to avoid that packaging hazard. Test and demo receiver ports are isolated (19090 and 9090).

Critical requirements map to ServiceIT: fullHttpJourneyAndAudit; fanoutFiltersTenantsAndDeletionBoundary; acceptsNoMatchesAndAllJsonKinds; concurrentIdempotencyCreatesOneEventAndOneDelivery; concurrentWorkersClaimDistinctJobs; realHttpFailuresRetryThenSucceed; permanentResponseAndRetryExhaustionAreAudited; expiredLeaseRecoversAcrossStoreRestartAndFencesOldWorker; finalInterruptedAttemptBecomesFailed; eventAndFanoutRollbackTogether; validationAuthenticationAndOwnershipOverHttp; redirectIsNotFollowedAndPolicyFailureHasNoInventedStatus; managedWorkerProcessesPersistedQueue; httpAcceptsNullPayloadRejectsMissingPayloadAndBoundsPages; retryScheduleIsNotClaimedEarly; lostSuccessResultCausesDuplicateWithStableDeliveryIdentity; connectionFailureIsRecordedWithoutHttpStatus. CoreTest covers filter semantics, destination rules, retry bounds, throttling and configuration validation.

Remaining release gaps: personal GitHub destination/authentication and hosted Actions verification; fresh Compose IDE workflow (Compose configuration validated; runtime Dockerfile built/tested separately); arbitrary domain-name webhooks; distributed/per-tenant fair scheduling; managed secrets/TLS/least-privilege production roles; actual backup/restore and retention automation; sustained representative load; deployed monitoring/tracing; cloud deployment/managed broker; ordering/replay/redrive. No production certification or unconditional eventual delivery is claimed.
