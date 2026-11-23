# Submission status

The working implementation includes durable REST ingestion, dynamic subscription filters, Kafka outbox publication and idempotent dispatch, retrying signed webhooks, tenant-scoped delivery audits, and health/metrics endpoints. Java 25, Dropwizard 5, PostgreSQL 17 and Kafka 4.3.1 were exercised in Rancher.

Pre-push verification on November 23, 2026: `mvn -B -ntp verify` passed 10 unit tests and 26 PostgreSQL/HTTP/Kafka integration tests, with zero failures, errors or skips. These include Kafka outage/recovery, publisher/consumer restart, duplicate consumption, poison-record quarantine, multi-partition dispatch failure, atomic outbox rollback and lease fencing. The earlier IntelliJ run preceded Kafka integration; rerun its saved Verify configuration to execute the current suite.

The DigitalOcean production configuration, App Platform spec generator and CI/CD definitions are prepared scaffolding. No cloud resources have been provisioned, no cloud release has been performed, and the deployment generator still requires region, VPC, registry, existing managed-cluster inputs and secrets. Cloud-specific TLS, migrations/least-privilege bootstrap, monitoring, backup restore and end-to-end release remain to be validated. Do not treat configuration files as proof of a production deployment. Ordering and replay remain extensions, not implemented features.

The short earlier load probes describe the pre-Kafka baseline; they do not validate current Kafka throughput or 10,000 events/second. A representative Kafka load/retention/recovery benchmark is still required. Some original design/validation sections describe the previous PostgreSQL-only milestone; use architecture.md for the current messaging flow and this file for current submission status.

GitHub Actions runs the complete suite with real Kafka and PostgreSQL. Hosted workflow results must be checked after publication. Automatic DigitalOcean deployment is gated on a successful default-branch CI run plus configured production environment variables/secrets; with no app ID configured, cloud deployment is skipped.


Current deployment direction: a Bangalore Ubuntu 24.04 Droplet with self-hosted Kafka and PostgreSQL, as requested instead of managed Kafka. Dedicated Compose configuration, bootstrap/provision scripts, SSH deployment workflow and a real-stack CI smoke job are under `deploy/droplet/`. The GitHub-to-Droplet connection needs DigitalOcean authentication, a provisioned server, verified SSH identity and repository deployment settings. These files are preparation; no Droplet has been created or live cloud deployment completed yet. The prior hosted Java verification passed before these changes; check the new run for the added configuration tests and smoke job.
