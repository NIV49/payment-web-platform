DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM iam_permission
         WHERE id = 3012 AND permission_code = 'menu:manage'
           AND resource_code = 'menu' AND action_code = 'manage'
           AND risk_level = 'NORMAL'
           AND required_dimensions = ARRAY['TENANT']::VARCHAR(32)[]
           AND NOT requires_step_up AND NOT requires_approval
           AND status = 'ACTIVE' AND cross_tenant_mode = 'SAME_TENANT_ONLY'
    ) OR NOT EXISTS (
        SELECT 1 FROM iam_permission
         WHERE id = 3014 AND permission_code = 'department:manage'
           AND resource_code = 'department' AND action_code = 'manage'
           AND risk_level = 'NORMAL'
           AND required_dimensions = ARRAY['TENANT']::VARCHAR(32)[]
           AND NOT requires_step_up AND NOT requires_approval
           AND status = 'ACTIVE' AND cross_tenant_mode = 'SAME_TENANT_ONLY'
    ) THEN
        RAISE EXCEPTION 'Granular administration permission upgrade requires the exact legacy catalog';
    END IF;

    IF EXISTS (
        SELECT 1 FROM iam_permission
         WHERE id BETWEEN 3015 AND 3021
            OR permission_code IN (
                'menu:create', 'menu:update', 'menu:delete',
                'department:create', 'department:update', 'department:delete',
                'role:grant-update'
            )
    ) THEN
        RAISE EXCEPTION 'Granular administration permission identifiers or codes already exist';
    END IF;
END;
$$;

CREATE TEMPORARY TABLE iam_v14_exact_tenant_all_grant ON COMMIT DROP AS
SELECT DISTINCT grant_row.tenant_id, grant_row.role_id, permission.permission_code
  FROM iam_role_grant grant_row
  JOIN iam_permission permission ON permission.id = grant_row.permission_id
 WHERE grant_row.status = 'ACTIVE'
   AND grant_row.valid_from IS NULL
   AND grant_row.valid_until IS NULL
   AND permission.status = 'ACTIVE'
   AND permission.permission_code IN ('role:view', 'menu:manage', 'department:manage')
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
         WHERE dimension_row.grant_id = grant_row.id
           AND dimension_row.dimension_code = 'TENANT'
           AND dimension_row.scope_mode = 'TENANT_ALL') = 1
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
         WHERE dimension_row.grant_id = grant_row.id) = 1
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
        WHERE target.dimension_id IN (
            SELECT id FROM iam_grant_dimension WHERE grant_id = grant_row.id
        )
   );

CREATE TEMPORARY TABLE iam_v14_system_role_upgrade ON COMMIT DROP AS
SELECT role.tenant_id, role.id AS role_id
  FROM iam_role role
 WHERE role.system_role
   AND role.status = 'ACTIVE'
   AND EXISTS (
       SELECT 1 FROM iam_v14_exact_tenant_all_grant exact_grant
        WHERE exact_grant.tenant_id = role.tenant_id
          AND exact_grant.role_id = role.id
          AND exact_grant.permission_code = 'role:view'
   )
   AND EXISTS (
       SELECT 1 FROM iam_v14_exact_tenant_all_grant exact_grant
        WHERE exact_grant.tenant_id = role.tenant_id
          AND exact_grant.role_id = role.id
          AND exact_grant.permission_code = 'menu:manage'
   )
   AND EXISTS (
       SELECT 1 FROM iam_v14_exact_tenant_all_grant exact_grant
        WHERE exact_grant.tenant_id = role.tenant_id
          AND exact_grant.role_id = role.id
          AND exact_grant.permission_code = 'department:manage'
   );

UPDATE iam_permission
   SET status = 'DISABLED', updated_at = now(), row_version = row_version + 1
 WHERE id IN (3012, 3014)
   AND status <> 'DISABLED';

INSERT INTO iam_permission (
    id, permission_code, resource_code, action_code, risk_level,
    required_dimensions, requires_step_up, requires_approval, status, description,
    cross_tenant_mode
)
VALUES
  (3015, 'menu:create', 'menu', 'create', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3016, 'menu:update', 'menu', 'update', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3017, 'menu:delete', 'menu', 'delete', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3018, 'department:create', 'department', 'create', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3019, 'department:update', 'department', 'update', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3020, 'department:delete', 'department', 'delete', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'Administration permission', 'SAME_TENANT_ONLY'),
  (3021, 'role:grant-update', 'role', 'grant-update', 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
   'ACTIVE', 'System administrator role grant maintenance', 'SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE iam_v14_inserted_grant (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
) ON COMMIT DROP;

WITH desired AS (
    SELECT legacy.tenant_id, legacy.role_id, permission.id AS permission_id,
           'migration-v14-' || replace(permission.permission_code, ':', '-') AS grant_key
      FROM iam_v14_exact_tenant_all_grant legacy
      JOIN iam_permission permission
        ON (legacy.permission_code = 'menu:manage'
            AND permission.permission_code IN ('menu:create', 'menu:update', 'menu:delete'))
        OR (legacy.permission_code = 'department:manage'
            AND permission.permission_code IN (
                'department:create', 'department:update', 'department:delete'))
    UNION ALL
    SELECT candidate.tenant_id, candidate.role_id, permission.id,
           'migration-v14-' || replace(permission.permission_code, ':', '-')
      FROM iam_v14_system_role_upgrade candidate
      JOIN iam_permission permission ON permission.permission_code = 'role:grant-update'
), inserted AS (
    INSERT INTO iam_role_grant(
        id, tenant_id, role_id, permission_id, grant_key, status, created_by, updated_by
    )
    SELECT nextval('iam_id_seq'), tenant_id, role_id, permission_id, grant_key,
           'ACTIVE', NULL, NULL
      FROM desired
    RETURNING id, tenant_id, role_id
)
INSERT INTO iam_v14_inserted_grant(id, tenant_id, role_id)
SELECT id, tenant_id, role_id FROM inserted;

INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
SELECT nextval('iam_id_seq'), id, 'TENANT', 'TENANT_ALL'
  FROM iam_v14_inserted_grant;

CREATE TEMPORARY TABLE iam_v14_affected_role ON COMMIT DROP AS
SELECT DISTINCT tenant_id, role_id FROM iam_v14_inserted_grant;

UPDATE iam_role role
   SET row_version = row_version + 1,
       updated_at = now()
 WHERE EXISTS (
     SELECT 1 FROM iam_v14_affected_role affected
      WHERE affected.tenant_id = role.tenant_id
        AND affected.role_id = role.id
 );

CREATE TEMPORARY TABLE iam_v14_changed_membership (
    tenant_id BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,
    permission_version BIGINT NOT NULL
) ON COMMIT DROP;

WITH changed AS (
    UPDATE iam_membership membership
       SET permission_version = permission_version + 1,
           updated_at = now()
     WHERE EXISTS (
         SELECT 1
           FROM iam_membership_role membership_role
           JOIN iam_v14_affected_role affected
             ON affected.tenant_id = membership_role.tenant_id
            AND affected.role_id = membership_role.role_id
          WHERE membership_role.tenant_id = membership.tenant_id
            AND membership_role.membership_id = membership.id
     )
    RETURNING tenant_id, id, permission_version
)
INSERT INTO iam_v14_changed_membership(tenant_id, membership_id, permission_version)
SELECT tenant_id, id, permission_version FROM changed;

INSERT INTO iam_audit_event(
    id, tenant_id, operator_membership_id, target_type, target_ref,
    action_code, decision, reason_code, permission_code, after_value, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, NULL, 'ROLE_GRANTS', role_id::text,
       'MIGRATE_GRANULAR_ADMIN_PERMISSIONS', 'NOT_APPLICABLE', 'MIGRATION', NULL,
       jsonb_build_object(
           'migration', 'V14',
           'grantCount', (SELECT count(*) FROM iam_v14_inserted_grant inserted
                           WHERE inserted.tenant_id = affected.tenant_id
                             AND inserted.role_id = affected.role_id)
       ), 'migration-v14'
  FROM iam_v14_affected_role affected;

INSERT INTO iam_permission_change_outbox(
    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
    aggregate_version, schema_version, partition_key, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, 'MEMBERSHIP', membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('membershipId', membership_id::text,
                          'permissionVersion', permission_version,
                          'reason', 'V14_GRANULAR_ADMIN_PERMISSION_UPGRADE'),
       permission_version, 1, tenant_id::text || ':' || membership_id::text,
       'migration-v14'
  FROM iam_v14_changed_membership;

COMMENT ON COLUMN iam_permission.status IS
    'ACTIVE permissions may authorize requests; disabled legacy manage codes remain for history and compatibility inspection';
