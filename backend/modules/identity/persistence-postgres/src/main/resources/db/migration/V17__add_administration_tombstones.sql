-- Soft deletion is forward-only: older binaries do not understand deleted_at.
ALTER TABLE iam_role
    ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE iam_department
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN system_managed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE iam_menu
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN system_managed BOOLEAN NOT NULL DEFAULT FALSE;

-- These identifiers belong to the guarded local fixture. The same values are
-- written explicitly by the local bootstrap for an empty post-V17 database.
UPDATE iam_department
   SET system_managed = TRUE
 WHERE tenant_id = 1
   AND id = 10
   AND department_code = 'head-office';

UPDATE iam_menu
   SET system_managed = TRUE
 WHERE tenant_id = 1
   AND (id BETWEEN 6000 AND 6004
        OR id BETWEEN 6010 AND 6012
        OR id BETWEEN 6020 AND 6040);

ALTER TABLE iam_role DROP CONSTRAINT uk_iam_role_name;
CREATE UNIQUE INDEX uk_iam_role_name
    ON iam_role (tenant_id, role_name)
    WHERE deleted_at IS NULL;

DROP INDEX uk_iam_menu_tenant_route_name_ci;
CREATE UNIQUE INDEX uk_iam_menu_tenant_route_name_ci
    ON iam_menu (tenant_id, lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name)))
    WHERE deleted_at IS NULL;

DROP INDEX uk_iam_menu_tenant_route_path;
CREATE UNIQUE INDEX uk_iam_menu_tenant_route_path
    ON iam_menu (
        tenant_id,
        lower(CASE
            WHEN route_path = '/' THEN '/'
            ELSE regexp_replace(route_path, '/+$', '')
        END)
    )
    WHERE route_path IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_iam_role_tenant_live
    ON iam_role (tenant_id, id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_iam_department_tenant_live
    ON iam_department (tenant_id, id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_iam_menu_tenant_live
    ON iam_menu (tenant_id, id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN iam_role.deleted_at IS
    'Forward-only soft-delete tombstone; old binaries must not run after this is populated';
COMMENT ON COLUMN iam_department.deleted_at IS
    'Forward-only soft-delete tombstone; old binaries must not run after this is populated';
COMMENT ON COLUMN iam_menu.deleted_at IS
    'Forward-only soft-delete tombstone; old binaries must not run after this is populated';
