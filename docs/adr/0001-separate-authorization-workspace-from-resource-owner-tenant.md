# Separate authorization workspace from resource owner tenant

Status: accepted.

A TenantMembership gets authority from one authorization workspace, while a business resource is owned by a resource-owner tenant. Same-tenant access remains the default; cross-owner-tenant access is limited to permissions explicitly marked `RELATED_PARTY_READ` and also requires a merchant/customer-scoped Grant plus trusted Party/Relationship evidence. Relationship evidence never creates permission, FUND permissions are always `SAME_TENANT_ONLY`, and missing adapters fail closed.

This rejects both blanket tenant equality—which cannot support agent access to related merchant orders—and relationship-only authorization—which could silently create cross-tenant access. The current Admin API intentionally supplies no relationship adapter, so this ADR establishes the extension boundary without opening a production route.
