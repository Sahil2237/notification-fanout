REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE notifications TO notification_runtime;
GRANT USAGE ON SCHEMA public TO notification_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON events, subscriptions, deliveries,
    delivery_attempts, event_outbox, kafka_dead_letters TO notification_runtime;
