-- MCH-003: PLATFORM-assisted onboarding, reviewed amendments and private documents.
-- This migration is additive and preserves V32-V36 receipt decoding.

DO $$
BEGIN
    IF (SELECT version FROM flyway_schema_history WHERE success
         ORDER BY installed_rank DESC LIMIT 1) IS DISTINCT FROM '36' THEN
        RAISE EXCEPTION 'V37 blocked: expected exact V36 schema history';
    END IF;
END
$$;

UPDATE merchant SET merchant_type_code='PLATFORM' WHERE merchant_type_code='DIRECT';

ALTER TABLE merchant DROP CONSTRAINT ck_merchant_type_code;
ALTER TABLE merchant ADD CONSTRAINT ck_merchant_type_code CHECK (
    merchant_type_code IS NULL OR merchant_type_code IN (
        'PLATFORM','INDIRECT','COMMISSION','SALES'));

ALTER TABLE merchant
    ADD COLUMN brand_name VARCHAR(128),
    ADD COLUMN industry_code VARCHAR(32),
    ADD COLUMN registered_address VARCHAR(300),
    ADD COLUMN operating_address VARCHAR(300),
    ADD COLUMN contact_email VARCHAR(254),
    ADD COLUMN contact_phone VARCHAR(16),
    ADD COLUMN legal_id_type_code VARCHAR(32),
    ADD COLUMN legal_id_no_masked VARCHAR(128),
    ADD COLUMN legal_id_ciphertext BYTEA,
    ADD COLUMN legal_id_nonce BYTEA,
    ADD COLUMN legal_id_auth_tag BYTEA,
    ADD COLUMN legal_id_aead_key_id VARCHAR(128),
    ADD COLUMN legal_id_aead_algorithm VARCHAR(32),
    ADD COLUMN legal_id_aad_scheme_version INTEGER,
    ADD COLUMN legal_id_valid_from DATE,
    ADD COLUMN legal_id_valid_to DATE,
    ADD COLUMN application_source VARCHAR(16),
    ADD COLUMN application_actor_tenant_id BIGINT,
    ADD COLUMN application_author_membership_id BIGINT;

UPDATE merchant merchant_row
   SET application_source=audit.actor_account_domain,
       application_actor_tenant_id=audit.actor_tenant_id,
       application_author_membership_id=audit.actor_membership_id
  FROM merchant_audit_event audit
 WHERE audit.merchant_id=merchant_row.id AND audit.merchant_version=0
   AND audit.action_code='SUBMIT';

ALTER TABLE merchant
    ADD CONSTRAINT fk_merchant_application_actor_tenant FOREIGN KEY (
        application_actor_tenant_id,application_source)
        REFERENCES iam_tenant(id,account_domain),
    ADD CONSTRAINT fk_merchant_application_author FOREIGN KEY (
        application_actor_tenant_id,application_author_membership_id)
        REFERENCES iam_membership(tenant_id,id),
    ADD CONSTRAINT ck_merchant_application_author CHECK (
        (application_source IS NULL AND application_actor_tenant_id IS NULL
            AND application_author_membership_id IS NULL)
        OR (application_source IN ('PLATFORM','MERCHANT')
            AND application_actor_tenant_id IS NOT NULL
            AND application_author_membership_id IS NOT NULL)),
    ADD CONSTRAINT ck_merchant_extended_profile CHECK (
        (brand_name IS NULL AND industry_code IS NULL AND registered_address IS NULL
            AND operating_address IS NULL AND contact_email IS NULL AND contact_phone IS NULL
            AND legal_id_type_code IS NULL AND legal_id_no_masked IS NULL
            AND legal_id_ciphertext IS NULL AND legal_id_nonce IS NULL
            AND legal_id_auth_tag IS NULL AND legal_id_aead_key_id IS NULL
            AND legal_id_aead_algorithm IS NULL AND legal_id_aad_scheme_version IS NULL
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
            AND legal_id_valid_from IS NOT NULL AND legal_id_valid_to>=legal_id_valid_from)),
    ADD CONSTRAINT uk_merchant_legal_id_aead_nonce UNIQUE (
        legal_id_aead_key_id,legal_id_nonce);

ALTER TABLE merchant_registration_key_metadata DROP CONSTRAINT ck_merchant_key_purpose;
ALTER TABLE merchant_registration_key_metadata DROP CONSTRAINT ck_merchant_key_algorithm;
ALTER TABLE merchant_registration_key_metadata
    ADD CONSTRAINT ck_merchant_key_purpose CHECK (key_purpose IN (
        'REGISTRATION_SEARCH_HMAC','REGISTRATION_AEAD','LEGAL_ID_AEAD','DOCUMENT_AEAD')),
    ADD CONSTRAINT ck_merchant_key_algorithm CHECK (
        (key_purpose='REGISTRATION_SEARCH_HMAC' AND algorithm='HMAC-SHA-256')
        OR (key_purpose IN ('REGISTRATION_AEAD','LEGAL_ID_AEAD','DOCUMENT_AEAD')
            AND algorithm='AES-256-GCM'));
INSERT INTO merchant_registration_key_metadata(
    key_purpose,key_id,algorithm,active,activated_at)
VALUES
  ('LEGAL_ID_AEAD','mch-legal-id-aead-v1','AES-256-GCM',TRUE,statement_timestamp()),
  ('DOCUMENT_AEAD','mch-document-aead-v1','AES-256-GCM',TRUE,statement_timestamp());

CREATE TABLE merchant_document (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    target_tenant_id BIGINT NOT NULL REFERENCES iam_tenant(id),
    actor_tenant_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    auth_tag BYTEA NOT NULL,
    aead_key_id VARCHAR(128) NOT NULL,
    aead_algorithm VARCHAR(32) NOT NULL,
    aad_scheme_version INTEGER NOT NULL,
    protection_version INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attachment_scope VARCHAR(16) NOT NULL DEFAULT 'TEMPORARY',
    merchant_id BIGINT,
    amendment_id BIGINT,
    attached_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_merchant_document_actor FOREIGN KEY (actor_tenant_id,actor_membership_id)
        REFERENCES iam_membership(tenant_id,id),
    CONSTRAINT fk_merchant_document_key FOREIGN KEY (aead_key_id,aead_algorithm)
        REFERENCES merchant_registration_key_metadata(key_id,algorithm),
    CONSTRAINT fk_merchant_document_merchant FOREIGN KEY (merchant_id,target_tenant_id)
        REFERENCES merchant(id,tenant_id),
    CONSTRAINT uk_merchant_document_nonce UNIQUE (aead_key_id,nonce),
    CONSTRAINT ck_merchant_document_kind CHECK (kind IN (
        'BRAND_LOGO','BUSINESS_LICENSE','LEGAL_ID_FRONT','LEGAL_ID_BACK','LEGAL_ID_HOLDING')),
    CONSTRAINT ck_merchant_document_media CHECK (media_type IN ('image/png','image/jpeg')),
    CONSTRAINT ck_merchant_document_shape CHECK (
        width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
        AND width::bigint*height::bigint<=12000000
        AND size_bytes BETWEEN 1 AND 2097152
        AND octet_length(ciphertext)>=1 AND octet_length(nonce)=12
        AND octet_length(auth_tag)=16 AND aead_algorithm='AES-256-GCM'
        AND aad_scheme_version=1 AND protection_version=1),
    CONSTRAINT ck_merchant_document_attachment CHECK (
        (attachment_scope='TEMPORARY' AND merchant_id IS NULL AND amendment_id IS NULL
            AND attached_at IS NULL AND deleted_at IS NULL)
        OR (attachment_scope='MERCHANT' AND merchant_id IS NOT NULL AND amendment_id IS NULL
            AND attached_at IS NOT NULL AND deleted_at IS NULL)
        OR (attachment_scope='AMENDMENT' AND merchant_id IS NOT NULL AND amendment_id IS NOT NULL
            AND attached_at IS NOT NULL AND deleted_at IS NULL)
        OR (attachment_scope='DELETED' AND merchant_id IS NULL AND amendment_id IS NULL
            AND attached_at IS NULL AND deleted_at IS NOT NULL))
);
CREATE INDEX idx_merchant_document_temporary_expiry
    ON merchant_document(expires_at,id) WHERE attachment_scope='TEMPORARY';

CREATE TABLE merchant_amendment (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    merchant_id BIGINT NOT NULL,
    target_tenant_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    origin_merchant_version BIGINT NOT NULL,
    origin_status VARCHAR(32) NOT NULL,
    author_tenant_id BIGINT NOT NULL,
    author_membership_id BIGINT NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    brand_name VARCHAR(128) NOT NULL,
    authentication_type VARCHAR(64) NOT NULL,
    merchant_type_code VARCHAR(32) NOT NULL,
    industry_code VARCHAR(32) NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    registration_country CHAR(2) NOT NULL,
    registered_address VARCHAR(300) NOT NULL,
    operating_address VARCHAR(300) NOT NULL,
    legal_person_name VARCHAR(200) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    contact_phone VARCHAR(16) NOT NULL,
    legal_id_type_code VARCHAR(32) NOT NULL,
    legal_id_valid_from DATE NOT NULL,
    legal_id_valid_to DATE NOT NULL,
    remarks VARCHAR(300) NOT NULL,
    registration_mode VARCHAR(8) NOT NULL,
    registration_number_masked VARCHAR(128) NOT NULL,
    registration_fingerprint BYTEA,
    registration_search_key_id VARCHAR(128),
    registration_ciphertext BYTEA,
    registration_nonce BYTEA,
    registration_auth_tag BYTEA,
    registration_aead_key_id VARCHAR(128),
    registration_aead_algorithm VARCHAR(32),
    legal_id_mode VARCHAR(8) NOT NULL,
    legal_id_no_masked VARCHAR(128) NOT NULL,
    legal_id_ciphertext BYTEA,
    legal_id_nonce BYTEA,
    legal_id_auth_tag BYTEA,
    legal_id_aead_key_id VARCHAR(128),
    legal_id_aead_algorithm VARCHAR(32),
    decision VARCHAR(8),
    decision_reason_code VARCHAR(64),
    decided_by_tenant_id BIGINT,
    decided_by_membership_id BIGINT,
    decided_at TIMESTAMPTZ,
    applied_merchant_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_merchant_amendment_target FOREIGN KEY (merchant_id,target_tenant_id)
        REFERENCES merchant(id,tenant_id),
    CONSTRAINT fk_merchant_amendment_author FOREIGN KEY (author_tenant_id,author_membership_id)
        REFERENCES iam_membership(tenant_id,id),
    CONSTRAINT fk_merchant_amendment_reviewer FOREIGN KEY (
        decided_by_tenant_id,decided_by_membership_id) REFERENCES iam_membership(tenant_id,id),
    CONSTRAINT ck_merchant_amendment_status CHECK (
        status IN ('PENDING_REVIEW','APPROVED','REJECTED','STALE')),
    CONSTRAINT ck_merchant_amendment_origin CHECK (
        origin_status IN ('ACTIVE','DISABLED') AND origin_merchant_version>=0 AND row_version>=0),
    CONSTRAINT ck_merchant_amendment_author_reviewer CHECK (
        decided_by_membership_id IS NULL
        OR (author_tenant_id,author_membership_id) IS DISTINCT FROM
           (decided_by_tenant_id,decided_by_membership_id)),
    CONSTRAINT ck_merchant_amendment_decision CHECK (
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
        OR (status='STALE' AND decision='APPROVE'
            AND decision_reason_code='PLATFORM_AMENDMENT_STALE'
            AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
            AND applied_merchant_version IS NULL)),
    CONSTRAINT ck_merchant_amendment_type CHECK (
        merchant_type_code IN ('PLATFORM','INDIRECT','COMMISSION','SALES')),
    CONSTRAINT ck_merchant_amendment_industry CHECK (industry_code IN (
        'FINANCIAL_SERVICES','ECOMMERCE','RETAIL','TRAVEL','EDUCATION','OTHER')),
    CONSTRAINT ck_merchant_amendment_legal_id_type CHECK (
        legal_id_type_code IN ('NATIONAL_ID','PASSPORT','DRIVER_LICENSE')),
    CONSTRAINT ck_merchant_amendment_phone CHECK (contact_phone ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT ck_merchant_amendment_validity CHECK (legal_id_valid_to>=legal_id_valid_from)
);
CREATE UNIQUE INDEX uk_merchant_amendment_pending
    ON merchant_amendment(merchant_id) WHERE status='PENDING_REVIEW';
ALTER TABLE merchant_document ADD CONSTRAINT fk_merchant_document_amendment
    FOREIGN KEY (amendment_id) REFERENCES merchant_amendment(id);

CREATE TABLE merchant_document_binding (
    merchant_id BIGINT NOT NULL REFERENCES merchant(id),
    kind VARCHAR(32) NOT NULL,
    document_id BIGINT NOT NULL UNIQUE REFERENCES merchant_document(id),
    bound_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id,kind),
    CONSTRAINT ck_merchant_document_binding_kind CHECK (kind IN (
        'BRAND_LOGO','BUSINESS_LICENSE','LEGAL_ID_FRONT','LEGAL_ID_BACK','LEGAL_ID_HOLDING'))
);

CREATE TABLE merchant_amendment_market (
    amendment_id BIGINT NOT NULL REFERENCES merchant_amendment(id),
    market_code CHAR(3) NOT NULL,
    PRIMARY KEY(amendment_id,market_code),
    CONSTRAINT ck_merchant_amendment_market CHECK (market_code IN ('BRA','PHL'))
);

CREATE TABLE merchant_amendment_command_dedup (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    actor_tenant_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_digest BYTEA NOT NULL,
    idempotency_hmac_key_id VARCHAR(128) NOT NULL,
    command_schema_version INTEGER NOT NULL,
    canonical_digest_scheme_version INTEGER NOT NULL,
    required_permission VARCHAR(128) NOT NULL,
    amendment_id BIGINT NOT NULL REFERENCES merchant_amendment(id),
    merchant_id BIGINT NOT NULL REFERENCES merchant(id),
    result_status VARCHAR(24) NOT NULL,
    result_row_version BIGINT NOT NULL,
    result_merchant_status VARCHAR(32) NOT NULL,
    result_merchant_row_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_amendment_dedup UNIQUE (
        actor_membership_id,command_type,idempotency_key),
    CONSTRAINT fk_merchant_amendment_dedup_actor FOREIGN KEY (
        actor_tenant_id,actor_membership_id) REFERENCES iam_membership(tenant_id,id),
    CONSTRAINT ck_merchant_amendment_dedup_command CHECK (
        (command_type='AMEND' AND required_permission='merchant:amend')
        OR (command_type='REVIEW' AND required_permission='merchant:review')),
    CONSTRAINT ck_merchant_amendment_dedup_digest CHECK (octet_length(request_digest)=32)
);
CREATE TRIGGER trg_merchant_amendment_dedup_append_only
BEFORE UPDATE OR DELETE ON merchant_amendment_command_dedup
FOR EACH ROW EXECUTE FUNCTION merchant_reject_append_only_mutation();

CREATE TABLE merchant_amendment_audit_event (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    amendment_id BIGINT NOT NULL REFERENCES merchant_amendment(id),
    merchant_id BIGINT NOT NULL REFERENCES merchant(id),
    actor_tenant_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    action_code VARCHAR(32) NOT NULL,
    previous_status VARCHAR(24),
    next_status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    amendment_version BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_merchant_amendment_audit_actor FOREIGN KEY (
        actor_tenant_id,actor_membership_id) REFERENCES iam_membership(tenant_id,id),
    CONSTRAINT uk_merchant_amendment_audit_version UNIQUE(amendment_id,amendment_version),
    CONSTRAINT ck_merchant_amendment_audit_action CHECK (
        (action_code='AMEND' AND previous_status IS NULL
            AND next_status='PENDING_REVIEW' AND reason_code='PLATFORM_AMENDMENT_SUBMITTED')
        OR (action_code='APPROVE' AND previous_status='PENDING_REVIEW'
            AND next_status='APPROVED' AND reason_code='PROFILE_AMENDMENT_VERIFIED')
        OR (action_code='REJECT' AND previous_status='PENDING_REVIEW'
            AND next_status='REJECTED' AND reason_code IN (
                'PROFILE_AMENDMENT_MISMATCH','DOCUMENT_UNVERIFIED','COMPLIANCE_REJECTED'))
        OR (action_code='STALE' AND previous_status='PENDING_REVIEW'
            AND next_status='STALE' AND reason_code='PLATFORM_AMENDMENT_STALE'))
);
CREATE TRIGGER trg_merchant_amendment_audit_append_only
BEFORE UPDATE OR DELETE ON merchant_amendment_audit_event
FOR EACH ROW EXECUTE FUNCTION merchant_reject_append_only_mutation();

ALTER TABLE merchant_command_dedup DROP CONSTRAINT ck_merchant_dedup_command;
ALTER TABLE merchant_command_dedup DROP CONSTRAINT ck_merchant_dedup_permission;
ALTER TABLE merchant_command_dedup
    ADD CONSTRAINT ck_merchant_dedup_command CHECK (command_type IN (
        'SUBMIT','RESUBMIT','APPROVE','REJECT','DISABLE','ENABLE','UPDATE_PROFILE',
        'TERMINATE','CREATE')),
    ADD CONSTRAINT ck_merchant_dedup_permission CHECK (
        (command_type='SUBMIT' AND required_permission='merchant:submit')
        OR (command_type='RESUBMIT' AND required_permission='merchant:resubmit')
        OR (command_type IN ('APPROVE','REJECT') AND required_permission='merchant:review')
        OR (command_type='DISABLE' AND required_permission='merchant:disable')
        OR (command_type='ENABLE' AND required_permission='merchant:enable')
        OR (command_type='UPDATE_PROFILE' AND required_permission='merchant:update')
        OR (command_type='TERMINATE' AND required_permission='merchant:terminate')
        OR (command_type='CREATE' AND required_permission='merchant:create'));

ALTER TABLE merchant DROP CONSTRAINT ck_merchant_status_reason;
ALTER TABLE merchant ADD CONSTRAINT ck_merchant_status_reason CHECK (
    status_reason_code IS NULL
    OR (status='PENDING_REVIEW' AND status_reason_code IN (
        'APPLICATION_SUBMITTED','APPLICATION_RESUBMITTED','PLATFORM_APPLICATION_SUBMITTED'))
    OR (status='REVIEW_REJECTED' AND status_reason_code IN (
        'PROFILE_MISMATCH','REGISTRATION_UNVERIFIED','COMPLIANCE_REJECTED'))
    OR (status='ACTIVE' AND status_reason_code IN (
        'PROFILE_VERIFIED','COMPLIANCE_CLEARED','RISK_CLEARED'))
    OR (status='DISABLED' AND status_reason_code IN ('COMPLIANCE_HOLD','RISK_CONTROL'))
    OR (status='TERMINATED' AND status_reason_code IN (
        'BUSINESS_CLOSED','COMPLIANCE_TERMINATION')));

ALTER TABLE merchant_audit_event DROP CONSTRAINT ck_merchant_audit_action_reason;
ALTER TABLE merchant_audit_event DROP CONSTRAINT ck_merchant_audit_changed_fields;
ALTER TABLE merchant_audit_event
    ADD CONSTRAINT ck_merchant_audit_action_reason CHECK (
        (action_code='SUBMIT' AND reason_code='APPLICATION_SUBMITTED')
        OR (action_code='CREATE' AND reason_code='PLATFORM_APPLICATION_SUBMITTED')
        OR (action_code='RESUBMIT' AND reason_code='APPLICATION_RESUBMITTED')
        OR (action_code='UPDATE_PROFILE' AND reason_code='PLATFORM_PROFILE_UPDATED')
        OR (action_code='APPROVE' AND reason_code='PROFILE_VERIFIED')
        OR (action_code='REJECT' AND reason_code IN (
            'PROFILE_MISMATCH','REGISTRATION_UNVERIFIED','COMPLIANCE_REJECTED'))
        OR (action_code='DISABLE' AND reason_code IN ('COMPLIANCE_HOLD','RISK_CONTROL'))
        OR (action_code='ENABLE' AND reason_code IN ('COMPLIANCE_CLEARED','RISK_CLEARED'))
        OR (action_code='TERMINATE' AND reason_code IN (
            'BUSINESS_CLOSED','COMPLIANCE_TERMINATION'))),
    ADD CONSTRAINT ck_merchant_audit_changed_fields CHECK (
        jsonb_typeof(changed_fields)='array'
        AND NOT (changed_fields - ARRAY[
            'legalName','displayName','registrationCountry','registrationNumber',
            'merchantTypeCode','legalPersonName','authenticationType','remarks','marketCodes',
            'brandName','industryCode','brandLogoDocument','registeredAddress','operatingAddress',
            'businessLicenseDocument','contactEmail','contactPhone','legalIdTypeCode','legalIdNo',
            'legalIdValidity','legalIdFrontDocument','legalIdBackDocument',
            'legalIdHoldingDocument']::TEXT[]) <> '[]'::jsonb);

CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'merchant rows are never physically deleted'; END IF;
    IF OLD.tenant_id<>NEW.tenant_id OR OLD.account_domain<>NEW.account_domain THEN
        RAISE EXCEPTION 'merchant tenant binding is immutable';
    END IF;
    IF OLD.merchant_code<>NEW.merchant_code THEN RAISE EXCEPTION 'merchant code is immutable'; END IF;
    IF OLD.status=NEW.status AND current_user='payment_merchant_registration_rotation' THEN
        IF OLD.row_version<>NEW.row_version THEN
            RAISE EXCEPTION 'merchant registration rotation cannot advance aggregate version';
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
               AND amendment.remarks=NEW.remarks) THEN
            NEW.updated_at:=statement_timestamp();
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'merchant profile change requires an approved amendment';
    END IF;
    NEW.updated_at:=statement_timestamp();
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM iam_permission WHERE permission_code IN (
        'merchant:create','merchant:amend','merchant:document:upload','merchant:document:view')) THEN
        RAISE EXCEPTION 'V37 blocked: reserved MCH-003 permissions already exist';
    END IF;
END
$$;
INSERT INTO iam_permission(
    id,permission_code,resource_code,action_code,risk_level,required_dimensions,
    requires_step_up,requires_approval,status,description,cross_tenant_mode)
VALUES
  (nextval('iam_id_seq'),'merchant:create','merchant','create','SENSITIVE',
   ARRAY['TENANT']::varchar(32)[],TRUE,FALSE,'ACTIVE','Create Merchant application','SAME_TENANT_ONLY'),
  (nextval('iam_id_seq'),'merchant:amend','merchant','amend','SENSITIVE',
   ARRAY['TENANT']::varchar(32)[],TRUE,FALSE,'ACTIVE','Submit Merchant amendment','SAME_TENANT_ONLY'),
  (nextval('iam_id_seq'),'merchant:document:upload','merchant:document','upload','SENSITIVE',
   ARRAY['TENANT']::varchar(32)[],TRUE,FALSE,'ACTIVE','Upload Merchant document','SAME_TENANT_ONLY'),
  (nextval('iam_id_seq'),'merchant:document:view','merchant:document','view','SENSITIVE',
   ARRAY['TENANT']::varchar(32)[],TRUE,FALSE,'ACTIVE','View Merchant document','SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE mch_v37_platform_role(tenant_id BIGINT PRIMARY KEY,role_id BIGINT NOT NULL)
ON COMMIT DROP;
INSERT INTO mch_v37_platform_role(tenant_id,role_id)
SELECT role_row.tenant_id,role_row.id FROM iam_role role_row
JOIN iam_tenant tenant ON tenant.id=role_row.tenant_id
WHERE tenant.account_domain='PLATFORM' AND role_row.system_role AND NOT role_row.assignable
  AND role_row.status='ACTIVE' AND role_row.deleted_at IS NULL
  AND EXISTS (
      SELECT 1 FROM iam_role_grant portal_grant
      JOIN iam_permission portal_permission ON portal_permission.id=portal_grant.permission_id
      JOIN iam_grant_dimension dimension ON dimension.grant_id=portal_grant.id
      WHERE portal_grant.tenant_id=role_row.tenant_id AND portal_grant.role_id=role_row.id
        AND portal_grant.grant_key='system-backoffice-access' AND portal_grant.status='ACTIVE'
        AND portal_permission.permission_code='backoffice:platform-access'
        AND dimension.dimension_code='TENANT' AND dimension.scope_mode='TENANT_ALL'
        AND NOT EXISTS (SELECT 1 FROM iam_grant_target target
                         WHERE target.dimension_id=dimension.id));
DO $$
BEGIN
    IF EXISTS (SELECT tenant.id FROM iam_tenant tenant
       LEFT JOIN mch_v37_platform_role selected ON selected.tenant_id=tenant.id
       WHERE tenant.account_domain='PLATFORM' GROUP BY tenant.id
       HAVING count(selected.role_id)<>1) THEN
        RAISE EXCEPTION 'V37 blocked: MCH-003 needs one protected PLATFORM role per tenant';
    END IF;
END
$$;
CREATE TEMPORARY TABLE mch_v37_grant(id BIGINT PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO iam_role_grant(
        id,tenant_id,role_id,permission_id,grant_key,status,valid_from,valid_until)
    SELECT nextval('iam_id_seq'),selected.tenant_id,selected.role_id,permission.id,
           'system-'||replace(permission.permission_code,':','-'),'ACTIVE',statement_timestamp(),
           statement_timestamp()+interval '10 years'
      FROM mch_v37_platform_role selected CROSS JOIN iam_permission permission
     WHERE permission.permission_code IN (
        'merchant:create','merchant:amend','merchant:document:upload','merchant:document:view')
    RETURNING id)
INSERT INTO mch_v37_grant SELECT id FROM inserted;
INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT nextval('iam_id_seq'),id,'TENANT','TENANT_ALL' FROM mch_v37_grant;

UPDATE iam_menu SET auth_code='merchant:amend',updated_at=statement_timestamp(),
                    row_version=row_version+1
 WHERE route_name='MerchantEdit' AND auth_code='merchant:update' AND system_managed;
WITH page AS (
    SELECT selected.tenant_id,selected.role_id,menu.id parent_id
      FROM mch_v37_platform_role selected JOIN iam_menu menu
        ON menu.tenant_id=selected.tenant_id AND menu.route_name='MerchantList'
       AND menu.menu_type='PAGE' AND menu.status='ACTIVE' AND menu.deleted_at IS NULL), inserted AS (
    INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,sort_order,
                         auth_code,status,meta_json,system_managed)
    SELECT nextval('iam_id_seq'),tenant_id,parent_id,'BUTTON','Merchant Create','MerchantCreate',
           205,'merchant:create','ACTIVE','{"title":"merchant.create"}'::jsonb,TRUE
      FROM page RETURNING tenant_id,id)
INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
SELECT inserted.tenant_id,selected.role_id,inserted.id FROM inserted
JOIN mch_v37_platform_role selected ON selected.tenant_id=inserted.tenant_id;

WITH changed AS (
    UPDATE iam_membership membership
       SET permission_version=permission_version+1,updated_at=statement_timestamp(),
           row_version=row_version+1
      FROM iam_membership_role assignment JOIN mch_v37_platform_role selected
        ON selected.tenant_id=assignment.tenant_id AND selected.role_id=assignment.role_id
     WHERE membership.tenant_id=assignment.tenant_id AND membership.id=assignment.membership_id
    RETURNING membership.tenant_id,membership.id,membership.permission_version)
INSERT INTO iam_permission_change_outbox(
    id,tenant_id,aggregate_type,aggregate_ref,event_type,payload,aggregate_version,
    schema_version,partition_key,trace_id)
SELECT nextval('iam_id_seq'),tenant_id,'MEMBERSHIP',id::text,'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('tenantId',tenant_id,'membershipId',id,
                          'permissionVersion',permission_version,
                          'reason','V37_MERCHANT_ONBOARDING_ACCESS'),
       permission_version,1,tenant_id::text||':'||id::text,'migration-v37'
  FROM changed;

DO $$
DECLARE type_id BIGINT;
BEGIN
    SELECT id INTO type_id FROM sys_dictionary_type WHERE dict_type='MERCHANT_TYPE_CODE';
    UPDATE sys_dictionary_data SET deleted_at=statement_timestamp(),row_version=row_version+1
     WHERE dictionary_type_id=type_id AND value='DIRECT' AND deleted_at IS NULL;
    UPDATE sys_dictionary_data SET sort_order=1,row_version=row_version+1
     WHERE dictionary_type_id=type_id AND value='PLATFORM' AND deleted_at IS NULL;
    UPDATE sys_dictionary_catalog_revision SET revision=revision+1,
        updated_at=statement_timestamp() WHERE singleton_id=1;
END
$$;
