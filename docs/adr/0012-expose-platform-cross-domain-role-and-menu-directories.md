# Expose PLATFORM cross-domain Role and Menu directories

Status: accepted.

Decision-ID: IAM-PLATFORM-CROSS-DOMAIN-IAM-DIRECTORY

## Context

PLATFORM operators need one operational inventory of the Users, Roles, and Menus that exist in the three backoffice account domains. [ADR-0010](0010-centralize-tenant-administrator-provisioning-with-delegated-user-governance.md) already permits a protected PLATFORM system administrator to read the cross-domain User directory, while ordinary Role administration remains inside the authenticated Session Tenant. Reusing the ordinary Role or Menu CRUD with a browser-selected Tenant would turn an inventory filter into an authorization-workspace switch and would contradict [ADR-0008](0008-isolate-three-backoffice-account-domains-and-sessions.md).

## Decision

1. A protected PLATFORM system administrator may read Role and Menu directories for PLATFORM, MERCHANT, and AGENT through PLATFORM-only control-plane endpoints. This extends the read-only inventory accepted by ADR-0010; it does not grant a Membership, Role, Session, permission, or impersonation capability in the target Tenant.
2. `accountDomain=PLATFORM` is the default. PLATFORM queries are always fixed to the authenticated source PLATFORM Tenant; a browser cannot select another PLATFORM Tenant.
3. A MERCHANT or AGENT query must provide one exact positive `tenantId`. The server verifies that the target Tenant is ACTIVE and belongs to the requested account domain. A missing Tenant, inactive Tenant, or domain/Tenant mismatch fails closed.
4. The dedicated read endpoints are `GET /api/platform/role-directory` and `GET /api/platform/menu-directory`. Role rows and Menu nodes return server-derived `accountDomain`, `tenantId`, `tenantName`, and `managementMode`; `managementMode` is `SAME_TENANT` for the source PLATFORM Tenant and `READ_ONLY` for MERCHANT or AGENT targets. It is a UI safety signal, not authorization evidence. Every Long ID remains a JSON string. A Menu response contains the tree for exactly one Tenant and never merges nodes from multiple Tenants.
5. Target MERCHANT and AGENT rows are read-only. PLATFORM cannot use this directory to create, edit, disable, delete, assign users, replace `menuIds`, or replace RoleGrants in a target Tenant. The existing `/api/system/role/**`, `/api/v1/iam/roles/**`, and `/api/system/menu/**` contracts continue to derive their Tenant from the authenticated Session and do not accept a target account domain or Tenant selector.
6. During the candidate phase, each directory read requires the corresponding existing `role:view` or `menu:view` permission and an ACTIVE, undeleted protected PLATFORM system Role. Production additionally requires dedicated `role:cross-domain-view` and `menu:cross-domain-view` permissions. Those permissions are server-owned, non-delegable, excluded from the ordinary grantable catalog, and unavailable in MERCHANT and AGENT composition roots.
7. The `BELONG_SYSTEM` dictionary is presentation data only. The application owns the fixed mapping `1 -> PLATFORM`, `2 -> MERCHANT`, `3 -> AGENT`; only dictionary color and order may decorate those three Select and Tag options. Labels always come from the application's `zh-CN` and `en-US` i18n resources because the dictionary has no locale dimension. Dictionary rows cannot add a legal account domain, change a request value, select a Tenant, or influence authentication or authorization. Unknown rows are ignored and missing valid rows use safe application color/order defaults.

## Consequences

- The PLATFORM Role and Menu pages may reuse one product surface for same-tenant management and cross-domain inventory, but every non-PLATFORM target state must remove mutation and assignment actions. Frontend hiding is presentation only; the backend directory exposes no mutation path.
- `accountDomain` describes the account-domain partition. `tenantId` identifies the owning authorization workspace, and `tenantName` provides its operator-facing label. The three fields cannot be collapsed into one mutable "platform" field on Role or Menu.
- The new directory queries are control-plane exceptions and do not use `RELATED_PARTY_READ`, business relationship evidence, or ordinary cross-tenant RoleGrants.
- Candidate implementation includes bounded-query and browser read-only regression coverage. Every change requires evidence bound to the exact immutable Candidate; production remains NO-GO until the two non-delegable permissions plus MERCHANT/AGENT negative composition-root gates are complete.

## Compatibility and rollback

The change is additive. Existing same-tenant endpoints and clients remain unchanged. A compatible PLATFORM frontend first learns the directory responses and treats target-domain rows as read-only; the dedicated endpoints can then be enabled. Rolling back the directory removes only cross-domain visibility and must not redirect its requests to ordinary CRUD. If dedicated permission rows are later added through Flyway, that migration remains append-only and a rollback leaves the unused permissions in place for a forward fix.
