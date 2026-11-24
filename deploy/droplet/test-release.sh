#!/usr/bin/env bash
# Disposable GitHub runner only. Exercises the real release script and cleans its test volumes.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'CI-only destructive fixture'; exit 1; }
root=/opt/notification-fanout
good=${GITHUB_SHA:?}
bad=ffffffffffffffffffffffffffffffffffffffff
source_dir="$PWD/deploy/droplet"
mkdir -p "$root/releases/$good" "$root/releases/$bad"
cat >"$root/.env" <<'EOF'
PG_OWNER_PASSWORD=ci-owner-credential-at-least-32-characters
REDIS_PASSWORD=ci-redis-password-at-least-32-characters
DB_PASSWORD=ci-runtime-credential-at-least-32-characters
API_TOKEN=ci-api-token-at-least-32-characters
ADMIN_TOKEN=ci-admin-token-at-least-32-characters
WEBHOOK_SIGNING_SECRET=ci-signing-secret-at-least-32-characters
WEBHOOK_ORIGINS=["https://example.com"]
TLS_DOMAIN=
EOF
cleanup() {
  APP_IMAGE="notification-fanout:$good" docker compose --project-name notifications --env-file "$root/.env" -f "$root/releases/$good/compose.yaml" logs --tail 60 || true
  APP_IMAGE="notification-fanout:$good" docker compose --project-name notifications --env-file "$root/.env" -f "$root/releases/$good/compose.yaml" down --volumes || true
}
trap cleanup EXIT
bundle() {
  local sha=$1
  cp "$source_dir"/{compose.yaml,init-db.sh,grants.sql,Caddyfile,release.sh,backup.sh,verify-image.py,ha-release.sh} "$root/releases/$sha/"
  chmod 0600 "$root/releases/$sha"/{init-db.sh,grants.sql,Caddyfile}
  docker save "notification-fanout:$sha" | gzip >"$root/releases/$sha/image.tar.gz"
  docker image inspect "notification-fanout:$sha" --format '{{.Id}}' >"$root/releases/$sha/image-id"
  printf '%s\n' "$sha" >"$root/releases/$sha/release-sha"
  (cd "$root/releases/$sha" && sha256sum image.tar.gz image-id release-sha compose.yaml init-db.sh grants.sql Caddyfile release.sh backup.sh verify-image.py ha-release.sh >SHA256SUMS)
}
bundle "$good"
bash "$root/releases/$good/release.sh" "$good"
# Migration works, but the deliberately broken image exits for server startup.
mkdir -p "$root/broken-build"
cat >"$root/broken-build/Dockerfile" <<EOF
FROM notification-fanout:$good
ENTRYPOINT ["/bin/sh", "-c", "if [ \"\$1\" = db ]; then exec java -jar /opt/app/notification-fanout.jar \"\$@\"; else exit 1; fi", "notification"]
EOF
docker build -t "notification-fanout:$bad" "$root/broken-build"
bundle "$bad"
if RELEASE_READY_ATTEMPTS=3 bash "$root/releases/$bad/release.sh" "$bad"; then
  echo 'Broken image unexpectedly deployed' >&2; exit 1
fi
test "$(readlink -f "$root/current")" = "$root/releases/$good"
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/health/ready
# Restore the pre-release backup on this disposable server only.
backup=$(find "$root/backups" -name '*.dump' -type f | head -1)
compose=(docker compose --project-name notifications --env-file "$root/.env" -f "$root/releases/$good/compose.yaml")
export APP_IMAGE="notification-fanout:$good"
"${compose[@]}" exec -T db createdb -U notification_owner notification_restore_check
"${compose[@]}" exec -T db pg_restore --exit-on-error -U notification_owner -d notification_restore_check <"$backup"
"${compose[@]}" exec -T db psql -U notification_owner -d notification_restore_check -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM events'
"${compose[@]}" exec -T db dropdb -U notification_owner notification_restore_check
echo 'Real failed-startup rollback and backup restore passed.'
