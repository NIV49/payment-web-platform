-- Local-only convergence for IAM-003. Production tenant provisioning and
-- historical backfill require a separately reviewed rollout migration.
BEGIN;

DO $$
BEGIN
    IF (SELECT count(*)
          FROM iam_tenant
         WHERE (id = 2 AND account_domain = 'MERCHANT' AND status = 'ACTIVE')
            OR (id = 3 AND account_domain = 'AGENT' AND status = 'ACTIVE')) <> 2
       OR (SELECT count(*)
             FROM iam_role
            WHERE (id = 2200 AND tenant_id = 2 AND system_role AND NOT assignable
                   AND status = 'ACTIVE' AND deleted_at IS NULL)
               OR (id = 3200 AND tenant_id = 3 AND system_role AND NOT assignable
                   AND status = 'ACTIVE' AND deleted_at IS NULL)) <> 2
       OR (SELECT count(*)
             FROM iam_menu
            WHERE tenant_id = 1 AND deleted_at IS NULL
              AND id IN (6000,6001,6002,6020,6021,6022,6023,6024,6025,
                         6026,6027,6028,6029,6040)) <> 14
       OR (SELECT count(*)
             FROM iam_permission
            WHERE status = 'ACTIVE'
              AND permission_code IN (
                  'user:view','user:create','user:update','user:delete',
                  'user:disable','user:assign-role','role:view','role:create',
                  'role:update','role:delete','menu:view','department:view',
                  'role:grant-update')) <> 13 THEN
        RAISE EXCEPTION 'IAM-003 local convergence prerequisites are not exact';
    END IF;
END
$$;

CREATE TEMPORARY TABLE iam003_local_changed_role ON COMMIT DROP AS
SELECT target.tenant_id, target.role_id
  FROM (VALUES (2::bigint,2200::bigint),(3::bigint,3200::bigint))
       AS target(tenant_id,role_id)
 WHERE EXISTS (
     SELECT 1
       FROM (VALUES (6000::bigint),(6001::bigint),(6002::bigint)) AS required(source_id)
      WHERE NOT EXISTS (
          SELECT 1 FROM iam_role_menu role_menu
           WHERE role_menu.tenant_id = target.tenant_id
             AND role_menu.role_id = target.role_id
             AND role_menu.menu_id = required.source_id
                 + CASE target.tenant_id WHEN 2 THEN 10000 ELSE 20000 END
      )
 ) OR EXISTS (
     SELECT 1
       FROM iam_permission permission
      WHERE permission.permission_code IN (
          'user:view','user:create','user:update','user:delete',
          'user:disable','user:assign-role','role:view','role:create',
          'role:update','role:delete','menu:view','department:view',
          'role:grant-update')
        AND NOT EXISTS (
            SELECT 1
              FROM iam_role_grant grant_row
             WHERE grant_row.tenant_id = target.tenant_id
               AND grant_row.role_id = target.role_id
               AND grant_row.permission_id = permission.id
               AND grant_row.status = 'ACTIVE'
               AND grant_row.valid_from IS NULL
               AND grant_row.valid_until IS NULL
               AND (SELECT count(*) FROM iam_grant_dimension dimension
                     WHERE dimension.grant_id = grant_row.id) = 1
               AND EXISTS (
                   SELECT 1 FROM iam_grant_dimension dimension
                    WHERE dimension.grant_id = grant_row.id
                      AND dimension.dimension_code = 'TENANT'
                      AND dimension.scope_mode = 'TENANT_ALL'
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM iam_grant_target grant_target
                     JOIN iam_grant_dimension dimension
                       ON dimension.id = grant_target.dimension_id
                    WHERE dimension.grant_id = grant_row.id
               )
        )
 );

INSERT INTO iam_menu(
    id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed
)
SELECT source.id + target.id_offset,target.tenant_id,
       CASE WHEN source.parent_id IS NULL THEN NULL ELSE source.parent_id + target.id_offset END,
       source.menu_type,source.menu_name,source.route_name,source.route_path,
       source.component_path,source.redirect_path,source.sort_order,source.auth_code,
       source.status,source.meta_json,true
  FROM iam_menu source
  CROSS JOIN (VALUES (2::bigint,10000::bigint),(3::bigint,20000::bigint))
       AS target(tenant_id,id_offset)
 WHERE source.tenant_id = 1 AND source.deleted_at IS NULL
   AND source.id IN (6000,6001,6002,6020,6021,6022,6023,6024,6025,
                    6026,6027,6028,6029,6040)
ON CONFLICT(id) DO NOTHING;

INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
VALUES
  (2,2200,16000),(2,2200,16001),(2,2200,16002),
  (3,3200,26000),(3,3200,26001),(3,3200,26002)
ON CONFLICT DO NOTHING;

WITH target(tenant_id,role_id,membership_id) AS (
    VALUES (2::bigint,2200::bigint,2100::bigint),
           (3::bigint,3200::bigint,3100::bigint)
), required AS (
    SELECT id AS permission_id, permission_code
      FROM iam_permission
     WHERE status = 'ACTIVE'
       AND permission_code IN (
           'user:view','user:create','user:update','user:delete',
           'user:disable','user:assign-role','role:view','role:create',
           'role:update','role:delete','menu:view','department:view',
           'role:grant-update')
), inserted AS (
    INSERT INTO iam_role_grant(
        id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
    )
    SELECT nextval('iam_id_seq'),target.tenant_id,target.role_id,
           required.permission_id,
           'local-iam003-' || replace(required.permission_code,':','-'),
           'ACTIVE',target.membership_id,target.membership_id
      FROM target CROSS JOIN required
     WHERE NOT EXISTS (
         SELECT 1
           FROM iam_role_grant grant_row
          WHERE grant_row.tenant_id = target.tenant_id
            AND grant_row.role_id = target.role_id
            AND grant_row.permission_id = required.permission_id
            AND grant_row.status = 'ACTIVE'
            AND grant_row.valid_from IS NULL
            AND grant_row.valid_until IS NULL
            AND (SELECT count(*) FROM iam_grant_dimension dimension
                  WHERE dimension.grant_id = grant_row.id) = 1
            AND EXISTS (
                SELECT 1 FROM iam_grant_dimension dimension
                 WHERE dimension.grant_id = grant_row.id
                   AND dimension.dimension_code = 'TENANT'
                   AND dimension.scope_mode = 'TENANT_ALL'
            )
            AND NOT EXISTS (
                SELECT 1
                  FROM iam_grant_target grant_target
                  JOIN iam_grant_dimension dimension
                    ON dimension.id = grant_target.dimension_id
                 WHERE dimension.grant_id = grant_row.id
            )
     )
    RETURNING id
)
INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT nextval('iam_id_seq'),id,'TENANT','TENANT_ALL' FROM inserted;

UPDATE iam_role role_row
   SET row_version = row_version + 1, updated_at = now()
 WHERE EXISTS (
     SELECT 1 FROM iam003_local_changed_role changed
      WHERE changed.tenant_id = role_row.tenant_id
        AND changed.role_id = role_row.id
 );

UPDATE iam_membership membership
   SET permission_version = permission_version + 1,
       session_version = session_version + 1,
       row_version = row_version + 1,
       updated_at = now()
 WHERE EXISTS (
     SELECT 1
       FROM iam_membership_role membership_role
       JOIN iam003_local_changed_role changed
         ON changed.tenant_id = membership_role.tenant_id
        AND changed.role_id = membership_role.role_id
      WHERE membership_role.tenant_id = membership.tenant_id
        AND membership_role.membership_id = membership.id
 );

DO $$
BEGIN
    IF (SELECT count(*)
          FROM iam_menu target_menu
          JOIN (VALUES (2::bigint,10000::bigint),(3::bigint,20000::bigint))
               AS target(tenant_id,id_offset)
            ON target.tenant_id = target_menu.tenant_id
          JOIN iam_menu source
            ON source.tenant_id = 1
           AND source.id = target_menu.id - target.id_offset
         WHERE source.id IN (6000,6001,6002,6020,6021,6022,6023,6024,6025,
                             6026,6027,6028,6029,6040)
           AND target_menu.deleted_at IS NULL
           AND target_menu.parent_id IS NOT DISTINCT FROM
               CASE WHEN source.parent_id IS NULL THEN NULL ELSE source.parent_id + target.id_offset END
           AND target_menu.menu_type = source.menu_type
           AND target_menu.route_name IS NOT DISTINCT FROM source.route_name
           AND target_menu.route_path IS NOT DISTINCT FROM source.route_path
           AND target_menu.component_path IS NOT DISTINCT FROM source.component_path
           AND target_menu.auth_code IS NOT DISTINCT FROM source.auth_code
           AND target_menu.status = source.status
           AND target_menu.meta_json = source.meta_json
           AND target_menu.system_managed) <> 28
       OR (SELECT count(*) FROM iam_role_menu
            WHERE (tenant_id=2 AND role_id=2200 AND menu_id IN (16000,16001,16002))
               OR (tenant_id=3 AND role_id=3200 AND menu_id IN (26000,26001,26002))) <> 6
       OR (SELECT count(*)
             FROM (VALUES (2::bigint,2200::bigint),(3::bigint,3200::bigint))
                  AS target(tenant_id,role_id)
             CROSS JOIN iam_permission permission
            WHERE permission.permission_code IN (
                'user:view','user:create','user:update','user:delete',
                'user:disable','user:assign-role','role:view','role:create',
                'role:update','role:delete','menu:view','department:view',
                'role:grant-update')
              AND EXISTS (
                  SELECT 1 FROM iam_role_grant grant_row
                   WHERE grant_row.tenant_id = target.tenant_id
                     AND grant_row.role_id = target.role_id
                     AND grant_row.permission_id = permission.id
                     AND grant_row.status = 'ACTIVE'
                     AND grant_row.valid_from IS NULL
                     AND grant_row.valid_until IS NULL
                     AND (SELECT count(*) FROM iam_grant_dimension dimension
                           WHERE dimension.grant_id = grant_row.id) = 1
                     AND EXISTS (
                         SELECT 1 FROM iam_grant_dimension dimension
                          WHERE dimension.grant_id = grant_row.id
                            AND dimension.dimension_code = 'TENANT'
                            AND dimension.scope_mode = 'TENANT_ALL'
                     )
                     AND NOT EXISTS (
                         SELECT 1
                           FROM iam_grant_target grant_target
                           JOIN iam_grant_dimension dimension
                             ON dimension.id = grant_target.dimension_id
                          WHERE dimension.grant_id = grant_row.id
                     )
              )) <> 26 THEN
        RAISE EXCEPTION 'IAM-003 local delegated administration convergence failed';
    END IF;
END
$$;

COMMIT;
