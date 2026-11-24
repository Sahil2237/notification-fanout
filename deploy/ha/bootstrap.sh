#!/usr/bin/env bash
# Ubuntu 24.04 cloud-init user data. No account tokens or application secrets.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openssl unattended-upgrades ufw jq
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
# Refuse to claim zero-loss automatic failover without a working watchdog fence.
modprobe softdog soft_noboot=0
[[ -e /dev/watchdog ]] || { echo 'Watchdog unavailable; HA bootstrap stopped'; exit 1; }
printf 'softdog\n' >/etc/modules-load.d/notification-watchdog.conf
printf 'options softdog soft_noboot=0\n' >/etc/modprobe.d/notification-watchdog.conf
printf 'KERNEL=="watchdog*", OWNER="999", GROUP="999", MODE="0660"\n' >/etc/udev/rules.d/99-notification-watchdog.rules
chown 999:999 /dev/watchdog /dev/watchdog0
chmod 0660 /dev/watchdog /dev/watchdog0
# BEGIN persistent watchdog boot dependency
# Ubuntu cloud images may deny-list softdog in the generic modules loader.
# Explicit modprobe is intentional; Docker must wait for fencing on every boot.
cat >/etc/systemd/system/notification-watchdog.service <<'WATCHDOG'
[Unit]
Description=Required notification cluster watchdog
After=systemd-udev-trigger.service
Before=docker.service
[Service]
Type=oneshot
ExecStart=/sbin/modprobe softdog soft_noboot=0
ExecStartPost=/usr/bin/udevadm settle
ExecStartPost=/bin/chown 999:999 /dev/watchdog /dev/watchdog0
ExecStartPost=/bin/chmod 0660 /dev/watchdog /dev/watchdog0
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
WATCHDOG
install -d -m 0755 /etc/systemd/system/docker.service.d
cat >/etc/systemd/system/docker.service.d/notification-watchdog.conf <<'WATCHDOG'
[Unit]
Requires=notification-watchdog.service
After=notification-watchdog.service
WATCHDOG
systemctl daemon-reload
systemctl enable --now notification-watchdog.service
# END persistent watchdog boot dependency
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
[[ $# -eq 3 ]] || { echo 'Supply exactly three VPC IPv4 peers'; exit 1; }
for peer in "$@"; do
  [[ "$peer" =~ ^10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || exit 1
  for port in 2379:2380 5432 8008 9092:9093; do ufw allow proto tcp from "$peer" to any port "$port"; done
done
ufw allow proto tcp from 10.122.0.0/20 to any port 9080
ufw --force enable
install -d -m 0700 /opt/notification-fanout/ha
