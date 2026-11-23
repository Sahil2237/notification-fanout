# Submission and deployment status

## Hardening candidate (cloud rollout pending verification)

The current changes add durable runtime origin approvals/revocations with separate administrator credentials and audit, 46 Java tests plus 12 signed-bundle/image security tests, a 10,000-record single-type partition test, real slow-receiver and storage-failure tests, pinned CI actions, a fixable HIGH/CRITICAL vulnerability gate, build-once signed image promotion, restricted CI SSH, and CI startup/rollback/restore checks. Local Java and gateway tests passed. The restricted upload account/public signature trust key are installed on the existing Droplet; secrets are not committed.

The next hosted run must prove the image scan, complete container/release test suite and signed-artifact deployment. Public-IP HTTPS, runtime admin operations and representative post-release cloud event/load checks are pending until that run succeeds. This candidate's production limits remain one host/broker/DB, no automated off-host backup, no external paging, no automated data retention, and no measured fanout SLO. Prior evidence below is historical and must not be mistaken for evidence of this candidate.

## Previously verified deployment


The service implements durable REST ingestion, dynamic subscription filters, Kafka outbox publication and idempotent dispatch, retrying signed webhooks, tenant-scoped delivery audits, and health/metrics endpoints. Java 25, Dropwizard 5, PostgreSQL 17 and Kafka 4.3.1 were exercised in Rancher.

Verification: 12 unit tests and 26 PostgreSQL/HTTP/Kafka integration tests pass locally. Hosted run [45239397092](https://github.com/Sahil2237/notification-fanout/actions/runs/45239397092) also passed the application suite and a real Linux-amd64 Docker Compose startup smoke test. The smoke test exercises migrations, runtime DML access with DDL denied, authentication, ingestion and Kafka dispatch using the Droplet configuration. The first startup test caught a database pool-size mismatch; the explicit initial size fixed it. The earlier IntelliJ run preceded Kafka integration; its saved Verify configuration runs the current suite.

## Selected cloud deployment

The user selected **one Bangalore Ubuntu 24.04 Droplet**, with self-hosted Kafka and PostgreSQL, rather than Managed Kafka. The Droplet `notification-fanout` (ID `622664599`, IPv4 `64.227.168.29`) was created in team `Blitz_team13_Hyd`, region `blr1`, regular Basic `s-2vcpu-4gb` (2 vCPU, 4 GB RAM, 80 GB SSD), at the displayed base price of **$24/month**. It incurs charges until destroyed, even if powered off. No managed database, managed Kafka, App Platform app or registry was provisioned for this project.

Ubuntu bootstrap and Docker Compose installation completed. A dedicated cloud firewall permits inbound SSH only; database, broker and API ports are not exposed publicly. The server's ED25519 host fingerprint was independently verified through DigitalOcean's authenticated Web Console before SSH: `SHA256:Jm3n0W3ncddd+viFM8avVYw6NKnp4IPWmPWtWI305GA`.

GitHub repository variable `DROPLET_HOST` and secrets `DROPLET_SSH_KEY` / `DROPLET_KNOWN_HOSTS` are configured. A successful default-branch push workflow triggers the tested-image deployment. **The first live application release succeeded.** [Verification run 45240950427](https://github.com/Sahil2237/notification-fanout/actions/runs/45240950427) and [Droplet deployment run 45241681807](https://github.com/Sahil2237/notification-fanout/actions/runs/45241681807) both passed for deployed revision `0b7907e6dd5a4fe40d5c01322c46ffb8970da4c7`.

Live checks passed for authenticated ingestion, identical idempotency-key retry, conflicting-key rejection, unauthenticated-request rejection, event delivery-history queries and real Kafka dispatch. The entire Droplet was rebooted; Docker restarted the three services automatically, readiness recovered, and the same accepted event survived. A consistent PostgreSQL backup was restored into a newly created temporary database on the same Droplet; the restored event was verified and that temporary database removed. The backup remains on the Droplet. Off-host encrypted backups and disaster-recovery drills remain operational work. Synthetic test state is recorded on the server in `/opt/notification-fanout/live-verification.json`; no API credentials are included in that evidence.

API access uses an SSH tunnel; the public HTTPS proxy stays disabled until an API domain is configured. An explicitly requested free Webhook.site receiver is now registered, and `https://webhook.site` has replaced the invalid placeholder in the server allowlist. The active demo subscription matches `type=order.created`, `source=assessment-demo`, `/synthetic=true` and `/customer/tier="gold"`. A real structured event traversed PostgreSQL, Kafka, matching delivery jobs and an external HTTPS POST. The receiver returned HTTP 204 on the first attempt; the audit recorded `delivered`, and the captured request body, delivery/event IDs, timestamp and HMAC signature were independently verified. No signing secret or API token was sent to the receiver.

Receiver capability URLs and audit IDs are kept in the ignored local `.deploy/external-webhook-verification.json` and server `/opt/notification-fanout/external-webhook-verification.json`, rather than Git. This is a temporary third-party test sink: free URLs expire after seven days and have a 50-request allowance. Anyone knowing the receiver ID can inspect captured data; use synthetic data only. See [Webhook.site free-plan details](https://webhook.site/pricing) and [FAQ](https://docs.webhook.site/). The demo endpoint is not a production subscriber application and does not itself enforce signature checks or deduplicate side effects.

Public API TLS, off-host backup restore, monitoring notifications and representative load remain to be validated. Reboot recovery and a same-server database restore were verified as described above.

The single-server deployment has no high availability or broker replication. Internal PostgreSQL/Kafka traffic is plaintext on one Docker bridge under an explicit single-host configuration. Secrets remain on the server. Follow [the Droplet guide](../deploy/droplet/README.md) for the trust boundary, least-privilege database setup, backup and rollback procedures. The older managed App Platform scripts remain an unused alternative, disabled without its separate app ID.

## Remaining scope

Per-source ordering and replay remain extensions. The short earlier load probes describe the pre-Kafka baseline and do not establish current throughput or 10,000 events/second. Some original design/validation sections are historical; use architecture.md for the current messaging and deployment flow.
