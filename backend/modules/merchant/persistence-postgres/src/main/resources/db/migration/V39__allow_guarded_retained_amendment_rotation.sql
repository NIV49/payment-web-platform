-- Forward-only least-privilege access for rotating retained amendment evidence.

DO $preflight$
BEGIN
    IF (SELECT version FROM flyway_schema_history WHERE success
         ORDER BY installed_rank DESC LIMIT 1) IS DISTINCT FROM '38'
       OR (SELECT checksum FROM flyway_schema_history WHERE version='38' AND success)
          IS DISTINCT FROM 1630466861 THEN
        RAISE EXCEPTION 'V39 blocked: expected frozen V38 checksum 1630466861';
    END IF;
    IF to_regclass('public.merchant_amendment') IS NULL
       OR to_regclass('public.merchant_protected_nonce') IS NULL
       OR to_regprocedure('merchant_amendment_rotation_guard()') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid='merchant_amendment'::regclass
                     AND tgname='trg_merchant_amendment_rotation_guard'
                     AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'V39 blocked: Merchant amendment rotation boundary is absent or modified';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname='payment_merchant_registration_rotation'
           AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole
           AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'V39 blocked: rotation capability role is not canonical';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM (VALUES
            ('id','bigint',TRUE),('merchant_id','bigint',TRUE),
            ('target_tenant_id','bigint',TRUE),
            ('status','character varying(24)',TRUE),
            ('row_version','bigint',TRUE),
            ('author_tenant_id','bigint',TRUE),
            ('author_membership_id','bigint',TRUE),
            ('registration_country','character(2)',TRUE),
            ('registration_fingerprint','bytea',FALSE),
            ('registration_search_key_id','character varying(128)',FALSE),
            ('registration_fingerprint_algorithm','character varying(32)',TRUE),
            ('registration_normalization_version','integer',TRUE),
            ('registration_ciphertext','bytea',FALSE),
            ('registration_nonce','bytea',FALSE),
            ('registration_auth_tag','bytea',FALSE),
            ('registration_aead_key_id','character varying(128)',FALSE),
            ('registration_aead_algorithm','character varying(32)',FALSE),
            ('registration_nonce_purpose','character varying(32)',TRUE),
            ('legal_id_type_code','character varying(32)',TRUE),
            ('legal_id_ciphertext','bytea',FALSE),
            ('legal_id_nonce','bytea',FALSE),
            ('legal_id_auth_tag','bytea',FALSE),
            ('legal_id_aead_key_id','character varying(128)',FALSE),
            ('legal_id_aead_algorithm','character varying(32)',FALSE),
            ('legal_id_aad_scheme_version','integer',TRUE),
            ('legal_id_protection_version','integer',TRUE),
            ('legal_id_nonce_purpose','character varying(32)',TRUE)
          ) expected(column_name,column_type,not_null)
          LEFT JOIN pg_attribute actual
            ON actual.attrelid='merchant_amendment'::regclass
           AND actual.attname=expected.column_name
           AND actual.attnum>0 AND NOT actual.attisdropped
         WHERE actual.attname IS NULL
            OR format_type(actual.atttypid,actual.atttypmod)<>expected.column_type
            OR actual.attnotnull<>expected.not_null
    ) THEN
        RAISE EXCEPTION 'V39 blocked: retained amendment rotation column shape drifted';
    END IF;
    IF (SELECT count(*) FROM pg_constraint
         WHERE conrelid='merchant_amendment'::regclass AND convalidated
           AND conname IN ('ck_merchant_amendment_registration_crypto',
                           'ck_merchant_amendment_legal_id_crypto',
                           'ck_merchant_amendment_decision',
                           'fk_merchant_amendment_registration_nonce_registry',
                           'fk_merchant_amendment_legal_id_nonce_registry'))<>5 THEN
        RAISE EXCEPTION 'V39 blocked: retained amendment constraints are absent or unvalidated';
    END IF;
    IF (SELECT pg_get_constraintdef(oid,false) FROM pg_constraint
         WHERE conrelid='merchant_amendment'::regclass
           AND conname='fk_merchant_amendment_registration_nonce_registry')
          <> 'FOREIGN KEY (registration_nonce_purpose, registration_aead_key_id, registration_nonce) REFERENCES merchant_protected_nonce(purpose, key_id, nonce)'
       OR (SELECT pg_get_constraintdef(oid,false) FROM pg_constraint
            WHERE conrelid='merchant_amendment'::regclass
              AND conname='fk_merchant_amendment_legal_id_nonce_registry')
          <> 'FOREIGN KEY (legal_id_nonce_purpose, legal_id_aead_key_id, legal_id_nonce) REFERENCES merchant_protected_nonce(purpose, key_id, nonce)' THEN
        RAISE EXCEPTION 'V39 blocked: retained amendment nonce registry binding drifted';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid='merchant_amendment'::regclass
           AND conname='ck_merchant_amendment_registration_crypto'
           AND position('REGISTRATION_AEAD' IN pg_get_constraintdef(oid,false))>0
           AND position('HMAC-SHA-256' IN pg_get_constraintdef(oid,false))>0
           AND position('AES-256-GCM' IN pg_get_constraintdef(oid,false))>0
           AND position('octet_length(registration_fingerprint)' IN
                        pg_get_constraintdef(oid,false))>0
           AND position('octet_length(registration_nonce)' IN
                        pg_get_constraintdef(oid,false))>0
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid='merchant_amendment'::regclass
           AND conname='ck_merchant_amendment_legal_id_crypto'
           AND position('LEGAL_ID_AEAD' IN pg_get_constraintdef(oid,false))>0
           AND position('AES-256-GCM' IN pg_get_constraintdef(oid,false))>0
           AND position('legal_id_aad_scheme_version' IN
                        pg_get_constraintdef(oid,false))>0
           AND position('legal_id_protection_version' IN
                        pg_get_constraintdef(oid,false))>0
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid='merchant_amendment'::regclass
           AND conname='ck_merchant_amendment_decision'
           AND position('PENDING_REVIEW' IN pg_get_constraintdef(oid,false))>0
           AND position('APPROVED' IN pg_get_constraintdef(oid,false))>0
           AND position('REJECTED' IN pg_get_constraintdef(oid,false))>0
           AND position('STALE' IN pg_get_constraintdef(oid,false))>0
           AND position('PLATFORM_AMENDMENT_STALE' IN
                        pg_get_constraintdef(oid,false))>0
    ) THEN
        RAISE EXCEPTION 'V39 blocked: retained amendment crypto or decision semantics drifted';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_class relation
        JOIN pg_roles owner ON owner.oid=relation.relowner
         WHERE relation.oid='merchant_amendment'::regclass
           AND owner.rolname='payment_merchant_registration_rotation'
    ) THEN
        RAISE EXCEPTION 'V39 blocked: rotation capability must not own Merchant evidence';
    END IF;
END
$preflight$;

LOCK TABLE merchant_amendment IN ACCESS EXCLUSIVE MODE;

CREATE FUNCTION merchant_amendment_rotation_guard()
RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF current_user='payment_merchant_registration_rotation' THEN
        IF (to_jsonb(OLD)-ARRAY[
              'registration_fingerprint','registration_search_key_id',
              'registration_fingerprint_algorithm','registration_ciphertext',
              'registration_nonce','registration_auth_tag','registration_aead_key_id',
              'registration_aead_algorithm','legal_id_ciphertext','legal_id_nonce',
              'legal_id_auth_tag','legal_id_aead_key_id','legal_id_aead_algorithm',
              'legal_id_aad_scheme_version','legal_id_protection_version']::text[])
           IS DISTINCT FROM
           (to_jsonb(NEW)-ARRAY[
              'registration_fingerprint','registration_search_key_id',
              'registration_fingerprint_algorithm','registration_ciphertext',
              'registration_nonce','registration_auth_tag','registration_aead_key_id',
              'registration_aead_algorithm','legal_id_ciphertext','legal_id_nonce',
              'legal_id_auth_tag','legal_id_aead_key_id','legal_id_aead_algorithm',
              'legal_id_aad_scheme_version','legal_id_protection_version']::text[]) THEN
            RAISE EXCEPTION 'amendment rotation may change only protected crypto fields';
        END IF;
        RETURN NEW;
    END IF;
    IF (OLD.registration_fingerprint,OLD.registration_search_key_id,
        OLD.registration_fingerprint_algorithm,OLD.registration_ciphertext,
        OLD.registration_nonce,OLD.registration_auth_tag,OLD.registration_aead_key_id,
        OLD.registration_aead_algorithm,OLD.legal_id_ciphertext,OLD.legal_id_nonce,
        OLD.legal_id_auth_tag,OLD.legal_id_aead_key_id,OLD.legal_id_aead_algorithm,
        OLD.legal_id_aad_scheme_version,OLD.legal_id_protection_version)
       IS DISTINCT FROM
       (NEW.registration_fingerprint,NEW.registration_search_key_id,
        NEW.registration_fingerprint_algorithm,NEW.registration_ciphertext,
        NEW.registration_nonce,NEW.registration_auth_tag,NEW.registration_aead_key_id,
        NEW.registration_aead_algorithm,NEW.legal_id_ciphertext,NEW.legal_id_nonce,
        NEW.legal_id_auth_tag,NEW.legal_id_aead_key_id,NEW.legal_id_aead_algorithm,
        NEW.legal_id_aad_scheme_version,NEW.legal_id_protection_version) THEN
        RAISE EXCEPTION 'Merchant amendment ciphertext is immutable outside rotation';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_merchant_amendment_rotation_guard
BEFORE UPDATE ON merchant_amendment
FOR EACH ROW EXECUTE FUNCTION merchant_amendment_rotation_guard();

REVOKE ALL ON merchant_amendment FROM PUBLIC;
REVOKE ALL ON merchant_amendment FROM payment_merchant_registration_rotation;
GRANT SELECT ON merchant_amendment TO payment_merchant_registration_rotation;
GRANT UPDATE (registration_fingerprint,registration_search_key_id,
              registration_fingerprint_algorithm,registration_ciphertext,
              registration_nonce,registration_auth_tag,registration_aead_key_id,
              registration_aead_algorithm,legal_id_ciphertext,legal_id_nonce,
              legal_id_auth_tag,legal_id_aead_key_id,legal_id_aead_algorithm,
              legal_id_aad_scheme_version,legal_id_protection_version)
    ON merchant_amendment TO payment_merchant_registration_rotation;
