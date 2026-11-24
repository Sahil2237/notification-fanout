-- Run after the migrator creates the application schema. Substitute role names
-- through psql variables, not string concatenation. Do not use the owner as runtime.
-- psql ... -v runtime_role=notification_runtime -v migrator_role=notification_migrator -f this-file
GRANT USAGE ON SCHEMA public TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON events, subscriptions, deliveries, delivery_attempts, event_outbox, kafka_dead_letters, delivery_dead_letters TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE ON approved_webhook_origins TO :"runtime_role";
GRANT INSERT ON webhook_origin_audit TO :"runtime_role";
