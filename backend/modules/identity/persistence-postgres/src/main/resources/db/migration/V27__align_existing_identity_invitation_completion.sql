ALTER TABLE iam_identity_invitation
    DROP CONSTRAINT ck_iam_identity_invitation_identity_state,
    ADD CONSTRAINT ck_iam_identity_invitation_identity_state CHECK (
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
            AND completed_at IS NOT NULL
            AND (
                (identity_mode = 'NEW_DISABLED'
                    AND keycloak_user_enabled_at IS NOT NULL
                    AND action_email_sent_at IS NOT NULL)
                OR
                (identity_mode = 'EXISTING_ACTIVE'
                    AND keycloak_user_enabled_at IS NULL
                    AND action_email_sent_at IS NULL)
            ))
    );

COMMENT ON CONSTRAINT ck_iam_identity_invitation_identity_state
    ON iam_identity_invitation IS
    'New identities require enable and action-email evidence; existing active identities never replay account recovery actions';
