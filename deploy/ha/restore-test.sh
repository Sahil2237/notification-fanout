#!/usr/bin/env bash
# Restore into a disposable network-isolated PostgreSQL, never over a live database.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || exit 1
manifest=${1:?Spaces completion manifest key}
[[ "$manifest" =~ ^postgres/daily/v1/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}\.json$ ]] || exit 1
root=/opt/notification-fanout/ha
image=$(cat "$root/tools-image")
work=$(mktemp -d /opt/notification-restore.XXXXXXXX)
name=notification-restore-$(openssl rand -hex 6)
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; rm -rf -- "$work"; }
trap cleanup EXIT
docker run --rm --network host --memory 384m --read-only --tmpfs /tmp:rw,nosuid,nodev,size=1g --cap-drop ALL --security-opt no-new-privileges --env-file "$root/backup.env" -v "$root/age-identity:/identity:ro" -v "$work:/restore" --entrypoint python3 "$image" /opt/ha/spaces-backup.py fetch --manifest "$manifest" --output /restore/data --identity /identity >"$work/manifest.json"
docker run -d --name "$name" --network none --memory 768m --tmpfs /var/lib/postgresql/data:rw,nosuid,size=512m -e POSTGRES_PASSWORD="$(openssl rand -hex 32)" --entrypoint docker-entrypoint.sh "$image" postgres >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if docker exec "$name" pg_isready -U postgres -d postgres >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]]
docker exec "$name" createdb -U postgres notification_restore
docker exec -i "$name" pg_restore --exit-on-error --no-owner --no-acl -U postgres -d notification_restore <"$work/data/database.dump"
python3 - "$work/data/snapshot.json" "$name" <<'PYCODE'
import json,pathlib,subprocess,sys
snapshot=json.loads(pathlib.Path(sys.argv[1]).read_text());container=sys.argv[2]
allowed={'events','subscriptions','deliveries','delivery_attempts','event_outbox','approved_webhook_origins','webhook_origin_audit','kafka_dead_letters','databasechangelog'}
assert set(snapshot['row_counts']) in (allowed, allowed | {'delivery_dead_letters'}, allowed | {'fixed_window_limits','delivery_dead_letters'}, allowed | {'delivery_dead_letters','subscription_versions','delivery_identities','delivery_history'})
for table,expected in snapshot['row_counts'].items():
    actual=int(subprocess.check_output(['docker','exec',container,'psql','-U','postgres','-d','notification_restore','-Atc','SELECT count(*) FROM '+table],text=True).strip());assert actual==expected,(table,expected,actual)
print(json.dumps({'restored_and_verified':True,'snapshot_created_at':snapshot['created_at'],'row_counts':snapshot['row_counts'],'network_isolated_restore':True},indent=2))
PYCODE
