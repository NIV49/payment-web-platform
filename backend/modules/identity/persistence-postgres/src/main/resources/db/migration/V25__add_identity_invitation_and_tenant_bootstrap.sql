CREATE TABLE iam_identity_invitation (
    id                          BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    invitation_kind             VARCHAR(32) NOT NULL,
    tenant_id                   BIGINT NOT NULL,
    account_domain              VARCHAR(16) NOT NULL,
    requested_by_tenant_id      BIGINT NOT NULL,
    requested_by_membership_id  BIGINT NOT NULL,
    idempotency_key             UUID NOT NULL,
    display_name                VARCHAR(128) NOT NULL,
    user_id                     BIGINT,
    membership_id               BIGINT,
    lifecycle_event_record_id   BIGINT,
    status                      VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    keycloak_user_created_at    TIMESTAMPTZ,
    keycloak_user_enabled_at    TIMESTAMPTZ,
    action_email_sent_at        TIMESTAMPTZ,
    attempts                    INTEGER NOT NULL DEFAULT 0,
    available_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until                 TIMESTAMPTZ,
    last_error_code             VARCHAR(64),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                TIMESTAMPTZ,
    row_version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_identity_invitation_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_iam_identity_invitation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_iam_identity_invitation_lifecycle_event UNIQUE (lifecycle_event_record_id),
    CONSTRAINT fk_iam_identity_invitation_tenant_domain
        FOREIGN KEY (tenant_id, account_domain)
        REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT fk_iam_identity_invitation_actor
        FOREIGN KEY (requested_by_tenant_id, requested_by_membership_id)
        REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT fk_iam_identity_invitation_user_domain
        FOREIGN KEY (user_id, account_domain)
        REFERENCES iam_user(id, account_domain),
    CONSTRAINT fk_iam_identity_invitation_membership_tenant
        FOREIGN KEY (tenant_id, membership_id)
        REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT fk_iam_identity_invitation_membership_domain
        FOREIGN KEY (account_domain, membership_id)
        REFERENCES iam_membership(account_domain, id),
    CONSTRAINT fk_iam_identity_invitation_lifecycle_event
        FOREIGN KEY (lifecycle_event_record_id)
        REFERENCES iam_identity_lifecycle_outbox(id) ON DELETE RESTRICT,
    CONSTRAINT ck_iam_identity_invitation_kind
        CHECK (invitation_kind IN ('MEMBER', 'TENANT_FIRST_ADMIN')),
    CONSTRAINT ck_iam_identity_invitation_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    CONSTRAINT ck_iam_identity_invitation_status
        CHECK (status IN ('RESERVED', 'PROVISION_PENDING', 'COMPLETED')),
    CONSTRAINT ck_iam_identity_invitation_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_iam_identity_invitation_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_iam_identity_invitation_identity_state CHECK (
        (status = 'RESERVED'
            AND user_id IS NULL
            AND membership_id IS NULL
            AND lifecycle_event_record_id IS NULL
            AND completed_at IS NULL)
        OR
        (status = 'PROVISION_PENDING'
            AND user_id IS NOT NULL
            AND membership_id IS NOT NULL
            AND lifecycle_event_record_id IS NOT NULL
            AND keycloak_user_created_at IS NOT NULL
            AND completed_at IS NULL)
        OR
        (status = 'COMPLETED'
            AND user_id IS NOT NULL
            AND membership_id IS NOT NULL
            AND lifecycle_event_record_id IS NOT NULL
            AND keycloak_user_created_at IS NOT NULL
            AND keycloak_user_enabled_at IS NOT NULL
            AND action_email_sent_at IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);

CREATE TABLE iam_identity_invitation_role (
    invitation_id  BIGINT NOT NULL,
    tenant_id      BIGINT NOT NULL,
    role_id        BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invitation_id, role_id),
    CONSTRAINT fk_iam_identity_invitation_role_invitation
        FOREIGN KEY (tenant_id, invitation_id)
        REFERENCES iam_identity_invitation(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_iam_identity_invitation_role_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES iam_role(tenant_id, id)
);

CREATE INDEX idx_iam_identity_invitation_ready
    ON iam_identity_invitation(account_domain, status, available_at, id)
    WHERE status = 'PROVISION_PENDING';

CREATE INDEX idx_iam_identity_invitation_actor
    ON iam_identity_invitation(requested_by_tenant_id, requested_by_membership_id, created_at DESC);

COMMENT ON TABLE iam_identity_invitation IS
    'Durable invitation orchestration without login email, invitation token, password, TOTP secret, or recovery code';
COMMENT ON COLUMN iam_identity_invitation.display_name IS
    'Application profile display name only; Keycloak exclusively owns the login email';
COMMENT ON COLUMN iam_identity_invitation.last_error_code IS
    'Bounded diagnostic code only; provider response bodies and personal authentication data are forbidden';
COMMENT ON TABLE iam_identity_invitation_role IS
    'Roles frozen at invitation reservation; MEMBER invitations may reference only ordinary assignable roles';
