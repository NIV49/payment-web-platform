-- V34 derives the current status reason from the audit event at the Merchant's
-- current version. More than one event at the same aggregate version is not
-- authoritative evidence, so reject the upgrade before any backfill occurs.

DO $preflight$
DECLARE
    current_version TEXT;
    audit_columns TEXT[];
    audit_constraints TEXT[];
    canonical_evidence_constraints INTEGER;
BEGIN
    SELECT version
      INTO current_version
      FROM flyway_schema_history
     WHERE success
     ORDER BY installed_rank DESC
     LIMIT 1;

    IF current_version IS DISTINCT FROM '33' THEN
        RETURN;
    END IF;

    IF to_regclass('public.merchant') IS NULL
       OR to_regclass('public.merchant_audit_event') IS NULL
       OR EXISTS (
           SELECT 1
             FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'merchant'
              AND column_name IN ('remarks', 'status_reason_code')) THEN
        RAISE EXCEPTION
            'V34 audit preflight blocked: expected exact V33 Merchant schema';
    END IF;

    SELECT array_agg(
               attribute.attname || ':'
               || format_type(attribute.atttypid, attribute.atttypmod) || ':'
               || attribute.attnotnull
               ORDER BY attribute.attnum)
      INTO audit_columns
      FROM pg_attribute attribute
     WHERE attribute.attrelid = 'public.merchant_audit_event'::regclass
       AND attribute.attnum > 0
       AND NOT attribute.attisdropped;

    IF audit_columns IS DISTINCT FROM ARRAY[
        'id:bigint:true', 'merchant_id:bigint:true',
        'target_tenant_id:bigint:true',
        'actor_account_domain:character varying(16):true',
        'actor_tenant_id:bigint:true', 'actor_membership_id:bigint:true',
        'action_code:character varying(32):true',
        'previous_status:character varying(32):false',
        'next_status:character varying(32):true',
        'reason_code:character varying(64):true', 'changed_fields:jsonb:true',
        'trace_id:character varying(64):true', 'merchant_version:bigint:true',
        'occurred_at:timestamp with time zone:true']::TEXT[] THEN
        RAISE EXCEPTION
            'V34 audit preflight blocked: Merchant audit columns are not canonical';
    END IF;

    SELECT array_agg(constraint_row.conname ORDER BY constraint_row.conname)
      INTO audit_constraints
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid = 'public.merchant_audit_event'::regclass
       AND constraint_row.contype IN ('c', 'f', 'p');

    IF audit_constraints IS DISTINCT FROM ARRAY[
        'ck_merchant_audit_action_reason', 'ck_merchant_audit_changed_fields',
        'ck_merchant_audit_domain', 'ck_merchant_audit_status',
        'ck_merchant_audit_version', 'fk_merchant_audit_actor_membership',
        'fk_merchant_audit_actor_tenant', 'fk_merchant_audit_target',
        'merchant_audit_event_pkey']::TEXT[] THEN
        RAISE EXCEPTION
            'V34 audit preflight blocked: Merchant audit constraints are not canonical';
    END IF;

    SELECT count(*)
      INTO canonical_evidence_constraints
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid = 'public.merchant_audit_event'::regclass
       AND (
           (constraint_row.conname = 'ck_merchant_audit_action_reason'
            AND pg_get_constraintdef(constraint_row.oid, true) = $definition$CHECK (action_code::text = 'SUBMIT'::text AND reason_code::text = 'APPLICATION_SUBMITTED'::text OR action_code::text = 'RESUBMIT'::text AND reason_code::text = 'APPLICATION_RESUBMITTED'::text OR action_code::text = 'APPROVE'::text AND reason_code::text = 'PROFILE_VERIFIED'::text OR action_code::text = 'REJECT'::text AND (reason_code::text = ANY (ARRAY['PROFILE_MISMATCH'::character varying, 'REGISTRATION_UNVERIFIED'::character varying, 'COMPLIANCE_REJECTED'::character varying]::text[])) OR action_code::text = 'DISABLE'::text AND (reason_code::text = ANY (ARRAY['COMPLIANCE_HOLD'::character varying, 'RISK_CONTROL'::character varying]::text[])) OR action_code::text = 'ENABLE'::text AND (reason_code::text = ANY (ARRAY['COMPLIANCE_CLEARED'::character varying, 'RISK_CLEARED'::character varying]::text[])) OR action_code::text = 'TERMINATE'::text AND (reason_code::text = ANY (ARRAY['BUSINESS_CLOSED'::character varying, 'COMPLIANCE_TERMINATION'::character varying]::text[])))$definition$)
           OR (constraint_row.conname = 'ck_merchant_audit_status'
               AND pg_get_constraintdef(constraint_row.oid, true) = $definition$CHECK ((previous_status IS NULL OR (previous_status::text = ANY (ARRAY['PENDING_REVIEW'::character varying, 'REVIEW_REJECTED'::character varying, 'ACTIVE'::character varying, 'DISABLED'::character varying, 'TERMINATED'::character varying]::text[]))) AND (next_status::text = ANY (ARRAY['PENDING_REVIEW'::character varying, 'REVIEW_REJECTED'::character varying, 'ACTIVE'::character varying, 'DISABLED'::character varying, 'TERMINATED'::character varying]::text[])))$definition$)
           OR (constraint_row.conname = 'ck_merchant_audit_version'
               AND pg_get_constraintdef(constraint_row.oid, true)
                   = 'CHECK (merchant_version >= 0)')
           OR (constraint_row.conname = 'fk_merchant_audit_target'
               AND pg_get_constraintdef(constraint_row.oid, true)
                   = 'FOREIGN KEY (merchant_id, target_tenant_id) REFERENCES merchant(id, tenant_id)'));

    IF canonical_evidence_constraints <> 4 THEN
        RAISE EXCEPTION
            'V34 audit preflight blocked: Merchant audit evidence constraints drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM merchant_audit_event audit
         GROUP BY audit.merchant_id, audit.merchant_version
        HAVING count(*) > 1) THEN
        RAISE EXCEPTION
            'V34 audit preflight blocked: ambiguous Merchant audit version evidence';
    END IF;
END
$preflight$;
