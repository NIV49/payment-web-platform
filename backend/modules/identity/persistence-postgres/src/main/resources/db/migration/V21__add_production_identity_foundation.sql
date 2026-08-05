DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint constraint_row
         WHERE constraint_row.conrelid = 'iam_authentication_credential'::regclass
           AND constraint_row.conname = 'uk_iam_authentication_username'
           AND constraint_row.contype = 'u'
           AND pg_get_constraintdef(constraint_row.oid) = 'UNIQUE (username)'
    ) THEN
        RAISE EXCEPTION
            'IAM-002 migration blocked: global username uniqueness must remain during expand';
    END IF;
END
$$;

ALTER TABLE iam_user
    ADD COLUMN identity_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN idp_provisioning_status VARCHAR(32);

UPDATE iam_user
   SET idp_provisioning_status = CASE
       WHEN idp_issuer = 'local' THEN 'LOCAL_ONLY'
       ELSE 'PROVISIONED'
   END;

ALTER TABLE iam_user
    ALTER COLUMN idp_provisioning_status SET DEFAULT 'LOCAL_ONLY',
    ALTER COLUMN idp_provisioning_status SET NOT NULL,
    ADD CONSTRAINT ck_iam_user_identity_version CHECK (identity_version >= 0),
    ADD CONSTRAINT ck_iam_user_idp_provisioning_status CHECK (
        idp_provisioning_status IN (
            'LOCAL_ONLY',
            'PROVISION_PENDING',
            'PROVISIONED',
            'DEPROVISION_PENDING',
            'DEPROVISIONED',
            'FAILED'
        )
    );

CREATE TABLE iam_tenant_entry_host (
    id              BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    entry_host      VARCHAR(253) NOT NULL,
    account_domain  VARCHAR(16) NOT NULL,
    tenant_id       BIGINT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_tenant_entry_host UNIQUE (entry_host),
    CONSTRAINT fk_iam_entry_host_tenant_domain
        FOREIGN KEY (tenant_id, account_domain)
        REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT ck_iam_entry_host_canonical CHECK (
        entry_host = lower(entry_host)
        AND entry_host ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'
    ),
    CONSTRAINT ck_iam_entry_host_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_entry_host_row_version CHECK (row_version >= 0)
);

CREATE TABLE iam_identity_lifecycle_outbox (
    id               BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    user_id          BIGINT NOT NULL,
    tenant_id        BIGINT NOT NULL,
    realm            VARCHAR(16) NOT NULL,
    operation_type   VARCHAR(32) NOT NULL,
    idempotency_key  UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_iam_identity_outbox_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_iam_identity_outbox_user_realm
        FOREIGN KEY (user_id, realm)
        REFERENCES iam_user(id, account_domain),
    CONSTRAINT fk_iam_identity_outbox_tenant_realm
        FOREIGN KEY (tenant_id, realm)
        REFERENCES iam_tenant(id, account_domain),
    CONSTRAINT ck_iam_identity_outbox_realm
        CHECK (realm IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    CONSTRAINT ck_iam_identity_outbox_operation CHECK (
        operation_type IN ('PROVISION', 'ENABLE', 'DISABLE', 'MFA_RECOVERY', 'DEPROVISION')
    )
);

CREATE TABLE iam_identity_lifecycle_relay_state (
    event_record_id  BIGINT PRIMARY KEY
        REFERENCES iam_identity_lifecycle_outbox(id) ON DELETE RESTRICT,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER NOT NULL DEFAULT 0,
    available_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until      TIMESTAMPTZ,
    published_at     TIMESTAMPTZ,
    last_error_code  VARCHAR(64),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_iam_identity_relay_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_iam_identity_relay_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_iam_identity_relay_pending
    ON iam_identity_lifecycle_relay_state (status, available_at, event_record_id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE OR REPLACE FUNCTION iam_initialize_identity_lifecycle_relay_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO iam_identity_lifecycle_relay_state(event_record_id)
    VALUES (NEW.id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_iam_identity_outbox_initialize_relay
AFTER INSERT ON iam_identity_lifecycle_outbox
FOR EACH ROW EXECUTE FUNCTION iam_initialize_identity_lifecycle_relay_state();

CREATE OR REPLACE FUNCTION iam_reject_identity_lifecycle_outbox_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'iam_identity_lifecycle_outbox is append-only';
END;
$$;

CREATE TRIGGER trg_iam_identity_outbox_append_only
BEFORE UPDATE OR DELETE ON iam_identity_lifecycle_outbox
FOR EACH ROW EXECUTE FUNCTION iam_reject_identity_lifecycle_outbox_mutation();

COMMENT ON TABLE iam_tenant_entry_host IS
    'Server-controlled canonical Host to account-domain and tenant mapping';
COMMENT ON TABLE iam_identity_lifecycle_outbox IS
    'Append-only identity lifecycle commands; never stores profile data or authentication secrets';
COMMENT ON TABLE iam_identity_lifecycle_relay_state IS
    'Mutable retry and publication state for identity lifecycle commands';
