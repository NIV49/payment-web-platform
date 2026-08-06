ALTER TABLE iam_user
    DROP CONSTRAINT ck_iam_user_idp_provisioning_status,
    ADD CONSTRAINT ck_iam_user_idp_provisioning_status CHECK (
        idp_provisioning_status IN (
            'LOCAL_ONLY',
            'PROVISION_PENDING',
            'PROVISIONED',
            'RECOVERY_PENDING',
            'DEPROVISION_PENDING',
            'DEPROVISIONED',
            'FAILED'
        )
    );

CREATE TABLE iam_mfa_recovery (
    id                               BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    user_id                          BIGINT NOT NULL,
    tenant_id                        BIGINT NOT NULL,
    target_membership_id             BIGINT NOT NULL,
    requested_by_membership_id       BIGINT NOT NULL,
    account_domain                   VARCHAR(16) NOT NULL,
    idempotency_key                  UUID NOT NULL,
    lifecycle_event_record_id        BIGINT NOT NULL,
    status                           VARCHAR(24) NOT NULL DEFAULT 'RECOVERY_PENDING',
    mfa_credentials_revoked_at       TIMESTAMPTZ,
    recovery_codes_revoked_at        TIMESTAMPTZ,
    keycloak_sessions_revoked_at     TIMESTAMPTZ,
    application_sessions_revoked_at  TIMESTAMPTZ,
    attempts                         INTEGER NOT NULL DEFAULT 0,
    available_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until                      TIMESTAMPTZ,
    last_error_code                  VARCHAR(64),
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                     TIMESTAMPTZ,
    row_version                      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_mfa_recovery_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_iam_mfa_recovery_lifecycle_event UNIQUE (lifecycle_event_record_id),
    CONSTRAINT fk_iam_mfa_recovery_user_domain
        FOREIGN KEY (user_id, account_domain)
        REFERENCES iam_user(id, account_domain),
    CONSTRAINT fk_iam_mfa_recovery_tenant_domain
        FOREIGN KEY (tenant_id, account_domain)
        REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT fk_iam_mfa_recovery_target_tenant
        FOREIGN KEY (tenant_id, target_membership_id)
        REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT fk_iam_mfa_recovery_target_domain
        FOREIGN KEY (account_domain, target_membership_id)
        REFERENCES iam_membership(account_domain, id),
    CONSTRAINT fk_iam_mfa_recovery_actor_tenant
        FOREIGN KEY (tenant_id, requested_by_membership_id)
        REFERENCES iam_membership(tenant_id, id),
    CONSTRAINT fk_iam_mfa_recovery_actor_domain
        FOREIGN KEY (account_domain, requested_by_membership_id)
        REFERENCES iam_membership(account_domain, id),
    CONSTRAINT fk_iam_mfa_recovery_lifecycle_event
        FOREIGN KEY (lifecycle_event_record_id)
        REFERENCES iam_identity_lifecycle_outbox(id) ON DELETE RESTRICT,
    CONSTRAINT ck_iam_mfa_recovery_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    CONSTRAINT ck_iam_mfa_recovery_distinct_actor
        CHECK (target_membership_id <> requested_by_membership_id),
    CONSTRAINT ck_iam_mfa_recovery_status
        CHECK (status IN ('RECOVERY_PENDING', 'COMPLETED')),
    CONSTRAINT ck_iam_mfa_recovery_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_iam_mfa_recovery_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_iam_mfa_recovery_completion CHECK (
        (status = 'RECOVERY_PENDING' AND completed_at IS NULL)
        OR
        (status = 'COMPLETED'
            AND mfa_credentials_revoked_at IS NOT NULL
            AND recovery_codes_revoked_at IS NOT NULL
            AND keycloak_sessions_revoked_at IS NOT NULL
            AND application_sessions_revoked_at IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_iam_mfa_recovery_active_user
    ON iam_mfa_recovery(account_domain, user_id)
    WHERE status = 'RECOVERY_PENDING';

CREATE INDEX idx_iam_mfa_recovery_ready
    ON iam_mfa_recovery(account_domain, status, available_at, id)
    WHERE status = 'RECOVERY_PENDING';

COMMENT ON TABLE iam_mfa_recovery IS
    'Retryable MFA recovery state; completion requires all Keycloak and application revocations';
COMMENT ON COLUMN iam_mfa_recovery.last_error_code IS
    'Bounded diagnostic code only; authentication secrets and provider response bodies are forbidden';
