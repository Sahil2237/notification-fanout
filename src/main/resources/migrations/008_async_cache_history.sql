-- Event identity remains authoritative beyond optional Redis expiry.
CREATE UNIQUE INDEX events_tenant_producer ON events(tenant_id,producer_event_id) WHERE producer_event_id IS NOT NULL;
CREATE TABLE subscription_versions (
 tenant_id VARCHAR(100) PRIMARY KEY,
 version BIGINT NOT NULL CHECK(version>0)
);
INSERT INTO subscription_versions SELECT tenant_id,1 FROM subscriptions GROUP BY tenant_id;

-- Permanent lightweight identity/FK anchor survives movement from hot work to history.
CREATE TABLE delivery_identities (
 id UUID PRIMARY KEY,
 tenant_id VARCHAR(100) NOT NULL,
 event_id UUID NOT NULL,
 subscription_id UUID NOT NULL,
 UNIQUE(event_id,subscription_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES events(tenant_id,id),
 FOREIGN KEY(tenant_id,subscription_id) REFERENCES subscriptions(tenant_id,id)
);
INSERT INTO delivery_identities SELECT id,tenant_id,event_id,subscription_id FROM deliveries;
ALTER TABLE delivery_attempts DROP CONSTRAINT delivery_attempts_delivery_id_fkey;
ALTER TABLE delivery_attempts ADD FOREIGN KEY(delivery_id) REFERENCES delivery_identities(id);
ALTER TABLE delivery_dead_letters DROP CONSTRAINT delivery_dead_letters_delivery_id_fkey;
ALTER TABLE delivery_dead_letters ADD FOREIGN KEY(delivery_id) REFERENCES delivery_identities(id);
CREATE TABLE delivery_history (LIKE deliveries INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE delivery_history ADD PRIMARY KEY(id);
ALTER TABLE delivery_history ADD FOREIGN KEY(id) REFERENCES delivery_identities(id);
ALTER TABLE deliveries ADD FOREIGN KEY(id) REFERENCES delivery_identities(id);
CREATE INDEX delivery_history_event ON delivery_history(tenant_id,event_id,created_at,id);
CREATE INDEX delivery_history_subscription ON delivery_history(tenant_id,subscription_id,created_at,id);
ALTER TABLE deliveries ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE delivery_history ADD COLUMN completed_at TIMESTAMPTZ;
UPDATE deliveries SET completed_at=COALESCE(delivered_at,clock_timestamp()) WHERE status<>'pending';
CREATE INDEX deliveries_terminal_archive ON deliveries(completed_at,id) WHERE status IN ('delivered','failed');
CREATE VIEW all_deliveries AS SELECT * FROM deliveries UNION ALL SELECT * FROM delivery_history;
