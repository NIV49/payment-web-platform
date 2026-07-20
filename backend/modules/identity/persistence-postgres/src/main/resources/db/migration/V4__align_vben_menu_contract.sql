-- Vben 5.7 backend routes consume locale keys, not translated display text.
WITH titles(menu_id, locale_key) AS (
    VALUES
      (6000, 'system.title'),
      (6001, 'system.user.title'),
      (6002, 'system.role.title'),
      (6003, 'system.menu.title'),
      (6004, 'system.dept.title')
)
UPDATE iam_menu menu
   SET meta_json = jsonb_set(menu.meta_json, '{title}', to_jsonb(titles.locale_key), true),
       updated_at = now(),
       row_version = row_version + 1
  FROM titles
 WHERE menu.tenant_id = 1
   AND menu.id = titles.menu_id;

-- The root route already owns BasicLayout. Current Vben catalog routes must not add a second layout.
UPDATE iam_menu
   SET component_path = NULL,
       updated_at = now(),
       row_version = row_version + 1
 WHERE tenant_id = 1
   AND id IN (6000, 6010)
   AND menu_type = 'DIRECTORY'
   AND component_path = 'BasicLayout';
