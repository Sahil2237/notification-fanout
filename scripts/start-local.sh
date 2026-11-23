#!/usr/bin/env bash
set -euo pipefail
cd /workspaces/notification-fanout
mkdir -p .run
runtime_jar=".run/notification-$(date +%s)-$$.jar"
cp target/notification-fanout.jar "$runtime_jar"
exec java -jar "$runtime_jar" server config/local.yml
