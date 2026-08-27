-- V34 was released with a status-reason backfill that is rejected by the V33
-- lifecycle trigger. This callback opens only the exact, data-only update used
-- by that backfill. V34 replaces the function with its final strict definition.

DO $bridge$
DECLARE
    current_version TEXT;
    lifecycle_body TEXT;
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
       OR EXISTS (
           SELECT 1
             FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'merchant'
              AND column_name IN ('remarks', 'status_reason_code')) THEN
        RAISE EXCEPTION
            'V34 bridge blocked: expected exact V33 Merchant schema';
    END IF;

    SELECT procedure.prosrc
      INTO lifecycle_body
      FROM pg_proc procedure
      JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
     WHERE namespace.nspname = 'public'
       AND procedure.proname = 'merchant_enforce_lifecycle'
       AND procedure.pronargs = 0;

    IF lifecycle_body IS DISTINCT FROM $v33$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'merchant rows are never physically deleted';
    END IF;
    IF OLD.tenant_id <> NEW.tenant_id OR OLD.account_domain <> NEW.account_domain THEN
        RAISE EXCEPTION 'merchant tenant binding is immutable';
    END IF;
    IF OLD.merchant_code <> NEW.merchant_code THEN
        RAISE EXCEPTION 'merchant code is immutable';
    END IF;
    IF OLD.status = NEW.status
       AND current_user = 'payment_merchant_registration_rotation' THEN
        IF OLD.row_version <> NEW.row_version
           OR (OLD.legal_name, OLD.display_name, OLD.registration_country,
               OLD.registration_number_masked, OLD.status, OLD.submitted_at,
               OLD.reviewed_at, OLD.last_decision, OLD.last_decision_reason_code,
               OLD.last_decided_by_membership_id, OLD.last_decided_at,
               OLD.created_at, OLD.updated_at)
              IS DISTINCT FROM
              (NEW.legal_name, NEW.display_name, NEW.registration_country,
               NEW.registration_number_masked, NEW.status, NEW.submitted_at,
               NEW.reviewed_at, NEW.last_decision, NEW.last_decision_reason_code,
               NEW.last_decided_by_membership_id, NEW.last_decided_at,
               NEW.created_at, NEW.updated_at)
           OR OLD.registration_normalization_version <> NEW.registration_normalization_version THEN
            RAISE EXCEPTION 'merchant registration rotation may change only protected crypto fields';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'merchant row_version must advance exactly once';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'PENDING_REVIEW' AND NEW.status IN ('ACTIVE', 'REVIEW_REJECTED'))
        OR (OLD.status = 'REVIEW_REJECTED' AND NEW.status IN ('PENDING_REVIEW', 'TERMINATED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status IN ('DISABLED', 'TERMINATED'))
        OR (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE', 'TERMINATED'))
    ) THEN
        RAISE EXCEPTION 'illegal merchant lifecycle transition';
    END IF;
    IF OLD.status = NEW.status THEN
        RAISE EXCEPTION 'merchant update requires a lifecycle transition';
    END IF;
    IF (OLD.legal_name, OLD.display_name, OLD.registration_country,
        OLD.registration_number_masked, OLD.registration_fingerprint,
        OLD.registration_search_key_id, OLD.registration_fingerprint_algorithm,
        OLD.registration_normalization_version, OLD.registration_ciphertext,
        OLD.registration_nonce, OLD.registration_auth_tag, OLD.registration_aead_key_id,
        OLD.registration_aead_algorithm)
       IS DISTINCT FROM
       (NEW.legal_name, NEW.display_name, NEW.registration_country,
        NEW.registration_number_masked, NEW.registration_fingerprint,
        NEW.registration_search_key_id, NEW.registration_fingerprint_algorithm,
        NEW.registration_normalization_version, NEW.registration_ciphertext,
        NEW.registration_nonce, NEW.registration_auth_tag, NEW.registration_aead_key_id,
        NEW.registration_aead_algorithm)
       AND NOT (OLD.status = 'REVIEW_REJECTED' AND NEW.status = 'PENDING_REVIEW') THEN
        RAISE EXCEPTION 'merchant profile may change only on rejected resubmission';
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$v33$ THEN
        RAISE EXCEPTION
            'V34 bridge blocked: V33 Merchant lifecycle function is not canonical';
    END IF;

    EXECUTE $function$
        CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
        RETURNS trigger LANGUAGE plpgsql AS $body$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'merchant rows are never physically deleted';
            END IF;
            IF OLD.tenant_id <> NEW.tenant_id OR OLD.account_domain <> NEW.account_domain THEN
                RAISE EXCEPTION 'merchant tenant binding is immutable';
            END IF;
            IF OLD.merchant_code <> NEW.merchant_code THEN
                RAISE EXCEPTION 'merchant code is immutable';
            END IF;
            IF OLD.status = NEW.status
               AND current_user = 'payment_merchant_registration_rotation' THEN
                IF OLD.row_version <> NEW.row_version
                   OR (OLD.legal_name, OLD.display_name, OLD.registration_country,
                       OLD.registration_number_masked, OLD.status, OLD.submitted_at,
                       OLD.reviewed_at, OLD.last_decision, OLD.last_decision_reason_code,
                       OLD.last_decided_by_membership_id, OLD.last_decided_at,
                       OLD.created_at, OLD.updated_at)
                      IS DISTINCT FROM
                      (NEW.legal_name, NEW.display_name, NEW.registration_country,
                       NEW.registration_number_masked, NEW.status, NEW.submitted_at,
                       NEW.reviewed_at, NEW.last_decision, NEW.last_decision_reason_code,
                       NEW.last_decided_by_membership_id, NEW.last_decided_at,
                       NEW.created_at, NEW.updated_at)
                   OR OLD.registration_normalization_version <> NEW.registration_normalization_version THEN
                    RAISE EXCEPTION 'merchant registration rotation may change only protected crypto fields';
                END IF;
                RETURN NEW;
            END IF;
            IF OLD.status = NEW.status
               AND NEW.row_version = OLD.row_version
               AND to_jsonb(OLD) ? 'status_reason_code'
               AND to_jsonb(OLD)->'status_reason_code' = 'null'::jsonb
               AND jsonb_typeof(to_jsonb(NEW)->'status_reason_code') = 'string'
               AND (to_jsonb(OLD) - 'status_reason_code')
                   IS NOT DISTINCT FROM
                   (to_jsonb(NEW) - 'status_reason_code') THEN
                RETURN NEW;
            END IF;
            IF NEW.row_version <> OLD.row_version + 1 THEN
                RAISE EXCEPTION 'merchant row_version must advance exactly once';
            END IF;
            IF OLD.status <> NEW.status AND NOT (
                (OLD.status = 'PENDING_REVIEW' AND NEW.status IN ('ACTIVE', 'REVIEW_REJECTED'))
                OR (OLD.status = 'REVIEW_REJECTED' AND NEW.status IN ('PENDING_REVIEW', 'TERMINATED'))
                OR (OLD.status = 'ACTIVE' AND NEW.status IN ('DISABLED', 'TERMINATED'))
                OR (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE', 'TERMINATED'))
            ) THEN
                RAISE EXCEPTION 'illegal merchant lifecycle transition';
            END IF;
            IF OLD.status = NEW.status THEN
                RAISE EXCEPTION 'merchant update requires a lifecycle transition';
            END IF;
            IF (OLD.legal_name, OLD.display_name, OLD.registration_country,
                OLD.registration_number_masked, OLD.registration_fingerprint,
                OLD.registration_search_key_id, OLD.registration_fingerprint_algorithm,
                OLD.registration_normalization_version, OLD.registration_ciphertext,
                OLD.registration_nonce, OLD.registration_auth_tag, OLD.registration_aead_key_id,
                OLD.registration_aead_algorithm)
               IS DISTINCT FROM
               (NEW.legal_name, NEW.display_name, NEW.registration_country,
                NEW.registration_number_masked, NEW.registration_fingerprint,
                NEW.registration_search_key_id, NEW.registration_fingerprint_algorithm,
                NEW.registration_normalization_version, NEW.registration_ciphertext,
                NEW.registration_nonce, NEW.registration_auth_tag, NEW.registration_aead_key_id,
                NEW.registration_aead_algorithm)
               AND NOT (OLD.status = 'REVIEW_REJECTED' AND NEW.status = 'PENDING_REVIEW') THEN
                RAISE EXCEPTION 'merchant profile may change only on rejected resubmission';
            END IF;
            NEW.updated_at := statement_timestamp();
            RETURN NEW;
        END;
        $body$;
    $function$;
END
$bridge$;
