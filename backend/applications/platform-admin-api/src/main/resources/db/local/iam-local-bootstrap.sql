-- Local-profile-only fixture. The Spring runner supplies @@ as the statement
-- separator and wraps this entire script in one transaction.

SELECT pg_advisory_xact_lock(hashtextextended('payment-platform:iam-local-identity-fixture', 0))
@@

LOCK TABLE
    iam_tenant, iam_department, iam_user, iam_membership,
    iam_authentication_credential, iam_role, iam_membership_role,
    iam_permission, iam_role_grant, iam_grant_dimension, iam_grant_target,
    iam_menu, iam_role_menu, iam_audit_event,
    iam_permission_change_outbox, iam_permission_change_relay_state,
    merchant, merchant_protected_nonce
IN SHARE ROW EXCLUSIVE MODE
@@

-- Expand-phase upgrade for the three deterministic local-only administrators.
-- Role codes remain stable; only interactive login identifiers become email-shaped.
UPDATE iam_user
   SET idp_subject = CASE id
       WHEN 100 THEN 'admin@platform.localhost'
       WHEN 200 THEN 'admin@merchant.localhost'
       WHEN 300 THEN 'admin@agent.localhost'
   END,
       updated_at = now()
 WHERE (id, account_domain, idp_issuer, idp_subject, idp_provisioning_status) IN (
       (100, 'PLATFORM', 'local', 'admin', 'LOCAL_ONLY'),
       (200, 'MERCHANT', 'local', 'merchant-admin', 'LOCAL_ONLY'),
       (300, 'AGENT', 'local', 'agent-admin', 'LOCAL_ONLY'))
@@

UPDATE iam_authentication_credential
   SET username = CASE user_id
       WHEN 100 THEN 'admin@platform.localhost'
       WHEN 200 THEN 'admin@merchant.localhost'
       WHEN 300 THEN 'admin@agent.localhost'
   END,
       updated_at = now()
 WHERE (user_id, account_domain, username) IN (
       (100, 'PLATFORM', 'admin'),
       (200, 'MERCHANT', 'merchant-admin'),
       (300, 'AGENT', 'agent-admin'))
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

CREATE OR REPLACE FUNCTION pg_temp.iam_local_platform_dictionary_extension_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH expected_permission(permission_code) AS (
    VALUES
      ('dictionary:view'), ('dictionary:create'), ('dictionary:update'), ('dictionary:delete'),
      ('dictionary-data:view'), ('dictionary-data:create'),
      ('dictionary-data:update'), ('dictionary-data:delete')
), expected_menu(route_name, menu_type, parent_route_name, route_path, component_path,
                   auth_code, sort_order, meta_json) AS (
    VALUES
      ('SystemDictionary','PAGE','System','/system/dict','/system/dict/list',NULL,150,
       '{"title":"system.dict.title","icon":"lucide:book-open"}'::jsonb),
      ('SystemDictionaryDataIndex','PAGE','System','/system/dict/data','/system/dict/data/list',NULL,160,
       '{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb),
      ('DictionaryView','BUTTON','SystemDictionary',NULL,NULL,'dictionary:view',151,
       '{"title":"system.dict.permission.view"}'::jsonb),
      ('DictionaryCreate','BUTTON','SystemDictionary',NULL,NULL,'dictionary:create',152,
       '{"title":"system.dict.permission.create"}'::jsonb),
      ('DictionaryUpdate','BUTTON','SystemDictionary',NULL,NULL,'dictionary:update',153,
       '{"title":"system.dict.permission.update"}'::jsonb),
      ('DictionaryDelete','BUTTON','SystemDictionary',NULL,NULL,'dictionary:delete',154,
       '{"title":"system.dict.permission.delete"}'::jsonb),
      ('DictionaryDataView','BUTTON','SystemDictionaryDataIndex',NULL,NULL,'dictionary-data:view',162,
       '{"title":"system.dictData.permission.view"}'::jsonb),
      ('DictionaryDataCreate','BUTTON','SystemDictionaryDataIndex',NULL,NULL,'dictionary-data:create',163,
       '{"title":"system.dictData.permission.create"}'::jsonb),
      ('DictionaryDataUpdate','BUTTON','SystemDictionaryDataIndex',NULL,NULL,'dictionary-data:update',164,
       '{"title":"system.dictData.permission.update"}'::jsonb),
      ('DictionaryDataDelete','BUTTON','SystemDictionaryDataIndex',NULL,NULL,'dictionary-data:delete',165,
       '{"title":"system.dictData.permission.delete"}'::jsonb)
)
SELECT (SELECT count(*)
          FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
          JOIN expected_permission expected ON expected.permission_code=permission.permission_code
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.grant_key=replace(expected.permission_code,':','-')
           AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NULL
           AND grant_row.valid_until IS NULL) = 8
   AND (SELECT count(*)
          FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND permission.permission_code IN (SELECT permission_code FROM expected_permission)) = 8
   AND (SELECT count(*)
          FROM iam_grant_dimension dimension_row
          JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND permission.permission_code IN (SELECT permission_code FROM expected_permission)
           AND dimension_row.dimension_code='TENANT'
           AND dimension_row.scope_mode='TENANT_ALL') = 8
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
       JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
       JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
       JOIN iam_permission permission ON permission.id=grant_row.permission_id
       WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
         AND permission.permission_code IN (SELECT permission_code FROM expected_permission)
   )
   AND (SELECT count(*)
          FROM expected_menu expected
          JOIN iam_menu menu ON menu.tenant_id=1 AND menu.route_name=expected.route_name
          JOIN iam_menu parent ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
         WHERE menu.menu_type=expected.menu_type
           AND parent.route_name=expected.parent_route_name
           AND menu.route_path IS NOT DISTINCT FROM expected.route_path
           AND menu.component_path IS NOT DISTINCT FROM expected.component_path
           AND menu.auth_code IS NOT DISTINCT FROM expected.auth_code
           AND menu.sort_order=expected.sort_order AND menu.status='ACTIVE'
           AND menu.meta_json=expected.meta_json AND menu.system_managed
           AND menu.deleted_at IS NULL) = 10
   AND (SELECT count(*) FROM iam_menu
         WHERE tenant_id=1 AND route_name IN (SELECT route_name FROM expected_menu)) = 10
   AND (SELECT count(*)
          FROM iam_role_menu role_menu
          JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
         WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
           AND menu.route_name IN ('SystemDictionary','SystemDictionaryDataIndex')) = 2
   AND (
       (NOT EXISTS (
           SELECT 1 FROM iam_menu menu
            WHERE menu.tenant_id=1
              AND (menu.route_name='SystemDictionaryData'
                   OR menu.route_path='/system/dict/data/type/:dictType')
        ) AND NOT EXISTS (
           SELECT 1 FROM iam_role_menu role_menu
           JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
           WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
             AND menu.route_name='SystemDictionaryData'
        ))
       OR
       ((SELECT count(*) FROM iam_menu menu
          JOIN iam_menu parent ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
          JOIN iam_permission permission ON permission.id=menu.display_permission_id
         WHERE menu.tenant_id=1 AND menu.route_name='SystemDictionaryData'
           AND menu.menu_type='PAGE' AND menu.menu_name='Dictionary Data Detail'
           AND menu.route_path='/system/dict/data/type/:dictType'
           AND menu.component_path='/system/dict/data/list' AND menu.redirect_path IS NULL
           AND menu.sort_order=161 AND menu.auth_code IS NULL
           AND menu.status='DISABLED' AND menu.system_managed
           AND menu.deleted_at IS NOT NULL AND menu.row_version=1
           AND menu.meta_json='{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb
           AND parent.route_name='System' AND permission.permission_code='dictionary-data:view') = 1
        AND (SELECT count(*) FROM iam_menu menu
              WHERE menu.tenant_id=1
                AND (menu.route_name='SystemDictionaryData'
                     OR menu.route_path='/system/dict/data/type/:dictType')) = 1
        AND (SELECT count(*) FROM iam_role_menu role_menu
              JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
             WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
               AND menu.route_name='SystemDictionaryData') = 1)
   )
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_platform_dictionary_extension_is_absent()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT NOT EXISTS (
           SELECT 1 FROM iam_role_grant grant_row
           JOIN iam_permission permission ON permission.id=grant_row.permission_id
           WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
             AND permission.permission_code LIKE 'dictionary%')
   AND NOT EXISTS (
           SELECT 1 FROM iam_menu WHERE tenant_id=1
             AND (route_name LIKE 'Dictionary%' OR route_name LIKE 'SystemDictionary%'))
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_identity_is_exact(migration_stage INTEGER)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT
    (SELECT count(*) FROM iam_tenant WHERE id = 1 OR tenant_code = 'platform') = 1
    AND EXISTS (SELECT 1 FROM iam_tenant WHERE id=1 AND tenant_code='platform'
        AND tenant_name='Platform Administration' AND tenant_type='PLATFORM'
        AND account_domain='PLATFORM' AND status='ACTIVE' AND row_version=0)
    AND (SELECT count(*) FROM iam_department WHERE id=10 OR (tenant_id=1 AND department_code='head-office')) = 1
    AND EXISTS (SELECT 1 FROM iam_department WHERE id=10 AND tenant_id=1
        AND department_code='head-office' AND status='ACTIVE'
        AND system_managed AND deleted_at IS NULL)
    AND (SELECT count(*) FROM iam_user WHERE id=100 OR (idp_issuer='local' AND idp_subject='admin@platform.localhost')) = 1
    AND EXISTS (SELECT 1 FROM iam_user WHERE id=100 AND idp_issuer='local' AND idp_subject='admin@platform.localhost'
        AND display_name='Platform Administrator' AND email_cipher IS NULL AND phone_cipher IS NULL
        AND account_domain='PLATFORM' AND status='ACTIVE'
        AND remark IS NOT DISTINCT FROM 'Local bootstrap administrator' AND row_version=0)
    AND (SELECT count(*) FROM iam_membership WHERE id=1000 OR user_id=100) = 1
    AND EXISTS (SELECT 1 FROM iam_membership WHERE id=1000 AND tenant_id=1 AND user_id=100
        AND department_id IS NOT DISTINCT FROM 10 AND status='ACTIVE'
        AND account_domain='PLATFORM'
        AND permission_version=migration_stage
        AND session_version=0
        AND row_version=CASE
            WHEN migration_stage=5 THEN 1
            WHEN migration_stage=6 THEN 2
            WHEN migration_stage=7 THEN row_version
            ELSE 0
        END
        AND (migration_stage<>7 OR row_version IN (0,3)))
    AND (SELECT count(*) FROM iam_authentication_credential WHERE user_id=100 OR username='admin@platform.localhost') = 1
    AND EXISTS (SELECT 1 FROM iam_authentication_credential WHERE user_id=100 AND username='admin@platform.localhost'
        AND account_domain='PLATFORM' AND status='ACTIVE'
        AND ((password_hash IS NULL AND last_login_at IS NULL AND row_version=0)
          OR (password_hash ~ '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$'
              AND ((last_login_at IS NULL AND row_version=1) OR (last_login_at IS NOT NULL AND row_version>=2)))))
    AND (SELECT count(*) FROM iam_role WHERE id=2000 OR (tenant_id=1
        AND (role_code='platform-admin' OR role_name='Platform Administrator'))) = 1
    AND EXISTS (SELECT 1 FROM iam_role WHERE id=2000 AND tenant_id=1 AND role_code='platform-admin'
        AND role_name='Platform Administrator' AND applicable_tenant_type='PLATFORM'
        AND NOT assignable AND system_role AND status='ACTIVE'
        AND remark IS NOT DISTINCT FROM 'Local bootstrap administration role'
        AND ((migration_stage=7 AND row_version IN (1,4))
          OR (migration_stage<>7
              AND row_version=CASE WHEN migration_stage IN (5,6) THEN 4 ELSE migration_stage END))
        AND deleted_at IS NULL)
    AND (SELECT count(*) FROM iam_membership_role
        WHERE membership_id=1000 OR (role_id=2000 AND membership_id<>1001)) = 1
    AND EXISTS (SELECT 1 FROM iam_membership_role WHERE tenant_id=1 AND membership_id=1000
        AND role_id=2000 AND assigned_by IS NOT DISTINCT FROM 1000)
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_v14_history_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_audit_event
         WHERE tenant_id=1 AND operator_membership_id IS NULL
           AND target_type='ROLE_GRANTS' AND target_ref='2000'
           AND action_code='MIGRATE_GRANULAR_ADMIN_PERMISSIONS'
           AND decision='NOT_APPLICABLE' AND reason_code='MIGRATION'
           AND permission_code IS NULL AND matched_grant_id IS NULL
           AND before_value IS NULL
           AND after_value='{"migration":"V14","grantCount":7}'::jsonb
           AND trace_id='migration-v14') = 1
   AND (SELECT count(*) FROM iam_audit_event
         WHERE tenant_id=1 AND trace_id='migration-v14') = 1
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND event_type='PERMISSION_VERSION_CHANGED'
           AND payload='{"membershipId":"1000","permissionVersion":1,"reason":"V14_GRANULAR_ADMIN_PERMISSION_UPGRADE"}'::jsonb
           AND aggregate_version=1 AND schema_version=1
           AND partition_key='1:1000' AND trace_id='migration-v14') = 1
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND trace_id='migration-v14') = 1
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1000' AND outbox.event_type='PERMISSION_VERSION_CHANGED'
           AND outbox.aggregate_version=1 AND outbox.trace_id='migration-v14') = 1
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_v15_history_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_audit_event
         WHERE tenant_id=1 AND operator_membership_id IS NULL
           AND target_type='ROLE_GRANTS' AND target_ref='2000'
           AND action_code='EXPAND_LEGACY_ADMIN_PERMISSIONS'
           AND decision='NOT_APPLICABLE' AND reason_code='MIGRATION'
           AND permission_code IS NULL AND matched_grant_id IS NULL
           AND before_value IS NULL
           AND after_value='{"migration":"V15","clonedGrantCount":0,"legacyCompatibilityActive":true}'::jsonb
           AND trace_id='migration-v15') = 1
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND event_type='PERMISSION_VERSION_CHANGED'
           AND payload='{"membershipId":"1000","permissionVersion":2,"reason":"V15_LEGACY_ADMIN_PERMISSION_EXPANSION"}'::jsonb
           AND aggregate_version=2 AND schema_version=1
           AND partition_key='1:1000' AND trace_id='migration-v15') = 1
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1000' AND outbox.event_type='PERMISSION_VERSION_CHANGED'
           AND outbox.aggregate_version=2 AND outbox.trace_id='migration-v15') = 1
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_v19_history_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_audit_event
         WHERE tenant_id=1 AND operator_membership_id IS NULL
           AND target_type='ROLE_GRANTS' AND target_ref='2000'
           AND action_code='MIGRATE_PROTECTED_BACKOFFICE_ACCESS'
           AND decision='NOT_APPLICABLE' AND reason_code='MIGRATION'
           AND permission_code IS NULL AND matched_grant_id IS NULL
           AND before_value IS NULL
           AND after_value='{"migration":"V19","grantKey":"system-backoffice-access"}'::jsonb
           AND trace_id='migration-v19') = 1
   AND (SELECT count(*) FROM iam_audit_event
         WHERE tenant_id=1 AND trace_id='migration-v19') = 1
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND event_type='PERMISSION_VERSION_CHANGED'
           AND payload='{"membershipId":"1000","permissionVersion":3,"reason":"V19_PROTECTED_BACKOFFICE_ACCESS"}'::jsonb
           AND aggregate_version=3 AND schema_version=1
           AND partition_key='1:1000' AND trace_id='migration-v19') = 1
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1000' AND outbox.event_type='PERMISSION_VERSION_CHANGED'
           AND outbox.aggregate_version=3 AND outbox.trace_id='migration-v19') = 1
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_platform_access_grant_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*)
          FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND permission.id=3022 AND permission.permission_code='backoffice:platform-access'
           AND grant_row.grant_key='system-backoffice-access' AND grant_row.status='ACTIVE'
           AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
           AND ((grant_row.created_by IS NULL AND grant_row.updated_by IS NULL)
             OR (grant_row.created_by=1000 AND grant_row.updated_by=1000))) = 1
   AND (SELECT count(*)
          FROM iam_grant_dimension dimension_row
          JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.permission_id=3022 AND grant_row.grant_key='system-backoffice-access'
           AND dimension_row.dimension_code='TENANT'
           AND dimension_row.scope_mode='TENANT_ALL') = 1
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
       JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
       JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
       WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
         AND grant_row.permission_id=3022 AND grant_row.grant_key='system-backoffice-access'
   )
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_v34_history_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND event_type='PERMISSION_VERSION_CHANGED'
           AND payload='{"tenantId":1,"membershipId":1000,"permissionVersion":6,"reason":"V34_MERCHANT_PROFILE_UPDATE_ACCESS"}'::jsonb
           AND aggregate_version=6 AND schema_version=1
           AND partition_key='1:1000' AND trace_id='migration-v34') = 1
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1000' AND outbox.event_type='PERMISSION_VERSION_CHANGED'
           AND outbox.aggregate_version=6 AND outbox.trace_id='migration-v34') = 1
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_mch003_history_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (
       ((SELECT row_version FROM iam_membership WHERE tenant_id=1 AND id=1000)=3
        AND pg_temp.iam_local_v14_history_is_exact()
        AND pg_temp.iam_local_v15_history_is_exact()
        AND pg_temp.iam_local_v19_history_is_exact()
        AND pg_temp.iam_local_v34_history_is_exact()
        AND (SELECT count(*) FROM iam_permission_change_outbox
              WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
                AND event_type='PERMISSION_VERSION_CHANGED'
                AND payload='{"tenantId":1,"membershipId":1000,"permissionVersion":7,"reason":"V37_MERCHANT_ONBOARDING_ACCESS"}'::jsonb
                AND aggregate_version=7 AND schema_version=1
                AND partition_key='1:1000' AND trace_id='migration-v37')=1)
       OR
       ((SELECT row_version FROM iam_membership WHERE tenant_id=1 AND id=1000)=0
        AND (SELECT count(*) FROM iam_permission_change_outbox
              WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
                AND event_type='PERMISSION_VERSION_CHANGED'
                AND payload='{"tenantId":1,"membershipId":1000,"permissionVersion":7,"reason":"LOCAL_MCH003_BOOTSTRAP"}'::jsonb
                AND aggregate_version=7 AND schema_version=1
                AND partition_key='1:1000' AND trace_id='local-mch003-bootstrap')=1)
       )
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND aggregate_version=7)=1
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1000' AND outbox.aggregate_version=7)=1
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_isolated_portal_fixture_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH expected(tenant_id,role_id,role_code,role_name,tenant_type,membership_id,
              grant_id,permission_id,permission_code) AS (
    VALUES
      (2::bigint,2200::bigint,'merchant-admin','Merchant Administrator','DIRECT_MERCHANT',2100::bigint,
       7023::bigint,3023::bigint,'backoffice:merchant-access'),
      (3::bigint,3200::bigint,'agent-admin','Agent Administrator','AGENT',3100::bigint,
       7024::bigint,3024::bigint,'backoffice:agent-access')
)
SELECT (SELECT count(*)
          FROM expected
          JOIN iam_role role
            ON role.id=expected.role_id AND role.tenant_id=expected.tenant_id
           AND role.role_code=expected.role_code AND role.role_name=expected.role_name
           AND role.applicable_tenant_type=expected.tenant_type
           AND NOT role.assignable AND role.system_role
           AND role.status='ACTIVE' AND role.deleted_at IS NULL) = 2
   AND NOT EXISTS (
       SELECT 1
         FROM expected
        WHERE NOT EXISTS (
            SELECT 1
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
              JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
             WHERE grant_row.id=expected.grant_id
               AND grant_row.tenant_id=expected.tenant_id
               AND grant_row.role_id=expected.role_id
               AND grant_row.permission_id=expected.permission_id
               AND grant_row.grant_key='system-backoffice-access'
               AND grant_row.status='ACTIVE'
               AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
               AND grant_row.created_by IS NOT DISTINCT FROM expected.membership_id
               AND grant_row.updated_by IS NOT DISTINCT FROM expected.membership_id
               AND permission.permission_code=expected.permission_code
               AND permission.status='ACTIVE' AND permission.risk_level='NORMAL'
               AND permission.cross_tenant_mode='SAME_TENANT_ONLY'
               AND permission.required_dimensions=ARRAY['TENANT']::varchar(32)[]
               AND NOT permission.requires_step_up AND NOT permission.requires_approval
               AND dimension_row.dimension_code='TENANT'
               AND dimension_row.scope_mode='TENANT_ALL'
               AND (SELECT count(*) FROM iam_grant_dimension
                     WHERE grant_id=grant_row.id) = 1
               AND NOT EXISTS (
                   SELECT 1 FROM iam_grant_target target
                    WHERE target.dimension_id=dimension_row.id
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM iam_role_grant extra_grant
                     JOIN iam_permission extra_permission
                       ON extra_permission.id=extra_grant.permission_id
                    WHERE extra_grant.tenant_id=grant_row.tenant_id
                      AND extra_grant.role_id=grant_row.role_id
                      AND extra_grant.status='ACTIVE'
                      AND extra_grant.id<>grant_row.id
                      AND extra_permission.permission_code IN (
                          'backoffice:platform-access',
                          'backoffice:merchant-access',
                          'backoffice:agent-access'
                      )
               )
        )
   )
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_role_menus_are_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*)
          FROM iam_role_menu role_menu
          LEFT JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
         WHERE role_menu.role_id=2000
           AND COALESCE(menu.route_name,'') NOT IN (
               'SystemDictionary','SystemDictionaryDataIndex','SystemDictionaryData')
           AND COALESCE(menu.route_name,'') NOT LIKE 'Merchant%') = 8
   AND (SELECT count(*) FROM iam_role_menu WHERE tenant_id=1 AND role_id=2000
          AND menu_id IN (6000,6001,6002,6003,6004,6010,6011,6012)) = 8
   AND ((pg_temp.iam_local_platform_dictionary_extension_is_absent()
         AND (SELECT count(*) FROM iam_role_menu role_menu
               JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
              WHERE role_menu.role_id=2000 AND menu.route_name NOT LIKE 'Merchant%') = 8)
     OR (pg_temp.iam_local_platform_dictionary_extension_is_exact()
         AND (SELECT count(*) FROM iam_role_menu role_menu
               JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
              WHERE role_menu.role_id=2000 AND menu.route_name NOT LIKE 'Merchant%') =
             10 + (SELECT count(*)
                     FROM iam_role_menu role_menu
                     JOIN iam_menu menu
                       ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                    WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
                      AND menu.route_name='SystemDictionaryData')))
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_final_fixture_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT ((pg_temp.iam_local_identity_is_exact(0)
         AND pg_temp.iam_local_platform_dictionary_extension_is_absent())
        OR (pg_temp.iam_local_identity_is_exact(1)
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
        OR (pg_temp.iam_local_identity_is_exact(3)
            AND pg_temp.iam_local_v14_history_is_exact()
            AND pg_temp.iam_local_v15_history_is_exact()
            AND pg_temp.iam_local_v19_history_is_exact()
            AND pg_temp.iam_local_platform_dictionary_extension_is_absent())
        OR (pg_temp.iam_local_identity_is_exact(4)
            AND pg_temp.iam_local_v14_history_is_exact()
            AND pg_temp.iam_local_v15_history_is_exact()
            AND pg_temp.iam_local_v19_history_is_exact()
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
        OR (pg_temp.iam_local_identity_is_exact(5)
            AND pg_temp.iam_local_v14_history_is_exact()
            AND pg_temp.iam_local_v15_history_is_exact()
            AND pg_temp.iam_local_v19_history_is_exact()
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
        OR (pg_temp.iam_local_identity_is_exact(6)
            AND pg_temp.iam_local_v14_history_is_exact()
            AND pg_temp.iam_local_v15_history_is_exact()
            AND pg_temp.iam_local_v19_history_is_exact()
            AND pg_temp.iam_local_v34_history_is_exact()
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
        OR (pg_temp.iam_local_identity_is_exact(7)
            AND pg_temp.iam_local_mch003_history_is_exact()
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact()))
   AND pg_temp.iam_local_role_menus_are_exact()
   AND (SELECT count(*) FROM iam_role_grant grant_row
          JOIN iam_local_final_permission expected
            ON grant_row.id=expected.id+1000 AND grant_row.permission_id=expected.id
           AND grant_row.grant_key=replace(expected.permission_code, ':', '-')
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000 AND grant_row.status='ACTIVE'
           AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
           AND grant_row.created_by IS NOT DISTINCT FROM 1000
           AND grant_row.updated_by IS NOT DISTINCT FROM 1000) = 19
   AND pg_temp.iam_local_platform_access_grant_is_exact()
   AND (SELECT count(*) FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE (grant_row.role_id=2000
            OR grant_row.id IN (SELECT id+1000 FROM iam_local_final_permission))
           AND permission.permission_code NOT LIKE 'dictionary%'
           AND permission.permission_code NOT LIKE 'merchant:%') = 20
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
          JOIN iam_local_final_permission expected
            ON dimension_row.id=expected.id+2000 AND dimension_row.grant_id=expected.id+1000
         WHERE dimension_row.dimension_code='TENANT' AND dimension_row.scope_mode='TENANT_ALL') = 19
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
          JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE (grant_row.role_id=2000
            OR dimension_row.id IN (SELECT id+2000 FROM iam_local_final_permission))
           AND permission.permission_code NOT LIKE 'dictionary%'
           AND permission.permission_code NOT LIKE 'merchant:%') = 20
   AND NOT EXISTS (SELECT 1 FROM iam_grant_target target JOIN iam_grant_dimension dimension_row
          ON dimension_row.id=target.dimension_id WHERE dimension_row.grant_id IN
          (SELECT id FROM iam_role_grant WHERE role_id=2000))
   AND (SELECT count(*) FROM iam_menu menu
          JOIN iam_local_final_menu expected ON expected.id=menu.id
         WHERE menu.tenant_id=1 AND menu.system_managed) = 29
   AND (SELECT count(*) FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)) = 29
   AND NOT EXISTS (
       SELECT 1 FROM iam_menu menu
        WHERE menu.tenant_id=1 AND menu.id NOT IN (SELECT id FROM iam_local_final_menu)
          AND menu.status='ACTIVE' AND menu.deleted_at IS NULL
          AND menu.auth_code IN (
              SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL
          )
   )
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_legacy_fixture_is_exact(
    include_buttons BOOLEAN,
    migration_stage INTEGER
)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT ((migration_stage=0 AND (
           (pg_temp.iam_local_identity_is_exact(0)
            AND pg_temp.iam_local_platform_dictionary_extension_is_absent())
        OR (pg_temp.iam_local_identity_is_exact(1)
            AND pg_temp.iam_local_platform_dictionary_extension_is_exact())))
     OR (migration_stage=3
         AND pg_temp.iam_local_v14_history_is_exact()
         AND pg_temp.iam_local_v15_history_is_exact()
         AND pg_temp.iam_local_v19_history_is_exact()
         AND ((pg_temp.iam_local_identity_is_exact(3)
               AND pg_temp.iam_local_platform_dictionary_extension_is_absent())
           OR (pg_temp.iam_local_identity_is_exact(4)
               AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
           OR (pg_temp.iam_local_identity_is_exact(5)
               AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
           OR (pg_temp.iam_local_identity_is_exact(6)
               AND pg_temp.iam_local_v34_history_is_exact()
               AND pg_temp.iam_local_platform_dictionary_extension_is_exact())
           OR (pg_temp.iam_local_identity_is_exact(7)
               AND pg_temp.iam_local_mch003_history_is_exact()
               AND pg_temp.iam_local_platform_dictionary_extension_is_exact()))))
   AND pg_temp.iam_local_role_menus_are_exact()
   AND (SELECT count(*) FROM iam_role_grant grant_row JOIN iam_local_legacy_permission expected
          ON grant_row.id=expected.id+1000 AND grant_row.permission_id=expected.id
         AND grant_row.grant_key=replace(expected.permission_code, ':', '-')
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000 AND grant_row.status='ACTIVE'
         AND grant_row.valid_from IS NULL AND grant_row.valid_until IS NULL
         AND grant_row.created_by IS NOT DISTINCT FROM 1000
         AND grant_row.updated_by IS NOT DISTINCT FROM 1000 AND grant_row.row_version=0) = 14
   AND (
       (migration_stage=0 AND (SELECT count(*) FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE (grant_row.role_id=2000 OR grant_row.id BETWEEN 4001 AND 4014)
           AND permission.permission_code NOT LIKE 'dictionary%'
           AND permission.permission_code NOT LIKE 'merchant:%') = 14)
       OR (migration_stage=0 AND pg_temp.iam_local_platform_access_grant_is_exact()
           AND (SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE (grant_row.role_id=2000 OR grant_row.id BETWEEN 4001 AND 4014)
               AND permission.permission_code NOT LIKE 'dictionary%'
               AND permission.permission_code NOT LIKE 'merchant:%') = 15)
       OR (migration_stage=3 AND pg_temp.iam_local_platform_access_grant_is_exact()
           AND (SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE (grant_row.role_id=2000 OR grant_row.id BETWEEN 4001 AND 4014)
               AND permission.permission_code NOT LIKE 'dictionary%'
               AND permission.permission_code NOT LIKE 'merchant:%') = 22)
   )
   AND (migration_stage=0 OR (SELECT count(*) FROM iam_role_grant grant_row
          JOIN iam_local_final_permission expected ON expected.id=grant_row.permission_id
         WHERE expected.id BETWEEN 3015 AND 3021
           AND grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.grant_key='migration-v14-' || replace(expected.permission_code, ':', '-')
           AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NULL
           AND grant_row.valid_until IS NULL AND grant_row.created_by IS NULL
           AND grant_row.updated_by IS NULL AND grant_row.row_version=0) = 7)
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row JOIN iam_local_legacy_permission expected
          ON dimension_row.id=expected.id+2000 AND dimension_row.grant_id=expected.id+1000
         WHERE dimension_row.dimension_code='TENANT' AND dimension_row.scope_mode='TENANT_ALL') = 14
   AND (migration_stage=0 OR (SELECT count(*) FROM iam_grant_dimension dimension_row
          JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
          JOIN iam_local_final_permission expected ON expected.id=grant_row.permission_id
         WHERE expected.id BETWEEN 3015 AND 3021
           AND grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.grant_key='migration-v14-' || replace(expected.permission_code, ':', '-')
           AND dimension_row.dimension_code='TENANT'
           AND dimension_row.scope_mode='TENANT_ALL') = 7)
   AND (
       (migration_stage=0 AND (SELECT count(*) FROM iam_grant_dimension dimension_row
          LEFT JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE (dimension_row.id BETWEEN 5001 AND 5014
            OR dimension_row.grant_id BETWEEN 4001 AND 4014
            OR grant_row.role_id=2000)
           AND permission.permission_code NOT LIKE 'dictionary%'
           AND permission.permission_code NOT LIKE 'merchant:%') = 14)
       OR (migration_stage=0 AND pg_temp.iam_local_platform_access_grant_is_exact()
           AND (SELECT count(*) FROM iam_grant_dimension dimension_row
              LEFT JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE (dimension_row.id BETWEEN 5001 AND 5014
                OR dimension_row.grant_id BETWEEN 4001 AND 4014
                OR grant_row.role_id=2000)
               AND permission.permission_code NOT LIKE 'dictionary%'
               AND permission.permission_code NOT LIKE 'merchant:%') = 15)
       OR (migration_stage=3 AND pg_temp.iam_local_platform_access_grant_is_exact()
           AND (SELECT count(*) FROM iam_grant_dimension dimension_row
              LEFT JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE (dimension_row.id BETWEEN 5001 AND 5014
                OR dimension_row.grant_id BETWEEN 4001 AND 4014
                OR grant_row.role_id=2000)
               AND permission.permission_code NOT LIKE 'dictionary%'
               AND permission.permission_code NOT LIKE 'merchant:%') = 22)
   )
   AND NOT EXISTS (SELECT 1 FROM iam_grant_target target JOIN iam_grant_dimension dimension_row
          ON dimension_row.id=target.dimension_id JOIN iam_role_grant grant_row
          ON grant_row.id=dimension_row.grant_id
         WHERE dimension_row.grant_id BETWEEN 4001 AND 4014 OR grant_row.role_id=2000)
   AND (SELECT count(*) FROM iam_menu menu JOIN iam_local_legacy_menu expected ON expected.id=menu.id
          AND expected.parent_id IS NOT DISTINCT FROM menu.parent_id AND expected.menu_type=menu.menu_type
          AND expected.menu_name=menu.menu_name AND expected.route_name=menu.route_name
          AND expected.route_path IS NOT DISTINCT FROM menu.route_path
          AND expected.component_path IS NOT DISTINCT FROM menu.component_path
          AND expected.redirect_path IS NOT DISTINCT FROM menu.redirect_path
          AND expected.sort_order=menu.sort_order AND expected.auth_code IS NOT DISTINCT FROM menu.auth_code
          AND expected.status=menu.status AND expected.meta_json=menu.meta_json
         WHERE menu.tenant_id=1 AND menu.display_permission_id IS NULL AND menu.remark IS NULL
          AND menu.system_managed AND menu.deleted_at IS NULL
          AND (NOT expected.permission_button OR include_buttons)) = CASE WHEN include_buttons THEN 22 ELSE 8 END
   AND (SELECT count(*) FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)
          OR (tenant_id=1 AND (route_name IN (SELECT route_name FROM iam_local_final_menu)
          OR route_path IN (SELECT route_path FROM iam_local_final_menu WHERE route_path IS NOT NULL)
          OR auth_code IN (SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL)))) = CASE WHEN include_buttons THEN 22 ELSE 8 END
$$
@@

DO $$
DECLARE
    fixture_footprint_present BOOLEAN;
    legacy_without_buttons BOOLEAN;
    legacy_with_buttons BOOLEAN;
    migrated_legacy_without_buttons BOOLEAN;
    migrated_legacy_with_buttons BOOLEAN;
    migrated BOOLEAN;
BEGIN
    IF (SELECT count(*) FROM iam_local_final_permission expected JOIN iam_permission permission
          ON permission.id=expected.id AND permission.permission_code=expected.permission_code
         AND permission.resource_code=expected.resource_code AND permission.action_code=expected.action_code
         AND permission.risk_level='NORMAL' AND permission.required_dimensions=ARRAY['TENANT']::VARCHAR(32)[]
         AND NOT permission.requires_step_up AND NOT permission.requires_approval
         AND permission.status='ACTIVE' AND permission.description IS NOT DISTINCT FROM expected.description
         AND permission.cross_tenant_mode='SAME_TENANT_ONLY') <> 19
       OR (SELECT count(*) FROM iam_permission WHERE
          (id=3012 AND permission_code='menu:manage' AND status='ACTIVE')
          OR (id=3014 AND permission_code='department:manage' AND status='ACTIVE')) <> 2 THEN
        RAISE EXCEPTION 'Local bootstrap refused: required permission catalog is incomplete or modified';
    END IF;

    SELECT EXISTS(SELECT 1 FROM iam_tenant WHERE id=1 OR tenant_code='platform')
        OR EXISTS(SELECT 1 FROM iam_department WHERE id=10 OR (tenant_id=1 AND department_code='head-office'))
        OR EXISTS(SELECT 1 FROM iam_user WHERE id=100 OR (idp_issuer='local' AND idp_subject='admin@platform.localhost'))
        OR EXISTS(SELECT 1 FROM iam_membership WHERE id=1000 OR user_id=100)
        OR EXISTS(SELECT 1 FROM iam_authentication_credential WHERE user_id=100 OR username='admin@platform.localhost')
        OR EXISTS(SELECT 1 FROM iam_role WHERE id=2000 OR (tenant_id=1
             AND (role_code='platform-admin' OR role_name='Platform Administrator')))
        OR EXISTS(SELECT 1 FROM iam_membership_role WHERE membership_id=1000 OR role_id=2000)
        OR EXISTS(SELECT 1 FROM iam_role_grant WHERE role_id=2000
             OR id IN (SELECT id+1000 FROM iam_local_final_permission) OR id BETWEEN 4001 AND 4014)
        OR EXISTS(SELECT 1 FROM iam_menu WHERE id IN (SELECT id FROM iam_local_final_menu)
             OR (tenant_id=1 AND (route_name IN (SELECT route_name FROM iam_local_final_menu)
             OR auth_code IN (SELECT auth_code FROM iam_local_final_menu WHERE auth_code IS NOT NULL))))
        OR EXISTS(SELECT 1 FROM iam_role_menu WHERE role_id=2000)
      INTO fixture_footprint_present;

    IF fixture_footprint_present AND NOT pg_temp.iam_local_final_fixture_is_exact() THEN
        legacy_without_buttons := pg_temp.iam_local_legacy_fixture_is_exact(false, 0);
        legacy_with_buttons := pg_temp.iam_local_legacy_fixture_is_exact(true, 0);
        migrated_legacy_without_buttons := pg_temp.iam_local_legacy_fixture_is_exact(false, 3);
        migrated_legacy_with_buttons := pg_temp.iam_local_legacy_fixture_is_exact(true, 3);
        IF NOT legacy_without_buttons AND NOT legacy_with_buttons
           AND NOT migrated_legacy_without_buttons AND NOT migrated_legacy_with_buttons THEN
            RAISE EXCEPTION 'Local bootstrap refused: local fixture footprint is incomplete or modified';
        END IF;

        migrated := migrated_legacy_without_buttons OR migrated_legacy_with_buttons;
        legacy_with_buttons := legacy_with_buttons OR migrated_legacy_with_buttons;

        IF migrated THEN
            DELETE FROM iam_role_grant grant_row
             USING iam_local_final_permission expected
             WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND grant_row.permission_id=expected.id AND expected.id BETWEEN 3015 AND 3021
               AND grant_row.grant_key='migration-v14-' || replace(expected.permission_code, ':', '-');
        END IF;
        DELETE FROM iam_role_grant WHERE tenant_id=1 AND role_id=2000 AND permission_id IN (3012,3014);
        INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by)
        SELECT id+1000,1,2000,id,replace(permission_code,':','-'),'ACTIVE',1000,1000
          FROM iam_local_final_permission
        ON CONFLICT (role_id,permission_id,grant_key) DO NOTHING;
        INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
        SELECT id+2000,id+1000,'TENANT','TENANT_ALL' FROM iam_local_final_permission
        ON CONFLICT (grant_id,dimension_code) DO NOTHING;

        INSERT INTO iam_role_grant(
            id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
        )
        VALUES(7022,1,2000,3022,'system-backoffice-access','ACTIVE',1000,1000)
        ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING;
        INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
        SELECT nextval('iam_id_seq'), grant_row.id, 'TENANT', 'TENANT_ALL'
          FROM iam_role_grant grant_row
         WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.permission_id=3022 AND grant_row.grant_key='system-backoffice-access'
        ON CONFLICT(grant_id,dimension_code) DO NOTHING;

        IF legacy_with_buttons THEN
            UPDATE iam_menu SET status='DISABLED', meta_json=expected.meta_json,
                   updated_at=now(), row_version=row_version+1
              FROM iam_local_final_menu expected
             WHERE iam_menu.id=expected.id AND iam_menu.tenant_id=1 AND expected.id IN (6031,6033);
            INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed)
            SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
                   sort_order,auth_code,status,meta_json,true FROM iam_local_final_menu WHERE id>=6034 ORDER BY id;
        ELSE
            INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed)
            SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
                   sort_order,auth_code,status,meta_json,true FROM iam_local_final_menu
             WHERE permission_button ORDER BY id;
        END IF;

        IF NOT pg_temp.iam_local_final_fixture_is_exact() THEN
            RAISE EXCEPTION 'Local bootstrap failed: legacy fixture upgrade was not atomic and complete';
        END IF;
    END IF;
END;
$$
@@

INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
VALUES(1,'platform','Platform Administration','PLATFORM','ACTIVE','PLATFORM') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status,remark,system_managed)
VALUES(10,1,NULL,'head-office','Head Office','ACTIVE','Local bootstrap department',true) ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark,account_domain)
VALUES(100,'local','admin@platform.localhost','Platform Administrator','ACTIVE','Local bootstrap administrator','PLATFORM') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status,account_domain)
VALUES(1000,1,100,10,'ACTIVE','PLATFORM') ON CONFLICT(id) DO NOTHING
@@
INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,account_domain)
VALUES(100,'admin@platform.localhost',NULL,'ACTIVE','PLATFORM') ON CONFLICT(user_id) DO NOTHING
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

INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by)
VALUES(7022,1,2000,3022,'system-backoffice-access','ACTIVE',1000,1000)
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT 8022, grant_row.id, 'TENANT', 'TENANT_ALL'
  FROM iam_role_grant grant_row
 WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
   AND grant_row.permission_id=3022 AND grant_row.grant_key='system-backoffice-access'
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed)
SELECT id,1,parent_id,menu_type,menu_name,route_name,route_path,component_path,redirect_path,
       sort_order,auth_code,status,meta_json,true FROM iam_local_final_menu ORDER BY id
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

CREATE OR REPLACE FUNCTION pg_temp.iam_local_platform_merchant_capability_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH expected_permission(permission_code) AS (
    VALUES ('merchant:view'),('merchant:review'),('merchant:disable'),
           ('merchant:enable'),('merchant:terminate'),('merchant:update'),
           ('merchant:create'),('merchant:amend'),('merchant:document:upload'),
           ('merchant:document:view')
), expected_menu(parent_route_name,menu_type,menu_name,route_name,route_path,component_path,
                   display_permission_code,auth_code,sort_order,meta_json) AS (
    VALUES
      (NULL::varchar,'DIRECTORY','Merchant Management','MerchantManagement','/merchant',
       NULL::varchar,NULL::varchar,NULL::varchar,200,
       '{"title":"merchant.title","icon":"lucide:store"}'::jsonb),
      ('MerchantManagement','PAGE','Merchant List','MerchantList','/merchant/list','/merchant/list',
       'merchant:view','merchant:view',201,
       '{"title":"merchant.list.title"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Review','MerchantReview',NULL,NULL,NULL,'merchant:review',202,
       '{"title":"merchant.permission.review"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Disable','MerchantDisable',NULL,NULL,NULL,'merchant:disable',203,
       '{"title":"merchant.permission.disable"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Enable','MerchantEnable',NULL,NULL,NULL,'merchant:enable',204,
       '{"title":"merchant.permission.enable"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Terminate','MerchantTerminate',NULL,NULL,NULL,'merchant:terminate',205,
       '{"title":"merchant.permission.terminate"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Edit','MerchantEdit',NULL,NULL,NULL,'merchant:amend',206,
       '{"title":"merchant.permission.edit"}'::jsonb),
      ('MerchantList','BUTTON','Merchant Create','MerchantCreate',NULL,NULL,NULL,'merchant:create',207,
       '{"title":"merchant.permission.create"}'::jsonb)
)
SELECT (SELECT count(*)
          FROM expected_permission expected
          JOIN iam_permission permission ON permission.permission_code=expected.permission_code
          JOIN iam_role_grant grant_row
            ON grant_row.tenant_id=1 AND grant_row.role_id=2000
           AND grant_row.permission_id=permission.id
           AND grant_row.grant_key='system-' || replace(expected.permission_code,':','-')
           AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NOT NULL
           AND grant_row.valid_until > grant_row.valid_from
          JOIN iam_grant_dimension dimension_row
            ON dimension_row.grant_id=grant_row.id
           AND dimension_row.dimension_code='TENANT'
           AND dimension_row.scope_mode='TENANT_ALL'
         WHERE (SELECT count(*) FROM iam_grant_dimension
                 WHERE grant_id=grant_row.id)=1) = 10
   AND (SELECT count(*) FROM iam_role_grant grant_row
         JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
          AND permission.permission_code IN (SELECT permission_code FROM expected_permission)) = 10
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
       JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
       JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
       JOIN iam_permission permission ON permission.id=grant_row.permission_id
       WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
         AND permission.permission_code IN (SELECT permission_code FROM expected_permission)
   )
   AND (SELECT count(*) FROM expected_menu expected
         JOIN iam_menu menu ON menu.tenant_id=1
          AND menu.menu_type=expected.menu_type AND menu.menu_name=expected.menu_name
          AND menu.route_name=expected.route_name
          AND menu.route_path IS NOT DISTINCT FROM expected.route_path
          AND menu.component_path IS NOT DISTINCT FROM expected.component_path
          AND menu.auth_code IS NOT DISTINCT FROM expected.auth_code
          AND menu.sort_order=expected.sort_order AND menu.status='ACTIVE'
          AND menu.meta_json=expected.meta_json AND menu.system_managed AND menu.deleted_at IS NULL
         LEFT JOIN iam_permission display_permission ON display_permission.id=menu.display_permission_id
         LEFT JOIN iam_menu parent ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
        WHERE parent.route_name IS NOT DISTINCT FROM expected.parent_route_name
          AND display_permission.permission_code
              IS NOT DISTINCT FROM expected.display_permission_code) = 8
   AND (SELECT count(*) FROM iam_menu WHERE tenant_id=1
         AND route_name IN (SELECT route_name FROM expected_menu)) = 8
   AND (SELECT count(*) FROM iam_role_menu role_menu
         JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
        WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
          AND menu.route_name IN (SELECT route_name FROM expected_menu)) = 8
$$
@@

DO $$
DECLARE
    capability_absent BOOLEAN;
BEGIN
    SELECT NOT EXISTS (
        SELECT 1 FROM iam_role_grant grant_row
        JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.tenant_id=1 AND permission.permission_code IN (
            'merchant:view','merchant:review','merchant:disable','merchant:enable',
            'merchant:terminate','merchant:update','merchant:create','merchant:amend',
            'merchant:document:upload','merchant:document:view')
    ) AND NOT EXISTS (
        SELECT 1 FROM iam_menu WHERE tenant_id=1 AND route_name IN (
            'MerchantManagement','MerchantList','MerchantReview','MerchantDisable',
            'MerchantEnable','MerchantTerminate','MerchantEdit','MerchantCreate')
    ) INTO capability_absent;
    IF NOT capability_absent AND NOT pg_temp.iam_local_platform_merchant_capability_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap refused: platform merchant capability is incomplete or modified';
    END IF;
END
$$
@@

CREATE TEMPORARY TABLE iam_local_platform_merchant_change(changed BOOLEAN NOT NULL) ON COMMIT DROP
@@

INSERT INTO iam_local_platform_merchant_change(changed)
SELECT NOT EXISTS (
    SELECT 1 FROM iam_role_grant grant_row
    JOIN iam_permission permission ON permission.id=grant_row.permission_id
    WHERE grant_row.tenant_id=1 AND permission.permission_code IN (
        'merchant:view','merchant:review','merchant:disable','merchant:enable',
        'merchant:terminate','merchant:update','merchant:create','merchant:amend',
        'merchant:document:upload','merchant:document:view')
) AND NOT EXISTS (
    SELECT 1 FROM iam_menu WHERE tenant_id=1 AND route_name IN (
        'MerchantManagement','MerchantList','MerchantReview','MerchantDisable',
        'MerchantEnable','MerchantTerminate','MerchantEdit','MerchantCreate')
)
@@

INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,valid_from,valid_until,created_by,updated_by
)
SELECT expected.grant_id,1,2000,permission.id,
       'system-' || replace(permission.permission_code,':','-'),'ACTIVE',
       TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2036-01-01 00:00:00+00',1000,1000
  FROM (VALUES
      (7501::bigint,'merchant:view'),(7502,'merchant:review'),(7503,'merchant:disable'),
      (7504,'merchant:enable'),(7505,'merchant:terminate'),(7506,'merchant:update'),
      (7507,'merchant:create'),(7508,'merchant:amend'),
      (7509,'merchant:document:upload'),(7510,'merchant:document:view')
  ) expected(grant_id,permission_code)
  JOIN iam_permission permission ON permission.permission_code=expected.permission_code
 WHERE (SELECT changed FROM iam_local_platform_merchant_change)
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT grant_row.id+1000,grant_row.id,'TENANT','TENANT_ALL'
  FROM iam_role_grant grant_row
 WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
   AND grant_row.id BETWEEN 7501 AND 7510
   AND (SELECT changed FROM iam_local_platform_merchant_change)
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

WITH parent AS (
    INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                         sort_order,status,meta_json,system_managed)
    SELECT 6060,1,NULL,'DIRECTORY','Merchant Management','MerchantManagement','/merchant',
           200,'ACTIVE','{"title":"merchant.title","icon":"lucide:store"}'::jsonb,true
     WHERE (SELECT changed FROM iam_local_platform_merchant_change)
    ON CONFLICT(id) DO NOTHING RETURNING id
), page AS (
    INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                         component_path,display_permission_id,sort_order,auth_code,status,meta_json,system_managed)
    SELECT 6061,1,parent.id,'PAGE','Merchant List','MerchantList','/merchant/list',
           '/merchant/list',permission.id,201,'merchant:view','ACTIVE',
           '{"title":"merchant.list.title"}'::jsonb,true
      FROM parent JOIN iam_permission permission ON permission.permission_code='merchant:view'
    ON CONFLICT(id) DO NOTHING RETURNING id
)
INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,sort_order,
                     auth_code,status,meta_json,system_managed)
SELECT button.id,1,page.id,'BUTTON',button.menu_name,button.route_name,button.sort_order,
       button.permission_code,'ACTIVE',jsonb_build_object('title',button.title_key),true
  FROM page CROSS JOIN (VALUES
    (6062::bigint,'Merchant Review','MerchantReview','merchant:review','merchant.permission.review',202),
    (6063,'Merchant Disable','MerchantDisable','merchant:disable','merchant.permission.disable',203),
    (6064,'Merchant Enable','MerchantEnable','merchant:enable','merchant.permission.enable',204),
    (6065,'Merchant Terminate','MerchantTerminate','merchant:terminate','merchant.permission.terminate',205),
    (6066,'Merchant Edit','MerchantEdit','merchant:amend','merchant.permission.edit',206),
    (6067,'Merchant Create','MerchantCreate','merchant:create','merchant.permission.create',207)
  ) button(id,menu_name,route_name,permission_code,title_key,sort_order)
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
SELECT 1,2000,menu.id FROM iam_menu menu
 WHERE menu.tenant_id=1 AND menu.route_name IN (
     'MerchantManagement','MerchantList','MerchantReview','MerchantDisable',
     'MerchantEnable','MerchantTerminate','MerchantEdit','MerchantCreate')
ON CONFLICT DO NOTHING
@@

DO $$
BEGIN
    IF NOT pg_temp.iam_local_platform_merchant_capability_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap failed: platform merchant capability is incomplete';
    END IF;
END
$$
@@

CREATE TEMPORARY TABLE sys_local_platform_dictionary_grant_change(changed BOOLEAN NOT NULL)
ON COMMIT DROP
@@

INSERT INTO sys_local_platform_dictionary_grant_change(changed)
SELECT EXISTS (
    SELECT 1 FROM iam_permission permission
     WHERE permission.permission_code IN (
        'dictionary:view','dictionary:create','dictionary:update','dictionary:delete',
        'dictionary-data:view','dictionary-data:create','dictionary-data:update','dictionary-data:delete')
       AND NOT EXISTS (
           SELECT 1 FROM iam_role_grant grant_row
            WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
              AND grant_row.permission_id=permission.id AND grant_row.status='ACTIVE'))
@@

INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
)
SELECT nextval('iam_id_seq'),1,2000,permission.id,
       replace(permission.permission_code,':','-'),'ACTIVE',1000,1000
  FROM iam_permission permission
 WHERE permission.permission_code IN (
    'dictionary:view','dictionary:create','dictionary:update','dictionary:delete',
    'dictionary-data:view','dictionary-data:create','dictionary-data:update','dictionary-data:delete')
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT nextval('iam_id_seq'),grant_row.id,'TENANT','TENANT_ALL'
  FROM iam_role_grant grant_row
  JOIN iam_permission permission ON permission.id=grant_row.permission_id
 WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
   AND permission.permission_code IN (
      'dictionary:view','dictionary:create','dictionary:update','dictionary:delete',
      'dictionary-data:view','dictionary-data:create','dictionary-data:update','dictionary-data:delete')
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

UPDATE iam_role
   SET row_version=row_version+1,updated_at=now()
 WHERE id=2000 AND (SELECT changed FROM sys_local_platform_dictionary_grant_change)
@@

UPDATE iam_membership
   SET permission_version=permission_version+1,updated_at=now()
 WHERE id=1000 AND (SELECT changed FROM sys_local_platform_dictionary_grant_change)
@@

DO $$
BEGIN
IF (SELECT changed FROM sys_local_platform_dictionary_grant_change) THEN
INSERT INTO iam_menu(
    id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,display_permission_id,sort_order,auth_code,
    status,meta_json,system_managed
)
VALUES
  (6041,1,6000,'PAGE','Dictionary Management','SystemDictionary','/system/dict',
   '/system/dict/list',NULL,3025,150,NULL,'ACTIVE',
   '{"title":"system.dict.title","icon":"lucide:book-open"}'::jsonb,true),
  (6042,1,6000,'PAGE','Dictionary Data','SystemDictionaryDataIndex','/system/dict/data',
   '/system/dict/data/list',NULL,3029,160,NULL,'ACTIVE',
   '{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb,true),
  (6044,1,6041,'BUTTON','View Dictionaries','DictionaryView',NULL,NULL,NULL,NULL,151,
   'dictionary:view','ACTIVE','{"title":"system.dict.permission.view"}'::jsonb,true),
  (6045,1,6041,'BUTTON','Create Dictionary','DictionaryCreate',NULL,NULL,NULL,NULL,152,
   'dictionary:create','ACTIVE','{"title":"system.dict.permission.create"}'::jsonb,true),
  (6046,1,6041,'BUTTON','Update Dictionary','DictionaryUpdate',NULL,NULL,NULL,NULL,153,
   'dictionary:update','ACTIVE','{"title":"system.dict.permission.update"}'::jsonb,true),
  (6047,1,6041,'BUTTON','Delete Dictionary','DictionaryDelete',NULL,NULL,NULL,NULL,154,
   'dictionary:delete','ACTIVE','{"title":"system.dict.permission.delete"}'::jsonb,true),
  (6048,1,6042,'BUTTON','View Dictionary Data','DictionaryDataView',NULL,NULL,NULL,NULL,162,
   'dictionary-data:view','ACTIVE','{"title":"system.dictData.permission.view"}'::jsonb,true),
  (6049,1,6042,'BUTTON','Create Dictionary Data','DictionaryDataCreate',NULL,NULL,NULL,NULL,163,
   'dictionary-data:create','ACTIVE','{"title":"system.dictData.permission.create"}'::jsonb,true),
  (6050,1,6042,'BUTTON','Update Dictionary Data','DictionaryDataUpdate',NULL,NULL,NULL,NULL,164,
   'dictionary-data:update','ACTIVE','{"title":"system.dictData.permission.update"}'::jsonb,true),
  (6051,1,6042,'BUTTON','Delete Dictionary Data','DictionaryDataDelete',NULL,NULL,NULL,NULL,165,
   'dictionary-data:delete','ACTIVE','{"title":"system.dictData.permission.delete"}'::jsonb,true)
ON CONFLICT(id) DO NOTHING;
END IF;
END
$$
@@

INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
SELECT 1,2000,menu.id
  FROM iam_menu menu
 WHERE menu.tenant_id=1
   AND menu.route_name IN ('SystemDictionary','SystemDictionaryDataIndex')
ON CONFLICT DO NOTHING
@@

DO $$
BEGIN
    IF NOT pg_temp.iam_local_final_fixture_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap failed: platform dictionary extension is incomplete';
    END IF;
END
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_merchant_self_service_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH expected_grant(permission_id, permission_code) AS (
    VALUES
      (3033::bigint,'merchant:self-view'),
      (3034::bigint,'merchant:submit'),
      (3035::bigint,'merchant:resubmit')
), expected_menu(parent_route_name,menu_type,menu_name,route_name,route_path,component_path,
                   display_permission_id,sort_order,auth_code,meta_json) AS (
    VALUES
      (NULL::varchar,'PAGE','Merchant Profile','MerchantProfile',
       '/merchant/profile','/merchant/profile',3033::bigint,200,'merchant:self-view',
       '{"title":"merchant.profile.title","icon":"lucide:store"}'::jsonb),
      ('MerchantProfile','BUTTON','Merchant Submit','MerchantSubmit',
       NULL::varchar,NULL::varchar,NULL::bigint,201,'merchant:submit',
       '{"title":"merchant.submit"}'::jsonb),
      ('MerchantProfile','BUTTON','Merchant Resubmit','MerchantResubmit',
       NULL::varchar,NULL::varchar,NULL::bigint,202,'merchant:resubmit',
       '{"title":"merchant.resubmit"}'::jsonb)
)
SELECT (SELECT count(*)
          FROM expected_grant expected
          JOIN iam_permission permission
            ON permission.id=expected.permission_id
           AND permission.permission_code=expected.permission_code
          JOIN iam_role_grant grant_row
            ON grant_row.tenant_id=2 AND grant_row.role_id=2200
           AND grant_row.permission_id=expected.permission_id
           AND grant_row.grant_key='system-' || replace(expected.permission_code,':','-')
           AND grant_row.status='ACTIVE'
           AND grant_row.valid_from IS NOT NULL
           AND grant_row.valid_until > grant_row.valid_from
           AND ((grant_row.created_by IS NULL AND grant_row.updated_by IS NULL)
             OR (grant_row.created_by=2100 AND grant_row.updated_by=2100))
          JOIN iam_grant_dimension dimension_row
            ON dimension_row.grant_id=grant_row.id
           AND dimension_row.dimension_code='TENANT'
           AND dimension_row.scope_mode='TENANT_ALL'
         WHERE (SELECT count(*) FROM iam_grant_dimension
                 WHERE grant_id=grant_row.id) = 1) = 3
   AND (SELECT count(*)
          FROM iam_role_grant grant_row
          JOIN iam_permission permission ON permission.id=grant_row.permission_id
         WHERE grant_row.tenant_id=2 AND grant_row.role_id=2200
           AND permission.permission_code LIKE 'merchant:%') = 3
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
       JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
       JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
       JOIN iam_permission permission ON permission.id=grant_row.permission_id
       WHERE grant_row.tenant_id=2 AND grant_row.role_id=2200
         AND permission.permission_code IN (SELECT permission_code FROM expected_grant)
   )
   AND (SELECT count(*)
          FROM expected_menu expected
          JOIN iam_menu menu ON menu.tenant_id=2
           AND menu.menu_type=expected.menu_type AND menu.menu_name=expected.menu_name
           AND menu.route_name=expected.route_name
           AND menu.route_path IS NOT DISTINCT FROM expected.route_path
           AND menu.component_path IS NOT DISTINCT FROM expected.component_path
           AND menu.redirect_path IS NULL
           AND menu.display_permission_id IS NOT DISTINCT FROM expected.display_permission_id
           AND menu.sort_order=expected.sort_order AND menu.auth_code=expected.auth_code
           AND menu.status='ACTIVE' AND menu.meta_json=expected.meta_json
           AND menu.system_managed AND menu.deleted_at IS NULL
          LEFT JOIN iam_menu parent
            ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
         WHERE parent.route_name IS NOT DISTINCT FROM expected.parent_route_name) = 3
   AND (SELECT count(*) FROM iam_menu
         WHERE tenant_id=2
           AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')) = 3
   AND (SELECT count(*)
          FROM iam_role_menu role_menu
          JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
         WHERE role_menu.tenant_id=2 AND role_menu.role_id=2200
           AND menu.route_name IN (SELECT route_name FROM expected_menu)) = 3
   AND NOT EXISTS (
       SELECT 1 FROM iam_role_grant grant_row
       JOIN iam_permission permission ON permission.id=grant_row.permission_id
       WHERE grant_row.tenant_id=3 AND permission.permission_code LIKE 'merchant:%'
   )
   AND NOT EXISTS (
       SELECT 1 FROM iam_menu WHERE tenant_id=3
        AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
   )
$$
@@

DO $$
DECLARE
    capability_absent BOOLEAN;
BEGIN
    SELECT NOT EXISTS (
        SELECT 1 FROM iam_role_grant grant_row
        JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.tenant_id IN (2,3) AND permission.permission_code LIKE 'merchant:%'
    ) AND NOT EXISTS (
        SELECT 1 FROM iam_menu WHERE tenant_id IN (2,3)
         AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
    ) INTO capability_absent;

    IF NOT capability_absent AND NOT pg_temp.iam_local_merchant_self_service_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap refused: merchant self-service capability is incomplete or modified';
    END IF;
END
$$
@@

CREATE TEMPORARY TABLE iam_local_merchant_capability_change(changed BOOLEAN NOT NULL)
ON COMMIT DROP
@@

INSERT INTO iam_local_merchant_capability_change(changed)
SELECT NOT EXISTS (
    SELECT 1 FROM iam_role_grant grant_row
    JOIN iam_permission permission ON permission.id=grant_row.permission_id
    WHERE grant_row.tenant_id IN (2,3) AND permission.permission_code LIKE 'merchant:%'
) AND NOT EXISTS (
    SELECT 1 FROM iam_menu WHERE tenant_id IN (2,3)
     AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
)
@@

-- Merchant and agent roots deliberately receive separate users, credentials,
-- memberships, roles and route catalogs. They share no platform grants.
INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
VALUES
  (2,'local-merchant','Local Merchant','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
  (3,'local-agent','Local Agent','AGENT','ACTIVE','AGENT')
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status,remark,system_managed)
VALUES
  (20,2,NULL,'merchant-head-office','Merchant Head Office','ACTIVE','Local merchant bootstrap department',true),
  (30,3,NULL,'agent-head-office','Agent Head Office','ACTIVE','Local agent bootstrap department',true)
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark,account_domain)
VALUES
  (200,'local','admin@merchant.localhost','Merchant Administrator','ACTIVE','Local merchant bootstrap administrator','MERCHANT'),
  (300,'local','admin@agent.localhost','Agent Administrator','ACTIVE','Local agent bootstrap administrator','AGENT')
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status,account_domain)
VALUES
  (2100,2,200,20,'ACTIVE','MERCHANT'),
  (3100,3,300,30,'ACTIVE','AGENT')
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,account_domain)
VALUES
  (200,'admin@merchant.localhost',NULL,'ACTIVE','MERCHANT'),
  (300,'admin@agent.localhost',NULL,'ACTIVE','AGENT')
ON CONFLICT(user_id) DO NOTHING
@@

INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,assignable,system_role,status,remark)
VALUES
  (2200,2,'merchant-admin','Merchant Administrator','DIRECT_MERCHANT',false,true,'ACTIVE',
   'Local merchant bootstrap administration role'),
  (3200,3,'agent-admin','Agent Administrator','AGENT',false,true,'ACTIVE',
   'Local agent bootstrap administration role')
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
VALUES
  (2,2100,2200,2100),
  (3,3100,3200,3100)
ON CONFLICT DO NOTHING
@@

INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by)
VALUES
  (7023,2,2200,3023,'system-backoffice-access','ACTIVE',2100,2100),
  (7024,3,3200,3024,'system-backoffice-access','ACTIVE',3100,3100)
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
VALUES
  (8023,7023,'TENANT','TENANT_ALL'),
  (8024,7024,'TENANT','TENANT_ALL')
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,valid_from,valid_until,created_by,updated_by
)
SELECT id,2,2200,permission_id,grant_key,'ACTIVE',
       TIMESTAMPTZ '2026-01-01 00:00:00+00',TIMESTAMPTZ '2036-01-01 00:00:00+00',2100,2100
  FROM (VALUES
      (7401::bigint,3033::bigint,'system-merchant-self-view'),
      (7402::bigint,3034::bigint,'system-merchant-submit'),
      (7403::bigint,3035::bigint,'system-merchant-resubmit')
  ) expected(id,permission_id,grant_key)
 WHERE (SELECT changed FROM iam_local_merchant_capability_change)
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT expected.dimension_id,grant_row.id,'TENANT','TENANT_ALL'
  FROM (VALUES
      (3033::bigint,8401::bigint),(3034::bigint,8402::bigint),(3035::bigint,8403::bigint)
  ) expected(permission_id,dimension_id)
  JOIN iam_role_grant grant_row
    ON grant_row.tenant_id=2 AND grant_row.role_id=2200
   AND grant_row.permission_id=expected.permission_id
 WHERE (SELECT changed FROM iam_local_merchant_capability_change)
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed)
VALUES
  (6200,2,NULL,'DIRECTORY','Dashboard','MerchantDashboard','/dashboard',NULL,
   '/dashboard/workspace',10,NULL,'ACTIVE','{"title":"page.dashboard.title","icon":"lucide:layout-dashboard"}'::jsonb,true),
  (6201,2,6200,'PAGE','Workspace','MerchantWorkspace','/dashboard/workspace',
   '/dashboard/workspace/index',NULL,20,NULL,'ACTIVE','{"title":"page.dashboard.workspace"}'::jsonb,true),
  (6300,3,NULL,'DIRECTORY','Dashboard','AgentDashboard','/dashboard',NULL,
   '/dashboard/workspace',10,NULL,'ACTIVE','{"title":"page.dashboard.title","icon":"lucide:layout-dashboard"}'::jsonb,true),
  (6301,3,6300,'PAGE','Workspace','AgentWorkspace','/dashboard/workspace',
   '/dashboard/workspace/index',NULL,20,NULL,'ACTIVE','{"title":"page.dashboard.workspace"}'::jsonb,true)
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_menu(
    id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,display_permission_id,sort_order,auth_code,
    status,meta_json,system_managed
)
SELECT id,2,parent_id,menu_type,menu_name,route_name,route_path,
       component_path,NULL,display_permission_id,sort_order,auth_code,'ACTIVE',meta_json,true
  FROM (VALUES
      (6210::bigint,NULL::bigint,'PAGE','Merchant Profile','MerchantProfile',
       '/merchant/profile','/merchant/profile',3033::bigint,200,'merchant:self-view',
       '{"title":"merchant.profile.title","icon":"lucide:store"}'::jsonb),
      (6211::bigint,6210::bigint,'BUTTON','Merchant Submit','MerchantSubmit',
       NULL::varchar,NULL::varchar,NULL::bigint,201,'merchant:submit',
       '{"title":"merchant.submit"}'::jsonb),
      (6212::bigint,6210::bigint,'BUTTON','Merchant Resubmit','MerchantResubmit',
       NULL::varchar,NULL::varchar,NULL::bigint,202,'merchant:resubmit',
       '{"title":"merchant.resubmit"}'::jsonb)
  ) expected(id,parent_id,menu_type,menu_name,route_name,route_path,component_path,
             display_permission_id,sort_order,auth_code,meta_json)
 WHERE (SELECT changed FROM iam_local_merchant_capability_change)
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
SELECT tenant_id,role_id,menu_id FROM (VALUES
  (2::bigint,2200::bigint,6200::bigint), (2,2200,6201),
  (3,3200,6300), (3,3200,6301)
) expected(tenant_id,role_id,menu_id)
UNION ALL
SELECT 2,2200,menu.id FROM iam_menu menu
 WHERE menu.tenant_id=2
   AND menu.route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
ON CONFLICT DO NOTHING
@@

DO $$
BEGIN
    IF NOT pg_temp.iam_local_merchant_self_service_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap failed: merchant self-service capability is incomplete (grants %, menus %, role menus %, agent grants %, agent menus %)',
            (SELECT count(*) FROM iam_role_grant grant_row JOIN iam_permission permission ON permission.id=grant_row.permission_id
              WHERE grant_row.tenant_id=2 AND grant_row.role_id=2200 AND permission.permission_code LIKE 'merchant:%'),
            (SELECT count(*) FROM iam_menu WHERE tenant_id=2
              AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')),
            (SELECT count(*) FROM iam_role_menu role_menu JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
              WHERE role_menu.tenant_id=2 AND role_menu.role_id=2200
                AND menu.route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')),
            (SELECT count(*) FROM iam_role_grant grant_row JOIN iam_permission permission ON permission.id=grant_row.permission_id
              WHERE grant_row.tenant_id=3 AND permission.permission_code LIKE 'merchant:%'),
            (SELECT count(*) FROM iam_menu WHERE tenant_id=3
              AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit'));
    END IF;
END
$$
@@

CREATE TEMPORARY TABLE iam003_local_admin_grant_change(changed BOOLEAN NOT NULL) ON COMMIT DROP
@@

INSERT INTO iam003_local_admin_grant_change(changed)
SELECT EXISTS (
    SELECT 1
      FROM (VALUES
          (2::bigint,2200::bigint),
          (3::bigint,3200::bigint)
      ) AS target(tenant_id,role_id)
      CROSS JOIN (VALUES
          (3001::bigint),(3002::bigint),(3003::bigint),(3004::bigint),(3005::bigint),
          (3006::bigint),(3007::bigint),(3008::bigint),(3009::bigint),(3010::bigint),
          (3011::bigint),(3013::bigint),(3021::bigint)
      ) AS required(permission_id)
     WHERE NOT EXISTS (
         SELECT 1 FROM iam_role_grant grant_row
          WHERE grant_row.tenant_id=target.tenant_id
            AND grant_row.role_id=target.role_id
            AND grant_row.permission_id=required.permission_id
            AND grant_row.status='ACTIVE'
     )
)
@@

-- User/Role administration is same-tenant. These grants never include the
-- PLATFORM-only cross-domain directory or tenant-administrator permissions.
INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
)
SELECT target.grant_base + required.permission_id,
       target.tenant_id,target.role_id,required.permission_id,
       replace(permission.permission_code,':','-'),'ACTIVE',
       target.membership_id,target.membership_id
  FROM (VALUES
      (2::bigint,2200::bigint,2100::bigint,720000::bigint),
      (3::bigint,3200::bigint,3100::bigint,730000::bigint)
  ) AS target(tenant_id,role_id,membership_id,grant_base)
  CROSS JOIN (VALUES
      (3001::bigint),(3002::bigint),(3003::bigint),(3004::bigint),(3005::bigint),
      (3006::bigint),(3007::bigint),(3008::bigint),(3009::bigint),(3010::bigint),
      (3011::bigint),(3013::bigint),(3021::bigint)
  ) AS required(permission_id)
  JOIN iam_permission permission ON permission.id=required.permission_id
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT grant_row.id + 100000,grant_row.id,'TENANT','TENANT_ALL'
  FROM iam_role_grant grant_row
 WHERE (grant_row.tenant_id=2 AND grant_row.role_id=2200
        AND grant_row.id BETWEEN 723001 AND 723021)
    OR (grant_row.tenant_id=3 AND grant_row.role_id=3200
        AND grant_row.id BETWEEN 733001 AND 733021)
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

UPDATE iam_membership
   SET permission_version=permission_version+1,updated_at=now()
 WHERE id IN (2100,3100)
   AND (SELECT changed FROM iam003_local_admin_grant_change)
@@

-- Reuse the accepted platform menu contract, but clone only User/Role pages
-- and their required permission BUTTON catalog into each isolated tenant.
INSERT INTO iam_menu(
    id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
    component_path,redirect_path,sort_order,auth_code,status,meta_json,system_managed
)
SELECT expected.id + target.id_offset,target.tenant_id,
       CASE WHEN expected.parent_id IS NULL THEN NULL ELSE expected.parent_id + target.id_offset END,
       expected.menu_type,expected.menu_name,expected.route_name,expected.route_path,
       expected.component_path,expected.redirect_path,expected.sort_order,expected.auth_code,
       expected.status,expected.meta_json,true
  FROM iam_local_final_menu expected
  CROSS JOIN (VALUES
      (2::bigint,10000::bigint),
      (3::bigint,20000::bigint)
  ) AS target(tenant_id,id_offset)
 WHERE expected.id IN (
     6000,6001,6002,
     6020,6021,6022,6023,6024,6025,
     6026,6027,6028,6029,6040
 )
ON CONFLICT(id) DO NOTHING
@@

INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
VALUES
  (2,2200,16000),(2,2200,16001),(2,2200,16002),
  (3,3200,26000),(3,3200,26001),(3,3200,26002)
ON CONFLICT DO NOTHING
@@

CREATE TEMPORARY TABLE sys_local_read_dictionary_grant_change(changed BOOLEAN NOT NULL)
ON COMMIT DROP
@@

INSERT INTO sys_local_read_dictionary_grant_change(changed)
SELECT EXISTS (
    SELECT 1
      FROM (VALUES (2::bigint,2200::bigint),(3::bigint,3200::bigint)) target(tenant_id,role_id)
      JOIN iam_permission permission ON permission.permission_code='dictionary-data:view'
     WHERE NOT EXISTS (
         SELECT 1 FROM iam_role_grant grant_row
          WHERE grant_row.tenant_id=target.tenant_id AND grant_row.role_id=target.role_id
            AND grant_row.permission_id=permission.id AND grant_row.status='ACTIVE'))
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_read_dictionary_extension_is_exact()
RETURNS BOOLEAN LANGUAGE plpgsql STABLE AS $$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            (2::bigint,2200::bigint,2100::bigint,'MERCHANT'::varchar),
            (3::bigint,3200::bigint,3100::bigint,'AGENT'::varchar)
        ) row_value(tenant_id,role_id,membership_id,account_domain)
    LOOP
        IF (SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=target.tenant_id
               AND grant_row.role_id=target.role_id
               AND permission.permission_code='dictionary-data:view'
               AND grant_row.grant_key='dictionary-data-view'
               AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NULL
               AND grant_row.valid_until IS NULL) <> 1
           OR (SELECT count(*)
                 FROM iam_role_grant grant_row
                 JOIN iam_permission permission ON permission.id=grant_row.permission_id
                WHERE grant_row.tenant_id=target.tenant_id
                  AND grant_row.role_id=target.role_id
                  AND permission.permission_code LIKE 'dictionary%') <> 1
           OR (SELECT count(*)
                 FROM iam_grant_dimension dimension_row
                 JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
                 JOIN iam_permission permission ON permission.id=grant_row.permission_id
                WHERE grant_row.tenant_id=target.tenant_id
                  AND grant_row.role_id=target.role_id
                  AND permission.permission_code='dictionary-data:view'
                  AND dimension_row.dimension_code='TENANT'
                  AND dimension_row.scope_mode='TENANT_ALL') <> 1
           OR EXISTS (
                SELECT 1 FROM iam_grant_target grant_target
                JOIN iam_grant_dimension dimension_row ON dimension_row.id=grant_target.dimension_id
                JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
                JOIN iam_permission permission ON permission.id=grant_row.permission_id
                WHERE grant_row.tenant_id=target.tenant_id
                  AND grant_row.role_id=target.role_id
                  AND permission.permission_code='dictionary-data:view'
           ) THEN
            RETURN FALSE;
        END IF;

        IF NOT (
            (NOT EXISTS (
                SELECT 1 FROM iam_menu menu
                 WHERE menu.tenant_id=target.tenant_id
                   AND (menu.route_name IN (
                            'SystemDictionaryDataIndex','DictionaryDataView','SystemDictionaryData')
                        OR menu.route_path IN (
                            '/system/dict/data','/system/dict/data/type/:dictType'))
             ) AND NOT EXISTS (
                SELECT 1 FROM iam_role_menu role_menu
                JOIN iam_menu menu
                  ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                WHERE role_menu.tenant_id=target.tenant_id
                  AND role_menu.role_id=target.role_id
                  AND menu.route_name IN (
                      'SystemDictionaryDataIndex','DictionaryDataView','SystemDictionaryData')
             ))
            OR
            ((SELECT count(*)
                FROM iam_menu menu
                JOIN iam_menu parent
                  ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
                JOIN iam_permission permission ON permission.id=menu.display_permission_id
               WHERE menu.tenant_id=target.tenant_id
                 AND menu.route_name='SystemDictionaryDataIndex'
                 AND menu.menu_type='PAGE' AND menu.menu_name='Dictionary Data'
                 AND menu.route_path='/system/dict/data'
                 AND menu.component_path='/system/dict/data/list'
                 AND menu.redirect_path IS NULL AND menu.sort_order=160
                 AND menu.auth_code IS NULL AND menu.status='DISABLED'
                 AND menu.system_managed AND menu.deleted_at IS NOT NULL
                 AND menu.row_version=1
                 AND menu.meta_json='{"title":"system.dictData.title","icon":"lucide:list-tree"}'::jsonb
                 AND parent.route_name='System'
                 AND permission.permission_code='dictionary-data:view') = 1
             AND (SELECT count(*)
                    FROM iam_menu menu
                    JOIN iam_menu parent
                      ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
                   WHERE menu.tenant_id=target.tenant_id
                     AND menu.route_name='DictionaryDataView'
                     AND menu.menu_type='BUTTON' AND menu.menu_name='View Dictionary Data'
                     AND menu.route_path IS NULL AND menu.component_path IS NULL
                     AND menu.redirect_path IS NULL AND menu.display_permission_id IS NULL
                     AND menu.sort_order=162 AND menu.auth_code='dictionary-data:view'
                     AND menu.status='DISABLED' AND menu.deleted_at IS NOT NULL
                     AND menu.row_version=1 AND menu.system_managed
                     AND menu.meta_json='{"title":"system.dictData.permission.view"}'::jsonb
                     AND parent.route_name='SystemDictionaryDataIndex') = 1
             AND (SELECT count(*)
                FROM iam_menu menu
                JOIN iam_menu parent
                  ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
                JOIN iam_permission permission ON permission.id=menu.display_permission_id
               WHERE menu.tenant_id=target.tenant_id
                 AND menu.route_name='SystemDictionaryData'
                 AND menu.menu_type='PAGE' AND menu.menu_name='Dictionary Data Detail'
                 AND menu.route_path='/system/dict/data/type/:dictType'
                 AND menu.component_path='/system/dict/data/list'
                 AND menu.redirect_path IS NULL AND menu.sort_order=161
                 AND menu.auth_code IS NULL AND menu.status='DISABLED'
                 AND menu.system_managed AND menu.deleted_at IS NOT NULL
                 AND menu.row_version=1
                 AND menu.meta_json='{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict/data"}'::jsonb
                 AND parent.route_name='System'
                 AND permission.permission_code='dictionary-data:view') = 1
             AND (SELECT count(*) FROM iam_menu menu
                   WHERE menu.tenant_id=target.tenant_id
                     AND (menu.route_name IN (
                              'SystemDictionaryDataIndex','DictionaryDataView','SystemDictionaryData')
                          OR menu.route_path IN (
                              '/system/dict/data','/system/dict/data/type/:dictType'))) = 3
             AND (SELECT count(*)
                    FROM iam_role_menu role_menu
                    JOIN iam_menu menu
                      ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                   WHERE role_menu.tenant_id=target.tenant_id
                     AND role_menu.role_id=target.role_id
                     AND menu.route_name='SystemDictionaryDataIndex') = 1
             AND (SELECT count(*)
                    FROM iam_role_menu role_menu
                    JOIN iam_menu menu
                      ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                   WHERE role_menu.tenant_id=target.tenant_id
                     AND role_menu.role_id=target.role_id
                     AND menu.route_name='SystemDictionaryData') = 1
             AND (SELECT count(*)
                    FROM iam_role_menu role_menu
                    JOIN iam_menu menu
                      ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                   WHERE role_menu.tenant_id=target.tenant_id
                     AND role_menu.role_id=target.role_id
                     AND menu.route_name IN (
                         'SystemDictionaryDataIndex','DictionaryDataView','SystemDictionaryData')) = 2)
        ) THEN
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
END
$$
@@

DO $$
DECLARE
    extension_absent BOOLEAN;
    extension_exact BOOLEAN;
BEGIN
    SELECT NOT EXISTS (
        SELECT 1 FROM iam_role_grant grant_row
        JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.role_id IN (2200,3200) AND permission.permission_code LIKE 'dictionary%'
    ) AND NOT EXISTS (
        SELECT 1 FROM iam_menu
         WHERE tenant_id IN (2,3)
           AND (route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData','DictionaryDataView')
                OR route_path IN ('/system/dict/data','/system/dict/data/type/:dictType'))
    ) INTO extension_absent;

    SELECT pg_temp.iam_local_read_dictionary_extension_is_exact()
      INTO extension_exact;

    IF NOT extension_absent AND NOT extension_exact THEN
        RAISE EXCEPTION 'Local bootstrap refused: dictionary read extension is incomplete or modified';
    END IF;
    IF (SELECT changed FROM sys_local_read_dictionary_grant_change) <> extension_absent THEN
        RAISE EXCEPTION 'Local bootstrap refused: dictionary read extension state is inconsistent';
    END IF;
END
$$
@@

INSERT INTO iam_role_grant(
    id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
)
SELECT nextval('iam_id_seq'),target.tenant_id,target.role_id,permission.id,
       'dictionary-data-view','ACTIVE',target.membership_id,target.membership_id
  FROM (VALUES
      (2::bigint,2200::bigint,2100::bigint),
      (3::bigint,3200::bigint,3100::bigint)
  ) target(tenant_id,role_id,membership_id)
  JOIN iam_permission permission ON permission.permission_code='dictionary-data:view'
ON CONFLICT(role_id,permission_id,grant_key) DO NOTHING
@@

INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
SELECT nextval('iam_id_seq'),grant_row.id,'TENANT','TENANT_ALL'
  FROM iam_role_grant grant_row
  JOIN iam_permission permission ON permission.id=grant_row.permission_id
 WHERE grant_row.role_id IN (2200,3200)
   AND permission.permission_code='dictionary-data:view'
ON CONFLICT(grant_id,dimension_code) DO NOTHING
@@

UPDATE iam_role
   SET row_version=row_version+1,updated_at=now()
 WHERE id IN (2200,3200)
   AND (SELECT changed FROM sys_local_read_dictionary_grant_change)
@@

UPDATE iam_membership
   SET permission_version=permission_version+1,updated_at=now()
 WHERE id IN (2100,3100)
   AND (SELECT changed FROM sys_local_read_dictionary_grant_change)
@@

DO $$
BEGIN
    IF (SELECT count(*) FROM iam_tenant
         WHERE (id=2 AND tenant_code='local-merchant' AND tenant_type='DIRECT_MERCHANT'
                AND account_domain='MERCHANT' AND status='ACTIVE')
            OR (id=3 AND tenant_code='local-agent' AND tenant_type='AGENT'
                AND account_domain='AGENT' AND status='ACTIVE')) <> 2
       OR (SELECT count(*) FROM iam_user
            WHERE (id=200 AND idp_subject='admin@merchant.localhost' AND account_domain='MERCHANT' AND status='ACTIVE')
               OR (id=300 AND idp_subject='admin@agent.localhost' AND account_domain='AGENT' AND status='ACTIVE')) <> 2
       OR (SELECT count(*) FROM iam_membership
            WHERE (id=2100 AND tenant_id=2 AND user_id=200 AND account_domain='MERCHANT' AND status='ACTIVE')
               OR (id=3100 AND tenant_id=3 AND user_id=300 AND account_domain='AGENT' AND status='ACTIVE')) <> 2
       OR (SELECT count(*) FROM iam_authentication_credential
            WHERE (user_id=200 AND username='admin@merchant.localhost' AND account_domain='MERCHANT' AND status='ACTIVE')
               OR (user_id=300 AND username='admin@agent.localhost' AND account_domain='AGENT' AND status='ACTIVE')) <> 2
       OR (SELECT count(*) FROM iam_membership_role
            WHERE (tenant_id=2 AND membership_id=2100 AND role_id=2200)
               OR (tenant_id=3 AND membership_id=3100 AND role_id=3200)) <> 2
       OR NOT pg_temp.iam_local_isolated_portal_fixture_is_exact()
       OR (SELECT count(*) FROM iam_role_menu
            WHERE (tenant_id=2 AND role_id=2200 AND menu_id IN (6200,6201))
               OR (tenant_id=3 AND role_id=3200 AND menu_id IN (6300,6301))) <> 4
       OR (SELECT count(*) FROM iam_role_menu
            WHERE (tenant_id=2 AND role_id=2200 AND menu_id IN (16000,16001,16002))
               OR (tenant_id=3 AND role_id=3200 AND menu_id IN (26000,26001,26002))) <> 6
       OR (SELECT count(*)
             FROM iam_role_grant grant_row
             JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
            WHERE grant_row.status='ACTIVE'
              AND dimension_row.dimension_code='TENANT'
              AND dimension_row.scope_mode='TENANT_ALL'
              AND ((grant_row.tenant_id=2 AND grant_row.role_id=2200
                    AND grant_row.id BETWEEN 723001 AND 723021)
                OR (grant_row.tenant_id=3 AND grant_row.role_id=3200
                    AND grant_row.id BETWEEN 733001 AND 733021))) <> 26
       OR (SELECT count(*) FROM iam_menu
            WHERE (tenant_id=2 AND id IN (
                    16000,16001,16002,16020,16021,16022,16023,16024,16025,
                    16026,16027,16028,16029,16040))
               OR (tenant_id=3 AND id IN (
                    26000,26001,26002,26020,26021,26022,26023,26024,26025,
                    26026,26027,26028,26029,26040))) <> 28
       OR (SELECT count(*)
             FROM iam_role_grant grant_row
             JOIN iam_permission permission ON permission.id=grant_row.permission_id
             JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
            WHERE grant_row.role_id IN (2200,3200)
              AND permission.permission_code='dictionary-data:view'
              AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NULL
              AND grant_row.valid_until IS NULL
              AND dimension_row.dimension_code='TENANT'
              AND dimension_row.scope_mode='TENANT_ALL') <> 2
       OR (SELECT count(*)
             FROM iam_role_grant grant_row
             JOIN iam_permission permission ON permission.id=grant_row.permission_id
            WHERE grant_row.role_id IN (2200,3200)
              AND permission.permission_code LIKE 'dictionary%') <> 2
       OR NOT pg_temp.iam_local_read_dictionary_extension_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap failed: merchant or agent identity fixture is incomplete';
    END IF;
END;
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_mch003_reviewer_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_user
         WHERE id=101 OR (idp_issuer='local' AND idp_subject='reviewer@platform.localhost'))=1
   AND EXISTS (SELECT 1 FROM iam_user
                WHERE id=101 AND idp_issuer='local'
                  AND idp_subject='reviewer@platform.localhost'
                  AND display_name='Platform Reviewer' AND account_domain='PLATFORM'
                  AND status='ACTIVE' AND remark='Local MCH-003 independent reviewer'
                  AND email_cipher IS NULL AND phone_cipher IS NULL AND row_version=0)
   AND (SELECT count(*) FROM iam_authentication_credential
         WHERE user_id=101 OR (account_domain='PLATFORM'
                               AND username='reviewer@platform.localhost'))=1
   AND EXISTS (SELECT 1 FROM iam_authentication_credential
                WHERE user_id=101 AND username='reviewer@platform.localhost'
                  AND account_domain='PLATFORM' AND status='ACTIVE'
                  AND ((password_hash IS NULL AND last_login_at IS NULL AND row_version=0)
                    OR (password_hash ~ '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$'
                        AND ((last_login_at IS NULL AND row_version=1)
                          OR (last_login_at IS NOT NULL AND row_version>=2)))))
   AND (SELECT count(*) FROM iam_membership WHERE id=1001 OR user_id=101)=1
   AND EXISTS (SELECT 1 FROM iam_membership
                WHERE id=1001 AND tenant_id=1 AND user_id=101 AND department_id=10
                  AND status='ACTIVE' AND account_domain='PLATFORM'
                  AND permission_version=7 AND session_version=0 AND row_version=0)
   AND (SELECT count(*) FROM iam_membership_role WHERE membership_id=1001)=1
   AND EXISTS (SELECT 1 FROM iam_membership_role
                WHERE tenant_id=1 AND membership_id=1001 AND role_id=2000
                  AND assigned_by=1000)
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1001')=1
   AND EXISTS (SELECT 1 FROM iam_permission_change_outbox
                WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1001'
                  AND event_type='PERMISSION_VERSION_CHANGED'
                  AND payload='{"tenantId":1,"membershipId":1001,"permissionVersion":7,"reason":"LOCAL_MCH003_BOOTSTRAP"}'::jsonb
                  AND aggregate_version=7 AND schema_version=1
                  AND partition_key='1:1001' AND trace_id='local-mch003-bootstrap')
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
          JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
         WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
           AND outbox.aggregate_ref='1001')=1
   AND pg_temp.iam_local_platform_merchant_capability_is_exact()
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_mch003_candidate_tenant_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH owned AS (
    SELECT tenant.*,
           CASE
             WHEN tenant.tenant_code='local-merchant-candidate' THEN 1
             WHEN tenant.tenant_code ~
                  '^local-merchant-candidate-([2-9]|[1-9][0-9]{1,5})$'
               THEN substring(tenant.tenant_code FROM 26)::integer
           END AS candidate_ordinal
      FROM iam_tenant tenant
     WHERE tenant.id=4000 OR tenant.tenant_code LIKE 'local-merchant-candidate%'
), classified AS (
    SELECT owned.*,
           (SELECT count(*) FROM merchant WHERE tenant_id=owned.id) AS merchant_count
      FROM owned
)
SELECT (SELECT count(*) FROM classified)>0
   AND (SELECT min(candidate_ordinal) FROM classified)=1
   AND (SELECT count(*) FROM classified)=(SELECT max(candidate_ordinal) FROM classified)
   AND (SELECT count(DISTINCT candidate_ordinal) FROM classified)=(SELECT count(*) FROM classified)
   AND NOT EXISTS (
       SELECT 1 FROM classified candidate
        WHERE candidate.candidate_ordinal IS NULL
           OR (candidate.candidate_ordinal=1 AND (candidate.id<>4000
               OR candidate.tenant_code<>'local-merchant-candidate'
               OR candidate.tenant_name<>'Local Merchant Candidate'))
           OR (candidate.candidate_ordinal>1 AND (
               candidate.tenant_code<>'local-merchant-candidate-' || candidate.candidate_ordinal
               OR candidate.tenant_name<>'Local Merchant Candidate ' || candidate.candidate_ordinal))
           OR candidate.tenant_type<>'DIRECT_MERCHANT'
           OR candidate.account_domain<>'MERCHANT' OR candidate.status<>'ACTIVE'
           OR candidate.row_version<>0
           OR EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=candidate.id)
           OR EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=candidate.id)
           OR EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=candidate.id)
           OR candidate.merchant_count>1
           OR (candidate.merchant_count=1 AND NOT EXISTS (
               SELECT 1 FROM merchant merchant_row
                WHERE merchant_row.tenant_id=candidate.id
                  AND merchant_row.account_domain='MERCHANT'
                  AND merchant_row.application_source='PLATFORM'
                  AND merchant_row.application_actor_tenant_id=1
                  AND merchant_row.application_author_membership_id IN (1000,1001)
                  AND (SELECT count(*) FROM merchant_audit_event audit
                        WHERE audit.merchant_id=merchant_row.id
                          AND audit.target_tenant_id=candidate.id
                          AND audit.actor_account_domain='PLATFORM'
                          AND audit.actor_tenant_id=merchant_row.application_actor_tenant_id
                          AND audit.actor_membership_id=merchant_row.application_author_membership_id
                          AND audit.action_code='CREATE' AND audit.previous_status IS NULL
                          AND audit.next_status='PENDING_REVIEW'
                          AND audit.reason_code='PLATFORM_APPLICATION_SUBMITTED'
                          AND audit.merchant_version=0)=1
                  AND (SELECT count(*) FROM merchant_command_dedup dedup
                        WHERE dedup.merchant_id=merchant_row.id
                          AND dedup.actor_account_domain='PLATFORM'
                          AND dedup.actor_tenant_id=merchant_row.application_actor_tenant_id
                          AND dedup.actor_membership_id=merchant_row.application_author_membership_id
                          AND dedup.command_type='CREATE'
                          AND dedup.required_permission='merchant:create'
                          AND dedup.command_schema_version=3
                          AND dedup.canonical_digest_scheme_version=1)=1))
   )
   AND (SELECT count(*) FROM classified WHERE merchant_count=0)<=1
   AND NOT EXISTS (
       SELECT 1 FROM classified candidate
        WHERE candidate.merchant_count=0
          AND candidate.candidate_ordinal<>(SELECT max(candidate_ordinal) FROM classified))
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam_local_mch003_bound_merchant_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM merchant
         WHERE id=9000 OR tenant_id=2 OR merchant_code='MCH_LOCAL_SELF')=1
   AND EXISTS (SELECT 1 FROM merchant
                WHERE id=9000 AND tenant_id=2 AND account_domain='MERCHANT'
                  AND merchant_code='MCH_LOCAL_SELF'
                  AND legal_name='Local Merchant Legal Name'
                  AND display_name='Local Merchant'
                  AND registration_country='SG'
                  AND registration_number_masked='********0002'
                  AND registration_fingerprint=decode(repeat('a1',32),'hex')
                  AND registration_search_key_id='mch-registration-search-v1'
                  AND registration_fingerprint_algorithm='HMAC-SHA-256'
                  AND registration_normalization_version=1
                  AND registration_ciphertext=decode('010203','hex')
                  AND registration_nonce=decode(repeat('a2',12),'hex')
                  AND registration_auth_tag=decode(repeat('a3',16),'hex')
                  AND registration_aead_key_id='mch-registration-aead-v1'
                  AND registration_aead_algorithm='AES-256-GCM'
                  AND registration_nonce_purpose='REGISTRATION_AEAD'
                  AND status='ACTIVE' AND row_version=0)
   AND (SELECT count(*) FROM merchant_protected_nonce
         WHERE purpose='REGISTRATION_AEAD'
           AND key_id='mch-registration-aead-v1'
           AND algorithm='AES-256-GCM'
           AND nonce=decode(repeat('a2',12),'hex'))=1
$$
@@

DO $$
DECLARE
    reviewer_absent BOOLEAN;
    candidate_absent BOOLEAN;
    merchant_absent BOOLEAN;
    main_permission_version BIGINT;
BEGIN
    SELECT NOT EXISTS (SELECT 1 FROM iam_user
                        WHERE id=101 OR (idp_issuer='local'
                                         AND idp_subject='reviewer@platform.localhost'))
       AND NOT EXISTS (SELECT 1 FROM iam_authentication_credential
                        WHERE user_id=101 OR username='reviewer@platform.localhost')
       AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE id=1001 OR user_id=101)
       AND NOT EXISTS (SELECT 1 FROM iam_membership_role WHERE membership_id=1001)
       AND NOT EXISTS (SELECT 1 FROM iam_permission_change_outbox
                        WHERE aggregate_type='MEMBERSHIP' AND aggregate_ref='1001')
      INTO reviewer_absent;
    IF NOT reviewer_absent AND NOT pg_temp.iam_local_mch003_reviewer_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap refused: MCH-003 reviewer fixture is incomplete or modified';
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM iam_tenant
                        WHERE id=4000 OR tenant_code LIKE 'local-merchant-candidate%')
      INTO candidate_absent;
    IF NOT candidate_absent AND NOT pg_temp.iam_local_mch003_candidate_tenant_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap refused: MCH-003 Merchant Tenant fixture is incomplete or modified';
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM merchant
                        WHERE id=9000 OR tenant_id=2 OR merchant_code='MCH_LOCAL_SELF')
       AND NOT EXISTS (SELECT 1 FROM merchant_protected_nonce
                        WHERE purpose='REGISTRATION_AEAD'
                          AND key_id='mch-registration-aead-v1'
                          AND nonce=decode(repeat('a2',12),'hex'))
      INTO merchant_absent;
    IF NOT merchant_absent AND NOT pg_temp.iam_local_mch003_bound_merchant_is_exact() THEN
        RAISE EXCEPTION
            'Local bootstrap refused: tenant 2 Merchant fixture is incomplete or modified';
    END IF;

    SELECT permission_version INTO main_permission_version
      FROM iam_membership WHERE tenant_id=1 AND id=1000 FOR UPDATE;
    IF main_permission_version=7 THEN
        IF NOT pg_temp.iam_local_mch003_history_is_exact() THEN
            RAISE EXCEPTION
                'Local bootstrap refused: MCH-003 permission history is incomplete or modified';
        END IF;
    ELSIF main_permission_version<>1 OR EXISTS (
        SELECT 1 FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
           AND aggregate_version=7
    ) THEN
        RAISE EXCEPTION
            'Local bootstrap refused: MCH-003 permission version is incomplete or modified';
    END IF;

    CREATE TEMPORARY TABLE iam_local_mch003_change(
        reviewer_absent BOOLEAN NOT NULL,
        candidate_absent BOOLEAN NOT NULL,
        merchant_absent BOOLEAN NOT NULL,
        main_version_changed BOOLEAN NOT NULL
    ) ON COMMIT DROP;
    INSERT INTO iam_local_mch003_change
    VALUES(reviewer_absent,candidate_absent,merchant_absent,main_permission_version=1);
END
$$
@@

INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
SELECT 4000,'local-merchant-candidate','Local Merchant Candidate',
       'DIRECT_MERCHANT','ACTIVE','MERCHANT'
 WHERE (SELECT candidate_absent FROM iam_local_mch003_change)
@@

INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark,account_domain)
SELECT 101,'local','reviewer@platform.localhost','Platform Reviewer','ACTIVE',
       'Local MCH-003 independent reviewer','PLATFORM'
 WHERE (SELECT reviewer_absent FROM iam_local_mch003_change)
@@

INSERT INTO iam_membership(
    id,tenant_id,user_id,department_id,status,permission_version,session_version,account_domain
)
SELECT 1001,1,101,10,'ACTIVE',7,0,'PLATFORM'
 WHERE (SELECT reviewer_absent FROM iam_local_mch003_change)
@@

INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,account_domain)
SELECT 101,'reviewer@platform.localhost',NULL,'ACTIVE','PLATFORM'
 WHERE (SELECT reviewer_absent FROM iam_local_mch003_change)
@@

INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
SELECT 1,1001,2000,1000 WHERE (SELECT reviewer_absent FROM iam_local_mch003_change)
@@

UPDATE iam_membership
   SET permission_version=7,updated_at=now()
 WHERE tenant_id=1 AND id=1000
   AND (SELECT main_version_changed FROM iam_local_mch003_change)
@@

INSERT INTO iam_permission_change_outbox(
    id,tenant_id,aggregate_type,aggregate_ref,event_type,payload,
    aggregate_version,schema_version,partition_key,trace_id
)
SELECT nextval('iam_id_seq'),1,'MEMBERSHIP',target.membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('tenantId',1,'membershipId',target.membership_id,
                          'permissionVersion',7,'reason','LOCAL_MCH003_BOOTSTRAP'),
       7,1,'1:' || target.membership_id::text,'local-mch003-bootstrap'
  FROM (VALUES
      (1000::bigint,(SELECT main_version_changed FROM iam_local_mch003_change)),
      (1001::bigint,(SELECT reviewer_absent FROM iam_local_mch003_change))
  ) target(membership_id,changed)
 WHERE target.changed
@@

INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
SELECT 'REGISTRATION_AEAD','mch-registration-aead-v1','AES-256-GCM',
       decode(repeat('a2',12),'hex')
 WHERE (SELECT merchant_absent FROM iam_local_mch003_change)
@@

INSERT INTO merchant(
    id,tenant_id,account_domain,merchant_code,legal_name,display_name,
    registration_country,registration_number_masked,registration_fingerprint,
    registration_search_key_id,registration_fingerprint_algorithm,
    registration_normalization_version,registration_ciphertext,registration_nonce,
    registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
    registration_nonce_purpose,status,row_version,submitted_at,reviewed_at
)
SELECT 9000,2,'MERCHANT','MCH_LOCAL_SELF','Local Merchant Legal Name','Local Merchant',
       'SG','********0002',decode(repeat('a1',32),'hex'),
       'mch-registration-search-v1','HMAC-SHA-256',1,decode('010203','hex'),
       decode(repeat('a2',12),'hex'),decode(repeat('a3',16),'hex'),
       'mch-registration-aead-v1','AES-256-GCM','REGISTRATION_AEAD',
       'ACTIVE',0,now(),now()
 WHERE (SELECT merchant_absent FROM iam_local_mch003_change)
@@

DO $$
BEGIN
    IF NOT pg_temp.iam_local_mch003_history_is_exact()
       OR NOT pg_temp.iam_local_mch003_reviewer_is_exact()
       OR NOT pg_temp.iam_local_mch003_candidate_tenant_is_exact()
       OR NOT pg_temp.iam_local_mch003_bound_merchant_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap failed: MCH-003 runtime fixture is incomplete';
    END IF;
END
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

DROP FUNCTION pg_temp.iam_local_legacy_fixture_is_exact(BOOLEAN, INTEGER)
@@
DROP FUNCTION pg_temp.iam_local_final_fixture_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_platform_dictionary_extension_is_absent()
@@
DROP FUNCTION pg_temp.iam_local_platform_dictionary_extension_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_read_dictionary_extension_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_platform_access_grant_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_v34_history_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_mch003_history_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_mch003_reviewer_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_mch003_candidate_tenant_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_mch003_bound_merchant_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_isolated_portal_fixture_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_merchant_self_service_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_platform_merchant_capability_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_v19_history_is_exact()
@@
DROP FUNCTION pg_temp.iam_local_role_menus_are_exact()
@@
DROP FUNCTION pg_temp.iam_local_v14_history_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_v15_history_is_exact()
@@

DROP FUNCTION pg_temp.iam_local_identity_is_exact(INTEGER)
@@

-- IAM002_PERSISTED_MCH003_BEGIN
SELECT pg_advisory_xact_lock(hashtextextended('payment-platform:iam002-local-mch003-persisted', 0))
@@

LOCK TABLE
    iam_tenant, iam_department, iam_user, iam_membership,
    iam_authentication_credential, iam_role, iam_membership_role,
    iam_permission, iam_role_grant, iam_grant_dimension, iam_grant_target,
    iam_menu, iam_role_menu, iam_permission_change_outbox,
    iam_permission_change_relay_state, merchant, merchant_protected_nonce
IN SHARE ROW EXCLUSIVE MODE
@@

CREATE OR REPLACE FUNCTION pg_temp.iam002_persisted_platform_mch003_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH expected_permission(permission_code) AS (
    VALUES ('merchant:view'),('merchant:review'),('merchant:disable'),('merchant:enable'),
           ('merchant:terminate'),('merchant:update'),('merchant:create'),('merchant:amend'),
           ('merchant:document:upload'),('merchant:document:view')
), expected_menu(route_name,parent_route_name,auth_code) AS (
    VALUES ('MerchantManagement',NULL::text,NULL::text),
           ('MerchantList','MerchantManagement','merchant:view'),
           ('MerchantReview','MerchantList','merchant:review'),
           ('MerchantDisable','MerchantList','merchant:disable'),
           ('MerchantEnable','MerchantList','merchant:enable'),
           ('MerchantTerminate','MerchantList','merchant:terminate'),
           ('MerchantEdit','MerchantList','merchant:amend'),
           ('MerchantCreate','MerchantList','merchant:create')
)
SELECT (SELECT count(*) FROM iam_permission permission
         JOIN expected_permission expected USING(permission_code)
        WHERE permission.status='ACTIVE')=10
   AND (SELECT count(*) FROM iam_role_grant grant_row
         JOIN iam_permission permission ON permission.id=grant_row.permission_id
         JOIN expected_permission expected USING(permission_code)
        WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
          AND grant_row.grant_key='system-' || replace(permission.permission_code,':','-')
          AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NOT NULL
          AND grant_row.valid_from<=statement_timestamp()
          AND grant_row.valid_until>statement_timestamp())=10
   AND (SELECT count(*) FROM iam_role_grant grant_row
         JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
          AND permission.permission_code IN (SELECT permission_code FROM expected_permission))=10
   AND (SELECT count(*) FROM iam_grant_dimension dimension_row
         JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
         JOIN iam_permission permission ON permission.id=grant_row.permission_id
        WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
          AND permission.permission_code IN (SELECT permission_code FROM expected_permission)
          AND dimension_row.dimension_code='TENANT'
          AND dimension_row.scope_mode='TENANT_ALL')=10
   AND NOT EXISTS (
       SELECT 1 FROM iam_grant_target target
       JOIN iam_grant_dimension dimension_row ON dimension_row.id=target.dimension_id
       JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
       JOIN iam_permission permission ON permission.id=grant_row.permission_id
       WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
         AND permission.permission_code IN (SELECT permission_code FROM expected_permission))
   AND (SELECT count(*) FROM expected_menu expected
         JOIN iam_menu menu ON menu.tenant_id=1 AND menu.route_name=expected.route_name
         LEFT JOIN iam_menu parent ON parent.id=menu.parent_id AND parent.tenant_id=menu.tenant_id
        WHERE parent.route_name IS NOT DISTINCT FROM expected.parent_route_name
          AND menu.auth_code IS NOT DISTINCT FROM expected.auth_code
          AND menu.status='ACTIVE' AND menu.deleted_at IS NULL AND menu.system_managed)=8
   AND (SELECT count(*) FROM iam_menu
        WHERE tenant_id=1 AND route_name IN (SELECT route_name FROM expected_menu))=8
   AND (SELECT count(*) FROM iam_role_menu role_menu
         JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
        WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
          AND menu.route_name IN (SELECT route_name FROM expected_menu))=8
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam002_persisted_reviewer_is_exact()
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT (SELECT count(*) FROM iam_user
         WHERE id=101 OR (idp_issuer IN ('local','local:platform')
                          AND idp_subject='reviewer@platform.localhost'))=1
   AND EXISTS (SELECT 1 FROM iam_user
                WHERE id=101 AND idp_issuer IN ('local','local:platform')
                  AND idp_subject='reviewer@platform.localhost'
                  AND display_name='Platform Reviewer' AND account_domain='PLATFORM'
                  AND status='ACTIVE' AND remark='Local MCH-003 independent reviewer'
                  AND email_cipher IS NULL AND phone_cipher IS NULL)
   AND (SELECT count(*) FROM iam_authentication_credential
         WHERE user_id=101 OR username='reviewer@platform.localhost')=1
   AND EXISTS (SELECT 1 FROM iam_authentication_credential
                WHERE user_id=101 AND username='reviewer@platform.localhost'
                  AND account_domain='PLATFORM' AND status='ACTIVE'
                  AND (password_hash IS NULL
                    OR password_hash ~ '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$'))
   AND (SELECT count(*) FROM iam_membership WHERE id=1001 OR user_id=101)=1
   AND EXISTS (SELECT 1 FROM iam_membership
                WHERE id=1001 AND tenant_id=1 AND user_id=101 AND department_id=10
                  AND status='ACTIVE' AND account_domain='PLATFORM'
                  AND permission_version>=1)
   AND (SELECT count(*) FROM iam_membership_role WHERE membership_id=1001)=1
   AND EXISTS (SELECT 1 FROM iam_membership_role
                WHERE tenant_id=1 AND membership_id=1001 AND role_id=2000
                  AND assigned_by=1000)
   AND (SELECT count(*) FROM iam_permission_change_outbox
         WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1001')=1
   AND EXISTS (SELECT 1 FROM iam_permission_change_outbox outbox
                JOIN iam_membership membership
                  ON membership.tenant_id=outbox.tenant_id
                 AND membership.id=outbox.aggregate_ref::bigint
                WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
                  AND outbox.aggregate_ref='1001'
                  AND outbox.event_type='PERMISSION_VERSION_CHANGED'
                  AND outbox.payload=jsonb_build_object(
                      'tenantId',1,'membershipId',1001,
                      'permissionVersion',membership.permission_version,
                      'reason','LOCAL_MCH003_BOOTSTRAP')
                  AND outbox.aggregate_version=membership.permission_version
                  AND outbox.schema_version=1 AND outbox.partition_key='1:1001'
                  AND outbox.trace_id='local-mch003-bootstrap')
   AND (SELECT count(*) FROM iam_permission_change_relay_state relay
         JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
        WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
          AND outbox.aggregate_ref='1001')=1
   AND pg_temp.iam002_persisted_platform_mch003_is_exact()
$$
@@

CREATE OR REPLACE FUNCTION pg_temp.iam002_persisted_candidate_lineage_is_exact(
    require_unbound BOOLEAN
)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH owned AS (
    SELECT tenant.*,
           CASE
             WHEN tenant.tenant_code='local-merchant-candidate' THEN 1
             WHEN tenant.tenant_code ~
                  '^local-merchant-candidate-([2-9]|[1-9][0-9]{1,5})$'
               THEN substring(tenant.tenant_code FROM 26)::integer
           END AS candidate_ordinal
      FROM iam_tenant tenant
     WHERE tenant.id=4000 OR tenant.tenant_code LIKE 'local-merchant-candidate%'
), classified AS (
    SELECT owned.*,
           (SELECT count(*) FROM merchant WHERE tenant_id=owned.id) AS merchant_count
      FROM owned
)
SELECT (SELECT count(*) FROM classified)>0
   AND (SELECT min(candidate_ordinal) FROM classified)=1
   AND (SELECT count(*) FROM classified)=(SELECT max(candidate_ordinal) FROM classified)
   AND (SELECT count(DISTINCT candidate_ordinal) FROM classified)=(SELECT count(*) FROM classified)
   AND NOT EXISTS (
       SELECT 1 FROM classified candidate
        WHERE candidate.candidate_ordinal IS NULL
           OR (candidate.candidate_ordinal=1 AND (candidate.id<>4000
               OR candidate.tenant_code<>'local-merchant-candidate'
               OR candidate.tenant_name<>'Local Merchant Candidate'))
           OR (candidate.candidate_ordinal>1 AND (
               candidate.tenant_code<>'local-merchant-candidate-' || candidate.candidate_ordinal
               OR candidate.tenant_name<>'Local Merchant Candidate ' || candidate.candidate_ordinal))
           OR candidate.tenant_type<>'DIRECT_MERCHANT'
           OR candidate.account_domain<>'MERCHANT' OR candidate.status<>'ACTIVE'
           OR candidate.row_version<>0
           OR EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=candidate.id)
           OR EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=candidate.id)
           OR EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=candidate.id)
           OR candidate.merchant_count>1
           OR (candidate.merchant_count=1 AND NOT EXISTS (
               SELECT 1 FROM merchant merchant_row
                WHERE merchant_row.tenant_id=candidate.id
                  AND merchant_row.account_domain='MERCHANT'
                  AND merchant_row.application_source='PLATFORM'
                  AND merchant_row.application_actor_tenant_id=1
                  AND merchant_row.application_author_membership_id IN (1000,1001)
                  AND (SELECT count(*) FROM merchant_audit_event audit
                        WHERE audit.merchant_id=merchant_row.id
                          AND audit.target_tenant_id=candidate.id
                          AND audit.actor_account_domain='PLATFORM'
                          AND audit.actor_tenant_id=merchant_row.application_actor_tenant_id
                          AND audit.actor_membership_id=merchant_row.application_author_membership_id
                          AND audit.action_code='CREATE' AND audit.previous_status IS NULL
                          AND audit.next_status='PENDING_REVIEW'
                          AND audit.reason_code='PLATFORM_APPLICATION_SUBMITTED'
                          AND audit.merchant_version=0)=1
                  AND (SELECT count(*) FROM merchant_command_dedup dedup
                        WHERE dedup.merchant_id=merchant_row.id
                          AND dedup.actor_account_domain='PLATFORM'
                          AND dedup.actor_tenant_id=merchant_row.application_actor_tenant_id
                          AND dedup.actor_membership_id=merchant_row.application_author_membership_id
                          AND dedup.command_type='CREATE'
                          AND dedup.required_permission='merchant:create'
                          AND dedup.command_schema_version=3
                          AND dedup.canonical_digest_scheme_version=1)=1))
   )
   AND (SELECT count(*) FROM classified WHERE merchant_count=0)
       = CASE WHEN require_unbound THEN 1 ELSE
           LEAST(1,(SELECT count(*) FROM classified WHERE merchant_count=0)) END
   AND NOT EXISTS (
       SELECT 1 FROM classified candidate
        WHERE candidate.merchant_count=0
          AND candidate.candidate_ordinal<>(SELECT max(candidate_ordinal) FROM classified))
$$
@@

DO $$
DECLARE
    reviewer_absent BOOLEAN;
    candidate_required BOOLEAN;
    candidate_ordinal INTEGER;
    candidate_id BIGINT;
    tenant_two_merchant_count BIGINT;
    reviewer_version BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM iam_tenant
                    WHERE id=1 AND tenant_code='platform' AND tenant_type='PLATFORM'
                      AND account_domain='PLATFORM' AND status='ACTIVE')
       OR NOT EXISTS (SELECT 1 FROM iam_department
                       WHERE id=10 AND tenant_id=1 AND status='ACTIVE')
       OR NOT EXISTS (SELECT 1 FROM iam_user
                       WHERE id=100 AND idp_issuer IN ('local','local:platform')
                         AND idp_subject='admin@platform.localhost'
                         AND account_domain='PLATFORM' AND status='ACTIVE')
       OR NOT EXISTS (SELECT 1 FROM iam_membership
                       WHERE id=1000 AND tenant_id=1 AND user_id=100
                         AND status='ACTIVE' AND permission_version>=1)
       OR NOT EXISTS (SELECT 1 FROM iam_role
                       WHERE id=2000 AND tenant_id=1 AND role_code='platform-admin'
                         AND system_role AND NOT assignable AND status='ACTIVE'
                         AND deleted_at IS NULL)
       OR NOT pg_temp.iam002_persisted_platform_mch003_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap refused: persisted MCH-003 platform capability is incomplete or modified';
    END IF;

    SELECT NOT EXISTS (SELECT 1 FROM iam_user
                        WHERE id=101 OR idp_subject='reviewer@platform.localhost')
       AND NOT EXISTS (SELECT 1 FROM iam_authentication_credential
                        WHERE user_id=101 OR username='reviewer@platform.localhost')
       AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE id=1001 OR user_id=101)
       AND NOT EXISTS (SELECT 1 FROM iam_membership_role WHERE membership_id=1001)
       AND NOT EXISTS (SELECT 1 FROM iam_permission_change_outbox
                        WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP'
                          AND aggregate_ref='1001')
      INTO reviewer_absent;
    IF NOT reviewer_absent AND NOT pg_temp.iam002_persisted_reviewer_is_exact() THEN
        RAISE EXCEPTION 'Local bootstrap refused: persisted MCH-003 reviewer is incomplete or modified';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM iam_tenant
                    WHERE id=4000 OR tenant_code LIKE 'local-merchant-candidate%') THEN
        candidate_required := TRUE;
        candidate_ordinal := 1;
        candidate_id := 4000;
    ELSIF NOT pg_temp.iam002_persisted_candidate_lineage_is_exact(FALSE) THEN
        RAISE EXCEPTION 'Local bootstrap refused: persisted MCH-003 candidate lineage is incomplete or modified';
    ELSIF NOT EXISTS (
        SELECT 1 FROM iam_tenant tenant
         WHERE (tenant.id=4000 OR tenant.tenant_code LIKE 'local-merchant-candidate%')
           AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=tenant.id)
    ) THEN
        candidate_required := TRUE;
        SELECT max(CASE
                 WHEN tenant_code='local-merchant-candidate' THEN 1
                 ELSE substring(tenant_code FROM 26)::integer
               END)+1
          INTO candidate_ordinal
          FROM iam_tenant
         WHERE id=4000 OR tenant_code LIKE 'local-merchant-candidate%';
        IF candidate_ordinal>999999 THEN
            RAISE EXCEPTION 'Local bootstrap refused: persisted MCH-003 candidate lineage is exhausted';
        END IF;
        LOOP
            candidate_id := nextval('iam_id_seq');
            EXIT WHEN NOT EXISTS (SELECT 1 FROM iam_tenant WHERE id=candidate_id);
        END LOOP;
    ELSE
        candidate_required := FALSE;
        SELECT candidate.id,
               CASE WHEN candidate.tenant_code='local-merchant-candidate' THEN 1
                    ELSE substring(candidate.tenant_code FROM 26)::integer END
          INTO candidate_id,candidate_ordinal
          FROM iam_tenant candidate
         WHERE (candidate.id=4000 OR candidate.tenant_code LIKE 'local-merchant-candidate%')
           AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=candidate.id);
    END IF;

    SELECT count(*) INTO tenant_two_merchant_count
      FROM merchant WHERE tenant_id=2 AND account_domain='MERCHANT';
    IF tenant_two_merchant_count>1
       OR (tenant_two_merchant_count=0 AND EXISTS (
           SELECT 1 FROM merchant WHERE id=9000 OR merchant_code='MCH_LOCAL_SELF')) THEN
        RAISE EXCEPTION 'Local bootstrap refused: persisted tenant 2 Merchant binding is ambiguous';
    END IF;

    SELECT permission_version INTO reviewer_version
      FROM iam_membership WHERE tenant_id=1 AND id=1000 FOR SHARE;
    CREATE TEMPORARY TABLE iam002_persisted_mch003_change(
        reviewer_absent BOOLEAN NOT NULL,
        candidate_required BOOLEAN NOT NULL,
        candidate_id BIGINT NOT NULL,
        candidate_ordinal INTEGER NOT NULL,
        merchant_absent BOOLEAN NOT NULL,
        reviewer_version BIGINT NOT NULL
    ) ON COMMIT DROP;
    INSERT INTO iam002_persisted_mch003_change
    VALUES(reviewer_absent,candidate_required,candidate_id,candidate_ordinal,
           tenant_two_merchant_count=0,reviewer_version);
END
$$
@@

INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
SELECT candidate_id,
       CASE candidate_ordinal WHEN 1 THEN 'local-merchant-candidate'
            ELSE 'local-merchant-candidate-' || candidate_ordinal END,
       CASE candidate_ordinal WHEN 1 THEN 'Local Merchant Candidate'
            ELSE 'Local Merchant Candidate ' || candidate_ordinal END,
       'DIRECT_MERCHANT','ACTIVE','MERCHANT'
  FROM iam002_persisted_mch003_change WHERE candidate_required
@@

INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark,account_domain)
SELECT 101,(SELECT idp_issuer FROM iam_user WHERE id=100),
       'reviewer@platform.localhost','Platform Reviewer','ACTIVE',
       'Local MCH-003 independent reviewer','PLATFORM'
 WHERE (SELECT reviewer_absent FROM iam002_persisted_mch003_change)
@@

INSERT INTO iam_membership(
    id,tenant_id,user_id,department_id,status,permission_version,session_version,account_domain
)
SELECT 1001,1,101,10,'ACTIVE',reviewer_version,0,'PLATFORM'
  FROM iam002_persisted_mch003_change WHERE reviewer_absent
@@

INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,account_domain)
SELECT 101,'reviewer@platform.localhost',NULL,'ACTIVE','PLATFORM'
 WHERE (SELECT reviewer_absent FROM iam002_persisted_mch003_change)
@@

INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
SELECT 1,1001,2000,1000 WHERE (SELECT reviewer_absent FROM iam002_persisted_mch003_change)
@@

INSERT INTO iam_permission_change_outbox(
    id,tenant_id,aggregate_type,aggregate_ref,event_type,payload,
    aggregate_version,schema_version,partition_key,trace_id
)
SELECT nextval('iam_id_seq'),1,'MEMBERSHIP','1001','PERMISSION_VERSION_CHANGED',
       jsonb_build_object('tenantId',1,'membershipId',1001,
                          'permissionVersion',reviewer_version,
                          'reason','LOCAL_MCH003_BOOTSTRAP'),
       reviewer_version,1,'1:1001','local-mch003-bootstrap'
  FROM iam002_persisted_mch003_change WHERE reviewer_absent
@@

INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
SELECT 'REGISTRATION_AEAD','mch-registration-aead-v1','AES-256-GCM',
       decode(repeat('a2',12),'hex')
 WHERE (SELECT merchant_absent FROM iam002_persisted_mch003_change)
   AND NOT EXISTS (SELECT 1 FROM merchant_protected_nonce
                    WHERE purpose='REGISTRATION_AEAD'
                      AND key_id='mch-registration-aead-v1'
                      AND nonce=decode(repeat('a2',12),'hex'))
@@

INSERT INTO merchant(
    id,tenant_id,account_domain,merchant_code,legal_name,display_name,
    registration_country,registration_number_masked,registration_fingerprint,
    registration_search_key_id,registration_fingerprint_algorithm,
    registration_normalization_version,registration_ciphertext,registration_nonce,
    registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
    registration_nonce_purpose,status,row_version,submitted_at,reviewed_at
)
SELECT 9000,2,'MERCHANT','MCH_LOCAL_SELF','Local Merchant Legal Name','Local Merchant',
       'SG','********0002',decode(repeat('a1',32),'hex'),
       'mch-registration-search-v1','HMAC-SHA-256',1,decode('010203','hex'),
       decode(repeat('a2',12),'hex'),decode(repeat('a3',16),'hex'),
       'mch-registration-aead-v1','AES-256-GCM','REGISTRATION_AEAD',
       'ACTIVE',0,now(),now()
 WHERE (SELECT merchant_absent FROM iam002_persisted_mch003_change)
@@

DO $$
BEGIN
    IF NOT pg_temp.iam002_persisted_reviewer_is_exact()
       OR NOT pg_temp.iam002_persisted_candidate_lineage_is_exact(TRUE)
       OR (SELECT count(*) FROM merchant
            WHERE tenant_id=2 AND account_domain='MERCHANT')<>1 THEN
        RAISE EXCEPTION 'Local bootstrap failed: persisted MCH-003 runtime is incomplete';
    END IF;
END
$$
@@

SELECT setval('iam_id_seq',GREATEST(10000,(SELECT last_value FROM iam_id_seq),
    COALESCE((SELECT max(id) FROM iam_user),0),COALESCE((SELECT max(id) FROM iam_tenant),0),
    COALESCE((SELECT max(id) FROM iam_membership),0),COALESCE((SELECT max(id) FROM merchant),0),
    COALESCE((SELECT max(id) FROM iam_permission_change_outbox),0)),true)
@@

DROP FUNCTION pg_temp.iam002_persisted_candidate_lineage_is_exact(BOOLEAN)
@@
DROP FUNCTION pg_temp.iam002_persisted_reviewer_is_exact()
@@
DROP FUNCTION pg_temp.iam002_persisted_platform_mch003_is_exact()
@@
-- IAM002_PERSISTED_MCH003_END
