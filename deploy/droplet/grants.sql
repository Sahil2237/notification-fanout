REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE notifications TO notification_runtime;
GRANT USAGE ON SCHEMA public TO notification_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON events, subscriptions, deliveries,
    delivery_attempts, event_outbox, kafka_dead_letters, delivery_dead_letters TO notification_runtime;
GRANT SELECT, INSERT, UPDATE ON approved_webhook_origins TO notification_runtime;
GRANT INSERT ON webhook_origin_audit TO notification_runtime;
