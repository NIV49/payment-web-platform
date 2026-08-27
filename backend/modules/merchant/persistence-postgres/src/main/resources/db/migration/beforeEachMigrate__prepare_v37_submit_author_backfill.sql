-- V37 was first executed on an empty Merchant table. Its application-author
-- backfill also needs a narrowly authenticated V36 lifecycle bridge.

DO $author_bridge$
DECLARE
    current_version TEXT;
    has_direct BOOLEAN;
    has_author_evidence BOOLEAN;
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
        RAISE EXCEPTION 'V37 author bridge blocked: V37 schema evidence already exists';
    END IF;

    LOCK TABLE merchant IN ACCESS EXCLUSIVE MODE;
    SELECT EXISTS (SELECT 1 FROM merchant WHERE merchant_type_code='DIRECT')
      INTO has_direct;
    SELECT EXISTS (
        SELECT 1
          FROM merchant_audit_event audit
          JOIN merchant merchant_row ON merchant_row.id=audit.merchant_id
         WHERE audit.merchant_version=0 AND audit.action_code='SUBMIT')
      INTO has_author_evidence;

    IF EXISTS (
        SELECT 1
          FROM merchant_audit_event audit
          JOIN merchant merchant_row ON merchant_row.id=audit.merchant_id
         WHERE audit.merchant_version=0 AND audit.action_code='SUBMIT'
           AND (audit.actor_account_domain NOT IN ('PLATFORM','MERCHANT')
             OR audit.actor_tenant_id IS NULL OR audit.actor_membership_id IS NULL
             OR NOT EXISTS (
                SELECT 1 FROM iam_tenant tenant
                 WHERE tenant.id=audit.actor_tenant_id
                   AND tenant.account_domain=audit.actor_account_domain)
             OR NOT EXISTS (
                SELECT 1 FROM iam_membership membership
                 WHERE membership.tenant_id=audit.actor_tenant_id
                   AND membership.id=audit.actor_membership_id))
    ) THEN
        RAISE EXCEPTION 'V37 author bridge blocked: incomplete SUBMIT author evidence';
    END IF;

    IF NOT has_author_evidence THEN
        RETURN;
    END IF;

    CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
    RETURNS trigger LANGUAGE plpgsql AS $function$
    DECLARE
        old_row JSONB := to_jsonb(OLD);
        new_row JSONB := to_jsonb(NEW);
    BEGIN
        IF TG_OP='UPDATE'
           AND OLD.merchant_type_code='DIRECT'
           AND NEW.merchant_type_code='PLATFORM'
           AND (old_row-'merchant_type_code')
               IS NOT DISTINCT FROM (new_row-'merchant_type_code') THEN
            RETURN NEW;
        END IF;
        IF TG_OP='UPDATE'
           AND old_row->>'application_source' IS NULL
           AND old_row->>'application_actor_tenant_id' IS NULL
           AND old_row->>'application_author_membership_id' IS NULL
           AND (old_row-ARRAY['application_source','application_actor_tenant_id',
                              'application_author_membership_id']::text[])
               IS NOT DISTINCT FROM
               (new_row-ARRAY['application_source','application_actor_tenant_id',
                              'application_author_membership_id']::text[])
           AND EXISTS (
                SELECT 1 FROM merchant_audit_event audit
                 WHERE audit.merchant_id=OLD.id
                   AND audit.merchant_version=0
                   AND audit.action_code='SUBMIT'
                   AND audit.actor_account_domain=new_row->>'application_source'
                   AND audit.actor_tenant_id=(new_row->>'application_actor_tenant_id')::bigint
                   AND audit.actor_membership_id=
                       (new_row->>'application_author_membership_id')::bigint) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'V37 bridge permits only exact canonicalization or author backfill';
    END;
    $function$;
END
$author_bridge$;
