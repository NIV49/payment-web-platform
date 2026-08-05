# Separate backoffice applications and production identity boundaries

Status: accepted.

Decision-ID: IAM-PRODUCTION-IDENTITY-BOUNDARY

## Context

[ADR-0004](0004-external-idp-and-application-authorization-boundary.md) assigns production authentication to an external OpenID Connect identity provider, while [ADR-0008](0008-isolate-three-backoffice-account-domains-and-sessions.md) separates PLATFORM, MERCHANT, and AGENT application accounts and sessions. Implementation proceeds in independently verifiable slices; accepting this ADR does not allow a partially implemented realm, revocation, CSRF, lifecycle, or recovery path to be described as production-ready.

The target topology uses three independently built and deployed frontend applications and three independently built and deployed backend services. Stable framework packages, Identity Core, PostgreSQL IAM tables, and one managed Keycloak cluster may still be shared. Sharing those components reduces duplication, but it also creates common administrative, storage, upgrade, and outage failure domains that must not be described as physical isolation.

## Decision

1. The deployable applications are `platform-admin`, `merchant-admin`, and `agent-admin`; the deployable backend services are `platform-admin-api`, `merchant-admin-api`, and `agent-admin-api`. Each owns its routes, HTTP surface, OIDC client, security configuration, release pipeline, rollback, metrics, and runtime secrets. Shared libraries cannot register another application's routes or endpoints implicitly.
2. One Keycloak cluster contains separate PLATFORM, MERCHANT, and AGENT realms, clients, issuers, credentials, MFA state, and audiences. No cross-realm SSO, identity brokering, credential sharing, or automatic account linking is allowed. The cluster is still shared infrastructure and therefore a shared control-plane and availability risk.
3. Browser authentication uses Authorization Code with PKCE through a backend-for-frontend flow. OIDC tokens remain server-side; a successful exchange creates only the account-domain-specific, host-only application Cookie session.
4. A trusted server-side entry maps the request Host to the account domain and tenant. Client `tenantId`, realm, portal, workspace, or return-host input cannot select or change the authorization workspace.
5. The following six security invariants are normative. IAM-002 and its Judges may strengthen them, but cannot remove, merge, or weaken them.

## Normative security invariants

### IAM-002-R1: Realm isolation is logical

Normative statement: PLATFORM, MERCHANT, and AGENT realms are logical security partitions inside one shared Keycloak infrastructure; they are not complete infrastructure isolation.

Realm-specific issuers, clients, audiences, users, credentials, and administrator roles limit ordinary cross-domain access. They do not isolate the realms from a compromised Keycloak cluster administrator, shared database or storage failure, bad cluster-wide upgrade, capacity exhaustion, or cluster outage. Any future claim of complete infrastructure isolation requires separate deployments, persistence, secrets, administration, backup, recovery, and a superseding ADR.

### IAM-002-R2: Local sessions follow external revocation

Normative statement: Every local application session must implement both validated Keycloak back-channel logout and per-request identity-version revocation; either signal invalidates the local session.

The local session records the exact issuer, subject, Keycloak session identifier, application User, account domain, tenant, Membership, and captured identity version. Each authenticated request compares the captured version with the authoritative application identity version in addition to the existing Membership and permission/session versions. A credential reset, identity disable, MFA recovery, subject unlink, or security response advances the identity version and revokes all affected application sessions.

The back-channel logout endpoint is server-to-server. It validates the Logout Token signature, exact issuer, intended client/audience, required logout event, issued time, session/subject binding, and replay before revoking sessions. Delivery failure, delay, or replay-cache failure cannot make back-channel logout the only revocation mechanism; per-request identity-version validation remains mandatory.

### IAM-002-R3: Origin is scoped to browser requests

Normative statement: Origin validation applies only to appropriate browser-facing requests; OIDC callbacks and server-to-server back-channel logout do not depend on Origin and must use protocol-specific validation.

State-changing same-origin browser APIs continue to validate their expected Origin as defense in depth. An OIDC callback is a cross-site protocol redirect and may legitimately have a missing or IdP Origin; it is authenticated with an exact redirect URI, one-time state, nonce, PKCE verifier, issuer, audience, authorization-code exchange, expiry, and replay protection. Back-channel logout authenticates its signed Logout Token. Neither endpoint becomes trusted because of an Origin value, and neither rejects an otherwise valid protocol message solely because Origin is absent.

### IAM-002-R4: Cookie sessions require CSRF protection

Normative statement: Every state-changing cookie-authenticated browser request must pass an independent CSRF control; SameSite cookies and Origin validation are defense in depth and cannot replace the CSRF control.

The application uses a server-generated synchronizer token bound to the authenticated session and requires it in a non-simple request header such as `X-CSRF-Token`. The token is rotated when authentication or privilege context changes and is never accepted from a query string. Login initiation and OIDC callback use their one-time OIDC transaction state; back-channel logout uses its signed Logout Token. GET and HEAD endpoints cannot perform application state changes.

### IAM-002-R5: User mapping uses issuer and subject

Normative statement: An application User is identified uniquely by the exact canonical issuer and subject pair; email, username, realm label, and account domain are not identity mapping keys.

The database preserves `UNIQUE(idp_issuer, idp_subject)`. The issuer is matched against an account-domain-specific allowlist, and the returned subject is treated as opaque and immutable. Email and username may change or collide across realms and are profile attributes only. Account domain, tenant, Membership, and permissions are validated after identity resolution and cannot substitute for the identity key.

### IAM-002-R6: MFA recovery revokes every credential path

Normative statement: MFA recovery must revoke the affected Keycloak credentials, every recovery code, every Keycloak session, and every application session before recovery can complete; partial failure remains fail closed and retries are idempotent.

MFA recovery is a durable state machine rather than a fictitious cross-system database transaction. It records an idempotency key and audit reason, blocks normal authentication for the affected application User, advances the application identity version, requests Keycloak credential and recovery-code invalidation, terminates every Keycloak session, and revokes every local session. The workflow reaches `COMPLETED` only after all required effects are confirmed. A timeout or partial response remains `RECOVERY_PENDING`, keeps the user blocked, raises an operational alert, and retries without restoring any old credential or session.

## Consequences

- The three realms reduce ordinary credential and issuer crossover but share Keycloak infrastructure risk. Availability, backup, restore, upgrade, administrator compromise, and capacity tests cover the complete cluster.
- Origin, OIDC state, PKCE, CSRF, Cookie attributes, back-channel logout, and identity versions solve different threats. Passing one control never disables another.
- The application never maps an IdP login by email or username and never infers identity from account domain alone.
- Keycloak owns passwords, MFA credentials, and recovery codes. The application owns account-domain mapping, Membership, authorization, local session versions, recovery orchestration state, and audit evidence.
- The repository now has three frontend applications and backend services, session-bound CSRF enforcement, per-request identity-version checks, and explicit OIDC/back-channel composition in all three backend roots. Real Keycloak integration, lifecycle version advancement, frontend OIDC cutover, complete MFA recovery, configuration-as-code, and formal Judges remain production blockers.

## Release and rollback

Production traffic remains blocked until independent contract, integration, adversarial, and process-boundary Judges cover all six invariants against immutable builds. Rollout begins with PLATFORM, followed by one MERCHANT tenant and one AGENT tenant. Any cross-realm acceptance, missing CSRF enforcement, stale session after logout/version change, ambiguous identity mapping, or partial MFA recovery reported as success stops rollout.

Before any new identity mapping or recovery state is written, code and realm configuration can be rolled back together. After new mappings or recovery workflows exist, rollback means stopping authentication and identity writes, preserving audit and recovery state, restoring compatible Keycloak/application configuration, and forward-fixing or restoring a complete coordinated snapshot. Redeploying an old binary that does not understand identity versions or recovery state is not a valid writable rollback.
