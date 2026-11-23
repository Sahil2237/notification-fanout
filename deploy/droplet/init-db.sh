#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set runtime_password="$DB_PASSWORD" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE ROLE notification_runtime LOGIN PASSWORD %L', :'runtime_password') \gexec
SQL
