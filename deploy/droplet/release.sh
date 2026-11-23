#!/usr/bin/env bash
# Runs on the Droplet, from an immutable GitHub Actions release directory.
set -euo pipefail
umask 077
[[ "$(id -u)" == 0 ]] || { echo 'Run as root on the Droplet'; exit 1; }
release_sha=${1:?Usage: release.sh TESTED_COMMIT_SHA}
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]] || exit 1
root=/opt/notification-fanout
release_dir=$(cd -- "$(dirname -- "$0")" && pwd)
[[ "$release_dir" == "$root/releases/$release_sha" ]] || exit 1
mkdir -p "$root"
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is running'; exit 1; }
test -f "$root/.env"
chmod 600 "$root/.env"
export APP_IMAGE="notification-fanout:$release_sha"
compose=(docker compose --project-name notifications --env-file "$root/.env" -f "$release_dir/compose.yaml")
"${compose[@]}" config --quiet
# Public ingress is opt-in: blank TLS_DOMAIN keeps the API on host loopback.
public_domain=$(sed -n 's/^TLS_DOMAIN=//p' "$root/.env" | tr -d '
')
if [[ -n "$public_domain" ]]; then
  [[ "$public_domain" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ ]] || { echo 'Invalid TLS_DOMAIN'; exit 1; }
  compose+=(--profile public)
fi
previous=$(readlink -f "$root/current" || true)
previous_proxy_running=$(docker inspect notifications-proxy-1 --format '{{.State.Running}}' 2>/dev/null || true)
rollback() {
  if [[ -n "$previous" && "$previous" != "$release_dir" && -f "$previous/compose.yaml" ]]; then
    export APP_IMAGE="notification-fanout:$(basename "$previous")"
    old=(docker compose --project-name notifications --env-file "$root/.env" -f "$previous/compose.yaml")
    [[ -z "$public_domain" ]] || old+=(--profile public)
    "${old[@]}" up -d --no-deps app || true
    if [[ -n "$public_domain" ]]; then
      if [[ "$previous_proxy_running" == true ]]; then "${old[@]}" up -d --no-deps proxy || true
      else docker stop notifications-proxy-1 >/dev/null 2>&1 || true; fi
    fi
    echo 'Attempted application rollback. Database migrations are not reversed.' >&2
  fi
}
trap rollback ERR
test "$(cat "$release_dir/release-sha")" = "$release_sha"
(cd "$release_dir" && sha256sum --check --status SHA256SUMS)
docker load -i "$release_dir/image.tar.gz"
python3 "$release_dir/verify-image.py" "$release_dir/image.tar.gz" "$(cat "$release_dir/image-id")" "$APP_IMAGE" > "$release_dir/verified-image.json"
# Bind-mounted initialization/configuration files must be readable by unprivileged container users.
chmod 0644 "$release_dir"/{init-db.sh,grants.sql,Caddyfile}
if [[ -n "$previous" && -f "$previous/backup.sh" ]]; then bash "$previous/backup.sh"; fi
"${compose[@]}" up -d --wait --wait-timeout 300 db kafka
"${compose[@]}" run --rm --no-deps kafka-init
"${compose[@]}" run --rm --no-deps migrate
"${compose[@]}" run --rm --no-deps grants
"${compose[@]}" up -d --no-deps app
ready=false
ready_attempts=${RELEASE_READY_ATTEMPTS:-90}
[[ "$ready_attempts" =~ ^[0-9]+$ && "$ready_attempts" -ge 1 && "$ready_attempts" -le 90 ]] || exit 1
for attempt in $(seq 1 "$ready_attempts"); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'New API did not become ready' >&2; false; }
if [[ -n "$public_domain" ]]; then
  "${compose[@]}" up -d --no-deps proxy
  curl --fail --silent --show-error --retry 15 --retry-all-errors --retry-delay 2 --max-time 3 \
    --connect-to "$public_domain:443:127.0.0.1:443" "https://$public_domain/health/ready" >/dev/null
fi
ln -sfn "$release_dir" "$root/current"
trap - ERR
rm -f "$release_dir/image.tar.gz"
echo "Deployed $release_sha; API ready on 127.0.0.1:8080"
