# Signed GitHub deployment to the Bangalore Droplet

The active deployment uses one Ubuntu 24.04 Droplet in `blr1`, regular Basic `s-2vcpu-4gb`, displayed base price $24/month. Kafka/PostgreSQL are self-hosted. No managed Kafka, managed DB, App Platform or registry is required. The current server is `notification-fanout`, IP `64.227.168.29`, in the authorized team `Blitz_team13_Hyd`. No additional paid resources are introduced by this release path. Powering off a Droplet does not stop its billing.

## First setup

Development commands belong in the Rancher container at `/workspaces/notification-fanout`. `provision.sh` is an explicit paid-infrastructure action, refuses a duplicate Droplet name and checks plan availability/base price <=$24 before creation. Reuse the existing server; do not provision it again. Its `bootstrap.sh` installs Docker/Compose from the official Ubuntu Docker repository, creates root-only random secrets, and disables password SSH.

Independently verify the ED25519 SSH host fingerprint through the DigitalOcean Web Console before saving a scanned key. The current fingerprint is `SHA256:Jm3n0W3ncddd+viFM8avVYw6NKnp4IPWmPWtWI305GA`. Pin it in `.deploy/known_hosts`. The existing `.deploy/droplet-deploy` key is the privileged **operator** key; it is not the new CI key.

Generate an independent CI SSH key and Ed25519 release signing key inside ignored `.deploy/`, and retain them securely:

```bash
ssh-keygen -t ed25519 -N '' -f .deploy/ci-deploy
openssl genpkey -algorithm ED25519 -out .deploy/release-signing.pem
chmod 600 .deploy/release-signing.pem
openssl pkey -in .deploy/release-signing.pem -pubout -out .deploy/release-signing.pub
```

Using the verified operator connection, copy **only** the CI public key, release public key, reviewed `promote.py` and `setup-deployer.sh` to `/root/` on the server. Run `bash /root/setup-deployer.sh /root/ci-deploy.pub /root/release-signing.pub /root/promote.py`. It creates the restricted `notification-deploy` user and root-owned gateway, installs the public signature trust key, permits sudo only for that gateway, and ensures a separate ADMIN_TOKEN exists in `.env`. The upload user has no Docker group membership and cannot read root's environment file. Its authorized key uses OpenSSH `restrict` to disable interactive forwarding/PTY features. Keep the operator key for controlled administration.

## Repository connection

|Setting|Value|
|---|---|
|Variable `DROPLET_HOST`|64.227.168.29|
|Secret `DROPLET_SSH_KEY`|Dedicated `.deploy/ci-deploy` private key, **not** the operator key|
|Secret `DROPLET_KNOWN_HOSTS`|Independently verified host-key line|
|Secret `RELEASE_SIGNING_KEY`|Ed25519 `.deploy/release-signing.pem` contents|

Upload secrets with `gh secret set NAME --repo Sahil2237/notification-fanout < FILE` without printing them. The current default branch is `codex/notification-fanout`; workflows follow the repository default if it changes. Pull requests are verified but never signed/deployed as releases. Protect repository write access and workflow changes: trusted CI can sign new application code.

CI runs the Java and gateway-security tests, builds one Linux-amd64 image, scans for fixable HIGH/CRITICAL findings, validates Caddy, executes real Compose startup and runtime DB permission checks, then tests a failed-startup rollback and backup restore. It packages this **same** image with its image ID, Git revision, configuration and checksums. Only a trusted default-branch push receives the signing secret. The bundled Compose file also pins PostgreSQL, Kafka and Caddy to the exact image digests used by that CI runner. The deploy workflow downloads the artifact from that successful run and transfers it over pinned SSH; it does not rebuild or clone source on the server.

The root-owned promotion gateway accepts only signed bundles with the expected filenames, regular files, bounded size, checksum/revision/image identity and immutable revision directory. It rejects symlink/traversal/tampering. The SSH key alone cannot sign a new privileged release; possession of a valid old signed release can permit redeployment, so protect upload access and rotate keys after compromise. Retained signed artifacts are also the rollback source.

## Environment, API access and HTTPS

The root-only `/opt/notification-fanout/.env` contains independent DB owner/runtime, tenant API, administrator and webhook-signing secrets. Do not commit it or put its values in chat. `WEBHOOK_ORIGINS` seeds previously unknown origins on startup; runtime admin API changes are authoritative and revoked entries are not resurrected by that seed.

Set `TLS_DOMAIN` to a domain pointing at the server, or to its public IP when using the included explicit Let's Encrypt `shortlived` ACME issuer. Public-IP certificates are short-lived and require reliable automatic renewal; Caddy's certificate storage is persistent. Open TCP 80/443 in the cloud firewall before enabling HTTPS. Port 80 handles validation/redirects; requests with API tokens belong on HTTPS only. The release checks the real certificate and HTTPS readiness before success. Public Caddy routes return 404 for `/v1/admin/*`; those remain private over SSH. Direct API port 8080 is bound to host loopback; admin, PostgreSQL and Kafka ports are not publicly published.

Without public HTTPS, or for private administrator calls, open a tunnel from the container:

```bash
cd /workspaces/notification-fanout
ssh -N -i .deploy/droplet-deploy -o UserKnownHostsFile=.deploy/known_hosts \
  -o StrictHostKeyChecking=yes -L 127.0.0.1:18080:127.0.0.1:8080 root@64.227.168.29
```

Use `http://127.0.0.1:18080` from another container terminal; forward port 18080 through IntelliJ to access it in the Mac browser. Tenant APIs use API_TOKEN, operator origin APIs use ADMIN_TOKEN. Use a secure operator channel to provision derived signing keys to real receivers. The free Webhook.site receiver is a temporary synthetic-data demonstration, not a production subscriber.

## Operations and recovery

Current release is `/opt/notification-fanout/current`. Use APP_IMAGE from its release SHA and `docker compose --project-name notifications --env-file /opt/notification-fanout/.env -f /opt/notification-fanout/current/compose.yaml ...` for bounded logs, status and controlled restart. Every release saves a pre-migration PostgreSQL backup, applies forward migrations/grants, gates readiness/TLS and attempts application rollback on failure. CI tests that rollback. Schema changes must stay backward-compatible; database migrations are never automatically reversed.

`backup.sh` writes a root-protected, consistent dump. A same-server restore is tested, but encrypted off-host backups and secrets escrow require an approved storage destination. A disk/Droplet failure can destroy both data and local backups. Event/audit retention is not automated; monitor disk growth and backup age. Never use `down --volumes` on the real server. Retain current/previous images and signed artifacts; do not replace an existing Git revision with different image contents.

This deployment deliberately has one host, one broker and one DB. No host failover, zero-downtime release or numerical disaster RPO/RTO is claimed. Internal DB/Kafka traffic is plaintext on the dedicated single-host bridge; migrate to authenticated TLS when crossing machines. Configure external alerting and validate representative fanout capacity before making availability/performance commitments. Full procedures are in docs/operations.md and evidence in docs/submission-status.md.
