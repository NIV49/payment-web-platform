DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_role role_row
          JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
         WHERE role_row.deleted_at IS NULL
           AND (
               (SELECT count(*)
                  FROM iam_role_grant grant_row
                  JOIN iam_permission permission ON permission.id = grant_row.permission_id
                 WHERE grant_row.tenant_id = role_row.tenant_id
                   AND grant_row.role_id = role_row.id
                   AND permission.permission_code IN (
                       'backoffice:platform-access',
                       'backoffice:merchant-access',
                       'backoffice:agent-access'
                   )) <> 1
               OR NOT EXISTS (
                   SELECT 1
                     FROM iam_role_grant grant_row
                     JOIN iam_permission permission ON permission.id = grant_row.permission_id
                    WHERE grant_row.tenant_id = role_row.tenant_id
                      AND grant_row.role_id = role_row.id
                      AND grant_row.grant_key = 'system-backoffice-access'
                      AND grant_row.status = 'ACTIVE'
                      AND grant_row.valid_from IS NULL
                      AND grant_row.valid_until IS NULL
                      AND permission.status = 'ACTIVE'
                      AND permission.permission_code = CASE tenant.account_domain
                          WHEN 'PLATFORM' THEN 'backoffice:platform-access'
                          WHEN 'MERCHANT' THEN 'backoffice:merchant-access'
                          WHEN 'AGENT' THEN 'backoffice:agent-access'
                      END
                      AND (SELECT count(*) FROM iam_grant_dimension dimension
                            WHERE dimension.grant_id = grant_row.id) = 1
                      AND EXISTS (
                          SELECT 1 FROM iam_grant_dimension dimension
                           WHERE dimension.grant_id = grant_row.id
                             AND dimension.dimension_code = 'TENANT'
                             AND dimension.scope_mode = 'TENANT_ALL'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                            FROM iam_grant_target target
                            JOIN iam_grant_dimension dimension ON dimension.id = target.dimension_id
                           WHERE dimension.grant_id = grant_row.id
                      )
               )
           )
    ) THEN
        RAISE EXCEPTION 'IAM-001 V20 blocked: malformed protected backoffice access inventory';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM iam_role_grant source_grant
          JOIN iam_permission source_permission ON source_permission.id = source_grant.permission_id
          JOIN iam_role_grant conflicting_grant
            ON conflicting_grant.tenant_id = source_grant.tenant_id
           AND conflicting_grant.role_id = source_grant.role_id
           AND conflicting_grant.grant_key = 'legacy-backoffice-access-' || source_grant.id::text
           AND conflicting_grant.id <> source_grant.id
         WHERE source_grant.grant_key = 'system-backoffice-access'
           AND source_permission.permission_code NOT IN (
               'backoffice:platform-access',
               'backoffice:merchant-access',
               'backoffice:agent-access'
           )
    ) THEN
        RAISE EXCEPTION 'IAM-001 V20 blocked: deterministic legacy grant key already exists';
    END IF;
END
$$;

CREATE TEMPORARY TABLE iam_v20_renamed_grant ON COMMIT DROP AS
SELECT grant_row.id,
       grant_row.tenant_id,
       grant_row.role_id,
       permission.permission_code,
       grant_row.grant_key AS previous_grant_key,
       'legacy-backoffice-access-' || grant_row.id::text AS replacement_grant_key
  FROM iam_role_grant grant_row
  JOIN iam_permission permission ON permission.id = grant_row.permission_id
 WHERE grant_row.grant_key = 'system-backoffice-access'
   AND permission.permission_code NOT IN (
       'backoffice:platform-access',
       'backoffice:merchant-access',
       'backoffice:agent-access'
   );

UPDATE iam_role_grant grant_row
   SET grant_key = renamed.replacement_grant_key,
       updated_at = now(),
       row_version = grant_row.row_version + 1
  FROM iam_v20_renamed_grant renamed
 WHERE grant_row.id = renamed.id;

CREATE TEMPORARY TABLE iam_v20_affected_role ON COMMIT DROP AS
SELECT DISTINCT renamed.tenant_id, renamed.role_id
  FROM iam_v20_renamed_grant renamed
  JOIN iam_role role_row
    ON role_row.tenant_id = renamed.tenant_id
   AND role_row.id = renamed.role_id
 WHERE role_row.deleted_at IS NULL;

UPDATE iam_role role_row
   SET row_version = role_row.row_version + 1,
       updated_at = now()
 WHERE EXISTS (
     SELECT 1 FROM iam_v20_affected_role affected
      WHERE affected.tenant_id = role_row.tenant_id
        AND affected.role_id = role_row.id
 );

CREATE TEMPORARY TABLE iam_v20_changed_membership (
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
           JOIN iam_v20_affected_role affected
             ON affected.tenant_id = membership_role.tenant_id
            AND affected.role_id = membership_role.role_id
          WHERE membership_role.tenant_id = membership.tenant_id
            AND membership_role.membership_id = membership.id
     )
    RETURNING tenant_id, id, permission_version
)
INSERT INTO iam_v20_changed_membership(tenant_id, membership_id, permission_version)
SELECT tenant_id, id, permission_version FROM changed;

INSERT INTO iam_audit_event(
    id, tenant_id, operator_membership_id, target_type, target_ref,
    action_code, decision, reason_code, permission_code,
    before_value, after_value, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, NULL, 'ROLE_GRANT', id::text,
       'MIGRATE_RESERVED_GRANT_KEY', 'NOT_APPLICABLE', 'MIGRATION', permission_code,
       jsonb_build_object('migration', 'V20', 'grantKey', previous_grant_key),
       jsonb_build_object('migration', 'V20', 'grantKey', replacement_grant_key),
       'migration-v20'
  FROM iam_v20_renamed_grant;

INSERT INTO iam_permission_change_outbox(
    id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
    aggregate_version, schema_version, partition_key, trace_id
)
SELECT nextval('iam_id_seq'), tenant_id, 'MEMBERSHIP', membership_id::text,
       'PERMISSION_VERSION_CHANGED',
       jsonb_build_object('membershipId', membership_id::text,
                          'permissionVersion', permission_version,
                          'reason', 'V20_RESERVED_GRANT_KEY_CONVERGENCE'),
       permission_version, 1, tenant_id::text || ':' || membership_id::text,
       'migration-v20'
  FROM iam_v20_changed_membership;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_role role_row
          JOIN iam_tenant tenant ON tenant.id = role_row.tenant_id
         WHERE role_row.deleted_at IS NULL
           AND (
               (SELECT count(*)
                  FROM iam_role_grant grant_row
                 WHERE grant_row.tenant_id = role_row.tenant_id
                   AND grant_row.role_id = role_row.id
                   AND grant_row.grant_key = 'system-backoffice-access') <> 1
               OR NOT EXISTS (
                   SELECT 1
                     FROM iam_role_grant grant_row
                     JOIN iam_permission permission ON permission.id = grant_row.permission_id
                    WHERE grant_row.tenant_id = role_row.tenant_id
                      AND grant_row.role_id = role_row.id
                      AND grant_row.grant_key = 'system-backoffice-access'
                      AND grant_row.status = 'ACTIVE'
                      AND permission.permission_code = CASE tenant.account_domain
                          WHEN 'PLATFORM' THEN 'backoffice:platform-access'
                          WHEN 'MERCHANT' THEN 'backoffice:merchant-access'
                          WHEN 'AGENT' THEN 'backoffice:agent-access'
                      END
               )
           )
    ) THEN
        RAISE EXCEPTION 'IAM-001 V20 blocked: reserved backoffice grant convergence failed';
    END IF;
END
$$;

COMMENT ON COLUMN iam_role_grant.grant_key IS
    'system-backoffice-access is reserved exclusively for the canonical server-managed portal Grant; V20 renames historical tenant Grants deterministically';
