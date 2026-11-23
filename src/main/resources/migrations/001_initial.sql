CREATE TABLE subscriptions (
 id UUID PRIMARY KEY,
 tenant_id VARCHAR(100) NOT NULL,
 webhook_url VARCHAR(2048) NOT NULL,
 filter JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 deleted_at TIMESTAMPTZ,
 UNIQUE (tenant_id, id)
);
CREATE INDEX subscriptions_active ON subscriptions(tenant_id, created_at, id) WHERE deleted_at IS NULL;
CREATE TABLE events (
 id UUID PRIMARY KEY,
 tenant_id VARCHAR(100) NOT NULL,
 type VARCHAR(128) NOT NULL,
 source VARCHAR(128) NOT NULL,
 payload JSONB NOT NULL,
 idempotency_key VARCHAR(200),
 content_hash VARCHAR(64) NOT NULL,
 accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (tenant_id, id),
 UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX events_history ON events(tenant_id, accepted_at, id);
CREATE TABLE deliveries (
 id UUID PRIMARY KEY,
 tenant_id VARCHAR(100) NOT NULL,
 event_id UUID NOT NULL,
 subscription_id UUID NOT NULL,
 webhook_url VARCHAR(2048) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','delivered','failed')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 lease_token UUID,
 lease_expires_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 delivered_at TIMESTAMPTZ,
 FOREIGN KEY (tenant_id,event_id) REFERENCES events(tenant_id,id),
 FOREIGN KEY (tenant_id,subscription_id) REFERENCES subscriptions(tenant_id,id),
 UNIQUE (event_id, subscription_id),
 CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL))
);
CREATE INDEX deliveries_due ON deliveries(next_attempt_at,id) WHERE status='pending' AND lease_token IS NULL;
CREATE INDEX deliveries_expired ON deliveries(lease_expires_at) WHERE status='pending' AND lease_token IS NOT NULL;
CREATE INDEX deliveries_event ON deliveries(tenant_id,event_id,created_at,id);
CREATE INDEX deliveries_subscription ON deliveries(tenant_id,subscription_id,created_at,id);
CREATE TABLE delivery_attempts (
 id UUID PRIMARY KEY,
 delivery_id UUID NOT NULL REFERENCES deliveries(id),
 attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finished_at TIMESTAMPTZ,
 http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
 outcome VARCHAR(32) NOT NULL DEFAULT 'started',
 error VARCHAR(128),
 UNIQUE(delivery_id,attempt_number)
);
