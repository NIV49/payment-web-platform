-- Keep every SQL `password_hash IS NOT NULL` subject check equivalent to the core
-- LoginCredentialPolicy. Historical corruption blocks the deployment for explicit repair.
ALTER TABLE iam_authentication_credential
    ADD CONSTRAINT ck_iam_authentication_bcrypt_hash
    CHECK (
        password_hash IS NULL
        OR password_hash ~ '^[$]2[aby][$](1[0-4])[$][./A-Za-z0-9]{53}$'
    ) NOT VALID;

ALTER TABLE iam_authentication_credential
    VALIDATE CONSTRAINT ck_iam_authentication_bcrypt_hash;
