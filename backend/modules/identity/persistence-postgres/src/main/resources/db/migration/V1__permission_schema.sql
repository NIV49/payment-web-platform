-- Reference schema for the new payment platform IAM/authorization module.
-- PostgreSQL 16+ baseline. Review and adapt before any production execution.

CREATE TABLE iam_user (
    id              BIGINT PRIMARY KEY,
    idp_issuer      VARCHAR(255) NOT NULL,
    idp_subject     VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    email_cipher    BYTEA,
    phone_cipher    BYTEA,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_user_idp_identity UNIQUE (idp_issuer, idp_subject),
    CONSTRAINT ck_iam_user_status CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED', 'LOCKED'))
);

CREATE TABLE iam_tenant (
    id              BIGINT PRIMARY KEY,
    tenant_code     VARCHAR(64) NOT NULL,
    tenant_name     VARCHAR(128) NOT NULL,
    tenant_type     VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_tenant_code UNIQUE (tenant_code),
    CONSTRAINT ck_iam_tenant_type CHECK (tenant_type IN ('PLATFORM', 'AGENT', 'DIRECT_MERCHANT', 'INDIRECT_MERCHANT')),
    CONSTRAINT ck_iam_tenant_status CHECK (status IN ('ACTIVE', 'DISABLED', 'TERMINATED'))
);

CREATE TABLE iam_department (
    id              BIGINT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES iam_tenant(id),
    parent_id       BIGINT,
    department_code VARCHAR(64) NOT NULL,
    department_name VARCHAR(128) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_department_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_iam_department_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES iam_department(tenant_id, id),
    CONSTRAINT uk_iam_department_code UNIQUE (tenant_id, department_code),
    CONSTRAINT ck_iam_department_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_iam_department_parent ON iam_department (tenant_id, parent_id);

CREATE TABLE iam_membership (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    user_id             BIGINT NOT NULL REFERENCES iam_user(id),
    department_id       BIGINT,
    status              VARCHAR(32) NOT NULL,
    permission_version  BIGINT NOT NULL DEFAULT 0,
    session_version     BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_membership_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT uk_iam_membership_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_iam_membership_department FOREIGN KEY (tenant_id, department_id) REFERENCES iam_department(tenant_id, id),
    CONSTRAINT ck_iam_membership_status CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED', 'TERMINATED'))
);

CREATE INDEX idx_iam_membership_tenant_status ON iam_membership (tenant_id, status);
CREATE INDEX idx_iam_membership_user ON iam_membership (user_id);

CREATE TABLE iam_role (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    role_code           VARCHAR(100) NOT NULL,
    role_name           VARCHAR(128) NOT NULL,
    applicable_tenant_type VARCHAR(32) NOT NULL,
    assignable          BOOLEAN NOT NULL DEFAULT true,
    system_role         BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(32) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_role_code UNIQUE (tenant_id, role_code),
    CONSTRAINT uk_iam_role_name UNIQUE (tenant_id, role_name),
    CONSTRAINT uk_iam_role_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_role_tenant_type CHECK (applicable_tenant_type IN ('PLATFORM', 'AGENT', 'DIRECT_MERCHANT', 'INDIRECT_MERCHANT')),
    CONSTRAINT ck_iam_role_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE iam_membership_role (
    tenant_id       BIGINT NOT NULL REFERENCES iam_tenant(id),
    membership_id   BIGINT NOT NULL,
    role_id         BIGINT NOT NULL,
    assigned_by     BIGINT,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, membership_id, role_id),
    CONSTRAINT fk_iam_membership_role_membership FOREIGN KEY (tenant_id, membership_id) REFERENCES iam_membership(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_iam_membership_role_role FOREIGN KEY (tenant_id, role_id) REFERENCES iam_role(tenant_id, id),
    CONSTRAINT fk_iam_membership_role_assigner FOREIGN KEY (tenant_id, assigned_by) REFERENCES iam_membership(tenant_id, id)
);

CREATE INDEX idx_iam_membership_role_role ON iam_membership_role (tenant_id, role_id, membership_id);

CREATE TABLE iam_permission (
    id                  BIGINT PRIMARY KEY,
    permission_code     VARCHAR(128) NOT NULL,
    resource_code       VARCHAR(64) NOT NULL,
    action_code         VARCHAR(64) NOT NULL,
    risk_level          VARCHAR(16) NOT NULL,
    required_dimensions VARCHAR(32)[] NOT NULL DEFAULT ARRAY['TENANT']::VARCHAR(32)[],
    requires_step_up    BOOLEAN NOT NULL DEFAULT false,
    requires_approval   BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(32) NOT NULL,
    description         VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_permission_code UNIQUE (permission_code),
    CONSTRAINT ck_iam_permission_code CHECK (permission_code = resource_code || ':' || action_code),
    CONSTRAINT ck_iam_permission_risk CHECK (risk_level IN ('NORMAL', 'SENSITIVE', 'FUND')),
    CONSTRAINT ck_iam_permission_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_fund_step_up CHECK (risk_level <> 'FUND' OR requires_step_up)
);

CREATE TABLE iam_role_grant (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    role_id             BIGINT NOT NULL,
    permission_id       BIGINT NOT NULL REFERENCES iam_permission(id),
    grant_key           VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    valid_from          TIMESTAMPTZ,
    valid_until         TIMESTAMPTZ,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_role_grant UNIQUE (role_id, permission_id, grant_key),
    CONSTRAINT fk_iam_role_grant_role FOREIGN KEY (tenant_id, role_id) REFERENCES iam_role(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_iam_role_grant_creator FOREIGN KEY (tenant_id, created_by) REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT fk_iam_role_grant_updater FOREIGN KEY (tenant_id, updated_by) REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT ck_iam_role_grant_key CHECK (grant_key ~ '^[a-z][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_iam_role_grant_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_role_grant_validity CHECK (valid_until IS NULL OR valid_from IS NULL OR valid_until > valid_from)
);

CREATE INDEX idx_iam_role_grant_tenant_permission ON iam_role_grant (tenant_id, permission_id, role_id);
CREATE INDEX idx_iam_role_grant_role_permission ON iam_role_grant (role_id, permission_id);

CREATE TABLE iam_grant_dimension (
    id                  BIGINT PRIMARY KEY,
    grant_id            BIGINT NOT NULL REFERENCES iam_role_grant(id) ON DELETE CASCADE,
    dimension_code      VARCHAR(32) NOT NULL,
    scope_mode          VARCHAR(32) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_iam_grant_dimension UNIQUE (grant_id, dimension_code),
    CONSTRAINT ck_iam_grant_dimension CHECK (dimension_code IN ('TENANT', 'OWNER', 'DEPARTMENT', 'CUSTOMER', 'MERCHANT', 'MARKET', 'CHANNEL')),
    CONSTRAINT ck_iam_grant_scope_mode CHECK (scope_mode IN ('TENANT_ALL', 'SELF', 'DEPARTMENT', 'DEPARTMENT_AND_CHILDREN', 'ASSIGNED', 'SPECIFIED', 'RELATION_CURRENT', 'RELATION_AT_EVENT'))
);

CREATE TABLE iam_grant_target (
    id                  BIGINT PRIMARY KEY,
    dimension_id        BIGINT NOT NULL REFERENCES iam_grant_dimension(id) ON DELETE CASCADE,
    target_ref          VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_iam_grant_target UNIQUE (dimension_id, target_ref)
);

CREATE INDEX idx_iam_grant_target_ref ON iam_grant_target (target_ref, dimension_id);

CREATE TABLE iam_menu (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    parent_id           BIGINT,
    menu_type           VARCHAR(16) NOT NULL,
    menu_name           VARCHAR(128) NOT NULL,
    route_path          VARCHAR(255),
    component_path      VARCHAR(255),
    display_permission_id BIGINT REFERENCES iam_permission(id),
    sort_order          INTEGER NOT NULL DEFAULT 999,
    status              VARCHAR(32) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_menu_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_iam_menu_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES iam_menu(tenant_id, id),
    CONSTRAINT ck_iam_menu_type CHECK (menu_type IN ('DIRECTORY', 'PAGE', 'BUTTON')),
    CONSTRAINT ck_iam_menu_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_iam_menu_parent ON iam_menu (tenant_id, parent_id, sort_order);

CREATE TABLE iam_role_menu (
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    role_id             BIGINT NOT NULL,
    menu_id             BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, role_id, menu_id),
    CONSTRAINT fk_iam_role_menu_role FOREIGN KEY (tenant_id, role_id) REFERENCES iam_role(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_iam_role_menu_menu FOREIGN KEY (tenant_id, menu_id) REFERENCES iam_menu(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE iam_audit_event (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    operator_membership_id BIGINT,
    target_type         VARCHAR(64) NOT NULL,
    target_ref          VARCHAR(128) NOT NULL,
    action_code         VARCHAR(128) NOT NULL,
    decision            VARCHAR(16) NOT NULL,
    reason_code         VARCHAR(64) NOT NULL,
    permission_code     VARCHAR(128),
    matched_grant_id    BIGINT,
    before_value        JSONB,
    after_value         JSONB,
    request_ip_hash     VARCHAR(128),
    device_summary      VARCHAR(255),
    trace_id            VARCHAR(64) NOT NULL,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_iam_audit_operator FOREIGN KEY (tenant_id, operator_membership_id) REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT ck_iam_audit_decision CHECK (decision IN ('ALLOW', 'DENY', 'NOT_APPLICABLE'))
);

CREATE INDEX idx_iam_audit_tenant_time ON iam_audit_event (tenant_id, occurred_at DESC);
CREATE INDEX idx_iam_audit_operator_time ON iam_audit_event (operator_membership_id, occurred_at DESC);
CREATE INDEX idx_iam_audit_target_time ON iam_audit_event (target_type, target_ref, occurred_at DESC);
CREATE INDEX idx_iam_audit_trace ON iam_audit_event (trace_id);

CREATE TABLE iam_permission_change_outbox (
    id                  BIGINT PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES iam_tenant(id),
    aggregate_type      VARCHAR(64) NOT NULL,
    aggregate_ref       VARCHAR(128) NOT NULL,
    event_type          VARCHAR(64) NOT NULL,
    payload             JSONB NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts            INTEGER NOT NULL DEFAULT 0,
    available_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    CONSTRAINT ck_iam_permission_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_iam_permission_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_iam_permission_outbox_pending
    ON iam_permission_change_outbox (status, available_at, id)
    WHERE status IN ('PENDING', 'FAILED');

COMMENT ON TABLE iam_role_grant IS 'Atomic authorization: one permission and all of its scope dimensions must be evaluated together';
COMMENT ON TABLE iam_grant_dimension IS 'Scope dimensions inside one grant are ANDed; targets inside one dimension are ORed';
COMMENT ON TABLE iam_audit_event IS 'Never store passwords, tokens, MFA secrets, private keys, or unmasked sensitive payloads';
COMMENT ON TABLE iam_permission_change_outbox IS 'Transactional permission-version and cache-invalidation events; payload must not contain secrets';
