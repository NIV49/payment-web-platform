DO $merchant_button_i18n$
DECLARE
    expected_count BIGINT;
    updated_count BIGINT;
BEGIN
    IF EXISTS (
        WITH expected(
            route_name, menu_name, auth_code, sort_order, old_title, new_title
        ) AS (
            VALUES
              ('MerchantReview','Merchant Review','merchant:review',202,
               'merchant.review','merchant.permission.review'),
              ('MerchantDisable','Merchant Disable','merchant:disable',203,
               'merchant.disable','merchant.permission.disable'),
              ('MerchantEnable','Merchant Enable','merchant:enable',204,
               'merchant.enable','merchant.permission.enable'),
              ('MerchantTerminate','Merchant Terminate','merchant:terminate',205,
               'merchant.terminate','merchant.permission.terminate'),
              ('MerchantEdit','Merchant Edit','merchant:amend',206,
               'merchant.edit','merchant.permission.edit'),
              ('MerchantCreate','Merchant Create','merchant:create',207,
               'merchant.create','merchant.permission.create')
        )
        SELECT 1
          FROM iam_menu button
          LEFT JOIN expected ON expected.route_name=button.route_name
          LEFT JOIN iam_menu parent
            ON parent.tenant_id=button.tenant_id AND parent.id=button.parent_id
          LEFT JOIN iam_tenant tenant ON tenant.id=button.tenant_id
         WHERE button.route_name IN (
                   'MerchantReview','MerchantDisable','MerchantEnable',
                   'MerchantTerminate','MerchantEdit','MerchantCreate')
           AND NOT (
               expected.route_name IS NOT NULL
               AND tenant.tenant_type='PLATFORM'
               AND tenant.account_domain='PLATFORM'
               AND button.menu_type='BUTTON'
               AND button.menu_name=expected.menu_name
               AND button.route_path IS NULL
               AND button.component_path IS NULL
               AND button.redirect_path IS NULL
               AND button.display_permission_id IS NULL
               AND button.sort_order=expected.sort_order
               AND button.auth_code=expected.auth_code
               AND button.status='ACTIVE'
               AND button.meta_json=jsonb_build_object('title',expected.old_title)
               AND button.system_managed
               AND button.deleted_at IS NULL
               AND parent.route_name='MerchantList'
               AND parent.menu_type='PAGE'
               AND parent.route_path='/merchant/list'
               AND parent.component_path='/merchant/list'
               AND parent.auth_code='merchant:view'
               AND parent.status='ACTIVE'
               AND parent.system_managed
               AND parent.deleted_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'V40 blocked: Merchant button menu is absent or modified';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM iam_menu page
          JOIN iam_tenant tenant ON tenant.id=page.tenant_id
         WHERE page.route_name='MerchantList'
           AND NOT (
               tenant.tenant_type='PLATFORM'
               AND tenant.account_domain='PLATFORM'
               AND page.menu_type='PAGE'
               AND page.route_path='/merchant/list'
               AND page.component_path='/merchant/list'
               AND page.auth_code='merchant:view'
               AND page.status='ACTIVE'
               AND page.system_managed
               AND page.deleted_at IS NULL
               AND (SELECT count(*)
                      FROM iam_menu button
                     WHERE button.tenant_id=page.tenant_id
                       AND button.parent_id=page.id
                       AND button.route_name IN (
                           'MerchantReview','MerchantDisable','MerchantEnable',
                           'MerchantTerminate','MerchantEdit','MerchantCreate'))=6)
    ) THEN
        RAISE EXCEPTION 'V40 blocked: MerchantList menu does not own six canonical buttons';
    END IF;

    SELECT count(*) INTO expected_count
      FROM iam_menu
     WHERE route_name IN (
         'MerchantReview','MerchantDisable','MerchantEnable',
         'MerchantTerminate','MerchantEdit','MerchantCreate');

    WITH expected(route_name,new_title) AS (
        VALUES
          ('MerchantReview','merchant.permission.review'),
          ('MerchantDisable','merchant.permission.disable'),
          ('MerchantEnable','merchant.permission.enable'),
          ('MerchantTerminate','merchant.permission.terminate'),
          ('MerchantEdit','merchant.permission.edit'),
          ('MerchantCreate','merchant.permission.create')
    )
    UPDATE iam_menu menu
       SET meta_json=jsonb_build_object('title',expected.new_title),
           updated_at=statement_timestamp(),
           row_version=menu.row_version+1
      FROM expected
     WHERE menu.route_name=expected.route_name;
    GET DIAGNOSTICS updated_count = ROW_COUNT;

    IF updated_count<>expected_count THEN
        RAISE EXCEPTION 'V40 blocked: Merchant button title update was incomplete';
    END IF;
END
$merchant_button_i18n$;
