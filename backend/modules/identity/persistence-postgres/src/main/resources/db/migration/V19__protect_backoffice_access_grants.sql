DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_permission
         WHERE id IN (3022, 3023, 3024)
            OR permission_code IN (
                'backoffice:platform-access',
                'backoffice:merchant-access',
                'backoffice:agent-access'
            )
    ) THEN
        RAISE EXCEPTION 'IAM-001 migration blocked: reserved backoffice access permissions already exist';
    END IF;
END
$$;

INSERT INTO iam_permission (
    id, permission_code, resource_code, action_code, risk_level,
    required_dimensions, requires_step_up, requires_approval, status, description,
    cross_tenant_mode
)
VALUES
  (3022, 'backoffice:platform-access', 'backoffice', 'platform-access', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Server-managed access to the platform operations backoffice', 'SAME_TENANT_ONLY'),
  (3023, 'backoffice:merchant-access', 'backoffice', 'merchant-access', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Server-managed access to the merchant backoffice', 'SAME_TENANT_ONLY'),
  (3024, 'backoffice:agent-access', 'backoffice', 'agent-access', 'NORMAL',
   ARRAY['TENANT']::VARCHAR(32)[], false, false, 'ACTIVE',
   'Server-managed access to the agent backoffice', 'SAME_TENANT_ONLY');

CREATE TEMPORARY TABLE iam_v19_inserted_portal_grant (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
) ON COMMIT DROP;

WITH portal_permission(account_domain, permission_id) AS (
    VALUES
      ('PLATFORM', 3022::BIGINT),
      ('MERCHANT', 3023::BIGINT),
      ('AGENT', 3024::BIGINT)
), inserted AS (
    INSERT INTO iam_role_grant(
        id, tenant_id, role_id, permission_id, grant_key, status, created_by, updated_by
    )
    SELECT nextval('iam_id_seq'), role_row.tenant_id, role_row.id,
           portal_permission.permission_id, 'system-backoffice-access', 'ACTIVE', NULL, NULL
      FROM iam_role role_row
      JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
      JOIN portal_permission ON portal_permission.account_domain = tenant.account_domain
     WHERE role_row.deleted_at IS NULL
    RETURNING id, tenant_id, role_id
)
INSERT INTO iam_v19_inserted_portal_grant(id, tenant_id, role_id)
SELECT id, tenant_id, role_id FROM inserted;

INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
SELECT nextval('iam_id_seq'), id, 'TENANT', 'TENANT_ALL'
  FROM iam_v19_inserted_portal_grant;

UPDATE iam_role role_row
   SET row_version = row_version + 1,
       updated_at = now()
 WHERE EXISTS (
     SELECT 1 FROM iam_v19_inserted_portal_grant affected
      WHERE affected.tenant_id = role_row.tenant_id
        AND affected.role_id = role_row.id
 );

CREATE TEMPORARY TABLE iam_v19_changed_membership (
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
           JOIN iam_v19_inserted_portal_grant affected
             ON affected.tenant_id = membership_role.tenant_id
            AND affected.role_id = membership_role.role_id
          WHERE membership_role.tenant_id = membership.tenant_id
            AND membership_role.membership_id = membership.id
     )
    RETURNING tenant_id, id, permission_version
)
INSERT INTO iam_v19_changed_membership(tenant_id, membership_id, permission_version)
SELECT tenant_id, id, permission_version FROM changed;

INSERT INTO iam_audit_event(
    id, tenant_id, operator_membership_id, target_type, target_ref,
    action_code, decision, reason_code, permission_code, after_value, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, NULL, 'ROLE_GRANTS', role_id::text,
       'MIGRATE_PROTECTED_BACKOFFICE_ACCESS', 'NOT_APPLICABLE', 'MIGRATION', NULL,
       jsonb_build_object('migration', 'V19', 'grantKey', 'system-backoffice-access'),
       'migration-v19'
  FROM iam_v19_inserted_portal_grant;

INSERT INTO iam_permission_change_outbox(
    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
    aggregate_version, schema_version, partition_key, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, 'MEMBERSHIP', membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('membershipId', membership_id::text,
                          'permissionVersion', permission_version,
                          'reason', 'V19_PROTECTED_BACKOFFICE_ACCESS'),
       permission_version, 1, tenant_id::text || ':' || membership_id::text,
       'migration-v19'
  FROM iam_v19_changed_membership;

COMMENT ON COLUMN iam_role_grant.grant_key IS
    'system-backoffice-access is server-managed and cannot be replaced by the tenant grant editor';
