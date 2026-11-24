#!/usr/bin/env bash
# Called only by the existing signature-verifying root promotion gateway.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || exit 1
sha=${1:?Tested revision required}
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || exit 1
root=/opt/notification-fanout
ha=$root/ha
release=$root/releases/$sha
[[ -f "$ha/enabled" && -f "$ha/compose.yaml" ]] || exit 1
exec 9>"$root/deploy.lock"
flock -n 9 || exit 1
(cd "$release" && sha256sum --check --status SHA256SUMS)
[[ $(cat "$release/release-sha") == "$sha" ]] || exit 1
export APP_IMAGE=notification-fanout:$sha
docker load -i "$release/image.tar.gz"
python3 "$release/verify-image.py" "$release/image.tar.gz" "$(cat "$release/image-id")" "$APP_IMAGE" >"$release/verified-image.json"
compose=(docker compose --project-name notification-ha --env-file "$ha/node.env" -f "$ha/compose.yaml")
"${compose[@]}" config --quiet
previous=$(cat "$ha/app-sha" 2>/dev/null || true)
install -d -m 0755 "$ha/health"
# Drain the local backend before replacing it. Other two nodes continue serving.
touch "$ha/health/drain"
sleep 20
rollback() {
  if [[ "$previous" =~ ^[0-9a-f]{40}$ && "$previous" != "$sha" ]]; then
    export APP_IMAGE=notification-fanout:$previous
    "${compose[@]}" up -d --no-deps app || true
    for attempt in $(seq 1 90); do
      if curl -fsS --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then [[ -f "$ha/coordinated-cutover" ]] || rm -f "$ha/health/drain"; break; fi
      sleep 1
    done
  fi
  echo 'HA application promotion failed; database migrations are not reversed' >&2
}
trap rollback ERR
"${compose[@]}" run --rm --no-deps migrate
"${compose[@]}" up -d --no-deps app
ready=false
for attempt in $(seq 1 90); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/health/ready >/dev/null; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]]
# Persist the successful image for later operator Compose commands as well as restarts.
printf 'APP_IMAGE=notification-fanout:%s\n' "$sha" >"$ha/node.env.next"
chmod 0600 "$ha/node.env.next"
mv -f "$ha/node.env.next" "$ha/node.env"
printf '%s\n' "$sha" >"$ha/app-sha"
ln -sfn "$release" "$root/current"
# Initial cross-version cutovers retain the drain until the operator verifies every replica.
[[ -f "$ha/coordinated-cutover" ]] || rm -f "$ha/health/drain"
trap - ERR
sleep 15
rm -f "$release/image.tar.gz"
echo "Promoted HA application $sha on $(hostname)"
