ALTER TABLE iam_permission_change_outbox
    ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN partition_key VARCHAR(128),
    ADD COLUMN trace_id VARCHAR(64);

UPDATE iam_permission_change_outbox
   SET partition_key = aggregate_ref,
       trace_id = COALESCE(NULLIF(payload ->> 'traceId', ''), 'migration-v7');

ALTER TABLE iam_permission_change_outbox
    ALTER COLUMN partition_key SET NOT NULL,
    ALTER COLUMN trace_id SET NOT NULL,
    ADD CONSTRAINT uk_iam_permission_outbox_event_id UNIQUE (event_id),
    ADD CONSTRAINT ck_iam_permission_outbox_aggregate_version CHECK (aggregate_version >= 0),
    ADD CONSTRAINT ck_iam_permission_outbox_schema_version CHECK (schema_version > 0);

ALTER TABLE iam_permission_change_outbox
    ALTER COLUMN aggregate_version DROP DEFAULT,
    ALTER COLUMN schema_version DROP DEFAULT;

CREATE TABLE iam_permission_change_relay_state (
    event_record_id BIGINT PRIMARY KEY REFERENCES iam_permission_change_outbox(id) ON DELETE RESTRICT,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until     TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(1000),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_iam_permission_relay_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_iam_permission_relay_attempts CHECK (attempts >= 0)
);

INSERT INTO iam_permission_change_relay_state
    (event_record_id, status, attempts, available_at, published_at)
SELECT id, status, attempts, available_at, published_at
  FROM iam_permission_change_outbox;

DROP INDEX idx_iam_permission_outbox_pending;

ALTER TABLE iam_permission_change_outbox
    DROP COLUMN status,
    DROP COLUMN attempts,
    DROP COLUMN available_at,
    DROP COLUMN published_at;

CREATE INDEX idx_iam_permission_relay_pending
    ON iam_permission_change_relay_state (status, available_at, event_record_id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE OR REPLACE FUNCTION iam_initialize_permission_relay_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO iam_permission_change_relay_state(event_record_id)
    VALUES (NEW.id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_iam_permission_outbox_initialize_relay
AFTER INSERT ON iam_permission_change_outbox
FOR EACH ROW EXECUTE FUNCTION iam_initialize_permission_relay_state();

CREATE OR REPLACE FUNCTION iam_reject_permission_outbox_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'iam_permission_change_outbox is append-only';
END;
$$;

CREATE TRIGGER trg_iam_permission_outbox_append_only
BEFORE UPDATE OR DELETE ON iam_permission_change_outbox
FOR EACH ROW EXECUTE FUNCTION iam_reject_permission_outbox_mutation();

COMMENT ON TABLE iam_permission_change_outbox IS
    'Append-only permission events; relay delivery state is stored separately';
COMMENT ON TABLE iam_permission_change_relay_state IS
    'Mutable polling relay lease, retry, and publication state for append-only permission events';
