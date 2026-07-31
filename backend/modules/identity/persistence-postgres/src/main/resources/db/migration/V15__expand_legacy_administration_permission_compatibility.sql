LOCK TABLE
    iam_permission, iam_role_grant, iam_grant_dimension, iam_grant_target,
    iam_role, iam_membership, iam_membership_role, iam_audit_event,
    iam_permission_change_outbox, iam_permission_change_relay_state
IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF (SELECT count(*) FROM iam_permission
         WHERE (id=3012 AND permission_code='menu:manage'
                AND resource_code='menu' AND action_code='manage' AND status='DISABLED')
            OR (id=3014 AND permission_code='department:manage'
                AND resource_code='department' AND action_code='manage' AND status='DISABLED')) <> 2
       OR (SELECT count(*) FROM iam_permission
            WHERE id BETWEEN 3015 AND 3020 AND status='ACTIVE'
              AND permission_code IN (
                'menu:create','menu:update','menu:delete',
                'department:create','department:update','department:delete')) <> 6 THEN
        RAISE EXCEPTION 'Legacy administration compatibility expansion requires the exact V14 catalog';
    END IF;
END;
$$;

CREATE TEMPORARY TABLE iam_v15_permission_mapping (
    legacy_permission_code VARCHAR(128) NOT NULL,
    modern_permission_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (legacy_permission_code, modern_permission_code)
) ON COMMIT DROP;

INSERT INTO iam_v15_permission_mapping(legacy_permission_code, modern_permission_code)
VALUES
  ('menu:manage','menu:create'),
  ('menu:manage','menu:update'),
  ('menu:manage','menu:delete'),
  ('department:manage','department:create'),
  ('department:manage','department:update'),
  ('department:manage','department:delete');

CREATE TEMPORARY TABLE iam_v15_active_legacy_grant ON COMMIT DROP AS
SELECT grant_row.id, grant_row.tenant_id, grant_row.role_id, grant_row.permission_id,
       permission.permission_code, grant_row.grant_key, grant_row.valid_from,
       grant_row.valid_until, grant_row.created_by, grant_row.created_at,
       grant_row.updated_by, grant_row.updated_at, grant_row.row_version,
       grant_row.valid_from IS NULL
       AND grant_row.valid_until IS NULL
       AND (SELECT count(*) FROM iam_grant_dimension dimension_row
             WHERE dimension_row.grant_id=grant_row.id
               AND dimension_row.dimension_code='TENANT'
               AND dimension_row.scope_mode='TENANT_ALL')=1
       AND (SELECT count(*) FROM iam_grant_dimension dimension_row
             WHERE dimension_row.grant_id=grant_row.id)=1
       AND NOT EXISTS (
           SELECT 1 FROM iam_grant_target target
            WHERE target.dimension_id IN (
                SELECT id FROM iam_grant_dimension WHERE grant_id=grant_row.id
            )
       ) AS v14_equivalent
  FROM iam_role_grant grant_row
  JOIN iam_permission permission ON permission.id=grant_row.permission_id
 WHERE grant_row.status='ACTIVE'
   AND permission.permission_code IN ('menu:manage','department:manage');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_v15_active_legacy_grant legacy
          JOIN iam_v15_permission_mapping mapping
            ON mapping.legacy_permission_code=legacy.permission_code
          JOIN iam_permission modern_permission
            ON modern_permission.permission_code=mapping.modern_permission_code
         WHERE legacy.v14_equivalent
           AND NOT EXISTS (
               SELECT 1
                 FROM iam_role_grant modern_grant
                WHERE modern_grant.tenant_id=legacy.tenant_id
                  AND modern_grant.role_id=legacy.role_id
                  AND modern_grant.permission_id=modern_permission.id
                  AND modern_grant.grant_key=
                      'migration-v14-' || replace(mapping.modern_permission_code, ':', '-')
                  AND modern_grant.status='ACTIVE'
                  AND modern_grant.valid_from IS NULL
                  AND modern_grant.valid_until IS NULL
                  AND (SELECT count(*) FROM iam_grant_dimension dimension_row
                        WHERE dimension_row.grant_id=modern_grant.id
                          AND dimension_row.dimension_code='TENANT'
                          AND dimension_row.scope_mode='TENANT_ALL')=1
                  AND (SELECT count(*) FROM iam_grant_dimension dimension_row
                        WHERE dimension_row.grant_id=modern_grant.id)=1
                  AND NOT EXISTS (
                      SELECT 1 FROM iam_grant_target target
                       WHERE target.dimension_id IN (
                           SELECT id FROM iam_grant_dimension
                            WHERE grant_id=modern_grant.id
                       )
                  )
           )
    ) THEN
        RAISE EXCEPTION 'V14 exact legacy grant expansion is incomplete or modified';
    END IF;
END;
$$;

CREATE TEMPORARY TABLE iam_v15_grant_clone ON COMMIT DROP AS
SELECT nextval('iam_id_seq') AS new_grant_id,
       legacy.id AS old_grant_id, legacy.tenant_id, legacy.role_id,
       modern_permission.id AS permission_id,
       ('v15-' || legacy.id || '-' || replace(mapping.modern_permission_code, ':', '-'))::VARCHAR(64)
           AS grant_key,
       legacy.valid_from, legacy.valid_until, legacy.created_by, legacy.created_at,
       legacy.updated_by, legacy.updated_at, legacy.row_version
  FROM iam_v15_active_legacy_grant legacy
  JOIN iam_v15_permission_mapping mapping
    ON mapping.legacy_permission_code=legacy.permission_code
  JOIN iam_permission modern_permission
    ON modern_permission.permission_code=mapping.modern_permission_code
 WHERE NOT legacy.v14_equivalent;

INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,valid_from,valid_until,
    created_by,created_at,updated_by,updated_at,row_version
)
SELECT new_grant_id,tenant_id,role_id,permission_id,grant_key,'ACTIVE',valid_from,valid_until,
       created_by,created_at,updated_by,updated_at,row_version
  FROM iam_v15_grant_clone;

CREATE TEMPORARY TABLE iam_v15_dimension_clone ON COMMIT DROP AS
SELECT nextval('iam_id_seq') AS new_dimension_id,
       dimension_row.id AS old_dimension_id,
       clone.new_grant_id, dimension_row.dimension_code,
       dimension_row.scope_mode, dimension_row.created_at
  FROM iam_v15_grant_clone clone
  JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=clone.old_grant_id;

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode,created_at)
SELECT new_dimension_id,new_grant_id,dimension_code,scope_mode,created_at
  FROM iam_v15_dimension_clone;

INSERT INTO iam_grant_target(id,dimension_id,target_ref,created_at)
SELECT nextval('iam_id_seq'),dimension_clone.new_dimension_id,target.target_ref,target.created_at
  FROM iam_v15_dimension_clone dimension_clone
  JOIN iam_grant_target target ON target.dimension_id=dimension_clone.old_dimension_id;

CREATE TEMPORARY TABLE iam_v15_affected_role ON COMMIT DROP AS
SELECT DISTINCT tenant_id, role_id FROM iam_v15_active_legacy_grant;

UPDATE iam_permission
   SET status='ACTIVE', updated_at=now(), row_version=row_version+1
 WHERE permission_code IN ('menu:manage','department:manage')
   AND status='DISABLED';

UPDATE iam_role role
   SET row_version=row_version+1, updated_at=now()
 WHERE EXISTS (
     SELECT 1 FROM iam_v15_affected_role affected
      WHERE affected.tenant_id=role.tenant_id AND affected.role_id=role.id
 );

CREATE TEMPORARY TABLE iam_v15_changed_membership ON COMMIT DROP AS
WITH changed AS (
    UPDATE iam_membership membership
       SET permission_version=permission_version+1, updated_at=now()
     WHERE EXISTS (
         SELECT 1
           FROM iam_membership_role membership_role
           JOIN iam_v15_affected_role affected
             ON affected.tenant_id=membership_role.tenant_id
            AND affected.role_id=membership_role.role_id
          WHERE membership_role.tenant_id=membership.tenant_id
            AND membership_role.membership_id=membership.id
     )
    RETURNING tenant_id,id,permission_version
)
SELECT tenant_id,id AS membership_id,permission_version FROM changed;

INSERT INTO iam_audit_event(
    id,tenant_id,operator_membership_id,target_type,target_ref,
    action_code,decision,reason_code,permission_code,after_value,trace_id
)
SELECT nextval('iam_id_seq'),affected.tenant_id,NULL,'ROLE_GRANTS',affected.role_id::text,
       'EXPAND_LEGACY_ADMIN_PERMISSIONS','NOT_APPLICABLE','MIGRATION',NULL,
       jsonb_build_object(
           'migration','V15',
           'clonedGrantCount',(SELECT count(*) FROM iam_v15_grant_clone clone
                                WHERE clone.tenant_id=affected.tenant_id
                                  AND clone.role_id=affected.role_id),
           'legacyCompatibilityActive',true
       ),'migration-v15'
  FROM iam_v15_affected_role affected;

INSERT INTO iam_permission_change_outbox(
    id,tenant_id,aggregate_type,aggregate_ref,event_type,payload,
    aggregate_version,schema_version,partition_key,trace_id
)
SELECT nextval('iam_id_seq'),tenant_id,'MEMBERSHIP',membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('membershipId',membership_id::text,
                          'permissionVersion',permission_version,
                          'reason','V15_LEGACY_ADMIN_PERMISSION_EXPANSION'),
       permission_version,1,tenant_id::text || ':' || membership_id::text,'migration-v15'
  FROM iam_v15_changed_membership;

COMMENT ON COLUMN iam_permission.status IS
    'ACTIVE permissions may authorize requests; legacy manage codes remain active only during the expand compatibility phase';
