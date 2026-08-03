# Isolate the three back-office account domains and sessions

Status: accepted.

Decision-ID: IAM-GLOBAL-USER-MULTI-TENANT

## Context

The platform-operations, merchant, and agent back offices authorize different organizations and expose different administrative risk. The prototype currently uses one browser entry, one API composition root, one Sa-Token login type and Cookie, one Redis namespace, and a login request that may select `tenantId`. That shape lets a browser influence the authorization workspace and cannot prove that a session issued for one back office is unusable by another.

The application `User` is a business account, not the canonical record for a natural person. Treating it as a cross-portal identity would couple credentials, status, memberships, role changes, and session revocation across independently administered account domains.

## Decision

1. `PLATFORM`, `MERCHANT`, and `AGENT` are separate account domains. `DIRECT_MERCHANT` and `INDIRECT_MERCHANT` tenants map to `MERCHANT`; `PLATFORM` and `AGENT` tenants map to the account domain with the same name.
2. One application `User` belongs to exactly one account domain and cannot hold a Membership in another account domain. A natural person who needs more than one back office uses separate application accounts. A future external IdP subject link may relate those accounts for identity proofing, but must not merge credentials, Memberships, RoleGrants, or sessions.
3. Multiple Memberships inside the same account domain remain legal. The browser cannot select a workspace with `tenantId` or an equivalent domain/workspace field. A trusted server entry or server-side context must resolve the authorization workspace. When the available trusted context cannot resolve exactly one ACTIVE Membership, authentication fails closed without disclosing membership existence.
4. Operations, merchant, and agent use independent browser entries and deployable API composition roots. Each root fixes its account domain, exact allowed Origin, Cookie name, Sa-Token login type/session realm, and Redis/cache namespace in server configuration. These values are not accepted from request bodies, queries, headers, routes, or browser storage.
5. Phase one uses an HttpOnly Cookie session and returns only the existing non-secret `cookie-session` browser marker. It has no bearer token. Any future token must carry and validate an account-domain-specific audience and may not be reused across roots.
6. Session state records the account domain. Every authenticated request verifies the root domain, session domain, Tenant/User/Credential/Membership ACTIVE state, and permission/session versions before authorization. Disable, role or grant revocation, and password changes invalidate old sessions.
7. Departments remain organization and data-scope inputs. Navigation comes from Role-to-Menu assignment; API and button authority comes from RoleGrant. Frontend visibility is never an authorization boundary.
8. Merchant and agent phase-one roots expose only login, logout, current user, dynamic menu, permission codes, health, and the IAM checks required to enforce this boundary. No payment, ledger, channel, settlement, or funds behavior is introduced by this decision.

## Consequences

- Username lookup is constrained by the server-fixed account domain. The existing global username uniqueness constraint remains during phase one; relaxing it requires a later expand/contract decision and is not required for isolation.
- Tenant, User, Credential, and Membership carry an account-domain invariant enforced in PostgreSQL. Historical cross-domain Users or Users whose domain cannot be derived block migration and require an explicit, audited account-splitting repair; migration code must not guess credential or audit ownership.
- The three frontends may share source and Vben framework packages, but each deployment artifact has its own title, storage namespace, API origin, entry URL, component allowlist, and build output. Frontend configuration is presentation and routing input only.
- Existing same-domain multi-Membership accounts are not deleted or merged. Until a trusted workspace resolver exists for such an account, the generic login entry rejects the ambiguous login.
- Independent Cookie names are necessary because browser Cookies are not isolated by port. Independent Sa-Token login types and Redis namespaces remain mandatory even if token values or numeric IDs collide.

## Migration And Rollback

The database change is forward-only and additive. Before schema cutover, run a deterministic preflight that reports every cross-domain or unresolved User and stop on any result. During cutover, freeze all IAM writes, deploy the three roots and browser artifacts, and run the cross-domain smoke matrix before lifting the freeze. Failure inside that window can restore the complete pre-cutover state with zero IAM write loss.

After merchant or agent entries accept traffic, the pre-decision binary is not a valid rollback target because it trusts client `tenantId` and has no realm boundary. Incident response must stop the affected entries, preserve new IAM and audit data, and forward-fix. This ADR can be superseded only by another accepted decision with an explicit account/session migration; disabling the new entries does not authorize reusing accounts or sessions across domains.
