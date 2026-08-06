ALTER TABLE iam_authentication_credential
    ADD CONSTRAINT uk_iam_authentication_domain_username
    UNIQUE USING INDEX ux_iam_authentication_domain_username_expand;
