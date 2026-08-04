ALTER TABLE iam_tenant ADD COLUMN account_domain VARCHAR(16);
ALTER TABLE iam_user ADD COLUMN account_domain VARCHAR(16);
ALTER TABLE iam_membership ADD COLUMN account_domain VARCHAR(16);
ALTER TABLE iam_authentication_credential ADD COLUMN account_domain VARCHAR(16);

UPDATE iam_tenant
SET account_domain = CASE tenant_type
    WHEN 'PLATFORM' THEN 'PLATFORM'
    WHEN 'AGENT' THEN 'AGENT'
    WHEN 'DIRECT_MERCHANT' THEN 'MERCHANT'
    WHEN 'INDIRECT_MERCHANT' THEN 'MERCHANT'
END;

DO $$
BEGIN
    IF EXISTS (
        SELECT u.id
        FROM iam_user u
        LEFT JOIN iam_membership m ON m.user_id = u.id
        LEFT JOIN iam_tenant t ON t.id = m.tenant_id
        GROUP BY u.id
        HAVING count(DISTINCT t.account_domain) <> 1
    ) THEN
        RAISE EXCEPTION 'IAM-001 migration blocked: every user must resolve to exactly one account domain';
    END IF;
END
$$;

UPDATE iam_user u
SET account_domain = resolved.account_domain
FROM (
    SELECT m.user_id, min(t.account_domain) AS account_domain
    FROM iam_membership m
    JOIN iam_tenant t ON t.id = m.tenant_id
    GROUP BY m.user_id
) resolved
WHERE resolved.user_id = u.id;

UPDATE iam_membership m
SET account_domain = t.account_domain
FROM iam_tenant t
WHERE t.id = m.tenant_id;

UPDATE iam_authentication_credential c
SET account_domain = u.account_domain
FROM iam_user u
WHERE u.id = c.user_id;

ALTER TABLE iam_tenant
    ADD CONSTRAINT ck_iam_tenant_account_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    ADD CONSTRAINT ck_iam_tenant_type_account_domain
        CHECK ((tenant_type = 'PLATFORM' AND account_domain = 'PLATFORM')
            OR (tenant_type = 'AGENT' AND account_domain = 'AGENT')
            OR (tenant_type IN ('DIRECT_MERCHANT', 'INDIRECT_MERCHANT') AND account_domain = 'MERCHANT')),
    ADD CONSTRAINT uk_iam_tenant_id_account_domain UNIQUE (id, account_domain);

ALTER TABLE iam_user
    ADD CONSTRAINT ck_iam_user_account_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    ADD CONSTRAINT uk_iam_user_id_account_domain UNIQUE (id, account_domain);

ALTER TABLE iam_membership
    ADD CONSTRAINT ck_iam_membership_account_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    ADD CONSTRAINT fk_iam_membership_tenant_domain
        FOREIGN KEY (tenant_id, account_domain)
        REFERENCES iam_tenant(id, account_domain),
    ADD CONSTRAINT fk_iam_membership_user_domain
        FOREIGN KEY (user_id, account_domain)
        REFERENCES iam_user(id, account_domain),
    ADD CONSTRAINT uk_iam_membership_domain_id UNIQUE (account_domain, id);

ALTER TABLE iam_authentication_credential
    ADD CONSTRAINT ck_iam_authentication_account_domain
        CHECK (account_domain IN ('PLATFORM', 'MERCHANT', 'AGENT')),
    ADD CONSTRAINT fk_iam_authentication_user_domain
        FOREIGN KEY (user_id, account_domain)
        REFERENCES iam_user(id, account_domain);

ALTER TABLE iam_tenant ALTER COLUMN account_domain SET NOT NULL;
ALTER TABLE iam_user ALTER COLUMN account_domain SET NOT NULL;
ALTER TABLE iam_membership ALTER COLUMN account_domain SET NOT NULL;
ALTER TABLE iam_authentication_credential ALTER COLUMN account_domain SET NOT NULL;

CREATE INDEX idx_iam_membership_domain_user
    ON iam_membership(account_domain, user_id, status);
