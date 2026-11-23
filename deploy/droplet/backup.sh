#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/opt/notification-fanout
release_dir=$(readlink -f "$root/current")
export APP_IMAGE="notification-fanout:$(basename "$release_dir")"
mkdir -p "$root/backups"
file="$root/backups/notifications-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose --project-name notifications --env-file "$root/.env" -f "$release_dir/compose.yaml" exec -T db pg_dump -U notification_owner -d notifications -Fc > "$file.tmp"
mv "$file.tmp" "$file"
echo "$file (copy off this Droplet; local backups do not survive Droplet loss)"
