LOCK TABLE iam_menu IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT DISTINCT tenant.id AS tenant_id
          FROM iam_tenant tenant
          JOIN iam_menu menu ON menu.tenant_id = tenant.id
         WHERE tenant.account_domain IN ('MERCHANT', 'AGENT')
           AND (menu.route_name IN ('SystemDictionaryDataIndex', 'DictionaryDataView')
                OR menu.route_path = '/system/dict/data')
    LOOP
        IF (SELECT count(*)
              FROM iam_menu menu
             WHERE menu.tenant_id = target.tenant_id
               AND (menu.route_name = 'SystemDictionaryDataIndex'
                    OR menu.route_path = '/system/dict/data')) <> 1
           OR (SELECT count(*)
                 FROM iam_menu menu
                WHERE menu.tenant_id = target.tenant_id
                  AND menu.route_name = 'DictionaryDataView') <> 1 THEN
            RAISE EXCEPTION
                'V31 blocked: dictionary data menu footprint is partial or duplicated for tenant %',
                target.tenant_id;
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM iam_menu landing
              JOIN iam_menu parent
                ON parent.tenant_id = landing.tenant_id AND parent.id = landing.parent_id
              JOIN iam_permission permission ON permission.id = landing.display_permission_id
             WHERE landing.tenant_id = target.tenant_id
               AND landing.menu_type = 'PAGE'
               AND landing.menu_name = 'Dictionary Data'
               AND landing.route_name = 'SystemDictionaryDataIndex'
               AND landing.route_path = '/system/dict/data'
               AND landing.component_path = '/system/dict/data/list'
               AND landing.redirect_path IS NULL
               AND landing.sort_order = 160
               AND landing.auth_code IS NULL
               AND landing.system_managed
               AND landing.meta_json =
                   '{"title":"system.dictData.title","icon":"lucide:list-tree"}'::jsonb
               AND permission.permission_code = 'dictionary-data:view'
               AND parent.menu_type = 'DIRECTORY'
               AND parent.route_name = 'System'
               AND parent.system_managed
               AND ((landing.status = 'ACTIVE' AND landing.deleted_at IS NULL
                     AND landing.row_version = 0)
                    OR (landing.status = 'DISABLED' AND landing.deleted_at IS NOT NULL
                        AND landing.row_version = 1))
        ) OR NOT EXISTS (
            SELECT 1
              FROM iam_menu button
              JOIN iam_menu landing
                ON landing.tenant_id = button.tenant_id AND landing.id = button.parent_id
             WHERE button.tenant_id = target.tenant_id
               AND button.menu_type = 'BUTTON'
               AND button.menu_name = 'View Dictionary Data'
               AND button.route_name = 'DictionaryDataView'
               AND button.route_path IS NULL
               AND button.component_path IS NULL
               AND button.redirect_path IS NULL
               AND button.display_permission_id IS NULL
               AND button.sort_order = 162
               AND button.auth_code = 'dictionary-data:view'
               AND button.system_managed
               AND button.meta_json = '{"title":"system.dictData.permission.view"}'::jsonb
               AND landing.route_name = 'SystemDictionaryDataIndex'
               AND ((button.status = 'ACTIVE' AND button.deleted_at IS NULL
                     AND button.row_version = 0)
                    OR (button.status = 'DISABLED' AND button.deleted_at IS NOT NULL
                        AND button.row_version = 1))
        ) THEN
            RAISE EXCEPTION
                'V31 blocked: dictionary data menu collides with non-canonical state for tenant %',
                target.tenant_id;
        END IF;

        IF NOT (
            (EXISTS (
                SELECT 1 FROM iam_menu
                 WHERE tenant_id = target.tenant_id
                   AND route_name = 'SystemDictionaryDataIndex'
                   AND status = 'ACTIVE' AND deleted_at IS NULL AND row_version = 0)
             AND EXISTS (
                SELECT 1 FROM iam_menu
                 WHERE tenant_id = target.tenant_id
                   AND route_name = 'DictionaryDataView'
                   AND status = 'ACTIVE' AND deleted_at IS NULL AND row_version = 0))
            OR
            (EXISTS (
                SELECT 1 FROM iam_menu
                 WHERE tenant_id = target.tenant_id
                   AND route_name = 'SystemDictionaryDataIndex'
                   AND status = 'DISABLED' AND deleted_at IS NOT NULL AND row_version = 1)
             AND EXISTS (
                SELECT 1 FROM iam_menu
                 WHERE tenant_id = target.tenant_id
                   AND route_name = 'DictionaryDataView'
                   AND status = 'DISABLED' AND deleted_at IS NOT NULL AND row_version = 1))
        ) THEN
            RAISE EXCEPTION
                'V31 blocked: dictionary data menu lifecycle state is mixed for tenant %',
                target.tenant_id;
        END IF;

        IF EXISTS (
            SELECT 1
              FROM iam_menu child
              JOIN iam_menu landing
                ON landing.tenant_id = child.tenant_id AND landing.id = child.parent_id
             WHERE landing.tenant_id = target.tenant_id
               AND landing.route_name = 'SystemDictionaryDataIndex'
               AND child.route_name <> 'DictionaryDataView'
               AND child.status = 'ACTIVE'
               AND child.deleted_at IS NULL
        ) THEN
            RAISE EXCEPTION
                'V31 blocked: dictionary data landing has an active non-canonical child for tenant %',
                target.tenant_id;
        END IF;
    END LOOP;

    UPDATE iam_menu menu
       SET status = 'DISABLED',
           deleted_at = now(),
           updated_at = now(),
           row_version = menu.row_version + 1
      FROM iam_tenant tenant
     WHERE tenant.id = menu.tenant_id
       AND tenant.account_domain IN ('MERCHANT', 'AGENT')
       AND menu.route_name IN ('SystemDictionaryDataIndex', 'DictionaryDataView')
       AND menu.status = 'ACTIVE'
       AND menu.deleted_at IS NULL;
END
$$;
