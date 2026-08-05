CREATE UNIQUE INDEX CONCURRENTLY ux_iam_authentication_domain_username_expand
    ON iam_authentication_credential(account_domain, username);
