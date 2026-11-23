ALTER TABLE deliveries ADD COLUMN dispatch_ready BOOLEAN NOT NULL DEFAULT TRUE;
DROP INDEX deliveries_due;
CREATE INDEX deliveries_due ON deliveries(next_attempt_at,id) WHERE status='pending' AND lease_token IS NULL AND dispatch_ready;
CREATE TABLE event_outbox (
 event_id UUID PRIMARY KEY,
 tenant_id VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 publish_attempts INTEGER NOT NULL DEFAULT 0,
 lease_token UUID,
 lease_expires_at TIMESTAMPTZ,
 published_at TIMESTAMPTZ,
 dispatched_at TIMESTAMPTZ,
 FOREIGN KEY (tenant_id,event_id) REFERENCES events(tenant_id,id),
 CHECK ((lease_token IS NULL)=(lease_expires_at IS NULL))
);
CREATE INDEX outbox_unpublished ON event_outbox(available_at,event_id) WHERE published_at IS NULL;
CREATE TABLE kafka_dead_letters (
 topic VARCHAR(249) NOT NULL,
 partition_id INTEGER NOT NULL,
 record_offset BIGINT NOT NULL,
 payload TEXT,
 error VARCHAR(100) NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(topic,partition_id,record_offset)
);
