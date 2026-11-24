#!/usr/bin/env bash
set -euo pipefail
root=/opt/notification-fanout/ha
exec 9>/run/notification-backup.lock
flock -n 9 || exit 0
image=$(cat "$root/tools-image")
exec docker run --rm --network host --read-only --tmpfs /tmp:rw,nosuid,nodev,size=1g --cap-drop ALL --security-opt no-new-privileges --env-file "$root/backup.env" -v "$root/tls/app/postgres-ca.crt:/tls/postgres-ca.crt:ro" --entrypoint python3 "$image" /opt/ha/spaces-backup.py run
