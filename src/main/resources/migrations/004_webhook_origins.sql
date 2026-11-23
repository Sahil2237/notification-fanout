CREATE TABLE approved_webhook_origins (
 origin VARCHAR(2048) PRIMARY KEY,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE webhook_origin_audit (
 id UUID PRIMARY KEY,
 origin VARCHAR(2048) NOT NULL,
 action VARCHAR(16) NOT NULL CHECK(action IN ('approved','revoked')),
 actor VARCHAR(32) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX webhook_origin_audit_time ON webhook_origin_audit(created_at,id);
