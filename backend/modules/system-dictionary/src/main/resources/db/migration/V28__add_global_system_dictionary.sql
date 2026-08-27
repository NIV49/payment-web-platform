CREATE TABLE sys_dictionary_catalog_revision (
    singleton_id SMALLINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sys_dictionary_revision_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_sys_dictionary_revision_nonnegative CHECK (revision >= 0)
);

INSERT INTO sys_dictionary_catalog_revision(singleton_id, revision) VALUES (1, 0);

CREATE TABLE sys_dictionary_type (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    dict_type VARCHAR(64) NOT NULL,
    dict_name VARCHAR(100) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    remark VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_sys_dictionary_type_code CHECK (
        dict_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_sys_dictionary_type_name CHECK (btrim(dict_name) <> ''),
    CONSTRAINT ck_sys_dictionary_type_sort CHECK (sort_order BETWEEN 0 AND 9999),
    CONSTRAINT ck_sys_dictionary_type_remark CHECK (char_length(remark) <= 500),
    CONSTRAINT ck_sys_dictionary_type_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uk_sys_dictionary_type_live
    ON sys_dictionary_type(dict_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dictionary_type_list
    ON sys_dictionary_type(sort_order, id) WHERE deleted_at IS NULL;

CREATE TABLE sys_dictionary_data (
    id BIGINT PRIMARY KEY DEFAULT nextval('iam_id_seq'),
    dictionary_type_id BIGINT NOT NULL REFERENCES sys_dictionary_type(id),
    label VARCHAR(100) NOT NULL,
    value VARCHAR(100) NOT NULL,
    color VARCHAR(32) NOT NULL DEFAULT 'default',
    sort_order INTEGER NOT NULL DEFAULT 0,
    remark VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_sys_dictionary_data_label CHECK (btrim(label) <> ''),
    CONSTRAINT ck_sys_dictionary_data_value CHECK (btrim(value) <> ''),
    CONSTRAINT ck_sys_dictionary_data_color CHECK (
        color IN ('default', 'processing', 'success', 'warning', 'error', 'purple')),
    CONSTRAINT ck_sys_dictionary_data_sort CHECK (sort_order BETWEEN 0 AND 9999),
    CONSTRAINT ck_sys_dictionary_data_remark CHECK (char_length(remark) <= 500),
    CONSTRAINT ck_sys_dictionary_data_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uk_sys_dictionary_data_live_value
    ON sys_dictionary_data(dictionary_type_id, value) WHERE deleted_at IS NULL;
CREATE INDEX idx_sys_dictionary_data_list
    ON sys_dictionary_data(dictionary_type_id, sort_order, id) WHERE deleted_at IS NULL;

COMMENT ON TABLE sys_dictionary_catalog_revision IS
    'Global committed dictionary revision used to version shared Redis cache keys';
COMMENT ON TABLE sys_dictionary_type IS
    'Global PLATFORM-owned dictionary type catalog shared read-only with all account domains';
COMMENT ON TABLE sys_dictionary_data IS
    'Global dictionary display values; never stores tenant-selected or authentication data';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM iam_permission
         WHERE id IN (3025, 3026, 3027, 3028, 3029, 3030, 3031, 3032)
            OR permission_code IN (
                'dictionary:view', 'dictionary:create', 'dictionary:update', 'dictionary:delete',
                'dictionary-data:view', 'dictionary-data:create',
                'dictionary-data:update', 'dictionary-data:delete')
    ) THEN
        RAISE EXCEPTION 'V28 blocked: reserved system dictionary permissions already exist';
    END IF;
END
$$;

INSERT INTO iam_permission (
    id, permission_code, resource_code, action_code, risk_level,
    required_dimensions, requires_step_up, requires_approval, status, description,
    cross_tenant_mode
)
VALUES
  (3025, 'dictionary:view', 'dictionary', 'view', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'View the global system dictionary type catalog', 'SAME_TENANT_ONLY'),
  (3026, 'dictionary:create', 'dictionary', 'create', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Create global system dictionary types from PLATFORM', 'SAME_TENANT_ONLY'),
  (3027, 'dictionary:update', 'dictionary', 'update', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Update global system dictionary types from PLATFORM', 'SAME_TENANT_ONLY'),
  (3028, 'dictionary:delete', 'dictionary', 'delete', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Soft-delete global system dictionary types from PLATFORM', 'SAME_TENANT_ONLY'),
  (3029, 'dictionary-data:view', 'dictionary-data', 'view', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Read global system dictionary display values', 'SAME_TENANT_ONLY'),
  (3030, 'dictionary-data:create', 'dictionary-data', 'create', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Create global system dictionary values from PLATFORM', 'SAME_TENANT_ONLY'),
  (3031, 'dictionary-data:update', 'dictionary-data', 'update', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Update global system dictionary values from PLATFORM', 'SAME_TENANT_ONLY'),
  (3032, 'dictionary-data:delete', 'dictionary-data', 'delete', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Soft-delete global system dictionary values from PLATFORM', 'SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE sys_v28_inserted_grant (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(128) NOT NULL
) ON COMMIT DROP;

WITH domain_permission(account_domain, permission_code) AS (
    VALUES
      ('PLATFORM', 'dictionary:view'),
      ('PLATFORM', 'dictionary:create'),
      ('PLATFORM', 'dictionary:update'),
      ('PLATFORM', 'dictionary:delete'),
      ('PLATFORM', 'dictionary-data:view'),
      ('PLATFORM', 'dictionary-data:create'),
      ('PLATFORM', 'dictionary-data:update'),
      ('PLATFORM', 'dictionary-data:delete'),
      ('MERCHANT', 'dictionary-data:view'),
      ('AGENT', 'dictionary-data:view')
), inserted AS (
    INSERT INTO iam_role_grant(
        id, tenant_id, role_id, permission_id, grant_key, status, created_by, updated_by
    )
    SELECT nextval('iam_id_seq'), role_row.tenant_id, role_row.id, permission.id,
           replace(permission.permission_code, ':', '-'), 'ACTIVE', NULL, NULL
      FROM iam_role role_row
      JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
      JOIN domain_permission domain_row ON domain_row.account_domain = tenant.account_domain
      JOIN iam_permission permission ON permission.permission_code = domain_row.permission_code
     WHERE role_row.system_role
       AND NOT role_row.assignable
       AND role_row.status = 'ACTIVE'
       AND role_row.deleted_at IS NULL
    RETURNING id, tenant_id, role_id, permission_id
)
INSERT INTO sys_v28_inserted_grant(id, tenant_id, role_id, permission_code)
SELECT inserted.id, inserted.tenant_id, inserted.role_id, permission.permission_code
  FROM inserted
  JOIN iam_permission permission ON permission.id = inserted.permission_id;

INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
SELECT nextval('iam_id_seq'), id, 'TENANT', 'TENANT_ALL'
  FROM sys_v28_inserted_grant;

CREATE TEMPORARY TABLE sys_v28_inserted_page (
    tenant_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL
) ON COMMIT DROP;

WITH inserted AS (
    INSERT INTO iam_menu(
        id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
        component_path, redirect_path, display_permission_id, sort_order,
        status, meta_json, system_managed
    )
    SELECT nextval('iam_id_seq'), tenant.id, parent.id, 'PAGE', 'Dictionary Management',
           'SystemDictionary', '/system/dict', '/system/dict/list', NULL,
           permission.id, 150, 'ACTIVE',
           '{"title":"system.dict.title","icon":"lucide:book-open"}'::jsonb, true
      FROM iam_tenant tenant
      JOIN iam_menu parent ON parent.tenant_id = tenant.id
       AND parent.route_name = 'System' AND parent.status = 'ACTIVE'
       AND parent.deleted_at IS NULL
      JOIN iam_permission permission ON permission.permission_code = 'dictionary:view'
     WHERE tenant.account_domain = 'PLATFORM' AND tenant.status = 'ACTIVE'
    RETURNING tenant_id, id
)
INSERT INTO sys_v28_inserted_page(tenant_id, menu_id)
SELECT tenant_id, id FROM inserted;

WITH inserted AS (
    INSERT INTO iam_menu(
        id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
        component_path, redirect_path, display_permission_id, sort_order,
        status, meta_json, system_managed
    )
    SELECT nextval('iam_id_seq'), tenant.id, parent.id, 'PAGE', 'Dictionary Data',
           'SystemDictionaryDataIndex', '/system/dict/data', '/system/dict/data/list', NULL,
           permission.id, 160, 'ACTIVE',
           '{"title":"system.dictData.title","icon":"lucide:list-tree"}'::jsonb, true
      FROM iam_tenant tenant
      JOIN iam_menu parent ON parent.tenant_id = tenant.id
       AND parent.route_name = 'System' AND parent.status = 'ACTIVE'
       AND parent.deleted_at IS NULL
      JOIN iam_permission permission ON permission.permission_code = 'dictionary-data:view'
     WHERE tenant.account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')
       AND tenant.status = 'ACTIVE'
    RETURNING tenant_id, id
)
INSERT INTO sys_v28_inserted_page(tenant_id, menu_id)
SELECT tenant_id, id FROM inserted;

WITH inserted AS (
    INSERT INTO iam_menu(
        id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
        component_path, redirect_path, display_permission_id, sort_order,
        status, meta_json, system_managed
    )
    SELECT nextval('iam_id_seq'), tenant.id, parent.id, 'PAGE', 'Dictionary Data Detail',
           'SystemDictionaryData', '/system/dict/data/type/:dictType',
           '/system/dict/data/list', NULL, permission.id, 161, 'ACTIVE',
           '{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict/data"}'::jsonb,
           true
      FROM iam_tenant tenant
      JOIN iam_menu parent ON parent.tenant_id = tenant.id
       AND parent.route_name = 'System' AND parent.status = 'ACTIVE'
       AND parent.deleted_at IS NULL
      JOIN iam_permission permission ON permission.permission_code = 'dictionary-data:view'
     WHERE tenant.account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')
       AND tenant.status = 'ACTIVE'
    RETURNING tenant_id, id
)
INSERT INTO sys_v28_inserted_page(tenant_id, menu_id)
SELECT tenant_id, id FROM inserted;

INSERT INTO iam_menu(
    id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
    component_path, redirect_path, sort_order, auth_code, status, meta_json, system_managed
)
SELECT nextval('iam_id_seq'), parent.tenant_id, parent.id, 'BUTTON', button.menu_name,
       button.route_name, NULL, NULL, NULL, button.sort_order, button.permission_code,
       'ACTIVE', jsonb_build_object('title', button.title_key), true
  FROM iam_menu parent
  CROSS JOIN (VALUES
      ('View Dictionaries', 'DictionaryView', 151, 'dictionary:view', 'system.dict.permission.view'),
      ('Create Dictionary', 'DictionaryCreate', 152, 'dictionary:create', 'system.dict.permission.create'),
      ('Update Dictionary', 'DictionaryUpdate', 153, 'dictionary:update', 'system.dict.permission.update'),
      ('Delete Dictionary', 'DictionaryDelete', 154, 'dictionary:delete', 'system.dict.permission.delete')
  ) AS button(menu_name, route_name, sort_order, permission_code, title_key)
 WHERE parent.route_name = 'SystemDictionary'
   AND parent.status = 'ACTIVE' AND parent.deleted_at IS NULL;

INSERT INTO iam_menu(
    id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
    component_path, redirect_path, sort_order, auth_code, status, meta_json, system_managed
)
SELECT nextval('iam_id_seq'), parent.tenant_id, parent.id, 'BUTTON', 'View Dictionary Data',
       'DictionaryDataView', NULL, NULL, NULL, 162, 'dictionary-data:view',
       'ACTIVE', '{"title":"system.dictData.permission.view"}'::jsonb, true
  FROM iam_menu parent
 WHERE parent.route_name = 'SystemDictionaryDataIndex'
   AND parent.status = 'ACTIVE' AND parent.deleted_at IS NULL;

INSERT INTO iam_menu(
    id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
    component_path, redirect_path, sort_order, auth_code, status, meta_json, system_managed
)
SELECT nextval('iam_id_seq'), parent.tenant_id, parent.id, 'BUTTON', button.menu_name,
       button.route_name, NULL, NULL, NULL, button.sort_order, button.permission_code,
       'ACTIVE', jsonb_build_object('title', button.title_key), true
  FROM iam_menu parent
  JOIN iam_tenant tenant ON tenant.id = parent.tenant_id AND tenant.account_domain = 'PLATFORM'
  CROSS JOIN (VALUES
      ('Create Dictionary Data', 'DictionaryDataCreate', 163,
       'dictionary-data:create', 'system.dictData.permission.create'),
      ('Update Dictionary Data', 'DictionaryDataUpdate', 164,
       'dictionary-data:update', 'system.dictData.permission.update'),
      ('Delete Dictionary Data', 'DictionaryDataDelete', 165,
       'dictionary-data:delete', 'system.dictData.permission.delete')
  ) AS button(menu_name, route_name, sort_order, permission_code, title_key)
 WHERE parent.route_name = 'SystemDictionaryDataIndex'
   AND parent.status = 'ACTIVE' AND parent.deleted_at IS NULL;

INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
SELECT role_row.tenant_id, role_row.id, page.menu_id
  FROM iam_role role_row
  JOIN sys_v28_inserted_page page ON page.tenant_id = role_row.tenant_id
 WHERE role_row.system_role AND NOT role_row.assignable
   AND role_row.status = 'ACTIVE' AND role_row.deleted_at IS NULL;

UPDATE iam_role role_row
   SET row_version = row_version + 1,
       updated_at = now()
 WHERE EXISTS (
     SELECT 1 FROM sys_v28_inserted_grant affected
      WHERE affected.tenant_id = role_row.tenant_id
        AND affected.role_id = role_row.id
 );

CREATE TEMPORARY TABLE sys_v28_changed_membership (
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
           JOIN sys_v28_inserted_grant affected
             ON affected.tenant_id = membership_role.tenant_id
            AND affected.role_id = membership_role.role_id
          WHERE membership_role.tenant_id = membership.tenant_id
            AND membership_role.membership_id = membership.id
     )
    RETURNING tenant_id, id, permission_version
)
INSERT INTO sys_v28_changed_membership(tenant_id, membership_id, permission_version)
SELECT tenant_id, id, permission_version FROM changed;

INSERT INTO iam_audit_event(
    id, tenant_id, operator_membership_id, target_type, target_ref,
    action_code, decision, reason_code, permission_code, after_value, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, NULL, 'ROLE_GRANTS', role_id::text,
       'MIGRATE_SYSTEM_DICTIONARY_ACCESS', 'NOT_APPLICABLE', 'MIGRATION', permission_code,
       jsonb_build_object('migration', 'V28', 'permissionCode', permission_code),
       'migration-v28'
  FROM sys_v28_inserted_grant;

INSERT INTO iam_permission_change_outbox(
    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
    aggregate_version, schema_version, partition_key, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, 'MEMBERSHIP', membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('membershipId', membership_id::text,
                          'permissionVersion', permission_version,
                          'reason', 'V28_SYSTEM_DICTIONARY_ACCESS'),
       permission_version, 1, tenant_id::text || ':' || membership_id::text,
       'migration-v28'
  FROM sys_v28_changed_membership;
