-- PLATFORM reaches dictionary data from Dictionary Management. Keep both data
-- routes registered, but hide them from left navigation and keep the parent
-- management page highlighted. MERCHANT and AGENT retain their read-only entry.
UPDATE iam_menu menu
   SET meta_json = '{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb
  FROM iam_tenant tenant
 WHERE tenant.id = menu.tenant_id
   AND tenant.account_domain = 'PLATFORM'
   AND menu.route_name IN ('SystemDictionaryDataIndex', 'SystemDictionaryData')
   AND menu.menu_type = 'PAGE'
   AND menu.system_managed
   AND menu.deleted_at IS NULL;
