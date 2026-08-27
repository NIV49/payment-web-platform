-- V40 is frozen after successful execution. This callback closes the
-- validation/update concurrency window without changing V40: immediately
-- before a pending V40 it holds the canonical IAM rows against DML for the
-- same Flyway migration transaction and validates the exact V39 input shape.

DO $v40_preflight$
DECLARE
    current_version TEXT;
BEGIN
    SELECT version INTO current_version
      FROM flyway_schema_history
     WHERE success
     ORDER BY installed_rank DESC
     LIMIT 1;

    IF current_version IS DISTINCT FROM '39'
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='40') THEN
        RETURN;
    END IF;

    LOCK TABLE iam_tenant, iam_menu IN SHARE ROW EXCLUSIVE MODE;

    IF EXISTS (
        WITH expected(
            route_name, menu_name, auth_code, sort_order, title_key
        ) AS (
            VALUES
              ('MerchantReview','Merchant Review','merchant:review',202,
               'merchant.review'),
              ('MerchantDisable','Merchant Disable','merchant:disable',203,
               'merchant.disable'),
              ('MerchantEnable','Merchant Enable','merchant:enable',204,
               'merchant.enable'),
              ('MerchantTerminate','Merchant Terminate','merchant:terminate',205,
               'merchant.terminate'),
              ('MerchantEdit','Merchant Edit','merchant:amend',206,
               'merchant.edit'),
              ('MerchantCreate','Merchant Create','merchant:create',207,
               'merchant.create')
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
               AND button.meta_json=jsonb_build_object('title',expected.title_key)
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
        RAISE EXCEPTION
            'V40 preflight blocked: Merchant button menu is absent or modified';
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
        RAISE EXCEPTION
            'V40 preflight blocked: MerchantList menu does not own six canonical buttons';
    END IF;
END
$v40_preflight$;
