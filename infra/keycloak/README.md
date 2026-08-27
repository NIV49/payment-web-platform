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
The login clients explicitly include Keycloak's `basic` and `acr` default client
scopes so ID tokens carry the required `sub`, `auth_time`, and `acr` claims.

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
--features=recovery-codes --import-realm`. Recovery codes are a supported
Keycloak 26.7 feature; this does not enable the `preview` feature set.
`start-dev` is test-only. Startup import ignores an already
existing realm, so these files are not an update or drift-remediation mechanism.
Production remains blocked until the deployment system can apply reviewed realm
changes to existing realms, detect drift, rotate secrets, and prove backup and
restore against the production database topology.

## Isolated local runtime

The repository includes a local-only runtime with three independent frontend
processes, three independent backend processes, PostgreSQL, Valkey, the pinned
Keycloak image, and Mailpit. Generated credentials and browser state stay under
the ignored, mode-restricted `.local/iam002/` directory.

From the repository root:

```bash
python3 scripts/dev/iam002_local.py test
python3 scripts/dev/iam002_local.py up
python3 scripts/dev/iam002_local.py status
python3 scripts/dev/iam002_local.py verify
python3 scripts/dev/iam002_local.py login-info
python3 scripts/dev/iam002_local.py down
```

`up` defaults to the local username/password mode. All three applications render
the same login form, use account-domain-specific copy, and prefill their isolated
development credentials. The generated passwords are never checked in or passed
to production builds. Use `up --auth-mode oidc` when the full Keycloak flow is the
test target.

`verify` requires Google Chrome and follows the mode selected by `up`. In the
default local mode it submits each prefilled form and verifies the three
independent sessions and host-only Cookie boundaries. In explicit OIDC mode it
exercises real browser OIDC login, step-up, tenant bootstrap, invitation and
required actions, TOTP and recovery-code revocation, Keycloak and application
session revocation, back-channel logout, CSRF, and Cookie boundaries. `down`
stops only this isolated runtime and preserves its named volumes.

`login-info` explicitly prints the local-only usernames and generated passwords
from mode-restricted files under `.local/iam002/`. In OIDC mode it also prints
current TOTP codes; run it again when a code expires. The additional MERCHANT
OIDC browser-verification account is available after `verify` has created and
activated its invitation fixture.

Local endpoints:

```text
Platform: http://platform.localhost:15999
Merchant: http://merchant-e2e.localhost:16002
Agent:    http://agent.localhost:16001
Keycloak: http://127.0.0.1:18080
Mailpit:  http://127.0.0.1:18025
```

This is local functional evidence, not a production topology or release gate.
