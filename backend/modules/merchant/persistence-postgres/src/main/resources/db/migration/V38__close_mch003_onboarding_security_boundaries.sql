-- MCH-003 forward closure. V37 is frozen at its first successful checksum.

DO $preflight$
BEGIN
    IF (SELECT version FROM flyway_schema_history WHERE success
         ORDER BY installed_rank DESC LIMIT 1) IS DISTINCT FROM '37'
       OR (SELECT checksum FROM flyway_schema_history WHERE version='37' AND success)
          IS DISTINCT FROM 1254522631 THEN
        RAISE EXCEPTION 'V38 blocked: expected frozen V37 checksum 1254522631';
    END IF;
    IF to_regclass('public.merchant_document') IS NULL
       OR to_regclass('public.merchant_amendment') IS NULL
       OR EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='public' AND table_name='merchant'
                     AND column_name='legal_id_protection_version') THEN
        RAISE EXCEPTION 'V38 blocked: V37 Merchant schema is absent or already modified';
    END IF;
END
$preflight$;

LOCK TABLE merchant,merchant_document,merchant_amendment IN ACCESS EXCLUSIVE MODE;

ALTER TABLE merchant
    ADD COLUMN legal_id_protection_version INTEGER,
    ADD COLUMN registration_nonce_purpose VARCHAR(32) NOT NULL DEFAULT 'REGISTRATION_AEAD',
    ADD COLUMN legal_id_nonce_purpose VARCHAR(32);
UPDATE merchant SET legal_id_protection_version=1,
                    legal_id_nonce_purpose='LEGAL_ID_AEAD'
 WHERE legal_id_ciphertext IS NOT NULL;
ALTER TABLE merchant DROP CONSTRAINT ck_merchant_extended_profile;
ALTER TABLE merchant ADD CONSTRAINT ck_merchant_extended_profile CHECK (
    (brand_name IS NULL AND industry_code IS NULL AND registered_address IS NULL
        AND operating_address IS NULL AND contact_email IS NULL AND contact_phone IS NULL
        AND legal_id_type_code IS NULL AND legal_id_no_masked IS NULL
        AND legal_id_ciphertext IS NULL AND legal_id_nonce IS NULL
        AND legal_id_auth_tag IS NULL AND legal_id_aead_key_id IS NULL
        AND legal_id_aead_algorithm IS NULL AND legal_id_aad_scheme_version IS NULL
        AND legal_id_protection_version IS NULL AND legal_id_nonce_purpose IS NULL
        AND legal_id_valid_from IS NULL AND legal_id_valid_to IS NULL)
    OR (btrim(brand_name)=brand_name AND char_length(brand_name) BETWEEN 1 AND 128
        AND industry_code IN ('FINANCIAL_SERVICES','ECOMMERCE','RETAIL','TRAVEL',
                              'EDUCATION','OTHER')
        AND btrim(registered_address)=registered_address
        AND char_length(registered_address) BETWEEN 1 AND 300
        AND btrim(operating_address)=operating_address
        AND char_length(operating_address) BETWEEN 1 AND 300
        AND contact_email ~ '^[^[:space:]@]+@[^[:space:]@]+$'
        AND contact_phone ~ '^\+[1-9][0-9]{1,14}$'
        AND legal_id_type_code IN ('NATIONAL_ID','PASSPORT','DRIVER_LICENSE')
        AND legal_id_no_masked ~ '^\*+.{0,4}$'
        AND octet_length(legal_id_ciphertext)>=1 AND octet_length(legal_id_nonce)=12
        AND octet_length(legal_id_auth_tag)=16
        AND legal_id_aead_algorithm='AES-256-GCM' AND legal_id_aad_scheme_version=1
        AND legal_id_protection_version=1 AND legal_id_nonce_purpose='LEGAL_ID_AEAD'
        AND legal_id_valid_from IS NOT NULL AND legal_id_valid_to>=legal_id_valid_from)),
    ADD CONSTRAINT ck_merchant_registration_nonce_purpose CHECK (
        registration_nonce_purpose='REGISTRATION_AEAD');

ALTER TABLE merchant_document
    ADD COLUMN nonce_purpose VARCHAR(32) NOT NULL DEFAULT 'DOCUMENT_AEAD';
ALTER TABLE merchant_amendment
    ADD COLUMN registration_fingerprint_algorithm VARCHAR(32),
    ADD COLUMN registration_normalization_version INTEGER,
    ADD COLUMN registration_nonce_purpose VARCHAR(32) NOT NULL DEFAULT 'REGISTRATION_AEAD',
    ADD COLUMN legal_id_aad_scheme_version INTEGER,
    ADD COLUMN legal_id_protection_version INTEGER,
    ADD COLUMN legal_id_nonce_purpose VARCHAR(32) NOT NULL DEFAULT 'LEGAL_ID_AEAD';
UPDATE merchant_amendment
   SET registration_fingerprint_algorithm='HMAC-SHA-256',
       registration_normalization_version=1,
       legal_id_aad_scheme_version=1,
       legal_id_protection_version=1;
ALTER TABLE merchant_amendment
    ALTER COLUMN registration_fingerprint_algorithm SET NOT NULL,
    ALTER COLUMN registration_normalization_version SET NOT NULL,
    ALTER COLUMN legal_id_aad_scheme_version SET NOT NULL,
    ALTER COLUMN legal_id_protection_version SET NOT NULL,
    ADD CONSTRAINT ck_merchant_amendment_registration_crypto CHECK (
        registration_mode IN ('RETAIN','REPLACE')
        AND registration_number_masked ~ '^\*+.{0,4}$'
        AND octet_length(registration_fingerprint)=32
        AND registration_fingerprint_algorithm='HMAC-SHA-256'
        AND registration_normalization_version=1
        AND registration_nonce_purpose='REGISTRATION_AEAD'
        AND octet_length(registration_ciphertext)>=1 AND octet_length(registration_nonce)=12
        AND octet_length(registration_auth_tag)=16
        AND registration_aead_algorithm='AES-256-GCM'),
    ADD CONSTRAINT ck_merchant_amendment_legal_id_crypto CHECK (
        legal_id_mode IN ('RETAIN','REPLACE') AND legal_id_no_masked ~ '^\*+.{0,4}$'
        AND legal_id_nonce_purpose='LEGAL_ID_AEAD'
        AND octet_length(legal_id_ciphertext)>=1 AND octet_length(legal_id_nonce)=12
        AND octet_length(legal_id_auth_tag)=16 AND legal_id_aead_algorithm='AES-256-GCM'
        AND legal_id_aad_scheme_version=1 AND legal_id_protection_version=1);
ALTER TABLE merchant_amendment DROP CONSTRAINT ck_merchant_amendment_decision;
ALTER TABLE merchant_amendment ADD CONSTRAINT ck_merchant_amendment_decision CHECK (
    (status='PENDING_REVIEW' AND decision IS NULL AND decision_reason_code IS NULL
        AND decided_by_tenant_id IS NULL AND decided_by_membership_id IS NULL
        AND decided_at IS NULL AND applied_merchant_version IS NULL)
    OR (status='APPROVED' AND decision='APPROVE'
        AND decision_reason_code='PROFILE_AMENDMENT_VERIFIED'
        AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
        AND applied_merchant_version=origin_merchant_version+1)
    OR (status='REJECTED' AND decision='REJECT'
        AND decision_reason_code IN ('PROFILE_AMENDMENT_MISMATCH','DOCUMENT_UNVERIFIED',
                                     'COMPLIANCE_REJECTED')
        AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
        AND applied_merchant_version IS NULL)
    OR (status='STALE' AND decision IN ('APPROVE','REJECT')
        AND decision_reason_code='PLATFORM_AMENDMENT_STALE'
        AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
        AND applied_merchant_version IS NULL));

CREATE TABLE merchant_protected_nonce (
    purpose VARCHAR(32) NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    nonce BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(purpose,key_id,nonce),
    CONSTRAINT fk_merchant_protected_nonce_key FOREIGN KEY(key_id,algorithm)
        REFERENCES merchant_registration_key_metadata(key_id,algorithm),
    CONSTRAINT ck_merchant_protected_nonce_purpose CHECK (
        purpose IN ('REGISTRATION_AEAD','LEGAL_ID_AEAD','DOCUMENT_AEAD')),
    CONSTRAINT ck_merchant_protected_nonce_shape CHECK (
        algorithm='AES-256-GCM' AND octet_length(nonce)=12)
);
DO $nonce_collision_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM (
            SELECT 'REGISTRATION_AEAD' purpose,registration_aead_key_id key_id,
                   registration_nonce nonce,registration_ciphertext ciphertext,
                   registration_auth_tag auth_tag FROM merchant
            UNION ALL
            SELECT 'LEGAL_ID_AEAD',legal_id_aead_key_id,legal_id_nonce,
                   legal_id_ciphertext,legal_id_auth_tag
              FROM merchant WHERE legal_id_nonce IS NOT NULL
            UNION ALL
            SELECT 'DOCUMENT_AEAD',aead_key_id,nonce,ciphertext,auth_tag
              FROM merchant_document
            UNION ALL
            SELECT 'REGISTRATION_AEAD',registration_aead_key_id,registration_nonce,
                   registration_ciphertext,registration_auth_tag FROM merchant_amendment
            UNION ALL
            SELECT 'LEGAL_ID_AEAD',legal_id_aead_key_id,legal_id_nonce,
                   legal_id_ciphertext,legal_id_auth_tag FROM merchant_amendment
          ) protected_row
         GROUP BY purpose,key_id,nonce
        HAVING count(DISTINCT encode(ciphertext,'hex') || ':' || encode(auth_tag,'hex')) > 1
    ) THEN
        RAISE EXCEPTION 'V38 blocked: protected nonce collision across retained evidence';
    END IF;
END
$nonce_collision_preflight$;
INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
SELECT purpose,key_id,algorithm,nonce FROM (
    SELECT 'REGISTRATION_AEAD' purpose,registration_aead_key_id key_id,
           registration_aead_algorithm algorithm,registration_nonce nonce FROM merchant
    UNION
    SELECT 'LEGAL_ID_AEAD',legal_id_aead_key_id,legal_id_aead_algorithm,legal_id_nonce
      FROM merchant WHERE legal_id_nonce IS NOT NULL
    UNION
    SELECT 'DOCUMENT_AEAD',aead_key_id,aead_algorithm,nonce FROM merchant_document
    UNION
    SELECT 'REGISTRATION_AEAD',registration_aead_key_id,registration_aead_algorithm,
           registration_nonce FROM merchant_amendment
    UNION
    SELECT 'LEGAL_ID_AEAD',legal_id_aead_key_id,legal_id_aead_algorithm,legal_id_nonce
      FROM merchant_amendment
) existing_nonce;
CREATE TRIGGER trg_merchant_protected_nonce_append_only
BEFORE UPDATE OR DELETE ON merchant_protected_nonce
FOR EACH ROW EXECUTE FUNCTION merchant_reject_append_only_mutation();
ALTER TABLE merchant
    ADD CONSTRAINT fk_merchant_registration_nonce_registry FOREIGN KEY(
        registration_nonce_purpose,registration_aead_key_id,registration_nonce)
        REFERENCES merchant_protected_nonce(purpose,key_id,nonce),
    ADD CONSTRAINT fk_merchant_legal_id_nonce_registry FOREIGN KEY(
        legal_id_nonce_purpose,legal_id_aead_key_id,legal_id_nonce)
        REFERENCES merchant_protected_nonce(purpose,key_id,nonce);
ALTER TABLE merchant_document ADD CONSTRAINT fk_merchant_document_nonce_registry FOREIGN KEY(
    nonce_purpose,aead_key_id,nonce) REFERENCES merchant_protected_nonce(purpose,key_id,nonce);
ALTER TABLE merchant_amendment
    ADD CONSTRAINT fk_merchant_amendment_registration_nonce_registry FOREIGN KEY(
        registration_nonce_purpose,registration_aead_key_id,registration_nonce)
        REFERENCES merchant_protected_nonce(purpose,key_id,nonce),
    ADD CONSTRAINT fk_merchant_amendment_legal_id_nonce_registry FOREIGN KEY(
        legal_id_nonce_purpose,legal_id_aead_key_id,legal_id_nonce)
        REFERENCES merchant_protected_nonce(purpose,key_id,nonce);

ALTER TABLE merchant_audit_event DROP CONSTRAINT ck_merchant_audit_action_reason;
ALTER TABLE merchant_audit_event ADD CONSTRAINT ck_merchant_audit_action_reason CHECK (
    (action_code='SUBMIT' AND reason_code='APPLICATION_SUBMITTED')
    OR (action_code='CREATE' AND reason_code='PLATFORM_APPLICATION_SUBMITTED')
    OR (action_code='RESUBMIT' AND reason_code='APPLICATION_RESUBMITTED')
    OR (action_code='UPDATE_PROFILE' AND reason_code='PLATFORM_PROFILE_UPDATED')
    OR (action_code='UPDATE_AMENDMENT' AND reason_code='PROFILE_AMENDMENT_VERIFIED')
    OR (action_code='APPROVE' AND reason_code='PROFILE_VERIFIED')
    OR (action_code='REJECT' AND reason_code IN (
        'PROFILE_MISMATCH','REGISTRATION_UNVERIFIED','COMPLIANCE_REJECTED'))
    OR (action_code='DISABLE' AND reason_code IN ('COMPLIANCE_HOLD','RISK_CONTROL'))
    OR (action_code='ENABLE' AND reason_code IN ('COMPLIANCE_CLEARED','RISK_CLEARED'))
    OR (action_code='TERMINATE' AND reason_code IN (
        'BUSINESS_CLOSED','COMPLIANCE_TERMINATION')));

CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'merchant rows are never physically deleted'; END IF;
    IF OLD.tenant_id<>NEW.tenant_id OR OLD.account_domain<>NEW.account_domain THEN
        RAISE EXCEPTION 'merchant tenant binding is immutable';
    END IF;
    IF OLD.merchant_code<>NEW.merchant_code THEN RAISE EXCEPTION 'merchant code is immutable'; END IF;
    IF (OLD.application_source,OLD.application_actor_tenant_id,
        OLD.application_author_membership_id) IS DISTINCT FROM
       (NEW.application_source,NEW.application_actor_tenant_id,
        NEW.application_author_membership_id) THEN
        RAISE EXCEPTION 'merchant application author is immutable';
    END IF;
    IF OLD.status=NEW.status AND current_user='payment_merchant_registration_rotation' THEN
        IF OLD.row_version<>NEW.row_version
           OR (to_jsonb(OLD)-ARRAY[
                'registration_fingerprint','registration_search_key_id',
                'registration_fingerprint_algorithm','registration_ciphertext',
                'registration_nonce','registration_auth_tag','registration_aead_key_id',
                'registration_aead_algorithm','legal_id_no_masked','legal_id_ciphertext',
                'legal_id_nonce','legal_id_auth_tag','legal_id_aead_key_id',
                'legal_id_aead_algorithm','legal_id_aad_scheme_version',
                'legal_id_protection_version']::text[])
              IS DISTINCT FROM
              (to_jsonb(NEW)-ARRAY[
                'registration_fingerprint','registration_search_key_id',
                'registration_fingerprint_algorithm','registration_ciphertext',
                'registration_nonce','registration_auth_tag','registration_aead_key_id',
                'registration_aead_algorithm','legal_id_no_masked','legal_id_ciphertext',
                'legal_id_nonce','legal_id_auth_tag','legal_id_aead_key_id',
                'legal_id_aead_algorithm','legal_id_aad_scheme_version',
                'legal_id_protection_version']::text[]) THEN
            RAISE EXCEPTION 'merchant rotation may change only protected crypto fields';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.row_version<>OLD.row_version+1 THEN
        RAISE EXCEPTION 'merchant row_version must advance exactly once';
    END IF;
    IF OLD.status<>NEW.status AND NOT (
        (OLD.status='PENDING_REVIEW' AND NEW.status IN ('ACTIVE','REVIEW_REJECTED'))
        OR (OLD.status='REVIEW_REJECTED' AND NEW.status IN ('PENDING_REVIEW','TERMINATED'))
        OR (OLD.status='ACTIVE' AND NEW.status IN ('DISABLED','TERMINATED'))
        OR (OLD.status='DISABLED' AND NEW.status IN ('ACTIVE','TERMINATED'))) THEN
        RAISE EXCEPTION 'illegal merchant lifecycle transition';
    END IF;
    IF OLD.status=NEW.status THEN
        IF OLD.status NOT IN ('ACTIVE','DISABLED') THEN
            RAISE EXCEPTION 'merchant amendment requires active or disabled state';
        END IF;
        IF EXISTS (
            SELECT 1 FROM merchant_amendment amendment
             WHERE amendment.merchant_id=OLD.id AND amendment.status='APPROVED'
               AND amendment.origin_merchant_version=OLD.row_version
               AND amendment.origin_status=OLD.status
               AND amendment.applied_merchant_version=NEW.row_version
               AND amendment.display_name=NEW.display_name
               AND amendment.brand_name=NEW.brand_name
               AND amendment.authentication_type=NEW.authentication_type
               AND amendment.merchant_type_code=NEW.merchant_type_code
               AND amendment.industry_code=NEW.industry_code
               AND amendment.legal_name=NEW.legal_name
               AND amendment.registration_country=NEW.registration_country
               AND amendment.registered_address=NEW.registered_address
               AND amendment.operating_address=NEW.operating_address
               AND amendment.legal_person_name=NEW.legal_person_name
               AND amendment.contact_email=NEW.contact_email
               AND amendment.contact_phone=NEW.contact_phone
               AND amendment.legal_id_type_code=NEW.legal_id_type_code
               AND amendment.legal_id_valid_from=NEW.legal_id_valid_from
               AND amendment.legal_id_valid_to=NEW.legal_id_valid_to
               AND amendment.remarks=NEW.remarks
               AND amendment.registration_number_masked=NEW.registration_number_masked
               AND amendment.registration_fingerprint=NEW.registration_fingerprint
               AND amendment.registration_search_key_id=NEW.registration_search_key_id
               AND amendment.registration_fingerprint_algorithm=
                   NEW.registration_fingerprint_algorithm
               AND amendment.registration_normalization_version=
                   NEW.registration_normalization_version
               AND amendment.registration_ciphertext=NEW.registration_ciphertext
               AND amendment.registration_nonce=NEW.registration_nonce
               AND amendment.registration_auth_tag=NEW.registration_auth_tag
               AND amendment.registration_aead_key_id=NEW.registration_aead_key_id
               AND amendment.registration_aead_algorithm=NEW.registration_aead_algorithm
               AND amendment.legal_id_no_masked=NEW.legal_id_no_masked
               AND amendment.legal_id_ciphertext=NEW.legal_id_ciphertext
               AND amendment.legal_id_nonce=NEW.legal_id_nonce
               AND amendment.legal_id_auth_tag=NEW.legal_id_auth_tag
               AND amendment.legal_id_aead_key_id=NEW.legal_id_aead_key_id
               AND amendment.legal_id_aead_algorithm=NEW.legal_id_aead_algorithm
               AND amendment.legal_id_aad_scheme_version=NEW.legal_id_aad_scheme_version
               AND amendment.legal_id_protection_version=NEW.legal_id_protection_version) THEN
            NEW.updated_at:=statement_timestamp();
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'merchant profile change requires an approved amendment';
    END IF;
    NEW.updated_at:=statement_timestamp();
    RETURN NEW;
END;
$function$;

CREATE FUNCTION merchant_document_guard()
RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.attachment_scope='TEMPORARY' THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'attached Merchant documents are immutable evidence';
    END IF;
    IF current_user='payment_merchant_registration_rotation' THEN
        IF (to_jsonb(OLD)-ARRAY['ciphertext','nonce','auth_tag','aead_key_id',
             'aead_algorithm','aad_scheme_version','protection_version']::text[])
           IS DISTINCT FROM
           (to_jsonb(NEW)-ARRAY['ciphertext','nonce','auth_tag','aead_key_id',
             'aead_algorithm','aad_scheme_version','protection_version']::text[]) THEN
            RAISE EXCEPTION 'document rotation may change only protected crypto fields';
        END IF;
        RETURN NEW;
    END IF;
    IF (OLD.ciphertext,OLD.nonce,OLD.auth_tag,OLD.aead_key_id,OLD.aead_algorithm,
        OLD.aad_scheme_version,OLD.protection_version) IS DISTINCT FROM
       (NEW.ciphertext,NEW.nonce,NEW.auth_tag,NEW.aead_key_id,NEW.aead_algorithm,
        NEW.aad_scheme_version,NEW.protection_version) THEN
        RAISE EXCEPTION 'Merchant document ciphertext is immutable outside rotation';
    END IF;
    RETURN NEW;
END;
$function$;
CREATE TRIGGER trg_merchant_document_guard BEFORE UPDATE OR DELETE ON merchant_document
FOR EACH ROW EXECUTE FUNCTION merchant_document_guard();

GRANT SELECT ON merchant_document,merchant_protected_nonce
    TO payment_merchant_registration_rotation;
GRANT INSERT ON merchant_protected_nonce TO payment_merchant_registration_rotation;
GRANT UPDATE (legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,legal_id_auth_tag,
              legal_id_aead_key_id,legal_id_aead_algorithm,legal_id_aad_scheme_version,
              legal_id_protection_version)
    ON merchant TO payment_merchant_registration_rotation;
GRANT UPDATE (ciphertext,nonce,auth_tag,aead_key_id,aead_algorithm,
              aad_scheme_version,protection_version)
    ON merchant_document TO payment_merchant_registration_rotation;

DO $menu$
BEGIN
    IF EXISTS (
        SELECT 1 FROM iam_menu create_menu
        LEFT JOIN iam_menu parent_menu
          ON parent_menu.tenant_id=create_menu.tenant_id
         AND parent_menu.id=create_menu.parent_id
        WHERE create_menu.route_name='MerchantCreate'
          AND NOT (create_menu.menu_type='BUTTON'
            AND create_menu.menu_name='Merchant Create'
            AND create_menu.auth_code='merchant:create'
            AND create_menu.system_managed
            AND create_menu.status='ACTIVE'
            AND create_menu.deleted_at IS NULL
            AND create_menu.sort_order=205
            AND create_menu.meta_json='{"title":"merchant.create"}'::jsonb
            AND parent_menu.route_name='MerchantList'
            AND parent_menu.menu_type='PAGE'
            AND parent_menu.status='ACTIVE'
            AND parent_menu.deleted_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'V38 blocked: MerchantCreate menu is not the frozen V37 row';
    END IF;
    UPDATE iam_menu SET sort_order=207,row_version=row_version+1,
                        updated_at=statement_timestamp()
     WHERE route_name='MerchantCreate' AND sort_order=205;
END
$menu$;

DO $dictionary$
DECLARE
    industry_id BIGINT;
    legal_id BIGINT;
    changed BOOLEAN := FALSE;
BEGIN
    PERFORM revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1 FOR UPDATE;
    SELECT id INTO industry_id FROM sys_dictionary_type WHERE dict_type='MERCHANT_INDUSTRY_CODE';
    IF industry_id IS NULL THEN
        industry_id:=nextval('iam_id_seq');
        INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
        VALUES(industry_id,'MERCHANT_INDUSTRY_CODE','商户-行业',0,'');
        INSERT INTO sys_dictionary_data(id,dictionary_type_id,label,value,color,sort_order,remark)
        VALUES
          (nextval('iam_id_seq'),industry_id,'金融服务','FINANCIAL_SERVICES','processing',1,''),
          (nextval('iam_id_seq'),industry_id,'电商','ECOMMERCE','success',2,''),
          (nextval('iam_id_seq'),industry_id,'零售','RETAIL','purple',3,''),
          (nextval('iam_id_seq'),industry_id,'旅行','TRAVEL','warning',4,''),
          (nextval('iam_id_seq'),industry_id,'教育','EDUCATION','default',5,''),
          (nextval('iam_id_seq'),industry_id,'其他','OTHER','default',6,'');
        changed:=TRUE;
    ELSIF NOT EXISTS (SELECT 1 FROM sys_dictionary_type
                       WHERE id=industry_id AND dict_name='商户-行业'
                         AND sort_order=0 AND remark='')
       OR (SELECT count(*) FROM sys_dictionary_data WHERE dictionary_type_id=industry_id)<>6
       OR EXISTS (
          SELECT 1 FROM (VALUES
            ('FINANCIAL_SERVICES','金融服务','processing',1),('ECOMMERCE','电商','success',2),
            ('RETAIL','零售','purple',3),('TRAVEL','旅行','warning',4),
            ('EDUCATION','教育','default',5),('OTHER','其他','default',6)
          ) expected(value,label,color,sort_order)
          LEFT JOIN sys_dictionary_data actual ON actual.dictionary_type_id=industry_id
           AND actual.value=expected.value AND actual.label=expected.label
           AND actual.color=expected.color AND actual.sort_order=expected.sort_order
           AND actual.remark='' AND actual.deleted_at IS NULL
          WHERE actual.id IS NULL) THEN
        RAISE EXCEPTION 'V38 blocked: MERCHANT_INDUSTRY_CODE must be absent or exact';
    END IF;

    SELECT id INTO legal_id FROM sys_dictionary_type WHERE dict_type='MERCHANT_LEGAL_ID_TYPE';
    IF legal_id IS NULL THEN
        legal_id:=nextval('iam_id_seq');
        INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
        VALUES(legal_id,'MERCHANT_LEGAL_ID_TYPE','商户-法人证件类型',0,'');
        INSERT INTO sys_dictionary_data(id,dictionary_type_id,label,value,color,sort_order,remark)
        VALUES
          (nextval('iam_id_seq'),legal_id,'国民身份证','NATIONAL_ID','processing',1,''),
          (nextval('iam_id_seq'),legal_id,'护照','PASSPORT','success',2,''),
          (nextval('iam_id_seq'),legal_id,'驾驶证','DRIVER_LICENSE','warning',3,'');
        changed:=TRUE;
    ELSIF NOT EXISTS (SELECT 1 FROM sys_dictionary_type
                       WHERE id=legal_id AND dict_name='商户-法人证件类型'
                         AND sort_order=0 AND remark='')
       OR (SELECT count(*) FROM sys_dictionary_data WHERE dictionary_type_id=legal_id)<>3
       OR EXISTS (
          SELECT 1 FROM (VALUES
            ('NATIONAL_ID','国民身份证','processing',1),
            ('PASSPORT','护照','success',2),
            ('DRIVER_LICENSE','驾驶证','warning',3)
          ) expected(value,label,color,sort_order)
          LEFT JOIN sys_dictionary_data actual ON actual.dictionary_type_id=legal_id
           AND actual.value=expected.value AND actual.label=expected.label
           AND actual.color=expected.color AND actual.sort_order=expected.sort_order
           AND actual.remark='' AND actual.deleted_at IS NULL
          WHERE actual.id IS NULL) THEN
        RAISE EXCEPTION 'V38 blocked: MERCHANT_LEGAL_ID_TYPE must be absent or exact';
    END IF;
    IF changed THEN
        UPDATE sys_dictionary_catalog_revision SET revision=revision+1,
          updated_at=statement_timestamp() WHERE singleton_id=1;
    END IF;
END
$dictionary$;
