-- Registration-key rotation is an offline operation. The migration principal must be
-- allowed to create this cluster capability role; application principals must not be
-- members of it.

DO $$
DECLARE
    capability RECORD;
BEGIN
    SELECT rolcanlogin, rolsuper, rolinherit, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO capability
      FROM pg_roles
     WHERE rolname = 'payment_merchant_registration_rotation';
    IF NOT FOUND THEN
        CREATE ROLE payment_merchant_registration_rotation
            NOLOGIN NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    ELSIF capability.rolcanlogin OR capability.rolsuper OR NOT capability.rolinherit
       OR capability.rolcreaterole OR capability.rolcreatedb
       OR capability.rolreplication OR capability.rolbypassrls THEN
        RAISE EXCEPTION 'V33 blocked: merchant registration rotation role is not canonical';
    END IF;
END
$$;

DO $$
DECLARE
    capability_oid OID;
BEGIN
    SELECT oid INTO STRICT capability_oid
      FROM pg_roles
     WHERE rolname = 'payment_merchant_registration_rotation';
    IF EXISTS (
        SELECT 1 FROM pg_auth_members
         WHERE roleid = capability_oid OR member = capability_oid
    ) THEN
        RAISE EXCEPTION 'V33 blocked: merchant registration rotation role has preexisting memberships';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO payment_merchant_registration_rotation;
GRANT SELECT ON merchant TO payment_merchant_registration_rotation;
GRANT UPDATE (
    registration_fingerprint,
    registration_search_key_id,
    registration_fingerprint_algorithm,
    registration_ciphertext,
    registration_nonce,
    registration_auth_tag,
    registration_aead_key_id,
    registration_aead_algorithm
) ON merchant TO payment_merchant_registration_rotation;
GRANT SELECT, INSERT ON merchant_registration_key_metadata
    TO payment_merchant_registration_rotation;
GRANT UPDATE (active, activated_at) ON merchant_registration_key_metadata
    TO payment_merchant_registration_rotation;

CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
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
$$;
