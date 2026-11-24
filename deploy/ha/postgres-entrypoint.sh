#!/usr/bin/env bash
set -euo pipefail
install -d -m 0700 -o postgres -g postgres /var/lib/postgresql/ha
exec gosu postgres patroni /config/patroni.json
