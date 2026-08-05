# Keycloak IAM-002 baseline

The files under `realms/` are the reviewed bootstrap baseline for three logical
identity partitions. They do not turn one Keycloak cluster into complete
infrastructure isolation.

Production baseline image:

```text
quay.io/keycloak/keycloak:26.7.0@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13
```

Each realm has one confidential Authorization Code + PKCE BFF client and one
separate service-account client for the identity lifecycle relay. Direct Access
Grant and identity providers are disabled. Client secrets, callback URLs,
back-channel logout URLs, web origins, post-logout URLs, and SMTP coordinates
are environment substitutions; real secret values must never be committed.

The PLATFORM, MERCHANT, and AGENT realms require separate values for:

```text
PAYMENT_<DOMAIN>_OIDC_CLIENT_SECRET
PAYMENT_<DOMAIN>_KEYCLOAK_ADMIN_CLIENT_SECRET
PAYMENT_<DOMAIN>_OIDC_REDIRECT_URI
PAYMENT_<DOMAIN>_OIDC_BACKCHANNEL_LOGOUT_URI
PAYMENT_<DOMAIN>_OIDC_POST_LOGOUT_REDIRECT_URI
PAYMENT_<DOMAIN>_WEB_ORIGIN
```

SMTP uses `PAYMENT_KEYCLOAK_SMTP_HOST`, `PAYMENT_KEYCLOAK_SMTP_PORT`, and
`PAYMENT_KEYCLOAK_SMTP_FROM`. The checked-in no-auth SMTP shape is suitable only
for a trusted local relay. Production SMTP authentication and transport settings
must be supplied through an environment-specific, secret-managed overlay.

Run the repository configuration Judge before importing:

```bash
python3 -I scripts/check_iam002_keycloak_realms.py --repository-root .
```

For an empty validation instance, mount `realms/` read-only at
`/opt/keycloak/data/import` and start the pinned image with `start-dev
--import-realm`. `start-dev` is test-only. Startup import ignores an already
existing realm, so these files are not an update or drift-remediation mechanism.
Production remains blocked until the deployment system can apply reviewed realm
changes to existing realms, detect drift, rotate secrets, and prove backup and
restore against the production database topology.
