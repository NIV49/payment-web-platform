-- MCH-001 is additive. Merchant is a business aggregate, not an IAM tenant profile.

CREATE TABLE merchant_registration_key_metadata (
    key_purpose VARCHAR(32) NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key_purpose, key_id),
    CONSTRAINT uk_merchant_key_id_algorithm UNIQUE (key_id, algorithm),
    CONSTRAINT ck_merchant_key_purpose CHECK (
        key_purpose IN ('REGISTRATION_SEARCH_HMAC', 'REGISTRATION_AEAD')),
    CONSTRAINT ck_merchant_key_algorithm CHECK (
        (key_purpose = 'REGISTRATION_SEARCH_HMAC' AND algorithm = 'HMAC-SHA-256')
        OR (key_purpose = 'REGISTRATION_AEAD' AND algorithm = 'AES-256-GCM')),
    CONSTRAINT ck_merchant_key_activation CHECK (
        (active AND activated_at IS NOT NULL) OR (NOT active))
);

CREATE UNIQUE INDEX uk_merchant_active_registration_key
    ON merchant_registration_key_metadata(key_purpose) WHERE active;

INSERT INTO merchant_registration_key_metadata(
    key_purpose, key_id, algorithm, active, activated_at)
VALUES
  ('REGISTRATION_SEARCH_HMAC', 'mch-registration-search-v1', 'HMAC-SHA-256', TRUE, now()),
  ('REGISTRATION_AEAD', 'mch-registration-aead-v1', 'AES-256-GCM', TRUE, now());

CREATE OR REPLACE FUNCTION merchant_require_one_active_key()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    purpose VARCHAR(32);
BEGIN
    purpose := COALESCE(NEW.key_purpose, OLD.key_purpose);
    IF (SELECT count(*) FROM merchant_registration_key_metadata
         WHERE key_purpose = purpose AND active) <> 1 THEN
        RAISE EXCEPTION 'merchant registration key purpose must have exactly one active key';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.key_purpose <> NEW.key_purpose THEN
        IF (SELECT count(*) FROM merchant_registration_key_metadata
             WHERE key_purpose = OLD.key_purpose AND active) <> 1 THEN
            RAISE EXCEPTION 'merchant registration key purpose must have exactly one active key';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_merchant_registration_active_key
AFTER INSERT OR UPDATE OR DELETE ON merchant_registration_key_metadata
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION merchant_require_one_active_key();

CREATE TABLE merchant (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    tenant_id BIGINT NOT NULL,
    account_domain VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    merchant_code VARCHAR(64) NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    registration_country CHAR(2) NOT NULL,
    registration_number_masked VARCHAR(128) NOT NULL,
    registration_fingerprint BYTEA NOT NULL,
    registration_search_key_id VARCHAR(128) NOT NULL,
    registration_fingerprint_algorithm VARCHAR(32) NOT NULL,
    registration_normalization_version INTEGER NOT NULL,
    registration_ciphertext BYTEA NOT NULL,
    registration_nonce BYTEA NOT NULL,
    registration_auth_tag BYTEA NOT NULL,
    registration_aead_key_id VARCHAR(128) NOT NULL,
    registration_aead_algorithm VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    last_decision VARCHAR(16),
    last_decision_reason_code VARCHAR(64),
    last_decided_by_membership_id BIGINT,
    last_decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_tenant UNIQUE (tenant_id),
    CONSTRAINT uk_merchant_code UNIQUE (merchant_code),
    CONSTRAINT uk_merchant_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uk_merchant_registration_fingerprint UNIQUE (
        registration_country, registration_fingerprint),
    CONSTRAINT uk_merchant_registration_aead_nonce UNIQUE (
        registration_aead_key_id, registration_nonce),
    CONSTRAINT fk_merchant_tenant_domain FOREIGN KEY (tenant_id, account_domain)
        REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT fk_merchant_search_key FOREIGN KEY (
        registration_search_key_id, registration_fingerprint_algorithm)
        REFERENCES merchant_registration_key_metadata(key_id, algorithm),
    CONSTRAINT fk_merchant_aead_key FOREIGN KEY (
        registration_aead_key_id, registration_aead_algorithm)
        REFERENCES merchant_registration_key_metadata(key_id, algorithm),
    CONSTRAINT ck_merchant_account_domain CHECK (account_domain = 'MERCHANT'),
    CONSTRAINT ck_merchant_code CHECK (
        merchant_code ~ '^MCH_[A-Z0-9_]{1,59}$'),
    CONSTRAINT ck_merchant_legal_name CHECK (
        btrim(legal_name) = legal_name AND legal_name <> ''),
    CONSTRAINT ck_merchant_display_name CHECK (
        btrim(display_name) = display_name AND display_name <> ''),
    CONSTRAINT ck_merchant_country CHECK (registration_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_merchant_mask CHECK (registration_number_masked ~ '^\*+.{0,4}$'),
    CONSTRAINT ck_merchant_registration_crypto CHECK (
        octet_length(registration_fingerprint) = 32
        AND registration_fingerprint_algorithm = 'HMAC-SHA-256'
        AND registration_normalization_version = 1
        AND octet_length(registration_ciphertext) >= 1
        AND octet_length(registration_nonce) = 12
        AND octet_length(registration_auth_tag) = 16
        AND registration_aead_algorithm = 'AES-256-GCM'),
    CONSTRAINT ck_merchant_status CHECK (status IN (
        'PENDING_REVIEW', 'REVIEW_REJECTED', 'ACTIVE', 'DISABLED', 'TERMINATED')),
    CONSTRAINT ck_merchant_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_merchant_last_decision CHECK (
        (last_decision IS NULL AND last_decision_reason_code IS NULL
            AND last_decided_by_membership_id IS NULL AND last_decided_at IS NULL)
        OR (last_decision IN ('APPROVE', 'REJECT')
            AND last_decision_reason_code IS NOT NULL
            AND last_decided_by_membership_id IS NOT NULL
            AND last_decided_at IS NOT NULL))
);

CREATE INDEX idx_merchant_platform_list
    ON merchant(status, created_at DESC, id DESC);
CREATE INDEX idx_merchant_country_list
    ON merchant(registration_country, created_at DESC, id DESC);
CREATE INDEX idx_merchant_name_search
    ON merchant(lower(legal_name), lower(display_name));

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
       AND current_setting('payment.merchant_registration_rotation', TRUE) = 'on' THEN
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

CREATE TRIGGER trg_merchant_lifecycle
BEFORE UPDATE OR DELETE ON merchant
FOR EACH ROW EXECUTE FUNCTION merchant_enforce_lifecycle();

CREATE TABLE merchant_command_dedup (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    actor_account_domain VARCHAR(16) NOT NULL,
    actor_tenant_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_digest BYTEA NOT NULL,
    idempotency_hmac_key_id VARCHAR(128) NOT NULL,
    command_schema_version INTEGER NOT NULL,
    canonical_digest_scheme_version INTEGER NOT NULL,
    registration_normalization_version INTEGER,
    required_permission VARCHAR(128) NOT NULL,
    merchant_id BIGINT NOT NULL REFERENCES merchant(id),
    result_merchant_code VARCHAR(64) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    result_row_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_command_dedup UNIQUE (
        actor_account_domain, actor_membership_id, command_type, idempotency_key),
    CONSTRAINT fk_merchant_dedup_actor_tenant FOREIGN KEY (
        actor_tenant_id, actor_account_domain) REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT fk_merchant_dedup_actor_membership FOREIGN KEY (
        actor_tenant_id, actor_membership_id) REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT ck_merchant_dedup_domain CHECK (
        actor_account_domain IN ('PLATFORM', 'MERCHANT')),
    CONSTRAINT ck_merchant_dedup_command CHECK (command_type IN (
        'SUBMIT', 'RESUBMIT', 'APPROVE', 'REJECT', 'DISABLE', 'ENABLE', 'TERMINATE')),
    CONSTRAINT ck_merchant_dedup_digest CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_merchant_dedup_versions CHECK (
        command_schema_version > 0 AND canonical_digest_scheme_version > 0
        AND (registration_normalization_version IS NULL
            OR registration_normalization_version > 0)
        AND result_row_version >= 0),
    CONSTRAINT ck_merchant_dedup_permission CHECK (
        (command_type = 'SUBMIT' AND required_permission = 'merchant:submit')
        OR (command_type = 'RESUBMIT' AND required_permission = 'merchant:resubmit')
        OR (command_type IN ('APPROVE', 'REJECT') AND required_permission = 'merchant:review')
        OR (command_type = 'DISABLE' AND required_permission = 'merchant:disable')
        OR (command_type = 'ENABLE' AND required_permission = 'merchant:enable')
        OR (command_type = 'TERMINATE' AND required_permission = 'merchant:terminate')),
    CONSTRAINT ck_merchant_dedup_result_status CHECK (result_status IN (
        'PENDING_REVIEW', 'REVIEW_REJECTED', 'ACTIVE', 'DISABLED', 'TERMINATED'))
);

CREATE TABLE merchant_audit_event (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    merchant_id BIGINT NOT NULL,
    target_tenant_id BIGINT NOT NULL,
    actor_account_domain VARCHAR(16) NOT NULL,
    actor_tenant_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    action_code VARCHAR(32) NOT NULL,
    previous_status VARCHAR(32),
    next_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    changed_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    trace_id VARCHAR(64) NOT NULL,
    merchant_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_merchant_audit_target FOREIGN KEY (merchant_id, target_tenant_id)
        REFERENCES merchant(id, tenant_id),
    CONSTRAINT fk_merchant_audit_actor_tenant FOREIGN KEY (
        actor_tenant_id, actor_account_domain) REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT fk_merchant_audit_actor_membership FOREIGN KEY (
        actor_tenant_id, actor_membership_id) REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT ck_merchant_audit_domain CHECK (
        actor_account_domain IN ('PLATFORM', 'MERCHANT')),
    CONSTRAINT ck_merchant_audit_status CHECK (
        (previous_status IS NULL OR previous_status IN (
            'PENDING_REVIEW', 'REVIEW_REJECTED', 'ACTIVE', 'DISABLED', 'TERMINATED'))
        AND next_status IN (
            'PENDING_REVIEW', 'REVIEW_REJECTED', 'ACTIVE', 'DISABLED', 'TERMINATED')),
    CONSTRAINT ck_merchant_audit_action_reason CHECK (
        (action_code = 'SUBMIT' AND reason_code = 'APPLICATION_SUBMITTED')
        OR (action_code = 'RESUBMIT' AND reason_code = 'APPLICATION_RESUBMITTED')
        OR (action_code = 'APPROVE' AND reason_code = 'PROFILE_VERIFIED')
        OR (action_code = 'REJECT' AND reason_code IN (
            'PROFILE_MISMATCH', 'REGISTRATION_UNVERIFIED', 'COMPLIANCE_REJECTED'))
        OR (action_code = 'DISABLE' AND reason_code IN ('COMPLIANCE_HOLD', 'RISK_CONTROL'))
        OR (action_code = 'ENABLE' AND reason_code IN ('COMPLIANCE_CLEARED', 'RISK_CLEARED'))
        OR (action_code = 'TERMINATE' AND reason_code IN (
            'BUSINESS_CLOSED', 'COMPLIANCE_TERMINATION'))),
    CONSTRAINT ck_merchant_audit_changed_fields CHECK (
        jsonb_typeof(changed_fields) = 'array'
        AND NOT (changed_fields - ARRAY[
            'legalName', 'displayName', 'registrationCountry', 'registrationNumber']::TEXT[])
            <> '[]'::jsonb),
    CONSTRAINT ck_merchant_audit_version CHECK (merchant_version >= 0)
);

CREATE INDEX idx_merchant_audit_target_time
    ON merchant_audit_event(merchant_id, occurred_at DESC, id DESC);

CREATE OR REPLACE FUNCTION merchant_reject_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_merchant_dedup_append_only
BEFORE UPDATE OR DELETE ON merchant_command_dedup
FOR EACH ROW EXECUTE FUNCTION merchant_reject_append_only_mutation();
CREATE TRIGGER trg_merchant_audit_append_only
BEFORE UPDATE OR DELETE ON merchant_audit_event
FOR EACH ROW EXECUTE FUNCTION merchant_reject_append_only_mutation();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM iam_permission WHERE permission_code IN (
        'merchant:self-view', 'merchant:submit', 'merchant:resubmit', 'merchant:view',
        'merchant:review', 'merchant:disable', 'merchant:enable', 'merchant:terminate')) THEN
        RAISE EXCEPTION 'V32 blocked: reserved MCH-001 permissions already exist';
    END IF;
END
$$;

INSERT INTO iam_permission(
    id, permission_code, resource_code, action_code, risk_level,
    required_dimensions, requires_step_up, requires_approval, status, description,
    cross_tenant_mode)
VALUES
  (3033, 'merchant:self-view', 'merchant', 'self-view', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], FALSE, FALSE, 'ACTIVE', 'View own Merchant application', 'SAME_TENANT_ONLY'),
  (3034, 'merchant:submit', 'merchant', 'submit', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], FALSE, FALSE, 'ACTIVE', 'Submit first Merchant application', 'SAME_TENANT_ONLY'),
  (3035, 'merchant:resubmit', 'merchant', 'resubmit', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], FALSE, FALSE, 'ACTIVE', 'Resubmit rejected Merchant application', 'SAME_TENANT_ONLY'),
  (3036, 'merchant:view', 'merchant', 'view', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], FALSE, FALSE, 'ACTIVE', 'View Merchant control plane', 'SAME_TENANT_ONLY'),
  (3037, 'merchant:review', 'merchant', 'review', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], TRUE, FALSE, 'ACTIVE', 'Review Merchant application', 'SAME_TENANT_ONLY'),
  (3038, 'merchant:disable', 'merchant', 'disable', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], TRUE, FALSE, 'ACTIVE', 'Disable active Merchant', 'SAME_TENANT_ONLY'),
  (3039, 'merchant:enable', 'merchant', 'enable', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], TRUE, FALSE, 'ACTIVE', 'Enable disabled Merchant', 'SAME_TENANT_ONLY'),
  (3040, 'merchant:terminate', 'merchant', 'terminate', 'SENSITIVE', ARRAY['TENANT']::VARCHAR(32)[], TRUE, FALSE, 'ACTIVE', 'Terminate Merchant lifecycle', 'SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE mch_v32_role(
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    account_domain VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, role_id)
) ON COMMIT DROP;

INSERT INTO mch_v32_role(tenant_id, role_id, account_domain)
SELECT role_row.tenant_id, role_row.id, tenant.account_domain
  FROM iam_role role_row
  JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
 WHERE tenant.account_domain IN ('PLATFORM', 'MERCHANT')
   AND role_row.system_role AND NOT role_row.assignable
   AND role_row.status = 'ACTIVE' AND role_row.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_grant portal_grant
         JOIN iam_permission portal_permission
           ON portal_permission.id = portal_grant.permission_id
          AND portal_permission.permission_code = CASE tenant.account_domain
              WHEN 'PLATFORM' THEN 'backoffice:platform-access'
              WHEN 'MERCHANT' THEN 'backoffice:merchant-access'
          END
          AND portal_permission.status = 'ACTIVE'
         JOIN iam_grant_dimension dimension ON dimension.grant_id = portal_grant.id
        WHERE portal_grant.tenant_id = role_row.tenant_id
          AND portal_grant.role_id = role_row.id
          AND portal_grant.grant_key = 'system-backoffice-access'
          AND portal_grant.status = 'ACTIVE'
          AND (portal_grant.valid_from IS NULL OR portal_grant.valid_from <= statement_timestamp())
          AND (portal_grant.valid_until IS NULL OR portal_grant.valid_until > statement_timestamp())
          AND dimension.dimension_code = 'TENANT'
          AND dimension.scope_mode = 'TENANT_ALL'
          AND NOT EXISTS (SELECT 1 FROM iam_grant_target target
                           WHERE target.dimension_id = dimension.id));

DO $$
BEGIN
    IF EXISTS (
        SELECT tenant.id
          FROM iam_tenant tenant
          LEFT JOIN mch_v32_role selected_role ON selected_role.tenant_id = tenant.id
         WHERE tenant.account_domain IN ('PLATFORM', 'MERCHANT')
         GROUP BY tenant.id
        HAVING count(selected_role.role_id) <> 1
    ) THEN
        RAISE EXCEPTION 'V32 blocked: Merchant capability needs one login-capable protected role per tenant';
    END IF;
END
$$;

CREATE TEMPORARY TABLE mch_v32_grant(id BIGINT PRIMARY KEY) ON COMMIT DROP;
WITH domain_permission(account_domain, permission_code) AS (
    VALUES
      ('PLATFORM', 'merchant:view'), ('PLATFORM', 'merchant:review'),
      ('PLATFORM', 'merchant:disable'), ('PLATFORM', 'merchant:enable'),
      ('PLATFORM', 'merchant:terminate'), ('MERCHANT', 'merchant:self-view'),
      ('MERCHANT', 'merchant:submit'), ('MERCHANT', 'merchant:resubmit')
), inserted AS (
    INSERT INTO iam_role_grant(
        id, tenant_id, role_id, permission_id, grant_key, status,
        valid_from, valid_until, created_by, updated_by)
    SELECT nextval('iam_id_seq'), role_row.tenant_id, role_row.role_id, permission.id,
           'system-' || replace(permission.permission_code, ':', '-'), 'ACTIVE',
           statement_timestamp(), statement_timestamp() + INTERVAL '10 years', NULL, NULL
      FROM mch_v32_role role_row
      JOIN domain_permission mapping ON mapping.account_domain = role_row.account_domain
      JOIN iam_permission permission ON permission.permission_code = mapping.permission_code
    RETURNING id
)
INSERT INTO mch_v32_grant(id) SELECT id FROM inserted;

INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
SELECT nextval('iam_id_seq'), id, 'TENANT', 'TENANT_ALL' FROM mch_v32_grant;

-- PLATFORM receives one directory, one list page and four action buttons.
WITH parent AS (
    INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                         route_path, component_path, sort_order, status, meta_json, system_managed)
    SELECT nextval('iam_id_seq'), tenant_id, NULL, 'DIRECTORY', 'Merchant Management',
           'MerchantManagement', '/merchant', NULL, 200, 'ACTIVE',
           '{"title":"merchant.title","icon":"lucide:store"}'::jsonb, TRUE
      FROM mch_v32_role WHERE account_domain = 'PLATFORM'
    RETURNING tenant_id, id
), page AS (
    INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                         route_path, component_path, display_permission_id, sort_order,
                         auth_code, status, meta_json, system_managed)
    SELECT nextval('iam_id_seq'), parent.tenant_id, parent.id, 'PAGE', 'Merchant List',
           'MerchantList', '/merchant/list', '/merchant/list', permission.id, 201,
           'merchant:view', 'ACTIVE', '{"title":"merchant.list.title"}'::jsonb, TRUE
      FROM parent JOIN iam_permission permission ON permission.permission_code = 'merchant:view'
    RETURNING tenant_id, id
)
INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                     sort_order, auth_code, status, meta_json, system_managed)
SELECT nextval('iam_id_seq'), page.tenant_id, page.id, 'BUTTON', button.menu_name,
       button.route_name, button.sort_order, button.permission_code, 'ACTIVE',
       jsonb_build_object('title', button.title_key), TRUE
  FROM page
 CROSS JOIN (VALUES
    ('MerchantReview', 'Merchant Review', 'merchant:review', 'merchant.review', 202),
    ('MerchantDisable', 'Merchant Disable', 'merchant:disable', 'merchant.disable', 203),
    ('MerchantEnable', 'Merchant Enable', 'merchant:enable', 'merchant.enable', 204),
    ('MerchantTerminate', 'Merchant Terminate', 'merchant:terminate', 'merchant.terminate', 205)
 ) AS button(route_name, menu_name, permission_code, title_key, sort_order);

-- MERCHANT receives only its self-service page and submit/resubmit buttons.
WITH page AS (
    INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                         route_path, component_path, display_permission_id, sort_order,
                         auth_code, status, meta_json, system_managed)
    SELECT nextval('iam_id_seq'), role_row.tenant_id, NULL, 'PAGE', 'Merchant Profile',
           'MerchantProfile', '/merchant/profile', '/merchant/profile', permission.id, 200,
           'merchant:self-view', 'ACTIVE',
           '{"title":"merchant.profile.title","icon":"lucide:store"}'::jsonb, TRUE
      FROM mch_v32_role role_row
      JOIN iam_permission permission ON permission.permission_code = 'merchant:self-view'
     WHERE role_row.account_domain = 'MERCHANT'
    RETURNING tenant_id, id
)
INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                     sort_order, auth_code, status, meta_json, system_managed)
SELECT nextval('iam_id_seq'), page.tenant_id, page.id, 'BUTTON', button.menu_name,
       button.route_name, button.sort_order, button.permission_code, 'ACTIVE',
       jsonb_build_object('title', button.title_key), TRUE
  FROM page
 CROSS JOIN (VALUES
    ('MerchantSubmit', 'Merchant Submit', 'merchant:submit', 'merchant.submit', 201),
    ('MerchantResubmit', 'Merchant Resubmit', 'merchant:resubmit', 'merchant.resubmit', 202)
 ) AS button(route_name, menu_name, permission_code, title_key, sort_order);

INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
SELECT role_row.tenant_id, role_row.role_id, menu.id
  FROM mch_v32_role role_row
  JOIN iam_menu menu ON menu.tenant_id = role_row.tenant_id
 WHERE menu.route_name LIKE 'Merchant%';

-- Existing sessions are invalid after their protected role gains MCH permissions.
WITH changed AS (
    UPDATE iam_membership membership
       SET permission_version = permission_version + 1,
           updated_at = statement_timestamp(), row_version = row_version + 1
      FROM iam_membership_role assignment
      JOIN mch_v32_role role_row
        ON role_row.tenant_id = assignment.tenant_id
       AND role_row.role_id = assignment.role_id
     WHERE membership.tenant_id = assignment.tenant_id
       AND membership.id = assignment.membership_id
    RETURNING membership.tenant_id, membership.id, membership.permission_version
)
INSERT INTO iam_permission_change_outbox(
    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
    aggregate_version, schema_version, partition_key, trace_id)
SELECT nextval('iam_id_seq'), tenant_id, 'MEMBERSHIP', id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('tenantId', tenant_id, 'membershipId', id,
                          'permissionVersion', permission_version,
                          'reason', 'V32_MERCHANT_CAPABILITY_ACCESS'),
       permission_version, 1, tenant_id::text || ':' || id::text,
       'migration-v32'
  FROM changed;

COMMENT ON COLUMN merchant.registration_ciphertext IS
    'AES-256-GCM ciphertext only; registration plaintext is forbidden from database, audit and dedup';
COMMENT ON TABLE merchant_command_dedup IS
    'Permanent versioned HMAC request digest and bounded result; never stores request plaintext';
COMMENT ON TABLE merchant_audit_event IS
    'Append-only lifecycle evidence; changed fields are names only, never protected values';
