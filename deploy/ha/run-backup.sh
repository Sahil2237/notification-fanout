#!/usr/bin/env bash
set -euo pipefail
root=/opt/notification-fanout/ha
exec 9>/run/notification-backup.lock
flock -n 9 || exit 0
image=$(cat "$root/tools-image")
install -d -m 0700 "$root/backup-work"
# Jobs have a 30-minute systemd deadline. Remove scratch left by a killed/rebooted job.
find "$root/backup-work" -mindepth 1 -maxdepth 1 -type d -name 'notification-backup.*' -mmin +60 -exec rm -rf -- {} +
work=$(mktemp -d "$root/backup-work/notification-backup.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
# Disk-backed streaming scratch avoids competing with the HA processes for backup-sized RAM.
docker run --rm --network host --memory 384m --cpus 0.5 --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m --cap-drop ALL --security-opt no-new-privileges --env-file "$root/backup.env" -e TMPDIR=/work -v "$work:/work" -v "$root/tls/app/postgres-ca.crt:/tls/postgres-ca.crt:ro" --entrypoint python3 "$image" /opt/ha/spaces-backup.py run
