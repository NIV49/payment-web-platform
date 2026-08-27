-- PLATFORM profile maintenance and the first explicit Merchant operating markets.
-- Existing V33 merchants are intentionally not inferred into BRA/PHL.

ALTER TABLE merchant
    ADD COLUMN remarks VARCHAR(300) NOT NULL DEFAULT '',
    ADD COLUMN status_reason_code VARCHAR(64);

UPDATE merchant merchant_row
   SET status_reason_code = (
       SELECT audit.reason_code
          FROM merchant_audit_event audit
         WHERE audit.merchant_id = merchant_row.id
           AND audit.merchant_version = merchant_row.row_version
           AND audit.next_status = merchant_row.status
         ORDER BY audit.id DESC
         LIMIT 1)
 WHERE EXISTS (SELECT 1 FROM merchant_audit_event audit
                WHERE audit.merchant_id = merchant_row.id
                  AND audit.merchant_version = merchant_row.row_version
                  AND audit.next_status = merchant_row.status);

ALTER TABLE merchant
    ADD CONSTRAINT ck_merchant_remarks CHECK (
        btrim(remarks) = remarks AND char_length(remarks) <= 300),
    ADD CONSTRAINT ck_merchant_status_reason CHECK (
        status_reason_code IS NULL
        OR (status = 'PENDING_REVIEW' AND status_reason_code IN (
            'APPLICATION_SUBMITTED', 'APPLICATION_RESUBMITTED'))
        OR (status = 'REVIEW_REJECTED' AND status_reason_code IN (
            'PROFILE_MISMATCH', 'REGISTRATION_UNVERIFIED', 'COMPLIANCE_REJECTED'))
        OR (status = 'ACTIVE' AND status_reason_code IN (
            'PROFILE_VERIFIED', 'COMPLIANCE_CLEARED', 'RISK_CLEARED'))
        OR (status = 'DISABLED' AND status_reason_code IN (
            'COMPLIANCE_HOLD', 'RISK_CONTROL'))
        OR (status = 'TERMINATED' AND status_reason_code IN (
            'BUSINESS_CLOSED', 'COMPLIANCE_TERMINATION')));

CREATE TABLE merchant_operating_market (
    merchant_id BIGINT NOT NULL,
    target_tenant_id BIGINT NOT NULL,
    market_code CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, market_code),
    CONSTRAINT fk_merchant_market_merchant FOREIGN KEY (merchant_id, target_tenant_id)
        REFERENCES merchant(id, tenant_id),
    CONSTRAINT ck_merchant_market_code CHECK (market_code IN ('BRA', 'PHL')),
    CONSTRAINT ck_merchant_market_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_merchant_market_time CHECK (
        (status = 'ACTIVE' AND deactivated_at IS NULL)
        OR (status = 'INACTIVE' AND deactivated_at IS NOT NULL
            AND deactivated_at >= activated_at))
);

CREATE INDEX idx_merchant_market_directory
    ON merchant_operating_market(market_code, status, merchant_id);

CREATE OR REPLACE FUNCTION merchant_enforce_operating_market()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'merchant operating markets use soft state';
    END IF;
    IF OLD.merchant_id <> NEW.merchant_id
       OR OLD.target_tenant_id <> NEW.target_tenant_id
       OR OLD.market_code <> NEW.market_code THEN
        RAISE EXCEPTION 'merchant operating market identity is immutable';
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_merchant_operating_market
BEFORE UPDATE OR DELETE ON merchant_operating_market
FOR EACH ROW EXECUTE FUNCTION merchant_enforce_operating_market();

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
               OLD.registration_number_masked, OLD.remarks, OLD.status,
               OLD.status_reason_code, OLD.submitted_at, OLD.reviewed_at,
               OLD.last_decision, OLD.last_decision_reason_code,
               OLD.last_decided_by_membership_id, OLD.last_decided_at,
               OLD.created_at, OLD.updated_at)
              IS DISTINCT FROM
              (NEW.legal_name, NEW.display_name, NEW.registration_country,
               NEW.registration_number_masked, NEW.remarks, NEW.status,
               NEW.status_reason_code, NEW.submitted_at, NEW.reviewed_at,
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

ALTER TABLE merchant_command_dedup
    DROP CONSTRAINT ck_merchant_dedup_command,
    DROP CONSTRAINT ck_merchant_dedup_permission;
ALTER TABLE merchant_command_dedup
    ADD CONSTRAINT ck_merchant_dedup_command CHECK (command_type IN (
        'SUBMIT', 'RESUBMIT', 'APPROVE', 'REJECT', 'DISABLE', 'ENABLE',
        'UPDATE_PROFILE', 'TERMINATE')),
    ADD CONSTRAINT ck_merchant_dedup_permission CHECK (
        (command_type = 'SUBMIT' AND required_permission = 'merchant:submit')
        OR (command_type = 'RESUBMIT' AND required_permission = 'merchant:resubmit')
        OR (command_type IN ('APPROVE', 'REJECT') AND required_permission = 'merchant:review')
        OR (command_type = 'DISABLE' AND required_permission = 'merchant:disable')
        OR (command_type = 'ENABLE' AND required_permission = 'merchant:enable')
        OR (command_type = 'UPDATE_PROFILE' AND required_permission = 'merchant:update')
        OR (command_type = 'TERMINATE' AND required_permission = 'merchant:terminate'));

ALTER TABLE merchant_audit_event
    DROP CONSTRAINT ck_merchant_audit_action_reason,
    DROP CONSTRAINT ck_merchant_audit_changed_fields;
ALTER TABLE merchant_audit_event
    ADD CONSTRAINT ck_merchant_audit_action_reason CHECK (
        (action_code = 'SUBMIT' AND reason_code = 'APPLICATION_SUBMITTED')
        OR (action_code = 'RESUBMIT' AND reason_code = 'APPLICATION_RESUBMITTED')
        OR (action_code = 'UPDATE_PROFILE' AND reason_code = 'PLATFORM_PROFILE_UPDATED')
        OR (action_code = 'APPROVE' AND reason_code = 'PROFILE_VERIFIED')
        OR (action_code = 'REJECT' AND reason_code IN (
            'PROFILE_MISMATCH', 'REGISTRATION_UNVERIFIED', 'COMPLIANCE_REJECTED'))
        OR (action_code = 'DISABLE' AND reason_code IN ('COMPLIANCE_HOLD', 'RISK_CONTROL'))
        OR (action_code = 'ENABLE' AND reason_code IN ('COMPLIANCE_CLEARED', 'RISK_CLEARED'))
        OR (action_code = 'TERMINATE' AND reason_code IN (
            'BUSINESS_CLOSED', 'COMPLIANCE_TERMINATION'))),
    ADD CONSTRAINT ck_merchant_audit_changed_fields CHECK (
        jsonb_typeof(changed_fields) = 'array'
        AND NOT (changed_fields - ARRAY[
            'legalName', 'displayName', 'registrationCountry', 'registrationNumber',
            'remarks', 'marketCodes']::TEXT[]) <> '[]'::jsonb);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM iam_permission WHERE permission_code = 'merchant:update') THEN
        RAISE EXCEPTION 'V34 blocked: reserved Merchant profile permission already exists';
    END IF;
END
$$;

INSERT INTO iam_permission(
    id, permission_code, resource_code, action_code, risk_level,
    required_dimensions, requires_step_up, requires_approval, status, description,
    cross_tenant_mode)
VALUES (
    nextval('iam_id_seq'), 'merchant:update', 'merchant', 'update', 'SENSITIVE',
    ARRAY['TENANT']::VARCHAR(32)[], TRUE, FALSE, 'ACTIVE',
    'Update active or disabled Merchant profile', 'SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE mch_v34_platform_role(
    tenant_id BIGINT PRIMARY KEY,
    role_id BIGINT NOT NULL
) ON COMMIT DROP;

INSERT INTO mch_v34_platform_role(tenant_id, role_id)
SELECT role_row.tenant_id, role_row.id
  FROM iam_role role_row
  JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
 WHERE tenant.account_domain = 'PLATFORM'
   AND role_row.system_role AND NOT role_row.assignable
   AND role_row.status = 'ACTIVE' AND role_row.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_grant portal_grant
         JOIN iam_permission portal_permission
           ON portal_permission.id = portal_grant.permission_id
          AND portal_permission.permission_code = 'backoffice:platform-access'
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
          LEFT JOIN mch_v34_platform_role selected ON selected.tenant_id = tenant.id
         WHERE tenant.account_domain = 'PLATFORM'
         GROUP BY tenant.id
        HAVING count(selected.role_id) <> 1
    ) THEN
        RAISE EXCEPTION 'V34 blocked: Merchant update needs one login-capable PLATFORM role per tenant';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT tenant.id
          FROM iam_tenant tenant
          LEFT JOIN iam_menu page ON page.tenant_id = tenant.id
           AND page.route_name = 'MerchantList' AND page.menu_type = 'PAGE'
           AND page.status = 'ACTIVE' AND page.deleted_at IS NULL
         WHERE tenant.account_domain = 'PLATFORM'
         GROUP BY tenant.id
        HAVING count(page.id) <> 1
    ) THEN
        RAISE EXCEPTION 'V34 blocked: Merchant update needs one live MerchantList page per PLATFORM tenant';
    END IF;
END
$$;

CREATE TEMPORARY TABLE mch_v34_grant(id BIGINT PRIMARY KEY) ON COMMIT DROP;
WITH inserted AS (
    INSERT INTO iam_role_grant(
        id, tenant_id, role_id, permission_id, grant_key, status,
        valid_from, valid_until, created_by, updated_by)
    SELECT nextval('iam_id_seq'), role_row.tenant_id, role_row.role_id, permission.id,
           'system-merchant-update', 'ACTIVE', statement_timestamp(),
           statement_timestamp() + INTERVAL '10 years', NULL, NULL
      FROM mch_v34_platform_role role_row
      JOIN iam_permission permission ON permission.permission_code = 'merchant:update'
    RETURNING id
)
INSERT INTO mch_v34_grant(id) SELECT id FROM inserted;

INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
SELECT nextval('iam_id_seq'), id, 'TENANT', 'TENANT_ALL' FROM mch_v34_grant;

WITH page AS (
    SELECT selected.tenant_id, menu.id
      FROM mch_v34_platform_role selected
      JOIN iam_menu menu ON menu.tenant_id = selected.tenant_id
       AND menu.route_name = 'MerchantList' AND menu.menu_type = 'PAGE'
       AND menu.status = 'ACTIVE' AND menu.deleted_at IS NULL
), inserted AS (
    INSERT INTO iam_menu(id, tenant_id, parent_id, menu_type, menu_name, route_name,
                         sort_order, auth_code, status, meta_json, system_managed)
    SELECT nextval('iam_id_seq'), page.tenant_id, page.id, 'BUTTON', 'Merchant Edit',
           'MerchantEdit', 206, 'merchant:update', 'ACTIVE',
           '{"title":"merchant.edit"}'::jsonb, TRUE
      FROM page
    RETURNING tenant_id, id
)
INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
SELECT inserted.tenant_id, selected.role_id, inserted.id
  FROM inserted
  JOIN mch_v34_platform_role selected ON selected.tenant_id = inserted.tenant_id;

WITH changed AS (
    UPDATE iam_membership membership
       SET permission_version = permission_version + 1,
           updated_at = statement_timestamp(), row_version = row_version + 1
      FROM iam_membership_role assignment
      JOIN mch_v34_platform_role selected
        ON selected.tenant_id = assignment.tenant_id
       AND selected.role_id = assignment.role_id
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
                          'reason', 'V34_MERCHANT_PROFILE_UPDATE_ACCESS'),
       permission_version, 1, tenant_id::text || ':' || id::text,
       'migration-v34'
  FROM changed;

COMMENT ON TABLE merchant_operating_market IS
    'Soft-state Merchant operating-market assignments; historical merchants are never inferred';
COMMENT ON COLUMN merchant.status_reason_code IS
    'Reason for the current lifecycle status, synchronized by every status transition';
