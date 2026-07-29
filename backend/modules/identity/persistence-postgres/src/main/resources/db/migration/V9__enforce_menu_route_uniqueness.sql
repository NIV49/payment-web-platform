-- The Vben backend-route contract treats route names and paths as stable keys.
-- Fail explicitly instead of guessing how to merge pre-existing duplicates.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_menu
         GROUP BY tenant_id, lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V9 menu route uniqueness refused: duplicate route names exist';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM iam_menu
         WHERE route_path IS NOT NULL
         GROUP BY tenant_id,
                  lower(CASE
                      WHEN route_path = '/' THEN '/'
                      ELSE regexp_replace(route_path, '/+$', '')
                  END)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V9 menu route uniqueness refused: duplicate route paths exist';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_iam_menu_tenant_route_name_ci
    ON iam_menu (tenant_id, lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name)));

CREATE UNIQUE INDEX uk_iam_menu_tenant_route_path
    ON iam_menu (
        tenant_id,
        lower(CASE
            WHEN route_path = '/' THEN '/'
            ELSE regexp_replace(route_path, '/+$', '')
        END)
    )
    WHERE route_path IS NOT NULL;
