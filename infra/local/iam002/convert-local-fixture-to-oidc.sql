\set ON_ERROR_STOP on

BEGIN;

SELECT pg_advisory_xact_lock(hashtextextended('payment-platform:iam002-local-oidc-fixture', 0));

CREATE TEMPORARY TABLE iam002_expected_identity ON COMMIT DROP AS
SELECT expected.user_id,
       expected.tenant_id,
       expected.membership_id,
       expected.account_domain,
       expected.local_subject,
       expected.issuer,
       expected.target_subject,
       expected.entry_host,
       user_account.identity_version,
       membership.session_version,
       membership.permission_version
  FROM (VALUES
      (100::bigint, 1::bigint, 1000::bigint, 'PLATFORM'::varchar,
       'admin@platform.localhost'::varchar, 'http://127.0.0.1:18080/realms/PLATFORM'::varchar,
       '10000000-0000-4000-8000-000000000100'::varchar, 'platform.localhost'::varchar),
      (200::bigint, 2::bigint, 2100::bigint, 'MERCHANT'::varchar,
       'admin@merchant.localhost'::varchar, 'http://127.0.0.1:18080/realms/MERCHANT'::varchar,
       '20000000-0000-4000-8000-000000000200'::varchar, 'merchant.localhost'::varchar),
      (300::bigint, 3::bigint, 3100::bigint, 'AGENT'::varchar,
       'admin@agent.localhost'::varchar, 'http://127.0.0.1:18080/realms/AGENT'::varchar,
       '30000000-0000-4000-8000-000000000300'::varchar, 'agent.localhost'::varchar)
  ) AS expected(user_id, tenant_id, membership_id, account_domain, local_subject,
                issuer, target_subject, entry_host)
  JOIN iam_user user_account ON user_account.id = expected.user_id
  JOIN iam_membership membership
    ON membership.id = expected.membership_id
   AND membership.user_id = expected.user_id
   AND membership.tenant_id = expected.tenant_id
   AND membership.account_domain = expected.account_domain;

DO $$
DECLARE
    converted BOOLEAN;
    local_ready BOOLEAN;
    changed INTEGER;
BEGIN
    IF (SELECT count(*) FROM iam002_expected_identity) <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: expected fixture identities are incomplete';
    END IF;

    SELECT count(*) = 3
      INTO converted
      FROM iam002_expected_identity expected
      JOIN iam_user user_account
        ON user_account.id = expected.user_id
       AND user_account.account_domain = expected.account_domain
       AND user_account.idp_issuer = expected.issuer
       AND user_account.idp_subject = expected.target_subject
       AND user_account.idp_provisioning_status = 'PROVISIONED'
       AND user_account.status = 'ACTIVE'
       AND user_account.identity_version = expected.identity_version
      JOIN iam_authentication_credential credential
        ON credential.user_id = expected.user_id
       AND credential.account_domain = expected.account_domain
       AND credential.status = 'ACTIVE'
       AND credential.password_hash IS NULL
      JOIN iam_membership membership
        ON membership.id = expected.membership_id
       AND membership.session_version = expected.session_version
       AND membership.permission_version = expected.permission_version
      JOIN iam_tenant_entry_host entry
        ON entry.entry_host = expected.entry_host
       AND entry.account_domain = expected.account_domain
       AND entry.tenant_id = expected.tenant_id
       AND entry.status = 'ACTIVE';

    IF converted THEN
        RETURN;
    END IF;

    SELECT count(*) = 3
      INTO local_ready
      FROM iam002_expected_identity expected
      JOIN iam_user user_account
        ON user_account.id = expected.user_id
       AND user_account.account_domain = expected.account_domain
       AND user_account.idp_issuer = 'local'
       AND user_account.idp_subject = expected.local_subject
       AND user_account.idp_provisioning_status = 'LOCAL_ONLY'
       AND user_account.status = 'ACTIVE'
      JOIN iam_authentication_credential credential
        ON credential.user_id = expected.user_id
       AND credential.account_domain = expected.account_domain
       AND credential.status = 'ACTIVE'
       AND credential.password_hash IS NOT NULL;

    IF NOT local_ready THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: fixture is neither exact local nor exact OIDC state';
    END IF;
    IF EXISTS (SELECT 1 FROM iam_tenant_entry_host) THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: entry host table is not empty';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM iam_user user_account
          JOIN iam002_expected_identity expected
            ON user_account.idp_issuer = expected.issuer
           AND user_account.idp_subject = expected.target_subject
         WHERE user_account.id <> expected.user_id
    ) THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: target issuer and subject already exist';
    END IF;

    UPDATE iam_user user_account
       SET idp_issuer = expected.issuer,
           idp_subject = expected.target_subject,
           idp_provisioning_status = 'PROVISIONED',
           updated_at = now(),
           row_version = user_account.row_version + 1
      FROM iam002_expected_identity expected
     WHERE user_account.id = expected.user_id
       AND user_account.account_domain = expected.account_domain
       AND user_account.idp_issuer = 'local'
       AND user_account.idp_subject = expected.local_subject
       AND user_account.idp_provisioning_status = 'LOCAL_ONLY'
       AND user_account.identity_version = expected.identity_version;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: identity update was not exact';
    END IF;

    UPDATE iam_authentication_credential credential
       SET password_hash = NULL,
           updated_at = now(),
           row_version = credential.row_version + 1
      FROM iam002_expected_identity expected
     WHERE credential.user_id = expected.user_id
       AND credential.account_domain = expected.account_domain
       AND credential.status = 'ACTIVE'
       AND credential.password_hash IS NOT NULL;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: credential update was not exact';
    END IF;

    INSERT INTO iam_tenant_entry_host(entry_host, account_domain, tenant_id, status)
    SELECT entry_host, account_domain, tenant_id, 'ACTIVE'
      FROM iam002_expected_identity;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: entry host insert was not exact';
    END IF;

    IF (SELECT count(*)
          FROM iam002_expected_identity expected
          JOIN iam_user user_account
            ON user_account.id = expected.user_id
           AND user_account.idp_issuer = expected.issuer
           AND user_account.idp_subject = expected.target_subject
           AND user_account.idp_provisioning_status = 'PROVISIONED'
           AND user_account.identity_version = expected.identity_version
          JOIN iam_authentication_credential credential
            ON credential.user_id = expected.user_id
           AND credential.status = 'ACTIVE'
           AND credential.password_hash IS NULL
          JOIN iam_membership membership
            ON membership.id = expected.membership_id
           AND membership.session_version = expected.session_version
           AND membership.permission_version = expected.permission_version
          JOIN iam_tenant_entry_host entry
            ON entry.entry_host = expected.entry_host
           AND entry.account_domain = expected.account_domain
           AND entry.tenant_id = expected.tenant_id
           AND entry.status = 'ACTIVE') <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local conversion blocked: post-state verification failed';
    END IF;
END;
$$;

COMMIT;
