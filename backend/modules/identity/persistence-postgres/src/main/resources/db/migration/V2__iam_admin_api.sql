CREATE SEQUENCE IF NOT EXISTS iam_id_seq START WITH 10000 INCREMENT BY 1;

ALTER TABLE iam_user ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE iam_role ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE iam_department ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS route_name VARCHAR(128);
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS redirect_path VARCHAR(255);
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS auth_code VARCHAR(128);
ALTER TABLE iam_menu ADD COLUMN IF NOT EXISTS meta_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE iam_menu DROP CONSTRAINT IF EXISTS ck_iam_menu_type;
ALTER TABLE iam_menu ADD CONSTRAINT ck_iam_menu_type
    CHECK (menu_type IN ('DIRECTORY', 'PAGE', 'EMBEDDED', 'LINK', 'BUTTON'));

CREATE TABLE iam_authentication_credential (
    user_id          BIGINT PRIMARY KEY REFERENCES iam_user(id) ON DELETE CASCADE,
    username         VARCHAR(100) NOT NULL,
    password_hash    VARCHAR(255),
    status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_iam_authentication_username UNIQUE (username),
    CONSTRAINT ck_iam_authentication_username CHECK (username = lower(username)),
    CONSTRAINT ck_iam_authentication_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
);

-- Local bootstrap data contains no default password. The application hashes
-- PAYMENT_BOOTSTRAP_PASSWORD once when explicitly supplied by the operator.
INSERT INTO iam_tenant (id, tenant_code, tenant_name, tenant_type, status)
VALUES (1, 'platform', 'Platform Administration', 'PLATFORM', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_department (id, tenant_id, parent_id, department_code, department_name, status, remark)
VALUES (10, 1, NULL, 'head-office', 'Head Office', 'ACTIVE', 'Local bootstrap department')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_user (id, idp_issuer, idp_subject, display_name, status, remark)
VALUES (100, 'local', 'admin', 'Platform Administrator', 'ACTIVE', 'Local bootstrap administrator')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_membership (id, tenant_id, user_id, department_id, status)
VALUES (1000, 1, 100, 10, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_authentication_credential (user_id, username, password_hash, status)
VALUES (100, 'admin', NULL, 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO iam_role (id, tenant_id, role_code, role_name, applicable_tenant_type,
                      assignable, system_role, status, remark)
VALUES (2000, 1, 'platform-admin', 'Platform Administrator', 'PLATFORM', false, true, 'ACTIVE',
        'Local bootstrap administration role')
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_membership_role (tenant_id, membership_id, role_id, assigned_by)
VALUES (1, 1000, 2000, 1000)
ON CONFLICT DO NOTHING;

WITH definitions(id, code, resource, action) AS (
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
      (3014, 'department:manage', 'department', 'manage')
)
INSERT INTO iam_permission (id, permission_code, resource_code, action_code, risk_level,
                            required_dimensions, requires_step_up, requires_approval, status, description)
SELECT id, code, resource, action, 'NORMAL', ARRAY['TENANT']::VARCHAR(32)[], false, false,
       'ACTIVE', 'Administration permission'
FROM definitions
ON CONFLICT (id) DO NOTHING;

WITH grants AS (
    SELECT 4000 + row_number() OVER (ORDER BY id) AS grant_id, id AS permission_id,
           permission_code
      FROM iam_permission
     WHERE id BETWEEN 3001 AND 3014
)
INSERT INTO iam_role_grant (id, tenant_id, role_id, permission_id, grant_key, status,
                            created_by, updated_by)
SELECT grant_id, 1, 2000, permission_id, replace(permission_code, ':', '-'), 'ACTIVE', 1000, 1000
FROM grants
ON CONFLICT (role_id, permission_id, grant_key) DO NOTHING;

WITH grants AS (
    SELECT rg.id AS grant_id, 5000 + row_number() OVER (ORDER BY rg.id) AS dimension_id
      FROM iam_role_grant rg
     WHERE rg.tenant_id = 1 AND rg.role_id = 2000
)
INSERT INTO iam_grant_dimension (id, grant_id, dimension_code, scope_mode)
SELECT dimension_id, grant_id, 'TENANT', 'TENANT_ALL'
FROM grants
ON CONFLICT (grant_id, dimension_code) DO NOTHING;

INSERT INTO iam_menu (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                      component_path, sort_order, status, meta_json)
VALUES
  (6000, 1, NULL, 'DIRECTORY', 'System Management', 'System', '/system', 'BasicLayout', 100, 'ACTIVE',
   '{"title":"System Management","icon":"lucide:settings"}'::jsonb),
  (6001, 1, 6000, 'PAGE', 'User Management', 'SystemUser', '/system/user',
   '/system/user/list', 110, 'ACTIVE', '{"title":"User Management","icon":"lucide:users"}'::jsonb),
  (6002, 1, 6000, 'PAGE', 'Role Management', 'SystemRole', '/system/role',
   '/system/role/list', 120, 'ACTIVE', '{"title":"Role Management","icon":"lucide:shield-check"}'::jsonb),
  (6003, 1, 6000, 'PAGE', 'Menu Management', 'SystemMenu', '/system/menu',
   '/system/menu/list', 130, 'ACTIVE', '{"title":"Menu Management","icon":"lucide:menu"}'::jsonb),
  (6004, 1, 6000, 'PAGE', 'Department Management', 'SystemDept', '/system/dept',
   '/system/dept/list', 140, 'ACTIVE', '{"title":"Department Management","icon":"lucide:building-2"}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 2000, id FROM iam_menu WHERE tenant_id = 1 AND id BETWEEN 6000 AND 6004
ON CONFLICT DO NOTHING;

SELECT setval('iam_id_seq', GREATEST(10000, (SELECT max(v) FROM (
    SELECT max(id) v FROM iam_user UNION ALL SELECT max(id) FROM iam_membership
    UNION ALL SELECT max(id) FROM iam_role UNION ALL SELECT max(id) FROM iam_permission
    UNION ALL SELECT max(id) FROM iam_role_grant UNION ALL SELECT max(id) FROM iam_grant_dimension
    UNION ALL SELECT max(id) FROM iam_menu
) ids)), true);
