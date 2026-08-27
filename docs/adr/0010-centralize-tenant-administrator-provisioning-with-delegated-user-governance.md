# Centralize tenant administrator provisioning with delegated user governance

Status: accepted.

Decision-ID: IAM-DELEGATED-USER-GOVERNANCE

## Context

The three backoffice applications need the same user and role administration vocabulary, but they do not have the same authority. Platform operators need an inventory of PLATFORM, MERCHANT, and AGENT accounts and must establish the system administrators who can enter a merchant or agent tenant. After that bootstrap, ordinary users and ordinary roles belong to the target tenant's own administration boundary.

Putting `merchantId` or `agentId` directly on `User`, or letting the ordinary user endpoint accept a caller-selected tenant, would collapse identity, authorization workspace, and business-party ownership into one mutable field. It would also contradict the account-domain and session isolation accepted in [ADR-0008](0008-isolate-three-backoffice-account-domains-and-sessions.md). The application already models tenant affiliation through `Membership`, while [ADR-0009](0009-separate-backoffice-applications-and-production-identity-boundaries.md) fixes external identity mapping to `issuer + subject`.

## Decision

1. All three applications expose navigation named **User Management** and **Role Management**. The previous product pages named Member Governance and Tenant First Administrator Bootstrap are removed from navigation. Their lifecycle primitives may remain behind replacement APIs during an expand/contract migration, but they are not parallel product workflows.
2. A PLATFORM system administrator may read a cross-domain user directory covering PLATFORM, MERCHANT, and AGENT. A requested account-domain filter defaults to PLATFORM. MERCHANT and AGENT sessions never receive this cross-domain directory authority.
3. PLATFORM cross-domain writes are limited to creating and maintaining protected system-administrator Memberships for an existing MERCHANT or AGENT Tenant, plus resetting an ACTIVE local/test password for a precisely bound MERCHANT or AGENT User. The reset exception is available only to the protected PLATFORM system administrator, never changes User, Credential, or Membership status, and revokes every non-terminated Membership session for that User. PLATFORM still cannot create, edit, terminate, or assign roles to an ordinary tenant user through the cross-domain management plane. External IdP credentials remain owned by Keycloak under ADR-0004 and are rejected by this local command.
4. Ordinary User and Role administration is same-tenant only. PLATFORM administrators manage ordinary PLATFORM users and roles; MERCHANT administrators manage ordinary users and roles in their current merchant Tenant; AGENT administrators do the same in their current agent Tenant. Tenant and account domain come from the trusted Session and application composition root, never from the ordinary request.
5. Target affiliation is represented by `Membership(tenantId, userId)` and the Tenant's account domain. `User` does not gain merchant ID, agent ID, tenant ID, portal, or realm selector columns. The Platform user form may display a target Tenant selector for the dedicated system-administrator command, but the server validates that target against an existing active Tenant of the requested account domain.
6. A Platform operator who provisions a target administrator does not receive a Membership, Role, session, or permission in the target Tenant. Cross-domain audit evidence records the PLATFORM actor separately from the target Membership; it must not forge a target-tenant `operator_membership_id` or `assigned_by` relationship.
7. A new login identifier is a normalized email address: trim surrounding whitespace, lowercase with `Locale.ROOT`, require exactly one syntactically valid address, and do not apply provider-specific dot or plus-address rewriting. New local and external identities use that normalized email as their login username. Existing non-email identifiers remain an explicit expand-phase migration deviation until remediated; new writes cannot create them.
8. Email is a mutable login/profile attribute, not an identity key. An application User continues to be mapped only by the exact canonical `issuer + subject`. The same normalized email may identify separate Users in separate account domains and Realms; it remains unique within one account domain. Cross-Realm accounts are not linked automatically.
9. Creating a MERCHANT or AGENT system administrator from PLATFORM is a dedicated lifecycle command. The server chooses the target Realm and protected system Role, creates or reuses the exact target-domain identity according to the lifecycle policy, creates the target Membership, and emits identity/audit evidence atomically where possible. The request cannot submit arbitrary role IDs, realm, issuer, system-role flags, or Platform Membership data.
10. Maintaining a target system administrator is limited to supported administrator lifecycle operations, including display/status changes and credential or MFA recovery orchestration. Local/test password reset is a separate recovery command that may target any precisely bound MERCHANT or AGENT local User; it does not grant broader edit authority. Last-active-administrator protection remains enforced in the target Tenant. Production-sensitive operations require the step-up and complete revocation controls from ADR-0009.

## API and authorization consequences

- Same-tenant endpoints retain `/api/system/user/**`, `/api/system/role/**`, and the role-configuration endpoints. They always derive `tenantId` from the authenticated Session.
- PLATFORM alone receives `/api/platform/user-directory` for cross-domain reads, `/api/platform/tenant-administrators` for target administrator lifecycle commands, and `/api/platform/users/{userId}/password/reset` for the local/test password-reset exception. These endpoints require protected PLATFORM system-administrator authority and reject PLATFORM as a cross-domain target account domain.
- A PLATFORM directory row returns both `accountDomain` and target `tenantId`; the same-tenant list may return those fields as server-derived context for a uniform UI. Long IDs remain JSON strings.
- Permission and menu bootstrap must give each domain's protected system Role access to its own User and Role Management pages. Ordinary roles can receive only the existing assignable same-tenant permissions; no tenant role may receive the PLATFORM cross-domain permissions.

## Migration and rollback

The change is expand/contract and forward-only:

1. Add cross-domain audit actor fields and dedicated permissions without weakening existing same-tenant foreign keys.
2. Generalize same-tenant repositories and register User/Role controllers explicitly in each composition root.
3. Add the PLATFORM directory and target-administrator lifecycle APIs, then switch the three product menus to User/Role Management.
4. Change local fixtures and Keycloak bootstrap users to normalized email identifiers. Reject non-email identifiers for all new user and invitation writes while inventorying historical non-email identifiers.
5. Remove the obsolete navigation and, only after no caller remains, retire the old Member Governance and Tenant Bootstrap HTTP routes in a later contract migration.

Before the contract phase, rollback may restore the previous application binaries while leaving additive schema unused. After email accounts, cross-domain administrator audit records, or tenant-managed users are written, an old binary is not a valid writable rollback target. Incident response stops identity writes and forward-fixes or restores a coordinated database and IdP snapshot.

## Consequences

- The three applications share a product concept without sharing authentication sessions or authorization workspaces.
- Platform has operational visibility, a narrowly scoped administrator bootstrap capability, and local/test password recovery for precisely bound MERCHANT/AGENT users, not general impersonation or unrestricted cross-tenant IAM.
- Merchant and agent administrators can manage their own ordinary users and roles without exposing client-selected tenant switching.
- The existing invitation, Keycloak provisioning, recovery, identity-version, CSRF, and back-channel logout foundations remain reusable, but their UI and API composition must converge on the User Management workflow.
- [ADR-0012](0012-expose-platform-cross-domain-role-and-menu-directories.md) later extends PLATFORM operational visibility to dedicated read-only Role/Menu directories. It does not change this ADR's same-tenant ordinary Role administration or cross-domain write restrictions.
