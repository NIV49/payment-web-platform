-- A scope mode has meaning only for specific resource dimensions. Enforce the
-- same fail-closed matrix at rest that DimensionScope enforces in the domain.
ALTER TABLE iam_grant_dimension
    ADD CONSTRAINT ck_iam_grant_dimension_mode_compatibility
    CHECK (
        (dimension_code = 'TENANT' AND scope_mode = 'TENANT_ALL')
        OR (dimension_code = 'OWNER' AND scope_mode = 'SELF')
        OR (dimension_code = 'DEPARTMENT' AND scope_mode IN (
            'SELF', 'DEPARTMENT', 'DEPARTMENT_AND_CHILDREN', 'SPECIFIED'))
        OR (dimension_code = 'CUSTOMER' AND scope_mode IN ('ASSIGNED', 'SPECIFIED'))
        OR (dimension_code = 'MERCHANT' AND scope_mode IN (
            'ASSIGNED', 'SPECIFIED', 'RELATION_CURRENT', 'RELATION_AT_EVENT'))
        OR (dimension_code = 'MARKET' AND scope_mode = 'SPECIFIED')
        OR (dimension_code = 'CHANNEL' AND scope_mode = 'SPECIFIED')
    ) NOT VALID;

-- Refuse the migration when legacy authorization rows are semantically
-- ambiguous; silently rewriting permissions would be a privilege decision.
ALTER TABLE iam_grant_dimension
    VALIDATE CONSTRAINT ck_iam_grant_dimension_mode_compatibility;
