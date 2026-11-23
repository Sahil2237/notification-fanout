#!/usr/bin/env bash
set -euo pipefail
cd /workspaces/notification-fanout
if [[ "$(. /etc/os-release; echo "$VERSION_ID")" != "24.04" ]]; then
  echo "Expected Ubuntu 24.04 development environment" >&2; exit 1
fi
java -version
mvn -B -ntp verify
