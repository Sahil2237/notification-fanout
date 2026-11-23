# Operations and release notes

> Current Kafka implementation and verified scope: see [submission-status.md](submission-status.md) and [architecture.md](architecture.md). Earlier milestone descriptions below are retained as historical design context.

## Local processes
Prepared development container: notification-dev; persisted project: Docker volume notification-workspaces at /workspaces. DB: notification-db; data: notification-postgres. Stop/start preserves volumes. `docker rm -v`/`docker volume rm` can destroy work: never use as routine cleanup.

Inside the IDE container, build `mvn verify`, migrate `java -jar target/notification-fanout.jar db migrate config/local.yml`, start `java -jar target/notification-fanout.jar server config/local.yml`. Stop with SIGTERM/Ctrl-C; workers stop claiming, allow in-flight calls to finish, then shut down. SIGKILL leaves leases for recovery. Liveness independent of DB; readiness checks DB and recent worker polling when enabled. App-only WORKER_ENABLED=false disables worker checks. Admin endpoints must remain private.

## Operational signals
/metrics on admin port exposes events.accepted/duplicates/acceptance_latency, delivery.pending, delivery.oldest_pending_seconds, delivery.attempts/succeeded/unsuccessful_attempts/recovered/stale_results/worker_errors plus JVM, HTTP and DB pool metrics. Acceptance timer includes idempotent responses. Queue gauges query PostgreSQL; metric scraping can fail during DB outage. Logs contain correlation ID and delivery/event/attempt identifiers, status and bounded errors; do not log bearer tokens, webhook bodies or signing keys. Full traces, provisioned dashboards and alert integrations remain future deployment work.

Proposed alerts: sustained readiness failure; pending>0 and oldest age>60s; increasing worker errors; sustained terminal failures; DB pool exhaustion/storage headroom. Receiver-specific failures are not automatically global availability incidents. Configure monitoring before external production release.

## Backlog or receiver failure
1. Check readiness, oldest pending age and worker errors.
2. Check database connectivity/storage/connection limits and due-job query latency.
3. Inspect one affected event/subscription history using tenant credentials. No direct cross-tenant audit API exists.
4. Retryable webhook failures wait for persisted schedules. Do not reset attempt counts during an incident without an explicit redrive policy.
5. Failed deliveries remain terminal; redrive/replay is not implemented. Document affected IDs/time range and obtain authorization for any repair.
6. Restore capacity gradually; increasing worker threads without checking receiver/DB limits can worsen overload. Per-tenant fair scheduling is a known limitation.

## Recovery and backups
Process restart preserves acknowledged commits if DB storage survives. Expired leases produce unknown attempts and can cause duplicate sends. Proposed production disaster RPO<=5min/RTO<=60min requires managed PITR, encrypted backups and regular restore drills; local Docker volumes do not provide these guarantees. Restore to a new DB, verify schema/counts and sample histories, then switch traffic; receiver deduplication handles uncertain sends. Do not claim a successful restore until tested.

## Retention and secrets
Initial policy target: 7 days events/attempts, retain unresolved pending work. No automated deletion job is enabled. Implement bounded child-first cleanup with tenant policy and idempotency-key expiry before production volume grows. For incident logs prefer IDs; payload may contain PII. Replace demo API tokens and signing master; distribute only derived tenant signing keys. Planned signing rotation needs key IDs and overlapping acceptance windows. Runtime DB role should have DML only; a separate migration role owns DDL. TLS to managed DB/HTTPS ingress and storage encryption are deployment configuration requirements.

## Deployment sequence and rollback
1. Separate dev/test/prod DBs and credentials; run tests in a disposable test DB only.
2. Build immutable image; scan dependencies/image and record digest. Runtime is unprivileged; dev image intentionally includes tools.
3. Check backup/recovery readiness. Run additive migration once before app rollout; Liquibase lock serializes migration execution.
4. Start new API/worker instances and gate on readiness. Keep deployment termination allowance longer than max HTTP timeout plus DB result-save allowance.
5. Verify a canary event and history, monitor backlog/error rates, drain old instances.
6. Roll back to a schema-compatible prior app image if needed. Do not delete committed events or blindly roll back data/schema. Expand/contract changes require separate releases.

## Release acceptance
All local/CI tests green; fresh setup and signed retry demo pass; restart recovery checked; secrets replaced; destinations reviewed; HTTPS/network restrictions enabled; capacity benchmark at representative fanout; backups/restore and retention validated; monitoring installed; repository/interviewer access verified. Several production infrastructure checks remain deliberately outside this local baseline. DigitalOcean, managed queue, ordering and replay follow as distinct milestones.
