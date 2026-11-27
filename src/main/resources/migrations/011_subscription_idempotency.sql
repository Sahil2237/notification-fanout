-- Keep identities on the subscription row so pg_dump/restore preserves them automatically.
-- Nullable columns preserve existing registrations and clients that omit a key.
ALTER TABLE subscriptions ADD COLUMN idempotency_key varchar(200), ADD COLUMN creation_hash varchar(64);
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_creation_identity CHECK (
 (idempotency_key IS NULL AND creation_hash IS NULL) OR
 (idempotency_key IS NOT NULL AND length(idempotency_key)>0 AND creation_hash IS NOT NULL AND length(creation_hash)=64)
);
CREATE UNIQUE INDEX subscriptions_tenant_idempotency ON subscriptions(tenant_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
