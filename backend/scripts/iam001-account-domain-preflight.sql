-- Run against the production schema before applying V18. This statement is read-only.
WITH resolved_memberships AS (
    SELECT
        user_account.id AS user_id,
        membership.id AS membership_id,
        CASE tenant.tenant_type
            WHEN 'PLATFORM' THEN 'PLATFORM'
            WHEN 'DIRECT_MERCHANT' THEN 'MERCHANT'
            WHEN 'INDIRECT_MERCHANT' THEN 'MERCHANT'
            WHEN 'AGENT' THEN 'AGENT'
        END AS account_domain
    FROM iam_user user_account
    LEFT JOIN iam_membership membership ON membership.user_id = user_account.id
    LEFT JOIN iam_tenant tenant ON tenant.id = membership.tenant_id
), blocking_users AS (
    SELECT
        user_id,
        count(membership_id) AS membership_count,
        count(membership_id) FILTER (WHERE account_domain IS NULL) AS unresolved_membership_count,
        count(DISTINCT account_domain) AS account_domain_count,
        coalesce(
            string_agg(DISTINCT account_domain, ',' ORDER BY account_domain),
            ''
        ) AS observed_account_domains
    FROM resolved_memberships
    GROUP BY user_id
)
SELECT
    user_id,
    CASE
        WHEN membership_count = 0 THEN 'NO_MEMBERSHIP'
        WHEN unresolved_membership_count > 0 THEN 'UNRESOLVED_TENANT_TYPE'
        ELSE 'CROSS_ACCOUNT_DOMAIN'
    END AS issue_code,
    observed_account_domains,
    membership_count,
    unresolved_membership_count
FROM blocking_users
WHERE membership_count = 0
   OR unresolved_membership_count > 0
   OR account_domain_count <> 1
ORDER BY user_id;
