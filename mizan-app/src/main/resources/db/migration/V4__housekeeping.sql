-- For the daily clean-up: old delivered events and old webhooks are deleted in small batches.
CREATE INDEX outbox_events_done ON outbox_events (created_at) WHERE status IN ('DELIVERED', 'SKIPPED');
