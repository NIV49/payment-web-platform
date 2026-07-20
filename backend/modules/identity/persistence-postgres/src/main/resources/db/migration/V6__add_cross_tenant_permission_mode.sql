ALTER TABLE iam_permission
    ADD COLUMN cross_tenant_mode VARCHAR(32) NOT NULL DEFAULT 'SAME_TENANT_ONLY';

ALTER TABLE iam_permission
    ADD CONSTRAINT ck_iam_permission_cross_tenant_mode
        CHECK (cross_tenant_mode IN ('SAME_TENANT_ONLY', 'RELATED_PARTY_READ')),
    ADD CONSTRAINT ck_iam_fund_same_tenant
        CHECK (risk_level <> 'FUND' OR cross_tenant_mode = 'SAME_TENANT_ONLY');

COMMENT ON COLUMN iam_permission.cross_tenant_mode IS
    'Explicit opt-in for related-party read access; relationship evidence is still required at authorization time';
