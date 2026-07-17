# Identity and Access Management

This context defines who may act in the payment platform, in which tenant workspace, and over which resources. It does not authenticate raw credentials or own payment-business entities.

## Language

**User**:
A global human or service identity mapped from a verified external identity-provider subject.
_Avoid_: Account, operator record

**Tenant**:
An authorization isolation space in which memberships, departments, roles, and grants are evaluated.
_Avoid_: Merchant, customer

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
