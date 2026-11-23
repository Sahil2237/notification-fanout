# GitHub to a Bangalore Droplet

This is the active low-cost deployment path. It uses **one Ubuntu 24.04 Droplet**, Docker Compose, the Java service, self-hosted Kafka and self-hosted PostgreSQL. No managed Kafka, managed database, App Platform or paid registry is required. GitHub Actions transfers the tested container image over SSH; the private repository and its GitHub token are never cloned onto the server.

## Size and limits

Start with `s-2vcpu-4gb` in `blr1`: the regular Basic plan is listed at **$24/month**, before optional backups, tax or excess transfer. Provisioning checks availability and refuses a listed base price above $24. The 2 GB plan costs less but leaves little room for two JVMs, PostgreSQL, Linux and deployment overlap. This recommendation is an assessment starting point, not a measured throughput commitment.

There is one application process and one broker with six partitions. One Droplet is a single failure boundary: no server failover, no broker replication, brief downtime during releases. Persistent Docker volumes survive container replacement and reboot, not disk/Droplet loss. Production availability requires more infrastructure. See [DigitalOcean pricing](https://www.digitalocean.com/pricing/droplets).

## One-time account and server setup

Run development commands from the Rancher Ubuntu container at `/workspaces/notification-fanout`.

1. Run `doctl auth init` interactively. Never put its token in chat, Git, shell history or workflow YAML.
2. Inspect existing Droplets first: `doctl compute droplet list`. Reuse a suitable server instead of provisioning twice.
3. To create the documented paid Droplet: `bash deploy/droplet/provision.sh`. It checks the plan, creates a dedicated SSH key in ignored `.deploy/`, configures a tagged cloud firewall, and creates the server. It does not deploy the app yet. Do not reuse the generated deployment key for personal administration. Record the resulting Droplet ID/IP and firewall ID for cleanup.
4. Confirm the SSH host-key fingerprint through DigitalOcean's Droplet Console (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`). Collect the host key with `ssh-keyscan -t ed25519 IP`, compare its fingerprint, then save the verified line in `.deploy/known_hosts`. Scanning alone does not authenticate a server. All SSH commands below must use this file and `StrictHostKeyChecking=yes`.
5. Connect with `ssh -i .deploy/droplet-deploy -o UserKnownHostsFile=.deploy/known_hosts -o StrictHostKeyChecking=yes root@IP`. Run `cloud-init status --wait` and `docker compose version`. Bootstrap uses the [official Docker Ubuntu repository](https://docs.docker.com/engine/install/ubuntu/) and disables password-based SSH.
6. Edit `/opt/notification-fanout/.env` on the server. Independent 256-bit owner, runtime, API and signing secrets were generated locally by bootstrap. Replace `WEBHOOK_ORIGINS` with a JSON array of your approved public HTTPS receiver origins. Keep `TLS_DOMAIN=` blank for tunnel-only access, or set a domain you own after its DNS A record points to this server. Keep `.env` owned by root with mode 600. Treat shell access and Docker access as server-administrator privileges.

The provisioning script allows inbound TCP 22, 80 and 443. The currently created server has a stricter SSH-only firewall because no HTTPS domain has been supplied; add 80/443 rules when enabling public HTTPS. Port 8080 binds only to host loopback; PostgreSQL, Kafka and the Dropwizard admin port have no host-published ports. SSH uses key authentication. GitHub-hosted runners have changing addresses; for stronger SSH restrictions use a private runner/VPN or maintained runner allowlist.

## Connect the private GitHub repository

In `Sahil2237/notification-fanout`, configure these Actions settings (repository settings → Secrets and variables → Actions):

| Kind | Name | Value |
|---|---|---|
| Variable | `DROPLET_HOST` | Verified public IPv4 address or hostname |
| Secret | `DROPLET_SSH_KEY` | The dedicated deployment private key |
| Secret | `DROPLET_KNOWN_HOSTS` | The verified SSH host-key line |

From the container, after verifying the server identity, these commands upload the key files without printing them:

```bash
gh secret set DROPLET_SSH_KEY --repo Sahil2237/notification-fanout < .deploy/droplet-deploy
gh secret set DROPLET_KNOWN_HOSTS --repo Sahil2237/notification-fanout < .deploy/known_hosts
gh variable set DROPLET_HOST --repo Sahil2237/notification-fanout --body YOUR_VERIFIED_IP
```

Setting `DROPLET_HOST` enables deployments on subsequent successful default-branch push workflows. The current default branch is `codex/notification-fanout`; the workflow follows the repository default if it changes. Only pushes from this repository and the default branch qualify. Pull requests do not deploy. `Java 25 verification` must pass both the application suite and the real Droplet Compose smoke job before deployment starts. The managed App Platform workflow remains disabled without its separate app ID; leave it unset.

Push a reviewed commit after configuration. Deployment builds a Linux amd64 image from the tested commit, pins the SSH server identity, transfers an immutable release, waits for database/broker readiness, creates the topic, applies migrations, grants runtime DML privileges, then replaces the app. The server serializes releases with a lock. Application secrets stay on the server. The CI deployment key gives administrator access to this Droplet; protect repository write access, workflow changes and secrets, and rotate this dedicated key if exposed.

## Access and verification

Without a domain, keep API traffic inside SSH:

```bash
ssh -i .deploy/droplet-deploy -o UserKnownHostsFile=.deploy/known_hosts \
  -o StrictHostKeyChecking=yes -L 127.0.0.1:18080:127.0.0.1:8080 root@YOUR_IP
```

Use `http://127.0.0.1:18080/health/ready` through that tunnel from a second terminal on the same machine. When SSH runs inside the Rancher container, run the check in another container terminal; forward port 18080 through IntelliJ if accessing it from the Mac browser. Use the server's API bearer token for `/v1` requests. For public HTTPS, configure your domain/DNS and `TLS_DOMAIN` before the next release; Caddy obtains and renews a certificate. Verify `https://YOUR_DOMAIN/health/ready` and a real signed webhook round trip. HTTPS certificate issuance and external receiver delivery require live cloud verification.

Internal database and broker traffic is plaintext on the single-host Docker bridge. `config/droplet.yml` explicitly enables this trust model, permits only `db:5432` and `kafka:9092`, retains non-demo credentials and public HTTPS webhook restrictions, and uses a database runtime role that cannot change the schema. The managed production configuration still requires TLS. Do not reuse single-host mode across servers or expose the Docker network publicly. A server administrator can read application data and secrets.

## Operations and recovery

- Current release: `/opt/notification-fanout/current`; environment/secrets: `/opt/notification-fanout/.env`.
- On the server, set `APP_IMAGE=notification-fanout:COMMIT_SHA` and use `docker compose --project-name notifications --env-file /opt/notification-fanout/.env -f /opt/notification-fanout/current/compose.yaml ...` for `ps`, `logs --tail 100`, and `restart app`. Container logs rotate. Check disk usage, memory, pending deliveries, old outbox records and failed attempts. Host monitoring is installed, but notification alerts still need account-specific configuration.
- Run `bash /opt/notification-fanout/current/backup.sh` for a consistent PostgreSQL custom-format dump. Copy it and the root-only `.env` to encrypted **off-host** storage. Automate and test restores before real production use. A local dump is not disaster recovery. Retention of application/audit rows is not yet automated; monitor disk growth.
- To restore, stop app delivery, restore the dump into a fresh migrated-compatible PostgreSQL database with `pg_restore`, restore the runtime role password and grants, recreate the Kafka topic, and start the publisher/consumer. PostgreSQL contains events and delivery/outbox state; undispatched events are republished. Unknown webhook outcomes can duplicate delivery. Validate counts and a real receiver before resuming traffic. RPO is the latest verified off-host backup; no live RTO is claimed yet.
- A failed readiness check attempts to restart the previous application release. Migrations are **not** reversed: use only backward-compatible schema changes. For manual rollback, rerun `release.sh PREVIOUS_SHA` from its retained release directory after restoring its image archive or exporting the retained local image. Never use `docker compose down --volumes` on this server. Old image/release cleanup is manual; retain at least the current and previous images.
- Rotating PostgreSQL passwords requires `ALTER ROLE` as well as updating `.env`; changing the file alone does not update an existing database volume.
- To stop billing, explicitly destroy the Droplet when finished; powering it off does not stop billing. Review snapshots/backups/other resources separately before deletion. Never destroy a server without confirming backup and data-retention needs.

## Verification status

See the GitHub Actions runs and `docs/submission-status.md`. The repository's smoke test validates the real Compose stack; it does not prove that your DigitalOcean account has been provisioned, DNS is configured, or backups have been restored. No deployment is complete until a live URL/tunnel, ingestion, Kafka dispatch, signed webhook, audit query and server restart have been checked.
