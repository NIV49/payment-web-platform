ALTER TABLE iam_identity_invitation
    ADD COLUMN identity_mode VARCHAR(24),
    ADD CONSTRAINT ck_iam_identity_invitation_mode CHECK (
        identity_mode IN ('NEW_DISABLED', 'EXISTING_ACTIVE')
    ),
    ADD CONSTRAINT ck_iam_identity_invitation_mode_state CHECK (
        (status = 'RESERVED' AND identity_mode IS NULL)
        OR
        (status IN ('PROVISION_PENDING', 'COMPLETED') AND identity_mode IS NOT NULL)
    );

COMMENT ON COLUMN iam_identity_invitation.identity_mode IS
    'Non-secret orchestration fact: create a disabled Realm identity or attach an existing active issuer+subject identity';
