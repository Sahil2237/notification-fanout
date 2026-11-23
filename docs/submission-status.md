# Submission and deployment status

The service implements durable REST ingestion, dynamic subscription filters, Kafka outbox publication and idempotent dispatch, retrying signed webhooks, tenant-scoped delivery audits, and health/metrics endpoints. Java 25, Dropwizard 5, PostgreSQL 17 and Kafka 4.3.1 were exercised in Rancher.

Verification: 12 unit tests and 26 PostgreSQL/HTTP/Kafka integration tests pass locally. Hosted run [45239397092](https://github.com/Sahil2237/notification-fanout/actions/runs/45239397092) also passed the application suite and a real Linux-amd64 Docker Compose startup smoke test. The smoke test exercises migrations, runtime DML access with DDL denied, authentication, ingestion and Kafka dispatch using the Droplet configuration. The first startup test caught a database pool-size mismatch; the explicit initial size fixed it. The earlier IntelliJ run preceded Kafka integration; its saved Verify configuration runs the current suite.

## Selected cloud deployment

The user selected **one Bangalore Ubuntu 24.04 Droplet**, with self-hosted Kafka and PostgreSQL, rather than Managed Kafka. The Droplet `notification-fanout` (ID `622664599`, IPv4 `64.227.168.29`) was created in team `Blitz_team13_Hyd`, region `blr1`, regular Basic `s-2vcpu-4gb` (2 vCPU, 4 GB RAM, 80 GB SSD), at the displayed base price of **$24/month**. It incurs charges until destroyed, even if powered off. No managed database, managed Kafka, App Platform app or registry was provisioned for this project.

Ubuntu bootstrap and Docker Compose installation completed. A dedicated cloud firewall permits inbound SSH only; database, broker and API ports are not exposed publicly. The server's ED25519 host fingerprint was independently verified through DigitalOcean's authenticated Web Console before SSH: `SHA256:Jm3n0W3ncddd+viFM8avVYw6NKnp4IPWmPWtWI305GA`.

GitHub repository variable `DROPLET_HOST` and secrets `DROPLET_SSH_KEY` / `DROPLET_KNOWN_HOSTS` are configured. A successful default-branch push workflow triggers the tested-image deployment. **The first live application release is pending at this documentation revision**; check the subsequent deployment workflow and `/opt/notification-fanout/current` on the server for its outcome. Infrastructure creation and green Compose CI do not alone prove a successful live application release.

No domain or receiver origin has been provided yet. API access defaults to an SSH tunnel; the public HTTPS proxy stays disabled. The generated webhook allowlist deliberately points at an invalid placeholder until an approved HTTPS receiver origin is configured. A real subscriber round trip, public TLS, reboot recovery, off-host backup restore, monitoring notifications and representative load remain to be validated on the live cloud deployment.

The single-server deployment has no high availability or broker replication. Internal PostgreSQL/Kafka traffic is plaintext on one Docker bridge under an explicit single-host configuration. Secrets remain on the server. Follow [the Droplet guide](../deploy/droplet/README.md) for the trust boundary, least-privilege database setup, backup and rollback procedures. The older managed App Platform scripts remain an unused alternative, disabled without its separate app ID.

## Remaining scope

Per-source ordering and replay remain extensions. The short earlier load probes describe the pre-Kafka baseline and do not establish current throughput or 10,000 events/second. Some original design/validation sections are historical; use architecture.md for the current messaging and deployment flow.
