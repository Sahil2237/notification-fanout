#!/usr/bin/env bash
# Explicitly running this script provisions a paid Droplet. Requires doctl auth init.
set -euo pipefail
umask 077
cd "$(dirname "$0")/../.."
mkdir -p .deploy
region=blr1
size=s-2vcpu-4gb
name=notification-fanout
# Verify account-specific availability and cap before any paid resource is created.
doctl account get --format Status --no-header
plans=$(doctl compute size list --output json)
jq -e --arg size "$size" --arg region "$region"   '.[] | select(.slug==$size and .available and .price_monthly<=24 and (.regions | index($region)))'   <<<"$plans" >/dev/null || { echo 'Selected Bangalore plan unavailable or above $24/month. No Droplet created.'; exit 1; }
existing=$(doctl compute droplet list --output json | jq -r --arg name "$name" '.[] | select(.name==$name) | .id')
[[ -z "$existing" ]] || { echo "Existing Droplet found: $existing. Inspect and reuse it; this script will not create a duplicate."; exit 1; }
key=.deploy/droplet-deploy
[[ -f "$key" ]] || ssh-keygen -q -t ed25519 -N '' -C notification-fanout-github-deploy -f "$key"
key_id=$(doctl compute ssh-key import "notification-fanout-$(date +%s)" --public-key-file "$key.pub" --output json | jq -r '.[0].id')
# Apply the firewall to the tag before creating the Droplet. Only 22/80/443 are public.
if doctl compute firewall list --output json | jq -e '.[] | select(.name=="notification-fanout")' >/dev/null; then
  echo 'An existing notification-fanout firewall needs review; refusing to reuse unknown rules.' >&2
  exit 1
else
  doctl compute firewall create --name notification-fanout --tag-names notification-fanout     --inbound-rules 'protocol:tcp,ports:22,address:0.0.0.0/0 protocol:tcp,ports:80,address:0.0.0.0/0 protocol:tcp,ports:443,address:0.0.0.0/0'     --outbound-rules 'protocol:tcp,ports:all,address:0.0.0.0/0 protocol:udp,ports:all,address:0.0.0.0/0 protocol:icmp,address:0.0.0.0/0' >/dev/null
fi
doctl compute droplet create "$name" --region "$region" --size "$size" --image ubuntu-24-04-x64   --ssh-keys "$key_id" --tag-names notification-fanout --enable-monitoring   --user-data-file deploy/droplet/bootstrap.sh --wait --output json > .deploy/droplet.json
jq '[.[]|{id,name,networks}]' .deploy/droplet.json
printf 'Wait for cloud-init to finish via SSH before the first release. Key: %s\n' "$key"
