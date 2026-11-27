# SSH access and CI boundary — sources retained by user decision

On 27 November 2026 the user selected **Keep SSH sources unchanged**. No new DigitalOcean firewall token was created, no GitHub firewall secret was added, and no temporary-source workflow was activated. The prepared helper/workflow modifications were removed; the original signed deployment workflow remains.

## Current protection and limitation

- Cloud/host TCP 22 remains allowed from all IPv4/IPv6 sources.
- Password and keyboard-interactive SSH authentication are disabled; keys are required.
- CI uses the unprivileged notification-deploy account, pinned host keys, restricted forwarding/PTY features and a root-owned signature-verifying promotion gateway.
- Root/operator key access remains privileged. A reachable SSH port is not an authentication bypass, but broad source reachability is a remaining hardening gap.

The application HTTP/HTTPS and private health ports are separately restricted to the load balancer by the provider firewall, and host egress now has explicit allowances/default-deny. Keeping SSH sources unchanged does not reopen those application ports.

GitHub-hosted deployment runners use changing public IPs. Narrowing SSH to the workstation alone would break the current automation. Future options include a reviewed firewall-only temporary runner rule, static runner/VPN egress or a controlled deployment agent. Each needs a separate access/lifecycle decision; none is silently installed here. No paid runner, bastion, VPN or new credential was introduced.

See [current network policy and evidence](network-hardening.md), [deployment workflow](../.github/workflows/deploy-ha.yml), and [onboarding](onboarding.md) for the access paths actually available.
