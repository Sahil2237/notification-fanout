# Restricted SSH with a temporary CI runner rule

## Prepared scope — not activated yet

The current broad SSH-source rule is retained until the new CI path is credentialed and tested. GitHub-hosted runners have changing source addresses. Restricting port 22 to only the workstation would break automatic deployment.

Reviewed implementation: [jit-ssh.py](../deploy/ha/jit-ssh.py), its [tests](../deploy/ha/test_jit_ssh.py), and [deployment workflow](../.github/workflows/deploy-ha.yml). The workflow variable `JIT_SSH_ENABLED` defaults to disabled. No new token has been created or copied by preparing this code.

## Activation plan

1. Obtain explicit permission to create a 90-day DigitalOcean token with **firewall:read** and **firewall:update** only, in Blitz_team13_Hyd. The update scope requires read. It can change team firewall rules and therefore must be protected; it cannot manage Droplets or read database credentials. [Provider scope contract](https://docs.digitalocean.com/reference/api/scopes/firewall/update/).
2. Store it only as repository Actions secret `DO_FIREWALL_TOKEN` in Sahil2237/notification-fanout. Do not store it on Droplets, print it, commit it, or reuse the metrics credential.
3. Enable `JIT_SSH_ENABLED=true`. Manually dispatch `deploy-ha.yml` with a trusted verification run and `access_only=true`; it checks SSH on all three hosts without downloading images or restarting applications.
4. Narrow permanent port-22 sources to the verified operator address and approved private management peers, then repeat the CI access-only test and confirm the temporary rule was removed. Preserve key-only login and the existing unprivileged/signed-promotion account.

No paid static runner, VPN, bastion or additional Droplet is introduced. The token is team firewall-scoped, not cryptographically constrained to one firewall; the helper pins the one expected firewall and exact three Droplet IDs in code. Anyone obtaining the token could bypass that helper, so treat it as a sensitive network-control credential and rotate before expiry.

## Workflow behavior

The trusted workflow checks the verification-run origin first. It checks out the workflow's own trusted commit with persisted checkout credentials disabled. The helper calls only the fixed DigitalOcean HTTPS API, refuses redirects, validates firewall membership, and discovers the runner's public IPv4 separately without sending the token to that lookup service.

Before adding a single-IP /32 SSH allowance, it writes intent to a mode-0600 runner-temporary state file. An already-existing exact source rule is never cleanup-owned. Offset/API acknowledgement uncertainty therefore does not make cleanup dependent on a successful open response. Bounded propagation checks precede use/removal confirmation. The workflow uses `always()` to remove its owned rule and delete its SSH key files, including after normal failure/cancellation.

This is best-effort job cleanup, **not a provider-native rule TTL**. A destroyed/unreachable runner can prevent cleanup and leave its source rule behind. The SSH key is still required. Use the logged runner address and Cloud Firewall page to remove that exact temporary source if job cleanup could not run; preserve operator/private-peer sources. A separate expiry/reconciliation service is not deployed. Do not claim guaranteed automatic expiry after catastrophic runner loss.

A new webhook HTTPS port beyond 443 is a separate egress-policy change and is unrelated to this CI SSH path. Code-signature verification and pinned SSH host keys remain required.
