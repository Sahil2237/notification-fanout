CREATE INDEX outbox_waiting_dispatch ON event_outbox(published_at) WHERE dispatched_at IS NULL;
