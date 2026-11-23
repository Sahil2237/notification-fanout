#!/usr/bin/env bash
# Ubuntu 24.04 cloud-init user data. No account tokens or application secrets.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openssl unattended-upgrades
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat >/etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
install -d -m 0700 /opt/notification-fanout
cat >/etc/ssh/sshd_config.d/00-notifications.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
sshd -t
systemctl reload ssh
# Keep generated credentials only on the Droplet. No API tokens in cloud-init.
if [[ ! -e /opt/notification-fanout/.env ]]; then
  umask 077
  {
    printf 'PG_OWNER_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    printf 'API_TOKEN=%s\n' "$(openssl rand -hex 32)"
    printf 'WEBHOOK_SIGNING_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'WEBHOOK_ORIGINS=["https://hooks.example.invalid"]\n'
    printf 'TLS_DOMAIN=\n'
  } >/opt/notification-fanout/.env
fi
