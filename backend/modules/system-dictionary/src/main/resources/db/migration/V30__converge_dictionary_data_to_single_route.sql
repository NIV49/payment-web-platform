DO $$
DECLARE
    common_type_id BIGINT;
    common_type_count INTEGER;
BEGIN
    PERFORM revision
      FROM sys_dictionary_catalog_revision
     WHERE singleton_id = 1
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V30 blocked: dictionary catalog revision singleton is missing';
    END IF;

    SELECT count(*), min(id)
      INTO common_type_count, common_type_id
      FROM sys_dictionary_type
     WHERE dict_type = 'SYS_COMMON_STATUS';

    IF common_type_count = 0 THEN
        common_type_id := nextval('iam_id_seq');
        INSERT INTO sys_dictionary_type(
            id, dict_type, dict_name, sort_order, remark
        ) VALUES (
            common_type_id, 'SYS_COMMON_STATUS', '系统-通用状态', 0, ''
        );
        INSERT INTO sys_dictionary_data(
            id, dictionary_type_id, label, value, color, sort_order, remark
        ) VALUES
          (nextval('iam_id_seq'), common_type_id, '启用', '1', 'success', 1, ''),
          (nextval('iam_id_seq'), common_type_id, '禁用', '0', 'error', 2, '');
        UPDATE sys_dictionary_catalog_revision
           SET revision = revision + 1,
               updated_at = now()
         WHERE singleton_id = 1;
    ELSIF common_type_count <> 1
       OR NOT EXISTS (
           SELECT 1
             FROM sys_dictionary_type type_row
            WHERE type_row.id = common_type_id
              AND type_row.dict_type = 'SYS_COMMON_STATUS'
              AND type_row.dict_name = '系统-通用状态'
              AND type_row.sort_order = 0
              AND type_row.remark = ''
              AND type_row.row_version = 0
              AND type_row.deleted_at IS NULL
       )
       OR (SELECT count(*) FROM sys_dictionary_data
            WHERE dictionary_type_id = common_type_id) <> 2
       OR (SELECT count(*)
             FROM sys_dictionary_data data_row
            WHERE data_row.dictionary_type_id = common_type_id
              AND data_row.remark = ''
              AND data_row.row_version = 0
              AND data_row.deleted_at IS NULL
              AND ((data_row.value = '1' AND data_row.label = '启用'
                    AND data_row.color = 'success' AND data_row.sort_order = 1)
                OR (data_row.value = '0' AND data_row.label = '禁用'
                    AND data_row.color = 'error' AND data_row.sort_order = 2))) <> 2 THEN
        RAISE EXCEPTION
            'V30 blocked: SYS_COMMON_STATUS must be absent or exactly match the canonical seed';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM iam_menu menu
          LEFT JOIN iam_tenant tenant ON tenant.id = menu.tenant_id
          LEFT JOIN iam_menu parent
            ON parent.tenant_id = menu.tenant_id AND parent.id = menu.parent_id
          LEFT JOIN iam_permission permission
            ON permission.id = menu.display_permission_id
         WHERE (menu.route_name = 'SystemDictionaryData'
                OR menu.route_path = '/system/dict/data/type/:dictType')
           AND ((
               menu.menu_type = 'PAGE'
               AND menu.menu_name = 'Dictionary Data Detail'
               AND menu.route_name = 'SystemDictionaryData'
               AND menu.route_path = '/system/dict/data/type/:dictType'
               AND menu.component_path = '/system/dict/data/list'
               AND menu.redirect_path IS NULL
               AND menu.sort_order = 161
               AND menu.auth_code IS NULL
               AND menu.status = 'ACTIVE'
               AND menu.system_managed
               AND menu.deleted_at IS NULL
               AND permission.permission_code = 'dictionary-data:view'
               AND tenant.account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')
               AND parent.route_name = 'System'
               AND parent.menu_type = 'DIRECTORY'
               AND parent.status = 'ACTIVE'
               AND parent.system_managed
               AND parent.deleted_at IS NULL
               AND menu.meta_json->>'title' = 'system.dictData.title'
               AND menu.meta_json->>'hideInMenu' = 'true'
               AND menu.meta_json->>'activePath' = CASE tenant.account_domain
                   WHEN 'PLATFORM' THEN '/system/dict'
                   ELSE '/system/dict/data'
               END
           ) IS NOT TRUE)
    ) THEN
        RAISE EXCEPTION
            'V30 blocked: legacy dictionary data route collides with a non-canonical menu';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM iam_menu child
          JOIN iam_menu menu
            ON menu.tenant_id = child.tenant_id AND menu.id = child.parent_id
         WHERE menu.route_name = 'SystemDictionaryData'
           AND menu.status = 'ACTIVE'
           AND menu.deleted_at IS NULL
           AND child.status = 'ACTIVE'
           AND child.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'V30 blocked: legacy dictionary data route has an active child';
    END IF;

    UPDATE iam_menu
       SET status = 'DISABLED',
           deleted_at = now(),
           updated_at = now(),
           row_version = row_version + 1
     WHERE route_name = 'SystemDictionaryData'
       AND route_path = '/system/dict/data/type/:dictType'
       AND menu_type = 'PAGE'
       AND system_managed
       AND status = 'ACTIVE'
       AND deleted_at IS NULL;
END
$$;
