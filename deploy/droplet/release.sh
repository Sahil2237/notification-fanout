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
public_domain=$(sed -n 's/^TLS_DOMAIN=//p' "$root/.env" | tr -d '')
if [[ -n "$public_domain" ]]; then
  [[ "$public_domain" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ ]] || { echo 'Invalid TLS_DOMAIN'; exit 1; }
  compose+=(--profile public)
fi
previous=$(readlink -f "$root/current" || true)
rollback() {
  if [[ -n "$previous" && "$previous" != "$release_dir" && -f "$previous/compose.yaml" ]]; then
    export APP_IMAGE="notification-fanout:$(basename "$previous")"
    old=(docker compose --project-name notifications --env-file "$root/.env" -f "$previous/compose.yaml")
    [[ -z "$public_domain" ]] || old+=(--profile public)
    "${old[@]}" up -d --no-deps app || true
    [[ -z "$public_domain" ]] || "${old[@]}" up -d --no-deps proxy || true
    echo 'Attempted application rollback. Database migrations are not reversed.' >&2
  fi
}
trap rollback ERR
docker load -i "$release_dir/image.tar.gz"
"${compose[@]}" up -d --wait --wait-timeout 300 db kafka
"${compose[@]}" run --rm --no-deps kafka-init
"${compose[@]}" run --rm --no-deps migrate
"${compose[@]}" run --rm --no-deps grants
"${compose[@]}" up -d --no-deps app
ready=false
for attempt in $(seq 1 90); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'New API did not become ready' >&2; false; }
if [[ -n "$public_domain" ]]; then "${compose[@]}" up -d --no-deps proxy; fi
ln -sfn "$release_dir" "$root/current"
trap - ERR
rm -f "$release_dir/image.tar.gz"
echo "Deployed $release_sha; API ready on 127.0.0.1:8080"
