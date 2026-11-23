#!/usr/bin/env bash
set -euo pipefail
kafka_home=/workspaces/.tools/kafka
config=/workspaces/notification-fanout/config/kafka-local.properties
if [[ ! -f /workspaces/.notification-kafka-data/meta.properties ]]; then
  cluster_id=$("$kafka_home/bin/kafka-storage.sh" random-uuid)
  "$kafka_home/bin/kafka-storage.sh" format -t "$cluster_id" -c "$config"
fi
exec "$kafka_home/bin/kafka-server-start.sh" "$config"
