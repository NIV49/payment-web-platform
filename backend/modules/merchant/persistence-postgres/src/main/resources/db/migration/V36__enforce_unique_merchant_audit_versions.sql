-- One committed aggregate version has exactly one authoritative audit event.
-- V34 upgrade preflight protects the historical status-reason backfill; this
-- forward constraint preserves the invariant for every later write.

DO $$
DECLARE
    current_version TEXT;
BEGIN
    SELECT version
      INTO current_version
      FROM flyway_schema_history
     WHERE success
     ORDER BY installed_rank DESC
     LIMIT 1;

    IF current_version IS DISTINCT FROM '35' THEN
        RAISE EXCEPTION 'V36 blocked: expected exact V35 schema history';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM merchant_audit_event audit
         GROUP BY audit.merchant_id, audit.merchant_version
        HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            'V36 blocked: duplicate Merchant audit version evidence exists';
    END IF;
END
$$;

ALTER TABLE merchant_audit_event
    ADD CONSTRAINT uk_merchant_audit_merchant_version
    UNIQUE (merchant_id, merchant_version);
