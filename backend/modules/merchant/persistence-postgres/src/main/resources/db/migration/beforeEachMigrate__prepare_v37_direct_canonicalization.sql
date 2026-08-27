-- V37 was first executed successfully on an empty Merchant table before its
-- DIRECT canonicalization had a lifecycle bridge. Preserve the frozen V37
-- checksum and open only the exact historical DIRECT -> PLATFORM update.

DO $bridge$
DECLARE
    current_version TEXT;
    lifecycle_digest TEXT;
    trigger_digest TEXT;
    type_constraint_digest TEXT;
    audit_constraint_digest TEXT;
BEGIN
    SELECT version INTO current_version
      FROM flyway_schema_history
     WHERE success
     ORDER BY installed_rank DESC
     LIMIT 1;

    IF current_version IS DISTINCT FROM '36' THEN
        RETURN;
    END IF;
    IF to_regclass('public.merchant_document') IS NOT NULL
       OR EXISTS (
           SELECT 1 FROM information_schema.columns
            WHERE table_schema='public' AND table_name='merchant'
              AND column_name IN ('brand_name','application_source'))
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='37') THEN
        RAISE EXCEPTION 'V37 bridge blocked: V37 schema evidence already exists';
    END IF;

    LOCK TABLE merchant IN ACCESS EXCLUSIVE MODE;

    SELECT encode(sha256(convert_to(pg_get_functiondef(
               'public.merchant_enforce_lifecycle()'::regprocedure),'UTF8')),'hex')
      INTO lifecycle_digest;
    SELECT encode(sha256(convert_to(pg_get_triggerdef(trigger_row.oid),'UTF8')),'hex')
      INTO trigger_digest
      FROM pg_trigger trigger_row
     WHERE trigger_row.tgrelid='public.merchant'::regclass
       AND trigger_row.tgname='trg_merchant_lifecycle'
       AND trigger_row.tgenabled='O' AND NOT trigger_row.tgisinternal;
    SELECT encode(sha256(convert_to(pg_get_constraintdef(constraint_row.oid),'UTF8')),'hex')
      INTO type_constraint_digest
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid='public.merchant'::regclass
       AND constraint_row.conname='ck_merchant_type_code';
    SELECT encode(sha256(convert_to(pg_get_constraintdef(constraint_row.oid),'UTF8')),'hex')
      INTO audit_constraint_digest
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid='public.merchant_audit_event'::regclass
       AND constraint_row.conname='uk_merchant_audit_merchant_version';

    IF lifecycle_digest IS DISTINCT FROM
           '9e91fb5e591482c4ce98e7bf9cea778c38885ad82ad20a0b98380a40f8061992'
       OR trigger_digest IS DISTINCT FROM
           '9daaafea20e20f974a92aa1beac69d301de8b795132c29a4d746c3a7c2de4f26'
       OR type_constraint_digest IS DISTINCT FROM
           '066c5e43bee0a8a99f1f5624920ccb48a2d1e26ed0c8e8d761efe106410c0808'
       OR audit_constraint_digest IS DISTINCT FROM
           '07e8de1b07d0abac951a2fb94280b441ad2593cb6854c4cd9fc81d040c3d0118' THEN
        RAISE EXCEPTION 'V37 bridge blocked: V36 lifecycle or constraints drifted';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_type_code='DIRECT') THEN
        RETURN;
    END IF;
    CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
    RETURNS trigger LANGUAGE plpgsql AS $function$
    BEGIN
        IF TG_OP='UPDATE'
           AND OLD.merchant_type_code='DIRECT'
           AND NEW.merchant_type_code='PLATFORM'
           AND (to_jsonb(OLD)-'merchant_type_code')
               IS NOT DISTINCT FROM (to_jsonb(NEW)-'merchant_type_code') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'V37 bridge permits only exact DIRECT to PLATFORM canonicalization';
    END;
    $function$;
END
$bridge$;
