# Operations, incidents and release acceptance

**Current state:** the user retained the existing single-host service. HA candidate `507f989` is verified/published and images are staged, but HA application deployment and daily Spaces timers are not activated. Do not use the new load-balancer IP as a live API. See [current decision and evidence](submission-status.md) before applying either runbook.

The selected environment is a single DigitalOcean Bangalore Ubuntu 24.04 Droplet. The root-only `/opt/notification-fanout/.env` contains tenant/admin tokens, database credentials and signing master. The API runs as UID 10001 in its container; PostgreSQL/Kafka use persistent Docker volumes. Never delete volumes as routine troubleshooting. Operator SSH and the restricted CI deployment account are different credentials.

## Routine checks and alerts

Application `/health/live` reports process health; `/health/ready` checks DB/worker/Kafka polling. The admin connector is bound to container loopback, not a published host port. Its `/metrics` exposes acceptance timers/counters, pending count/age, delivery attempts/failures/recoveries, Kafka publish/dispatch/quarantine errors and JVM/DB pools. JSON logs contain request/event/delivery identifiers and bounded error categories; no bearer token, signing key or event body belongs in logs. Caddy access logs use its default credential redaction. Host metrics are installed. External paging/dashboards are not configured; an operator must connect an approved alert destination before an availability commitment.

Alert candidates: readiness unavailable >1 minute; oldest pending or undispatched event >60 seconds; rising terminal failures or poison records; disk >85%; DB pool exhausted; last successful backup >26 hours. Receiver-specific failures should not automatically declare global service outage. Measure SLOs against successful durable ingestion and on-time delivery to healthy receivers; do not count an arbitrary 2xx as proof of a subscriber business side effect.

## Backlog, receiver or broker incident

1. Check readiness and application/DB/Kafka logs with bounded tails; inspect backlog age, not only size.
2. Check disk runway, DB connectivity/locks and receiver latency before increasing worker threads.
3. Query tenant-scoped event/subscription/delivery histories; preserve attempt timestamps and real HTTP status/null.
4. Kafka failure delays dispatch: accepted work stays in the DB outbox. A broker/consumer restart may repeat processing; uniqueness and receiver deduplication handle repeats.
5. Expired leases record unknown outcomes. An old worker cannot overwrite a new claim. Never assume an unknown HTTP outcome means the receiver did nothing.
6. Failed deliveries stay terminal after the configured attempt limit. Replay/redrive is not implemented; retain IDs and follow an explicitly authorized repair plan. Do not reset attempt counts silently.
7. If queue capacity is reached, new ingestion returns 503 and rolls back, while an existing idempotent request can still return its event. Global capacity enforcement is an approximate admission threshold under concurrent tenants, not a distributed quota guarantee.

## Runtime destination policy

Use the administrator bearer token only through the SSH tunnel. `/v1/admin/webhook-origins` supports list, approve and revoke. Revocation works even if DNS has become unavailable/private. All application instances read current DB state; no cached approval survives revocation. Initial configuration seeds unknown origins once; a revoked row stays revoked across restarts. Read policy-command history from `webhook_origin_audit` using an authorized database operator; the app can insert audit rows but cannot update/delete them. Origin permission covers the host/port, not individual paths. Signed receiver-key provisioning remains an operator responsibility.

## Deployment, rollback and trust

CI tests and scans one image; that exact image is checksummed and signed after real startup/rollback/restore verification. Signed artifacts are uploaded to the private repository's Actions storage. The deploy workflow downloads only from the successful same-repository default-branch verification run. The SSH upload user cannot read `.env`, has no Docker membership, and cannot sudo a shell. A root-owned gateway copies only regular named files, verifies the detached Ed25519 signature against the installed public key, validates checksums/revision/image identity and refuses changed contents for an existing revision. That gateway is deliberately privileged to run a trusted signed release; the CI signing key and repository write access therefore remain powerful credentials.

Each release takes a consistent pre-migration database backup, applies additive migrations, grants runtime privileges, starts the application, and gates readiness/public TLS before moving `current`. Failure attempts to restore the previous application image; migrations are never blindly reversed. CI exercises an image that migrates but fails server startup. Preserve at least the current and previous images. A manual rollback needs its retained image/archive and schema compatibility. Re-running a previously removed image archive requires restoring the exact signed artifact; do not modify an existing revision in place.

Rotate the CI SSH key and release signing key independently. Install a new trusted release public key before signing with its private key, or use a coordinated maintenance window; multiple overlapping signing keys are not implemented. Changing `.env` database passwords alone does not change database roles: rotate with ALTER ROLE and an application restart. Runtime origin changes need neither.

## Backup and recovery

`bash /opt/notification-fanout/current/backup.sh` writes a consistent custom-format PostgreSQL dump with mode protected by root's umask. The release path invokes it before migrations. Existing cloud evidence includes reboot recovery and a same-server restore into a newly created temporary database. CI repeats a restore on disposable containers. These checks do not prove off-host disaster recovery. Configure encrypted off-host storage and secrets escrow with an approved destination; no backup is exported to a third party by these scripts. Local disk loss can destroy both data and local backups. No numerical disaster RPO/RTO is claimed.

Restore to a replacement environment with owner/runtime roles and their protected credentials, restore a compatible dump using pg_restore, apply required forward migrations/grants, recreate Kafka/topic, then start the outbox/consumer/workers. Verify counts, histories and synthetic delivery before switching traffic. Undispatched DB events can be republished. Restore can repeat deliveries whose receiver success was newer than the backup, so receiver deduplication is essential.

## Retention and release acceptance

No automatic deletion of events, attempts or audits is enabled because the business retention period and idempotency-key expiry have not been agreed. Monitor growth and local backup storage; do not promise indefinite retention on an 80-GB disk. Future cleanup must preserve unresolved work and respect tenant/audit policy.

Accept a release only after unit/integration and bundle tests, vulnerability gate, real container smoke, failed-startup rollback and restore tests pass; the signed image identity matches; live auth/origin changes and representative synthetic events pass; audit/receiver capture agree; and relevant recovery checks pass. A 10,000-event no-match probe cannot establish delivery capacity for slow receivers or large fanout. HA, off-host recovery, retention and external alerting remain explicit limits of the budget deployment.

## HA candidate runbook (activation requires an authorized rollout)

The candidate settings/scripts live in `deploy/ha/`; they are separate from the legacy single-host release above. Verify a successful trusted `ci.yml` run, detached bundle signatures, checksums and portable image identity before installation. Exactly three nodes, private peer firewalls, separate TLS authorities and a working required watchdog are prerequisites. Keep offline CA signing keys outside the cloud hosts. Preserve API/admin/signing identity only through an explicitly authorized replication plan.

Freeze legacy application writes for a controlled import. Take a same-server consistent dump and compare every business/audit/schema-history table after restoring into the synchronous cluster; never export production database contents to the development workstation. Create roles, apply runtime grants, configure the RF3/min-ISR2 Kafka topic and least-privilege application ACLs, then enable the signed application promotion path. Do not force promotion of an asynchronous database replica. Once new writes are accepted, the old database is stale and cannot be used as an automatic rollback target.

For subsequent app releases, `ha-release.sh` drains a backend, migrates forward, updates only that application, checks readiness and restores traffic before moving to the next node. PostgreSQL/Kafka/etcd/Caddy infrastructure updates are separate operator operations, one node at a time with quorum/replication health checked between nodes. Keep acknowledged synthetic event IDs and verify retrieval/fanout after failures. A local shared-VM test cannot establish host fencing.

When activated, `notification-backup.timer` checks every five minutes; a database advisory lock permits one successful daily snapshot. `run-backup.sh` uses disk-backed scratch and a scoped Spaces key, a repeatable-read exported snapshot, age encryption, hashes and authenticated completion manifests. Retention is 24 hours plus check jitter while the cluster is healthy. Expiry can leave no backup after a failed next backup, and a whole-cluster outage can delay pruning. No provider lifecycle policy has been installed.

Run `restore-test.sh MANIFEST_KEY` on an authorized host with the protected age identity and manifest key. It downloads/authenticates/decrypts the actual Spaces object, restores a fresh network-isolated PostgreSQL database, compares nine table counts, and cleans up plaintext/disposable state. Preserve recovery keys and role/configuration secrets outside the cluster. A daily dump is not continuous WAL/PITR and cannot provide zero-loss regional disaster recovery. Internal certificates expire after 90 days; renew and monitor them before expiry. No cloud restore or fencing result may be claimed until actually executed.
