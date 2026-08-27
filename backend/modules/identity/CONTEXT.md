# Identity and Access Management

This context defines who may act in the payment platform, in which tenant workspace, and over which resources. It does not authenticate raw credentials or own payment-business entities.

## Language

**User**:
A global human or service identity mapped from a verified external identity-provider subject.
_Avoid_: Account, operator record

**Tenant (Authorization Workspace)**:
An authorization isolation space in which memberships, departments, roles, and grants are evaluated. It identifies where authority comes from; it is not necessarily the owner of a business resource.
_Avoid_: Merchant, customer, resource owner

**Resource Owner Tenant**:
The tenant that owns a business resource. It may differ from the actor's Authorization Workspace only for explicitly scoped, read-only access backed by trusted Business Relationship Evidence.
_Avoid_: Request tenant, caller-supplied tenant

**Business Relationship Evidence**:
A trusted Party/Relationship fact proving that the actor's workspace is related to a customer or merchant resource. It supplements an explicit IAM grant and never creates a permission by itself.
_Avoid_: Role, implicit permission, frontend scope

**Tenant Membership**:
A User's tenant-scoped working identity, including organization placement and permission/session versions.
_Avoid_: User copy, merchant user

**Role**:
A tenant-scoped named grouping to which atomic permission grants are assigned.
_Avoid_: User type, menu group

**Permission**:
A stable operation code with trusted risk and scope metadata, independent of menus and routes.
_Avoid_: Menu URL, button name

**Role Grant**:
An atomic assignment that binds one Role to one Permission and its correlated data-scope and fund-operation constraints.
_Avoid_: Role-menu relation, flattened data range

**Data Scope**:
The server-enforced resource dimensions and targets over which an authorized operation may act.
_Avoid_: Frontend filter, tenant ID supplied by the caller

**Identity Provider**:
The external authority that verifies credentials and issues a trusted subject identity; it is not the source of platform roles or data scope.
_Avoid_: IAM database, permission service

**Account Domain**:
The PLATFORM, MERCHANT, or AGENT login, identity-provider, application-session, and cache boundary to which one User belongs.
_Avoid_: Tenant selector, role, natural-person identity

**Login Email**:
A normalized email address used as a mutable login identifier within one Account Domain. It is not the User identity key; external identity mapping remains the exact issuer and subject pair.
_Avoid_: Global person key, cross-Realm link

**Tenant System Administrator**:
A protected, non-assignable system Role Membership that may administer ordinary Users and Roles inside one Tenant. PLATFORM may provision this Membership through a dedicated cross-domain lifecycle command without joining or impersonating the target Tenant.
_Avoid_: Platform operator Membership, ordinary role assignment

**Platform Identity Management Plane**:
The PLATFORM-only cross-domain directory and target system-administrator lifecycle surface. It can inspect account-domain affiliation and maintain protected tenant administrators, but it cannot manage target ordinary users or roles.
_Avoid_: Cross-tenant session, tenant switcher, impersonation
