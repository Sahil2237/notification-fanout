ALTER TABLE events ADD COLUMN producer_event_id UUID;
ALTER TABLE events ADD COLUMN user_id VARCHAR(128);
ALTER TABLE events ADD COLUMN compartment_id VARCHAR(128);
CREATE UNIQUE INDEX events_producer_identity ON events(tenant_id,user_id,compartment_id,producer_event_id) WHERE producer_event_id IS NOT NULL;
ALTER TABLE subscriptions ADD COLUMN last_claimed_at TIMESTAMPTZ;
CREATE INDEX deliveries_subscriber_leases ON deliveries(subscription_id,lease_expires_at) WHERE status='pending' AND lease_token IS NOT NULL;
CREATE INDEX deliveries_subscriber_due ON deliveries(subscription_id,next_attempt_at,id) WHERE status='pending' AND dispatch_ready AND lease_token IS NULL;
CREATE TABLE fixed_window_limits (
 tenant_id VARCHAR(100) PRIMARY KEY,
 window_number BIGINT NOT NULL,
 request_count INTEGER NOT NULL CHECK(request_count > 0)
);
CREATE TABLE delivery_dead_letters (
 delivery_id UUID PRIMARY KEY REFERENCES deliveries(id),
 tenant_id VARCHAR(100) NOT NULL,
 event_id UUID NOT NULL REFERENCES events(id),
 subscription_id UUID NOT NULL REFERENCES subscriptions(id),
 attempt_count INTEGER NOT NULL,
 reason VARCHAR(128) NOT NULL,
 failed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 lease_token UUID,
 lease_expires_at TIMESTAMPTZ,
 published_at TIMESTAMPTZ,
 publish_attempts INTEGER NOT NULL DEFAULT 0,
 CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL))
);
CREATE INDEX delivery_dead_letters_history ON delivery_dead_letters(tenant_id,failed_at,delivery_id);
CREATE INDEX delivery_dead_letters_unpublished ON delivery_dead_letters(failed_at,delivery_id) WHERE published_at IS NULL;
INSERT INTO delivery_dead_letters(delivery_id,tenant_id,event_id,subscription_id,attempt_count,reason)
 SELECT id,tenant_id,event_id,subscription_id,attempts,'legacy_failed' FROM deliveries WHERE status='failed';
