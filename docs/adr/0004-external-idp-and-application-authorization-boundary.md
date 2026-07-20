# Separate external authentication from application authorization

Status: accepted.

Production authentication belongs to Keycloak or an equivalent managed OpenID Connect identity provider. It owns passwords, password policy, credential recovery, MFA enrollment and primary authentication events. The payment platform owns tenant memberships, roles, permission codes, data grants, delegation limits, step-up requirements, approvals and business audit evidence.

The application maps a verified issuer-and-subject pair to its global User and then selects an active TenantMembership. Browser sessions may be represented by a short-lived server-side session, but Sa-Token and Redis are transport/session adapters rather than identity or authorization truth sources. Tenant, User, Credential and Membership status/version checks remain server-side and fail closed.

Local username/password bootstrap is permitted only in an explicit local or test profile until the OIDC adapter is delivered. It must never be enabled implicitly in production, and production database migrations must not create fixed credentials. The current local credential login is therefore a time-bounded architecture deviation, not the target production design.
