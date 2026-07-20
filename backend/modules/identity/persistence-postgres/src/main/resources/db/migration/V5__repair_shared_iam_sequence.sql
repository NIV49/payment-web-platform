-- V3 reset the shared sequence from iam_menu alone and could move it behind
-- identifiers already allocated by another IAM table. Never edit V3 after it
-- has been applied; repair every existing database with this forward migration.
SELECT setval(
    'iam_id_seq',
    GREATEST(
        10000,
        (SELECT last_value FROM iam_id_seq),
        COALESCE((SELECT max(id) FROM iam_user), 0),
        COALESCE((SELECT max(id) FROM iam_tenant), 0),
        COALESCE((SELECT max(id) FROM iam_department), 0),
        COALESCE((SELECT max(id) FROM iam_membership), 0),
        COALESCE((SELECT max(id) FROM iam_role), 0),
        COALESCE((SELECT max(id) FROM iam_permission), 0),
        COALESCE((SELECT max(id) FROM iam_role_grant), 0),
        COALESCE((SELECT max(id) FROM iam_grant_dimension), 0),
        COALESCE((SELECT max(id) FROM iam_grant_target), 0),
        COALESCE((SELECT max(id) FROM iam_menu), 0),
        COALESCE((SELECT max(id) FROM iam_audit_event), 0),
        COALESCE((SELECT max(id) FROM iam_permission_change_outbox), 0)
    ),
    true
);
