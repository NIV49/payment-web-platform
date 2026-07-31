-- V15 is immutable and may already have run. This forward-only guard ensures that
-- every later deployment starts from the exact administration catalog expected by
-- the application without rewriting unknown or tampered authorization metadata.
LOCK TABLE iam_permission IN SHARE MODE;

DO $$
BEGIN
    IF (
        SELECT count(*)
          FROM (VALUES
              (3001::BIGINT, 'user:view',        'user',       'view'),
              (3002::BIGINT, 'user:create',      'user',       'create'),
              (3003::BIGINT, 'user:update',      'user',       'update'),
              (3004::BIGINT, 'user:delete',      'user',       'delete'),
              (3005::BIGINT, 'user:disable',     'user',       'disable'),
              (3006::BIGINT, 'user:assign-role', 'user',       'assign-role'),
              (3007::BIGINT, 'role:view',        'role',       'view'),
              (3008::BIGINT, 'role:create',      'role',       'create'),
              (3009::BIGINT, 'role:update',      'role',       'update'),
              (3010::BIGINT, 'role:delete',      'role',       'delete'),
              (3011::BIGINT, 'menu:view',        'menu',       'view'),
              (3012::BIGINT, 'menu:manage',      'menu',       'manage'),
              (3013::BIGINT, 'department:view',  'department', 'view'),
              (3014::BIGINT, 'department:manage','department', 'manage'),
              (3015::BIGINT, 'menu:create',      'menu',       'create'),
              (3016::BIGINT, 'menu:update',      'menu',       'update'),
              (3017::BIGINT, 'menu:delete',      'menu',       'delete'),
              (3018::BIGINT, 'department:create','department', 'create'),
              (3019::BIGINT, 'department:update','department', 'update'),
              (3020::BIGINT, 'department:delete','department', 'delete'),
              (3021::BIGINT, 'role:grant-update','role',       'grant-update')
          ) AS expected(id, permission_code, resource_code, action_code)
          JOIN iam_permission permission
            ON permission.id = expected.id
           AND permission.permission_code = expected.permission_code
           AND permission.resource_code = expected.resource_code
           AND permission.action_code = expected.action_code
           AND permission.risk_level = 'NORMAL'
           AND permission.required_dimensions = ARRAY['TENANT']::VARCHAR(32)[]
           AND NOT permission.requires_step_up
           AND NOT permission.requires_approval
           AND permission.cross_tenant_mode = 'SAME_TENANT_ONLY'
           AND permission.status = 'ACTIVE'
    ) <> 21 THEN
        RAISE EXCEPTION 'The administration permission catalog is incomplete or modified';
    END IF;
END;
$$;
