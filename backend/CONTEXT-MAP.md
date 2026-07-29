# Backend Context Map

## Contexts

- [Identity and Access Management](./modules/identity/CONTEXT.md) — owns platform identities, tenant memberships, organization, roles, permissions, and authorization scope.

## Relationships

- **External Identity Provider → Identity and Access Management**: authenticates credentials and supplies a verified issuer/subject identity; IAM owns the platform mapping and authorization state.
- **Party and Relationship → Identity and Access Management**: supplies merchant, agent, sales, market, and channel relationships through explicit ports; IAM does not copy those business relationships as its source of truth.
- **Identity and Access Management → Payment domains**: supplies authorization decisions and data-scope constraints; it does not own orders, balances, ledgers, channels, or money movement.
