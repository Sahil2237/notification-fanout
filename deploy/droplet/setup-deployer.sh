#!/usr/bin/env bash
# Run as the existing server administrator with reviewed, public key files.
set -euo pipefail
[[ "$(id -u)" == 0 ]] || exit 1
ssh_public=${1:?SSH public-key file}
release_public=${2:?Release-signing public-key file}
gateway=${3:?Reviewed promote.py file}
getent passwd notification-deploy >/dev/null || useradd --create-home --shell /bin/bash notification-deploy
install -d -m 0700 -o notification-deploy -g notification-deploy /home/notification-deploy/.ssh /home/notification-deploy/incoming
[[ "$(cat "$ssh_public")" == ssh-ed25519\ * ]] || exit 1
printf 'restrict %s\n' "$(cat "$ssh_public")" >/home/notification-deploy/.ssh/authorized_keys
chown notification-deploy:notification-deploy /home/notification-deploy/.ssh/authorized_keys
chmod 0600 /home/notification-deploy/.ssh/authorized_keys
install -m 0644 -o root -g root "$release_public" /etc/notification-fanout-release.pub
install -m 0755 -o root -g root "$gateway" /usr/local/sbin/notification-promote
printf 'notification-deploy ALL=(root) NOPASSWD: /usr/local/sbin/notification-promote *\n' >/etc/sudoers.d/notification-deploy
chmod 0440 /etc/sudoers.d/notification-deploy
visudo -cf /etc/sudoers.d/notification-deploy
python3 - <<'PYCODE'
import os,pathlib,secrets
path=pathlib.Path('/opt/notification-fanout/.env')
text=path.read_text()
if not any(line.startswith('ADMIN_TOKEN=') for line in text.splitlines()):
    with path.open('a') as stream:stream.write('\nADMIN_TOKEN='+secrets.token_hex(32)+'\n')
path.chmod(0o600)
PYCODE
id notification-deploy
