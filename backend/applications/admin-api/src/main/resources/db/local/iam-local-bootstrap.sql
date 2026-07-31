-- Local-profile-only fixture. The Spring runner supplies @@ as the statement
-- separator and wraps this entire script in one transaction.

SELECT pg_advisory_xact_lock(hashtextextended('payment-platform:iam-local-identity-fixture', 0))
@@

LOCK TABLE
    iam_tenant, iam_department, iam_user, iam_membership,
    iam_authentication_credential, iam_role, iam_membership_role,
    iam_permission, iam_role_grant, iam_grant_dimension, iam_grant_target,
    iam_menu, iam_role_menu
IN SHARE ROW EXCLUSIVE MODE
@@

CREATE TEMPORARY TABLE iam_local_final_permission (
    id BIGINT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL,
    resource_code VARCHAR(64) NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    description VARCHAR(500) NOT NULL
) ON COMMIT DROP
@@

INSERT INTO iam_local_final_permission(id, permission_code, resource_code, action_code, description)
VALUES
  (3001, 'user:view', 'user', 'view', 'Administration permission'),
  (3002, 'user:create', 'user', 'create', 'Administration permission'),
  (3003, 'user:update', 'user', 'update', 'Administration permission'),
  (3004, 'user:delete', 'user', 'delete', 'Administration permission'),
  (3005, 'user:disable', 'user', 'disable', 'Administration permission'),
  (3006, 'user:assign-role', 'user', 'assign-role', 'Administration permission'),
  (3007, 'role:view', 'role', 'view', 'Administration permission'),
  (3008, 'role:create', 'role', 'create', 'Administration permission'),
  (3009, 'role:update', 'role', 'update', 'Administration permission'),
  (3010, 'role:delete', 'role', 'delete', 'Administration permission'),
  (3011, 'menu:view', 'menu', 'view', 'Administration permission'),
  (3013, 'department:view', 'department', 'view', 'Administration permission'),
  (3015, 'menu:create', 'menu', 'create', 'Administration permission'),
  (3016, 'menu:update', 'menu', 'update', 'Administration permission'),
  (3017, 'menu:delete', 'menu', 'delete', 'Administration permission'),
  (3018, 'department:create', 'department', 'create', 'Administration permission'),
  (3019, 'department:update', 'department', 'update', 'Administration permission'),
  (3020, 'department:delete', 'department', 'delete', 'Administration permission'),
  (3021, 'role:grant-update', 'role', 'grant-update', 'System administrator role grant maintenance')
@@

CREATE TEMPORARY TABLE iam_local_legacy_permission AS
SELECT id, permission_code
  FROM iam_permission
 WHERE id BETWEEN 3001 AND 3014
 ORDER BY id
@@

CREATE TEMPORARY TABLE iam_local_final_menu (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT,
    menu_type VARCHAR(16) NOT NULL,
    menu_name VARCHAR(128) NOT NULL,
    route_name VARCHAR(128) NOT NULL,
    route_path VARCHAR(255),
    component_path VARCHAR(255),
    redirect_path VARCHAR(255),
    sort_order INTEGER NOT NULL,
    auth_code VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    meta_json JSONB NOT NULL,
    permission_button BOOLEAN NOT NULL
) ON COMMIT DROP
@@

INSERT INTO iam_local_final_menu(
    id, parent_id, menu_type, menu_name, route_name, route_path,
    component_path, redirect_path, sort_order, auth_code, status, meta_json, permission_button
)
VALUES
  (6000, NULL, 'DIRECTORY', 'System Management', 'System', '/system', NULL, NULL, 100, NULL,
   'ACTIVE', '{"title":"system.title","icon":"lucide:settings"}'::jsonb, false),
  (6001, 6000, 'PAGE', 'User Management', 'SystemUser', '/system/user', '/system/user/list', NULL,
   110, NULL, 'ACTIVE', '{"title":"system.user.title","icon":"lucide:users"}'::jsonb, false),
  (6002, 6000, 'PAGE', 'Role Management', 'SystemRole', '/system/role', '/system/role/list', NULL,
   120, NULL, 'ACTIVE', '{"title":"system.role.title","icon":"lucide:shield-check"}'::jsonb, false),
  (6003, 6000, 'PAGE', 'Menu Management', 'SystemMenu', '/system/menu', '/system/menu/list', NULL,
   130, NULL, 'ACTIVE', '{"title":"system.menu.title","icon":"lucide:menu"}'::jsonb, false),
  (6004, 6000, 'PAGE', 'Department Management', 'SystemDept', '/system/dept', '/system/dept/list', NULL,
   140, NULL, 'ACTIVE', '{"title":"system.dept.title","icon":"lucide:building-2"}'::jsonb, false),
  (6010, NULL, 'DIRECTORY', 'Dashboard', 'Dashboard', '/dashboard', NULL, '/dashboard/analytics',
   -100, NULL, 'ACTIVE', '{"title":"page.dashboard.title","icon":"lucide:layout-dashboard","order":-1}'::jsonb, false),
  (6011, 6010, 'PAGE', 'Analytics', 'Analytics', '/dashboard/analytics', '/dashboard/analytics/index', NULL,
   -90, NULL, 'ACTIVE', '{"title":"page.dashboard.analytics","icon":"lucide:area-chart","affixTab":true}'::jsonb, false),
  (6012, 6010, 'PAGE', 'Workspace', 'Workspace', '/dashboard/workspace', '/dashboard/workspace/index', NULL,
   -80, NULL, 'ACTIVE', '{"title":"page.dashboard.workspace","icon":"carbon:workspace"}'::jsonb, false),
  (6020, 6001, 'BUTTON', 'View Users', 'UserView', NULL, NULL, NULL, 111, 'user:view',
   'ACTIVE', '{"title":"system.user.permission.view"}'::jsonb, true),
  (6021, 6001, 'BUTTON', 'Create User', 'UserCreate', NULL, NULL, NULL, 112, 'user:create',
   'ACTIVE', '{"title":"system.user.permission.create"}'::jsonb, true),
  (6022, 6001, 'BUTTON', 'Update User', 'UserUpdate', NULL, NULL, NULL, 113, 'user:update',
   'ACTIVE', '{"title":"system.user.permission.update"}'::jsonb, true),
  (6023, 6001, 'BUTTON', 'Delete User', 'UserDelete', NULL, NULL, NULL, 114, 'user:delete',
   'ACTIVE', '{"title":"system.user.permission.delete"}'::jsonb, true),
  (6024, 6001, 'BUTTON', 'Disable User', 'UserDisable', NULL, NULL, NULL, 115, 'user:disable',
   'ACTIVE', '{"title":"system.user.permission.disable"}'::jsonb, true),
  (6025, 6001, 'BUTTON', 'Assign User Roles', 'UserAssignRole', NULL, NULL, NULL, 116, 'user:assign-role',
   'ACTIVE', '{"title":"system.user.permission.assignRole"}'::jsonb, true),
  (6026, 6002, 'BUTTON', 'View Roles', 'RoleView', NULL, NULL, NULL, 121, 'role:view',
   'ACTIVE', '{"title":"system.role.permission.view"}'::jsonb, true),
  (6027, 6002, 'BUTTON', 'Create Role', 'RoleCreate', NULL, NULL, NULL, 122, 'role:create',
   'ACTIVE', '{"title":"system.role.permission.create"}'::jsonb, true),
  (6028, 6002, 'BUTTON', 'Update Role', 'RoleUpdate', NULL, NULL, NULL, 123, 'role:update',
   'ACTIVE', '{"title":"system.role.permission.update"}'::jsonb, true),
  (6029, 6002, 'BUTTON', 'Delete Role', 'RoleDelete', NULL, NULL, NULL, 124, 'role:delete',
   'ACTIVE', '{"title":"system.role.permission.delete"}'::jsonb, true),
  (6030, 6003, 'BUTTON', 'View Menus', 'MenuView', NULL, NULL, NULL, 131, 'menu:view',
   'ACTIVE', '{"title":"system.menu.permission.view"}'::jsonb, true),
  (6031, 6003, 'BUTTON', 'Manage Menus', 'MenuManage', NULL, NULL, NULL, 132, 'menu:manage',
   'DISABLED', '{"title":"system.menu.permission.manage","hideInMenu":true}'::jsonb, true),
  (6032, 6004, 'BUTTON', 'View Departments', 'DepartmentView', NULL, NULL, NULL, 141, 'department:view',
   'ACTIVE', '{"title":"system.dept.permission.view"}'::jsonb, true),
  (6033, 6004, 'BUTTON', 'Manage Departments', 'DepartmentManage', NULL, NULL, NULL, 142, 'department:manage',
   'DISABLED', '{"title":"system.dept.permission.manage","hideInMenu":true}'::jsonb, true),
  (6034, 6003, 'BUTTON', 'Create Menus', 'MenuCreate', NULL, NULL, NULL, 133, 'menu:create',
   'ACTIVE', '{"title":"system.menu.permission.create"}'::jsonb, true),
  (6035, 6003, 'BUTTON', 'Update Menus', 'MenuUpdate', NULL, NULL, NULL, 134, 'menu:update',
   'ACTIVE', '{"title":"system.menu.permission.update"}'::jsonb, true),
  (6036, 6003, 'BUTTON', 'Delete Menus', 'MenuDelete', NULL, NULL, NULL, 135, 'menu:delete',
   'ACTIVE', '{"title":"system.menu.permission.delete"}'::jsonb, true),
  (6037, 6004, 'BUTTON', 'Create Departments', 'DepartmentCreate', NULL, NULL, NULL, 143, 'department:create',
   'ACTIVE', '{"title":"system.dept.permission.create"}'::jsonb, true),
  (6038, 6004, 'BUTTON', 'Update Departments', 'DepartmentUpdate', NULL, NULL, NULL, 144, 'department:update',
   'ACTIVE', '{"title":"system.dept.permission.update"}'::jsonb, true),
  (6039, 6004, 'BUTTON', 'Delete Departments', 'DepartmentDelete', NULL, NULL, NULL, 145, 'department:delete',
   'ACTIVE', '{"title":"system.dept.permission.delete"}'::jsonb, true),
  (6040, 6002, 'BUTTON', 'Edit Role Grants', 'RoleGrantUpdate', NULL, NULL, NULL, 125, 'role:grant-update',
   'ACTIVE', '{"title":"system.role.permission.grantUpdate"}'::jsonb, true)
@@

CREATE TEMPORARY TABLE iam_local_legacy_menu AS
SELECT * FROM iam_local_final_menu WHERE id <= 6033
@@

UPDATE iam_local_legacy_menu
   SET status = 'ACTIVE', meta_json = '{"title":"system.menu.permission.manage"}'::jsonb
 WHERE id = 6031
@@

UPDATE iam_local_legacy_menu
   SET status = 'ACTIVE', meta_json = '{"title":"system.dept.permission.manage"}'::jsonb
 WHERE id = 6033
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_identity_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT
    (SELECT count(*) FROM iam_tenant WHERE id = 1 OR tenant_code = 'platform') = 1
    AND EXISTS (SELECT 1 FROM iam_tenant WHERE id=1 AND tenant_code='platform'
        AND tenant_name='Platform Administration' AND tenant_type='PLATFORM'
        AND status='ACTIVE' AND row_version=0)
    AND (SELECT count(*) FROM iam_department WHERE id=10 OR (tenant_id=1 AND department_code='head-office')) = 1
    AND EXISTS (SELECT 1 FROM iam_department WHERE id=10 AND tenant_id=1 AND parent_id IS NULL
        AND department_code='head-office' AND department_name='Head Office' AND status='ACTIVE'
        AND remark IS NOT DISTINCT FROM 'Local bootstrap department' AND row_version=0)
    AND (SELECT count(*) FROM iam_user WHERE id=100 OR (idp_issuer='local' AND idp_subject='admin')) = 1
    AND EXISTS (SELECT 1 FROM iam_user WHERE id=100 AND idp_issuer='local' AND idp_subject='admin'
        AND display_name='Platform Administrator' AND email_cipher IS NULL AND phone_cipher IS NULL
        AND status='ACTIVE' AND remark IS NOT DISTINCT FROM 'Local bootstrap administrator' AND row_version=0)
    AND (SELECT count(*) FROM iam_membership WHERE id=1000 OR user_id=100) = 1
    AND EXISTS (SELECT 1 FROM iam_membership WHERE id=1000 AND tenant_id=1 AND user_id=100
        AND department_id IS NOT DISTINCT FROM 10 AND status='ACTIVE'
        AND permission_version=0 AND session_version=0 AND row_version=0)
    AND (SELECT count(*) FROM iam_authentication_credential WHERE user_id=100 OR username='admin') = 1
    AND EXISTS (SELECT 1 FROM iam_authentication_credential WHERE user_id=100 AND username='admin'
        AND status='ACTIVE' AND ((password_hash IS NULL AND last_login_at IS NULL AND row_version=0)
          OR (password_hash ~ '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$'
              AND ((last_login_at IS NULL AND row_version=1) OR (last_login_at IS NOT NULL AND row_version>=2)))))
    AND (SELECT count(*) FROM iam_role WHERE id=2000 OR (tenant_id=1
        AND (role_code='platform-admin' OR role_name='Platform Administrator'))) = 1
    AND EXISTS (SELECT 1 FROM iam_role WHERE id=2000 AND tenant_id=1 AND role_code='platform-admin'
        AND role_name='Platform Administrator' AND applicable_tenant_type='PLATFORM'
        AND NOT assignable AND system_role AND status='ACTIVE'
        AND remark IS NOT DISTINCT FROM 'Local bootstrap administration role' AND row_version=0)
    AND (SELECT count(*) FROM iam_membership_role
        WHERE membership_id=1000 OR role_id=2000 OR assigned_by=1000) = 1
    AND EXISTS (SELECT 1 FROM iam_membership_role WHERE tenant_id=1 AND membership_id=1000
        AND role_id=2000 AND assigned_by IS NOT DISTINCT FROM 1000)
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_role_menus_are_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_role_menu WHERE role_id=2000
          OR menu_id IN (SELECT id FROM iam_local_final_menu)) = 8
   AND (SELECT count(*) FROM iam_role_menu WHERE tenant_id=1 AND role_id=2000
          AND menu_id IN (6000,6001,6002,6003,6004,6010,6011,6012)) = 8
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_final_fixture_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT pg_temp.iam_local_identity_is_exact()
   AND pg_temp.iam_local_role_menus_are_exact()
   AND (SELECT count(*) FROM iam_role_grant grant_row
          JOIN iam_local_final_permission expected
            ON grant_row.id=expected.id+1000 AND grant_row.permission_id=expected.id
           AND grant_row.grant_key=replace(expected.permission_code, ':', '-')
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000 AND grant_row.status='ACTIVE'
           AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
           AND grant_row.created_by IS NOT DISTINCT FROM 1000
           AND grant_row.updated_by IS NOT DISTINCT FROM 1000) = 19
   AND (SELECT count(*) FROM iam_role_grant WHERE role_id=2000 OR created_by=1000 OR updated_by=1000
          OR id IN (SELECT id+1000 FROM iam_local_final_permission)) = 19
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
          JOIN iam_local_final_permission expected
            ON dimension_row.id=expected.id+2000 AND dimension_row.grant_id=expected.id+1000
         WHERE dimension_row.dimension_code='TENANT' AND dimension_row.scope_mode='TENANT_ALL') = 19
   AND (SELECT count(*) FROM iam_grant_dimension WHERE grant_id IN
          (SELECT id FROM iam_role_grant WHERE role_id=2000)
          OR id IN (SELECT id+2000 FROM iam_local_final_permission)) = 19
   AND NOT EXISTS (SELECT 1 FROM iam_grant_target target JOIN iam_grant_dimension dimension_row
          ON dimension_row.id=target.dimension_id WHERE dimension_row.grant_id IN
          (SELECT id FROM iam_role_grant WHERE role_id=2000))
   AND (SELECT count(*) FROM iam_menu menu JOIN iam_local_final_menu expected ON expected.id=menu.id
          AND expected.parent_id IS NOT DISTINCT FROM menu.parent_id AND expected.menu_type=menu.menu_type
          AND expected.menu_name=menu.menu_name AND expected.route_name=menu.route_name
          AND expected.route_path IS NOT DISTINCT FROM menu.route_path
          AND expected.component_path IS NOT DISTINCT FROM menu.component_path
          AND expected.redirect_path IS NOT DISTINCT FROM menu.redirect_path
          AND expected.sort_order=menu.sort_order AND expected.auth_code IS NOT DISTINCT FROM menu.auth_code
          AND expected.status=menu.status AND expected.meta_json=menu.meta_json
         WHERE menu.tenant_id=1 AND menu.display_permission_id IS NULL AND menu.remark IS NULL) = 29
   AND (SELECT count(*) FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)
          OR (tenant_id=1 AND (route_name IN (SELECT route_name FROM iam_local_final_menu)
          OR route_path IN (SELECT route_path FROM iam_local_final_menu WHERE route_path IS NOT NULL)
          OR auth_code IN (SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL)
          OR parent_id IN (SELECT id FROM iam_local_final_menu)))) = 29
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_legacy_fixture_is_exact(include_buttons BOOLEAN)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT pg_temp.iam_local_identity_is_exact()
   AND pg_temp.iam_local_role_menus_are_exact()
   AND (SELECT count(*) FROM iam_role_grant grant_row JOIN iam_local_legacy_permission expected
          ON grant_row.id=expected.id+1000 AND grant_row.permission_id=expected.id
         AND grant_row.grant_key=replace(expected.permission_code, ':', '-')
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000 AND grant_row.status='ACTIVE'
         AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
         AND grant_row.created_by IS NOT DISTINCT FROM 1000
         AND grant_row.updated_by IS NOT DISTINCT FROM 1000 AND grant_row.row_version=0) = 14
   AND (SELECT count(*) FROM iam_role_grant WHERE role_id=2000 OR created_by=1000 OR updated_by=1000
          OR id BETWEEN 4001 AND 4014) = 14
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row JOIN iam_local_legacy_permission expected
          ON dimension_row.id=expected.id+2000 AND dimension_row.grant_id=expected.id+1000
         WHERE dimension_row.dimension_code='TENANT' AND dimension_row.scope_mode='TENANT_ALL') = 14
   AND (SELECT count(*) FROM iam_grant_dimension WHERE id BETWEEN 5001 AND 5014
          OR grant_id BETWEEN 4001 AND 4014) = 14
   AND NOT EXISTS (SELECT 1 FROM iam_grant_target target JOIN iam_grant_dimension dimension_row
          ON dimension_row.id=target.dimension_id WHERE dimension_row.grant_id BETWEEN 4001 AND 4014)
   AND (SELECT count(*) FROM iam_menu menu JOIN iam_local_legacy_menu expected ON expected.id=menu.id
          AND expected.parent_id IS NOT DISTINCT FROM menu.parent_id AND expected.menu_type=menu.menu_type
          AND expected.menu_name=menu.menu_name AND expected.route_name=menu.route_name
          AND expected.route_path IS NOT DISTINCT FROM menu.route_path
          AND expected.component_path IS NOT DISTINCT FROM menu.component_path
          AND expected.redirect_path IS NOT DISTINCT FROM menu.redirect_path
          AND expected.sort_order=menu.sort_order AND expected.auth_code IS NOT DISTINCT FROM menu.auth_code
          AND expected.status=menu.status AND expected.meta_json=menu.meta_json
         WHERE menu.tenant_id=1 AND menu.display_permission_id IS NULL AND menu.remark IS NULL
          AND (NOT expected.permission_button OR include_buttons)) = CASE WHEN include_buttons THEN 22 ELSE 8 END
   AND (SELECT count(*) FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)
          OR (tenant_id=1 AND (route_name IN (SELECT route_name FROM iam_local_final_menu)
          OR route_path IN (SELECT route_path FROM iam_local_final_menu WHERE route_path IS NOT NULL)
          OR auth_code IN (SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL)
          OR parent_id IN (SELECT id FROM iam_local_final_menu)))) = CASE WHEN include_buttons THEN 22 ELSE 8 END
$$
@@

DO $$
DECLARE
    fixture_footprint_present BOOLEAN;
    legacy_without_buttons BOOLEAN;
    legacy_with_buttons BOOLEAN;
BEGIN
    IF (SELECT count(*) FROM iam_local_final_permission expected JOIN iam_permission permission
          ON permission.id=expected.id AND permission.permission_code=expected.permission_code
         AND permission.resource_code=expected.resource_code AND permission.action_code=expected.action_code
         AND permission.risk_level='NORMAL' AND permission.required_dimensions=ARRAY['TENANT']::VARCHAR(32)[]
         AND NOT permission.requires_step_up AND NOT permission.requires_approval
         AND permission.status='ACTIVE' AND permission.description IS NOT DISTINCT FROM expected.description
         AND permission.cross_tenant_mode='SAME_TENANT_ONLY') <> 19
       OR (SELECT count(*) FROM iam_permission WHERE
          (id=3012 AND permission_code='menu:manage' AND status='DISABLED')
          OR (id=3014 AND permission_code='department:manage' AND status='DISABLED')) <> 2 THEN
        RAISE EXCEPTION 'Local bootstrap refused: required permission catalog is incomplete or modified';
    END IF;

    SELECT EXISTS(SELECT 1 FROM iam_tenant WHERE id=1 OR tenant_code='platform')
        OR EXISTS(SELECT 1 FROM iam_department WHERE id=10 OR (tenant_id=1 AND department_code='head-office'))
        OR EXISTS(SELECT 1 FROM iam_user WHERE id=100 OR (idp_issuer='local' AND idp_subject='admin'))
        OR EXISTS(SELECT 1 FROM iam_membership WHERE id=1000 OR user_id=100)
        OR EXISTS(SELECT 1 FROM iam_authentication_credential WHERE user_id=100 OR username='admin')
        OR EXISTS(SELECT 1 FROM iam_role WHERE id=2000 OR (tenant_id=1
             AND (role_code='platform-admin' OR role_name='Platform Administrator')))
        OR EXISTS(SELECT 1 FROM iam_membership_role WHERE membership_id=1000 OR role_id=2000 OR assigned_by=1000)
        OR EXISTS(SELECT 1 FROM iam_role_grant WHERE role_id=2000 OR created_by=1000 OR updated_by=1000
             OR id IN (SELECT id+1000 FROM iam_local_final_permission) OR id BETWEEN 4001 AND 4014)
        OR EXISTS(SELECT 1 FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)
             OR (tenant_id=1 AND (route_name IN (SELECT route_name FROM iam_local_final_menu)
             OR auth_code IN (SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL)
             OR parent_id IN (SELECT id FROM iam_local_final_menu))))
        OR EXISTS(SELECT 1 FROM iam_role_menu WHERE role_id=2000 OR menu_id IN
             (SELECT id FROM iam_local_final_menu))
      INTO fixture_footprint_present;

    IF fixture_footprint_present AND NOT pg_temp.iam_local_final_fixture_is_exact() THEN
        legacy_without_buttons := pg_temp.iam_local_legacy_fixture_is_exact(false);
        legacy_with_buttons := pg_temp.iam_local_legacy_fixture_is_exact(true);
        IF NOT legacy_without_buttons AND NOT legacy_with_buttons THEN
            RAISE EXCEPTION 'Local bootstrap refused: local fixture footprint is incomplete or modified';
        END IF;

        DELETE FROM iam_role_grant WHERE tenant_id=1 AND role_id=2000 AND permission_id IN (3012,3014);
        INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by)
        SELECT id+1000,1,2000,id,replace(permission_code,':','-'),'ACTIVE',1000,1000
          FROM iam_local_final_permission
        ON CONFLICT (role_id,permission_id,grant_key) DO NOTHING;
        INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
        SELECT id+2000,id+1000,'TENANT','TENANT_ALL' FROM iam_local_final_permission
        ON CONFLICT (grant_id,dimension_code) DO NOTHING;

        IF legacy_with_buttons THEN
            UPDATE iam_menu SET status='DISABLED', meta_json=expected.meta_json,
                   updated_at=now(), row_version=row_version+1
              FROM iam_local_final_menu expected
             WHERE iam_menu.id=expected.id AND iam_menu.tenant_id=1 AND expected.id IN (6031,6033);
            INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,sort_order,auth_code,status,meta_json)
            SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
                   sort_order,auth_code,status,meta_json FROM iam_local_final_menu WHERE id>=6034 ORDER BY id;
        ELSE
            INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,sort_order,auth_code,status,meta_json)
            SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
                   sort_order,auth_code,status,meta_json FROM iam_local_final_menu
             WHERE permission_button ORDER BY id;
        END IF;

        IF NOT pg_temp.iam_local_final_fixture_is_exact() THEN
            RAISE EXCEPTION 'Local bootstrap failed: legacy fixture upgrade was not atomic and complete';
        END IF;
    END IF;
END;
$$
@@

INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
VALUES(1,'platform','Platform Administration','PLATFORM','ACTIVE') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status,remark)
VALUES(10,1,NULL,'head-office','Head Office','ACTIVE','Local bootstrap department') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark)
VALUES(100,'local','admin','Platform Administrator','ACTIVE','Local bootstrap administrator') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
VALUES(1000,1,100,10,'ACTIVE') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_authentication_credential(user_id,username,password_hash,status)
VALUES(100,'admin',NULL,'ACTIVE') ON CONFLICT(user_id) DO NOTHING
@@
INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,assignable,system_role,status,remark)
VALUES(2000,1,'platform-admin','Platform Administrator','PLATFORM',false,true,'ACTIVE',
       'Local bootstrap administration role') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
VALUES(1,1000,2000,1000) ON CONFLICT DO NOTHING
@@
INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by)
SELECT id+1000,1,2000,id,replace(permission_code,':','-'),'ACTIVE',1000,1000
  FROM iam_local_final_permission ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@
INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT id+2000,id+1000,'TENANT','TENANT_ALL' FROM iam_local_final_permission
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@
INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,sort_order,auth_code,status,meta_json)
SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
       sort_order,auth_code,status,meta_json FROM iam_local_final_menu ORDER BY id
ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
SELECT 1,2000,id FROM iam_local_final_menu WHERE NOT permission_button ON CONFLICT DO NOTHING
@@

DO $$
BEGIN
    IF NOT pg_temp.iam_local_final_fixture_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap failed: local identity fixture is incomplete after writing';
    END IF;
END;
$$
@@

SELECT setval('iam_id_seq', GREATEST(10000,(SELECT last_value FROM iam_id_seq),
    COALESCE((SELECT max(id) FROM iam_user),0),COALESCE((SELECT max(id) FROM iam_tenant),0),
    COALESCE((SELECT max(id) FROM iam_department),0),COALESCE((SELECT max(id) FROM iam_membership),0),
    COALESCE((SELECT max(id) FROM iam_role),0),COALESCE((SELECT max(id) FROM iam_permission),0),
    COALESCE((SELECT max(id) FROM iam_role_grant),0),COALESCE((SELECT max(id) FROM iam_grant_dimension),0),
    COALESCE((SELECT max(id) FROM iam_grant_target),0),COALESCE((SELECT max(id) FROM iam_menu),0),
    COALESCE((SELECT max(id) FROM iam_audit_event),0),COALESCE((SELECT max(id) FROM iam_permission_change_outbox),0)),true)
@@

DROP FUNCTION pg_temp.iam_local_legacy_fixture_is_exact(BOOLEAN)
@@
DROP FUNCTION pg_temp.iam_local_final_fixture_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_role_menus_are_exact()
@@
DROP FUNCTION pg_temp.iam_local_identity_is_exact()
@@
