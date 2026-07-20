INSERT INTO iam_menu (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                      component_path, redirect_path, sort_order, status, meta_json)
VALUES
  (6010, 1, NULL, 'DIRECTORY', 'Dashboard', 'Dashboard', '/dashboard',
   'BasicLayout', '/dashboard/analytics', -100, 'ACTIVE',
   '{"title":"page.dashboard.title","icon":"lucide:layout-dashboard","order":-1}'::jsonb),
  (6011, 1, 6010, 'PAGE', 'Analytics', 'Analytics', '/dashboard/analytics',
   '/dashboard/analytics/index', NULL, -90, 'ACTIVE',
   '{"title":"page.dashboard.analytics","icon":"lucide:area-chart","affixTab":true}'::jsonb),
  (6012, 1, 6010, 'PAGE', 'Workspace', 'Workspace', '/dashboard/workspace',
   '/dashboard/workspace/index', NULL, -80, 'ACTIVE',
   '{"title":"page.dashboard.workspace","icon":"carbon:workspace"}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam_role_menu (tenant_id, role_id, menu_id)
SELECT 1, 2000, id
  FROM iam_menu
 WHERE tenant_id = 1 AND id IN (6010, 6011, 6012)
ON CONFLICT DO NOTHING;

SELECT setval('iam_id_seq', GREATEST(10000, (SELECT max(id) FROM iam_menu)), true);
