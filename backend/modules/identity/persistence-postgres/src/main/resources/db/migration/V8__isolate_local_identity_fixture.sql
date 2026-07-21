-- Forward-only migration.
--
-- V2/V3 accidentally installed a local administrator fixture in every schema.
-- V8 may remove only that fixed, complete fixture. It never treats unrelated IAM,
-- audit, outbox, relay, or permission-catalog rows as disposable. A collision,
-- partial fixture, or modified fixture aborts the whole migration so an operator
-- can classify the data explicitly. Do not add a down migration that recreates
-- local identities in a production schema; the local profile owns bootstrap data.

-- Coordinate Flyway and local bootstrap executions, then stop concurrent IAM
-- writes for the short validation/delete window. Flyway executes PostgreSQL SQL
-- migrations in one transaction, so both the advisory and table locks are held
-- until V8 either commits in full or rolls back in full.
SELECT pg_advisory_xact_lock(hashtextextended('payment-platform:iam-local-identity-fixture', 0));

LOCK TABLE
    iam_tenant,
    iam_department,
    iam_user,
    iam_membership,
    iam_authentication_credential,
    iam_role,
    iam_membership_role,
    iam_permission,
    iam_role_grant,
    iam_grant_dimension,
    iam_grant_target,
    iam_menu,
    iam_role_menu,
    iam_audit_event,
    iam_permission_change_outbox,
    iam_permission_change_relay_state
IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMPORARY TABLE iam_v8_expected_permission (
    id BIGINT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL,
    resource_code VARCHAR(64) NOT NULL,
    action_code VARCHAR(64) NOT NULL
) ON COMMIT DROP;

INSERT INTO iam_v8_expected_permission(id, permission_code, resource_code, action_code)
VALUES
  (3001, 'user:view', 'user', 'view'),
  (3002, 'user:create', 'user', 'create'),
  (3003, 'user:update', 'user', 'update'),
  (3004, 'user:delete', 'user', 'delete'),
  (3005, 'user:disable', 'user', 'disable'),
  (3006, 'user:assign-role', 'user', 'assign-role'),
  (3007, 'role:view', 'role', 'view'),
  (3008, 'role:create', 'role', 'create'),
  (3009, 'role:update', 'role', 'update'),
  (3010, 'role:delete', 'role', 'delete'),
  (3011, 'menu:view', 'menu', 'view'),
  (3012, 'menu:manage', 'menu', 'manage'),
  (3013, 'department:view', 'department', 'view'),
  (3014, 'department:manage', 'department', 'manage');

CREATE TEMPORARY TABLE iam_v8_expected_menu (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT,
    menu_type VARCHAR(16) NOT NULL,
    menu_name VARCHAR(128) NOT NULL,
    route_name VARCHAR(128) NOT NULL,
    route_path VARCHAR(255) NOT NULL,
    component_path VARCHAR(255),
    redirect_path VARCHAR(255),
    sort_order INTEGER NOT NULL,
    meta_json JSONB NOT NULL,
    row_version BIGINT NOT NULL
) ON COMMIT DROP;

INSERT INTO iam_v8_expected_menu(
    id, parent_id, menu_type, menu_name, route_name, route_path,
    component_path, redirect_path, sort_order, meta_json, row_version
)
VALUES
  (6000, NULL, 'DIRECTORY', 'System Management', 'System', '/system',
   NULL, NULL, 100, '{"title":"system.title","icon":"lucide:settings"}'::jsonb, 2),
  (6001, 6000, 'PAGE', 'User Management', 'SystemUser', '/system/user',
   '/system/user/list', NULL, 110,
   '{"title":"system.user.title","icon":"lucide:users"}'::jsonb, 1),
  (6002, 6000, 'PAGE', 'Role Management', 'SystemRole', '/system/role',
   '/system/role/list', NULL, 120,
   '{"title":"system.role.title","icon":"lucide:shield-check"}'::jsonb, 1),
  (6003, 6000, 'PAGE', 'Menu Management', 'SystemMenu', '/system/menu',
   '/system/menu/list', NULL, 130,
   '{"title":"system.menu.title","icon":"lucide:menu"}'::jsonb, 1),
  (6004, 6000, 'PAGE', 'Department Management', 'SystemDept', '/system/dept',
   '/system/dept/list', NULL, 140,
   '{"title":"system.dept.title","icon":"lucide:building-2"}'::jsonb, 1),
  (6010, NULL, 'DIRECTORY', 'Dashboard', 'Dashboard', '/dashboard',
   NULL, '/dashboard/analytics', -100,
   '{"title":"page.dashboard.title","icon":"lucide:layout-dashboard","order":-1}'::jsonb, 1),
  (6011, 6010, 'PAGE', 'Analytics', 'Analytics', '/dashboard/analytics',
   '/dashboard/analytics/index', NULL, -90,
   '{"title":"page.dashboard.analytics","icon":"lucide:area-chart","affixTab":true}'::jsonb, 0),
  (6012, 6010, 'PAGE', 'Workspace', 'Workspace', '/dashboard/workspace',
   '/dashboard/workspace/index', NULL, -80,
   '{"title":"page.dashboard.workspace","icon":"carbon:workspace"}'::jsonb, 0);

DO $$
DECLARE
    fixture_footprint_present BOOLEAN;
BEGIN
    -- These 14 rows are product permission catalog, not local fixture data.
    -- Extensions are allowed. Every required row must retain its full contract.
    IF (
        SELECT count(*)
          FROM iam_v8_expected_permission expected
          JOIN iam_permission permission
            ON permission.id = expected.id
           AND permission.permission_code = expected.permission_code
           AND permission.resource_code = expected.resource_code
           AND permission.action_code = expected.action_code
           AND permission.risk_level = 'NORMAL'
           AND permission.required_dimensions = ARRAY['TENANT']::VARCHAR(32)[]
           AND NOT permission.requires_step_up
           AND NOT permission.requires_approval
           AND permission.status = 'ACTIVE'
           AND permission.description IS NOT DISTINCT FROM 'Administration permission'
           AND permission.cross_tenant_mode = 'SAME_TENANT_ONLY'
           AND permission.row_version = 0
    ) <> 14 THEN
        RAISE EXCEPTION
            'V8 fixture isolation refused: required permission catalog is incomplete or modified';
    END IF;

    SELECT
        EXISTS (SELECT 1 FROM iam_tenant WHERE id = 1 OR tenant_code = 'platform')
        OR EXISTS (SELECT 1 FROM iam_department
                    WHERE id = 10 OR (tenant_id = 1 AND department_code = 'head-office'))
        OR EXISTS (SELECT 1 FROM iam_user
                    WHERE id = 100 OR (idp_issuer = 'local' AND idp_subject = 'admin'))
        OR EXISTS (SELECT 1 FROM iam_membership WHERE id = 1000 OR tenant_id = 1 OR user_id = 100)
        OR EXISTS (SELECT 1 FROM iam_authentication_credential WHERE user_id = 100 OR username = 'admin')
        OR EXISTS (SELECT 1 FROM iam_role
                    WHERE id = 2000 OR (tenant_id = 1 AND role_code = 'platform-admin'))
        OR EXISTS (SELECT 1 FROM iam_membership_role
                    WHERE tenant_id = 1 OR membership_id = 1000 OR role_id = 2000)
        OR EXISTS (SELECT 1 FROM iam_role_grant
                    WHERE id BETWEEN 4001 AND 4014 OR tenant_id = 1 OR role_id = 2000)
        OR EXISTS (SELECT 1 FROM iam_grant_dimension
                    WHERE id BETWEEN 5001 AND 5014 OR grant_id BETWEEN 4001 AND 4014)
        OR EXISTS (
            SELECT 1
              FROM iam_grant_target target
              JOIN iam_grant_dimension dimension_row ON dimension_row.id = target.dimension_id
             WHERE dimension_row.id BETWEEN 5001 AND 5014
                OR dimension_row.grant_id BETWEEN 4001 AND 4014
        )
        OR EXISTS (SELECT 1 FROM iam_menu
                    WHERE tenant_id = 1
                       OR id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012))
        OR EXISTS (SELECT 1 FROM iam_role_menu
                    WHERE tenant_id = 1 OR role_id = 2000
                       OR menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012))
      INTO fixture_footprint_present;

    -- If operators already removed the whole legacy fixture, V8 is deliberately
    -- a no-op. Unrelated rows do not become evidence that this fixture exists.
    IF NOT fixture_footprint_present THEN
        RETURN;
    END IF;

    IF (SELECT count(*) FROM iam_tenant WHERE id = 1 OR tenant_code = 'platform') <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_tenant
             WHERE id = 1 AND tenant_code = 'platform'
               AND tenant_name = 'Platform Administration'
               AND tenant_type = 'PLATFORM' AND status = 'ACTIVE' AND row_version = 0
        )
        OR (SELECT count(*) FROM iam_department WHERE tenant_id = 1 OR id = 10) <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_department
             WHERE id = 10 AND tenant_id = 1 AND parent_id IS NULL
               AND department_code = 'head-office' AND department_name = 'Head Office'
               AND status = 'ACTIVE' AND remark IS NOT DISTINCT FROM 'Local bootstrap department'
               AND row_version = 0
        )
        OR (SELECT count(*) FROM iam_user
             WHERE id = 100 OR (idp_issuer = 'local' AND idp_subject = 'admin')) <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_user
             WHERE id = 100 AND idp_issuer = 'local' AND idp_subject = 'admin'
               AND display_name = 'Platform Administrator'
               AND email_cipher IS NULL AND phone_cipher IS NULL
               AND status = 'ACTIVE'
               AND remark IS NOT DISTINCT FROM 'Local bootstrap administrator'
               AND row_version = 0
        )
        OR (SELECT count(*) FROM iam_membership
             WHERE id = 1000 OR tenant_id = 1 OR user_id = 100) <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_membership
             WHERE id = 1000 AND tenant_id = 1 AND user_id = 100
               AND department_id IS NOT DISTINCT FROM 10 AND status = 'ACTIVE'
               AND permission_version = 0 AND session_version = 0 AND row_version = 0
        )
        OR (SELECT count(*) FROM iam_authentication_credential
             WHERE user_id = 100 OR username = 'admin') <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_authentication_credential
             WHERE user_id = 100 AND username = 'admin' AND status = 'ACTIVE'
               AND last_login_at IS NULL
               AND (password_hash IS NULL OR password_hash LIKE '$2%')
               AND row_version = CASE WHEN password_hash IS NULL THEN 0 ELSE 1 END
        )
        OR (SELECT count(*) FROM iam_role WHERE tenant_id = 1 OR id = 2000) <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_role
             WHERE id = 2000 AND tenant_id = 1 AND role_code = 'platform-admin'
               AND role_name = 'Platform Administrator'
               AND applicable_tenant_type = 'PLATFORM'
               AND NOT assignable AND system_role AND status = 'ACTIVE'
               AND remark IS NOT DISTINCT FROM 'Local bootstrap administration role'
               AND row_version = 0
        )
        OR (SELECT count(*) FROM iam_membership_role
             WHERE tenant_id = 1 OR membership_id = 1000 OR role_id = 2000) <> 1
        OR NOT EXISTS (
            SELECT 1 FROM iam_membership_role
             WHERE tenant_id = 1 AND membership_id = 1000 AND role_id = 2000
               AND assigned_by IS NOT DISTINCT FROM 1000
        )
        OR (
            SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_v8_expected_permission expected
                ON grant_row.id = expected.id + 1000
               AND grant_row.permission_id = expected.id
               AND grant_row.grant_key = replace(expected.permission_code, ':', '-')
             WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000
               AND grant_row.status = 'ACTIVE'
               AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
               AND grant_row.created_by IS NOT DISTINCT FROM 1000
               AND grant_row.updated_by IS NOT DISTINCT FROM 1000
               AND grant_row.row_version = 0
        ) <> 14
        OR (SELECT count(*) FROM iam_role_grant
             WHERE tenant_id = 1 OR role_id = 2000 OR id BETWEEN 4001 AND 4014) <> 14
        OR (
            SELECT count(*)
              FROM iam_grant_dimension dimension_row
              JOIN iam_v8_expected_permission expected
                ON dimension_row.id = expected.id + 2000
               AND dimension_row.grant_id = expected.id + 1000
             WHERE dimension_row.dimension_code = 'TENANT'
               AND dimension_row.scope_mode = 'TENANT_ALL'
        ) <> 14
        OR (SELECT count(*) FROM iam_grant_dimension
             WHERE id BETWEEN 5001 AND 5014 OR grant_id BETWEEN 4001 AND 4014) <> 14
        OR EXISTS (
            SELECT 1
              FROM iam_grant_target target
              JOIN iam_grant_dimension dimension_row ON dimension_row.id = target.dimension_id
             WHERE dimension_row.id BETWEEN 5001 AND 5014
                OR dimension_row.grant_id BETWEEN 4001 AND 4014
        )
        OR (
            SELECT count(*)
              FROM iam_menu menu
              JOIN iam_v8_expected_menu expected
                ON expected.id = menu.id
               AND expected.parent_id IS NOT DISTINCT FROM menu.parent_id
               AND expected.menu_type = menu.menu_type
               AND expected.menu_name = menu.menu_name
               AND expected.route_name = menu.route_name
               AND expected.route_path = menu.route_path
               AND expected.component_path IS NOT DISTINCT FROM menu.component_path
               AND expected.redirect_path IS NOT DISTINCT FROM menu.redirect_path
               AND expected.sort_order = menu.sort_order
               AND expected.meta_json = menu.meta_json
               AND expected.row_version = menu.row_version
             WHERE menu.tenant_id = 1 AND menu.display_permission_id IS NULL
               AND menu.auth_code IS NULL AND menu.remark IS NULL AND menu.status = 'ACTIVE'
        ) <> 8
        OR (SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1
                OR id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)) <> 8
        OR (
            SELECT count(*)
              FROM iam_role_menu role_menu
              JOIN iam_v8_expected_menu expected ON expected.id = role_menu.menu_id
             WHERE role_menu.tenant_id = 1 AND role_menu.role_id = 2000
        ) <> 8
        OR (SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 1 OR role_id = 2000
                OR menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)) <> 8
        OR EXISTS (SELECT 1 FROM iam_audit_event WHERE tenant_id = 1)
        OR EXISTS (SELECT 1 FROM iam_permission_change_outbox WHERE tenant_id = 1)
        OR EXISTS (
            SELECT 1
              FROM iam_permission_change_relay_state relay
              JOIN iam_permission_change_outbox outbox ON outbox.id = relay.event_record_id
             WHERE outbox.tenant_id = 1
        ) THEN
        RAISE EXCEPTION
            'V8 fixture isolation refused: fixture footprint is incomplete or modified';
    END IF;
END;
$$;

DELETE FROM iam_role_menu
 WHERE tenant_id = 1 AND role_id = 2000
   AND menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012);

DELETE FROM iam_grant_dimension
 WHERE id BETWEEN 5001 AND 5014 AND grant_id BETWEEN 4001 AND 4014;

DELETE FROM iam_role_grant
 WHERE id BETWEEN 4001 AND 4014 AND tenant_id = 1 AND role_id = 2000;

DELETE FROM iam_membership_role
 WHERE tenant_id = 1 AND membership_id = 1000 AND role_id = 2000;

DELETE FROM iam_menu
 WHERE tenant_id = 1
   AND id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012);

DELETE FROM iam_role WHERE tenant_id = 1 AND id = 2000;
DELETE FROM iam_authentication_credential WHERE user_id = 100 AND username = 'admin';
DELETE FROM iam_membership WHERE tenant_id = 1 AND id = 1000 AND user_id = 100;
DELETE FROM iam_department WHERE tenant_id = 1 AND id = 10;
DELETE FROM iam_user WHERE id = 100 AND idp_issuer = 'local' AND idp_subject = 'admin';
DELETE FROM iam_tenant WHERE id = 1 AND tenant_code = 'platform';

COMMENT ON TABLE iam_permission IS
    'Global permission catalog. Tenant, administrator, role, grant, and menu fixtures are local-profile data only';
