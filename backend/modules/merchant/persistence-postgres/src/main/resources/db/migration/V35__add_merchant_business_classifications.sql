-- Merchant business classifications and their PLATFORM-owned display dictionaries.
-- Historical merchants remain unclassified until the first successful V35 profile update.

ALTER TABLE merchant
    ADD COLUMN merchant_type_code VARCHAR(32),
    ADD COLUMN legal_person_name VARCHAR(200),
    ADD COLUMN authentication_type VARCHAR(64);

ALTER TABLE merchant
    ADD CONSTRAINT ck_merchant_business_classification_shape CHECK (
        (merchant_type_code IS NULL AND legal_person_name IS NULL
            AND authentication_type IS NULL)
        OR (merchant_type_code IS NOT NULL AND legal_person_name IS NOT NULL
            AND authentication_type IS NOT NULL)),
    ADD CONSTRAINT ck_merchant_type_code CHECK (
        merchant_type_code IS NULL OR merchant_type_code IN (
            'DIRECT', 'INDIRECT', 'COMMISSION', 'SALES', 'PLATFORM')),
    ADD CONSTRAINT ck_merchant_legal_person_name CHECK (
        legal_person_name IS NULL OR (
            btrim(legal_person_name) = legal_person_name
            AND char_length(legal_person_name) BETWEEN 1 AND 200)),
    ADD CONSTRAINT ck_merchant_authentication_type CHECK (
        authentication_type IS NULL OR authentication_type IN (
            'ENTERPRISE', 'NON_PROFIT_ORGANIZATIONS', 'CLIQUE',
            'INDIVIDUAL', 'INDIVIDUAL_HOUSEHOLD'));

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
           OR (OLD.legal_name, OLD.display_name, OLD.merchant_type_code,
               OLD.legal_person_name, OLD.authentication_type,
               OLD.registration_country, OLD.registration_number_masked, OLD.remarks,
               OLD.status, OLD.status_reason_code, OLD.submitted_at, OLD.reviewed_at,
               OLD.last_decision, OLD.last_decision_reason_code,
               OLD.last_decided_by_membership_id, OLD.last_decided_at,
               OLD.created_at, OLD.updated_at)
              IS DISTINCT FROM
              (NEW.legal_name, NEW.display_name, NEW.merchant_type_code,
               NEW.legal_person_name, NEW.authentication_type,
               NEW.registration_country, NEW.registration_number_masked, NEW.remarks,
               NEW.status, NEW.status_reason_code, NEW.submitted_at, NEW.reviewed_at,
               NEW.last_decision, NEW.last_decision_reason_code,
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
        IF OLD.status NOT IN ('ACTIVE', 'DISABLED') THEN
            RAISE EXCEPTION 'merchant profile update requires active or disabled state';
        END IF;
        IF OLD.status_reason_code IS DISTINCT FROM NEW.status_reason_code
           OR (OLD.registration_country, OLD.registration_number_masked,
               OLD.registration_fingerprint, OLD.registration_search_key_id,
               OLD.registration_fingerprint_algorithm, OLD.registration_normalization_version,
               OLD.registration_ciphertext, OLD.registration_nonce, OLD.registration_auth_tag,
               OLD.registration_aead_key_id, OLD.registration_aead_algorithm,
               OLD.submitted_at, OLD.reviewed_at, OLD.last_decision,
               OLD.last_decision_reason_code, OLD.last_decided_by_membership_id,
               OLD.last_decided_at, OLD.created_at)
              IS DISTINCT FROM
              (NEW.registration_country, NEW.registration_number_masked,
               NEW.registration_fingerprint, NEW.registration_search_key_id,
               NEW.registration_fingerprint_algorithm, NEW.registration_normalization_version,
               NEW.registration_ciphertext, NEW.registration_nonce, NEW.registration_auth_tag,
               NEW.registration_aead_key_id, NEW.registration_aead_algorithm,
               NEW.submitted_at, NEW.reviewed_at, NEW.last_decision,
               NEW.last_decision_reason_code, NEW.last_decided_by_membership_id,
               NEW.last_decided_at, NEW.created_at) THEN
            RAISE EXCEPTION 'merchant profile update changed protected lifecycle fields';
        END IF;
        NEW.updated_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF (OLD.merchant_type_code, OLD.legal_person_name, OLD.authentication_type)
       IS DISTINCT FROM
       (NEW.merchant_type_code, NEW.legal_person_name, NEW.authentication_type) THEN
        RAISE EXCEPTION 'merchant business classification changes require profile update';
    END IF;
    IF NEW.status_reason_code IS NULL
       OR OLD.status_reason_code IS NOT DISTINCT FROM NEW.status_reason_code THEN
        RAISE EXCEPTION 'merchant lifecycle transition requires a new status reason';
    END IF;
    IF (OLD.legal_name, OLD.display_name, OLD.registration_country,
        OLD.registration_number_masked, OLD.registration_fingerprint,
        OLD.registration_search_key_id, OLD.registration_fingerprint_algorithm,
        OLD.registration_normalization_version, OLD.registration_ciphertext,
        OLD.registration_nonce, OLD.registration_auth_tag, OLD.registration_aead_key_id,
        OLD.registration_aead_algorithm, OLD.remarks)
       IS DISTINCT FROM
       (NEW.legal_name, NEW.display_name, NEW.registration_country,
        NEW.registration_number_masked, NEW.registration_fingerprint,
        NEW.registration_search_key_id, NEW.registration_fingerprint_algorithm,
        NEW.registration_normalization_version, NEW.registration_ciphertext,
        NEW.registration_nonce, NEW.registration_auth_tag, NEW.registration_aead_key_id,
        NEW.registration_aead_algorithm, NEW.remarks)
       AND NOT (OLD.status = 'REVIEW_REJECTED' AND NEW.status = 'PENDING_REVIEW') THEN
        RAISE EXCEPTION 'merchant profile may change only on rejected resubmission';
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;

ALTER TABLE merchant_audit_event DROP CONSTRAINT ck_merchant_audit_changed_fields;
ALTER TABLE merchant_audit_event
    ADD CONSTRAINT ck_merchant_audit_changed_fields CHECK (
        jsonb_typeof(changed_fields) = 'array'
        AND NOT (changed_fields - ARRAY[
            'legalName', 'displayName', 'registrationCountry', 'registrationNumber',
            'merchantTypeCode', 'legalPersonName', 'authenticationType',
            'remarks', 'marketCodes']::TEXT[]) <> '[]'::jsonb);

DO $$
DECLARE
    selected_type_id BIGINT;
    selected_type_count INTEGER;
    catalog_changed BOOLEAN := FALSE;
BEGIN
    PERFORM revision FROM sys_dictionary_catalog_revision
     WHERE singleton_id = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V35 blocked: dictionary catalog revision singleton is missing';
    END IF;

    SELECT count(*), min(id) INTO selected_type_count, selected_type_id
      FROM sys_dictionary_type WHERE dict_type = 'MERCHANT_TYPE_CODE';
    IF selected_type_count = 0 THEN
        selected_type_id := nextval('iam_id_seq');
        INSERT INTO sys_dictionary_type(id, dict_type, dict_name, sort_order, remark)
        VALUES (selected_type_id, 'MERCHANT_TYPE_CODE', '商户-商户类型', 0, '');
        INSERT INTO sys_dictionary_data(
            id, dictionary_type_id, label, value, color, sort_order, remark)
        VALUES
          (nextval('iam_id_seq'), selected_type_id, '直连商户', 'DIRECT', 'success', 1, ''),
          (nextval('iam_id_seq'), selected_type_id, '间连商户', 'INDIRECT', 'warning', 2, ''),
          (nextval('iam_id_seq'), selected_type_id, '分佣商户', 'COMMISSION', 'default', 3, ''),
          (nextval('iam_id_seq'), selected_type_id, '销售商户', 'SALES', 'processing', 4, ''),
          (nextval('iam_id_seq'), selected_type_id, '平台商户', 'PLATFORM', 'purple', 5, '');
        catalog_changed := TRUE;
    ELSIF selected_type_count <> 1
       OR NOT EXISTS (
           SELECT 1 FROM sys_dictionary_type type_row
            WHERE type_row.id = selected_type_id
              AND type_row.dict_type = 'MERCHANT_TYPE_CODE'
              AND type_row.dict_name = '商户-商户类型' AND type_row.sort_order = 0
              AND type_row.remark = '' AND type_row.row_version = 0
              AND type_row.deleted_at IS NULL)
       OR (SELECT count(*) FROM sys_dictionary_data
            WHERE dictionary_type_id = selected_type_id) <> 5
       OR (SELECT count(*) FROM sys_dictionary_data data_row
            WHERE data_row.dictionary_type_id = selected_type_id
              AND data_row.remark = '' AND data_row.row_version = 0
              AND data_row.deleted_at IS NULL
              AND ((data_row.value='DIRECT' AND data_row.label='直连商户'
                    AND data_row.color='success' AND data_row.sort_order=1)
                OR (data_row.value='INDIRECT' AND data_row.label='间连商户'
                    AND data_row.color='warning' AND data_row.sort_order=2)
                OR (data_row.value='COMMISSION' AND data_row.label='分佣商户'
                    AND data_row.color='default' AND data_row.sort_order=3)
                OR (data_row.value='SALES' AND data_row.label='销售商户'
                    AND data_row.color='processing' AND data_row.sort_order=4)
                OR (data_row.value='PLATFORM' AND data_row.label='平台商户'
                    AND data_row.color='purple' AND data_row.sort_order=5))) <> 5 THEN
        RAISE EXCEPTION
            'V35 blocked: MERCHANT_TYPE_CODE must be absent or exactly match the canonical seed';
    END IF;

    SELECT count(*), min(id) INTO selected_type_count, selected_type_id
      FROM sys_dictionary_type WHERE dict_type = 'MERCHANT_AUTH_TYPE';
    IF selected_type_count = 0 THEN
        selected_type_id := nextval('iam_id_seq');
        INSERT INTO sys_dictionary_type(id, dict_type, dict_name, sort_order, remark)
        VALUES (selected_type_id, 'MERCHANT_AUTH_TYPE', '商户-认证类型', 0, '');
        INSERT INTO sys_dictionary_data(
            id, dictionary_type_id, label, value, color, sort_order, remark)
        VALUES
          (nextval('iam_id_seq'), selected_type_id, '企业', 'ENTERPRISE', 'processing', 1, ''),
          (nextval('iam_id_seq'), selected_type_id, '非盈利组织',
           'NON_PROFIT_ORGANIZATIONS', 'success', 2, ''),
          (nextval('iam_id_seq'), selected_type_id, '集团', 'CLIQUE', 'purple', 3, ''),
          (nextval('iam_id_seq'), selected_type_id, '个人', 'INDIVIDUAL', 'default', 4, ''),
          (nextval('iam_id_seq'), selected_type_id, '个体户',
           'INDIVIDUAL_HOUSEHOLD', 'warning', 5, '');
        catalog_changed := TRUE;
    ELSIF selected_type_count <> 1
       OR NOT EXISTS (
           SELECT 1 FROM sys_dictionary_type type_row
            WHERE type_row.id = selected_type_id
              AND type_row.dict_type = 'MERCHANT_AUTH_TYPE'
              AND type_row.dict_name = '商户-认证类型' AND type_row.sort_order = 0
              AND type_row.remark = '' AND type_row.row_version = 0
              AND type_row.deleted_at IS NULL)
       OR (SELECT count(*) FROM sys_dictionary_data
            WHERE dictionary_type_id = selected_type_id) <> 5
       OR (SELECT count(*) FROM sys_dictionary_data data_row
            WHERE data_row.dictionary_type_id = selected_type_id
              AND data_row.remark = '' AND data_row.row_version = 0
              AND data_row.deleted_at IS NULL
              AND ((data_row.value='ENTERPRISE' AND data_row.label='企业'
                    AND data_row.color='processing' AND data_row.sort_order=1)
                OR (data_row.value='NON_PROFIT_ORGANIZATIONS'
                    AND data_row.label='非盈利组织' AND data_row.color='success'
                    AND data_row.sort_order=2)
                OR (data_row.value='CLIQUE' AND data_row.label='集团'
                    AND data_row.color='purple' AND data_row.sort_order=3)
                OR (data_row.value='INDIVIDUAL' AND data_row.label='个人'
                    AND data_row.color='default' AND data_row.sort_order=4)
                OR (data_row.value='INDIVIDUAL_HOUSEHOLD' AND data_row.label='个体户'
                    AND data_row.color='warning' AND data_row.sort_order=5))) <> 5 THEN
        RAISE EXCEPTION
            'V35 blocked: MERCHANT_AUTH_TYPE must be absent or exactly match the canonical seed';
    END IF;

    IF catalog_changed THEN
        UPDATE sys_dictionary_catalog_revision
           SET revision = revision + 1, updated_at = statement_timestamp()
         WHERE singleton_id = 1;
    END IF;
END
$$;

COMMENT ON COLUMN merchant.merchant_type_code IS
    'Merchant business classification; never an authorization or tenancy dimension';
COMMENT ON COLUMN merchant.legal_person_name IS
    'Merchant legal representative name, populated by the first V35 profile update';
COMMENT ON COLUMN merchant.authentication_type IS
    'Merchant authentication classification; never an identity-provider fact';
