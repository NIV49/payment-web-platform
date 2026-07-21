-- Cross-owner-tenant access is a read-only product capability. Reject historical semantic
-- mismatches rather than silently rewriting permissions whose intended authority is unknown.
ALTER TABLE iam_permission
    ADD CONSTRAINT ck_iam_permission_related_party_read_action
    CHECK (
        cross_tenant_mode <> 'RELATED_PARTY_READ'
        OR action_code IN ('read', 'view')
    ) NOT VALID;

ALTER TABLE iam_permission
    VALIDATE CONSTRAINT ck_iam_permission_related_party_read_action;
