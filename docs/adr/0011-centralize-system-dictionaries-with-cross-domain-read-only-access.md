# Centralize system dictionaries with cross-domain read-only access

Status: accepted.

Decision-ID: SYSTEM-DICTIONARY-CATALOG

## Context

PLATFORM operators need to maintain stable labels and values such as `BELONG_SYSTEM`, while the MERCHANT and AGENT applications need to render the same values. Replicating dictionaries per tenant would create drift, and allowing a browser-supplied tenant or account-domain selector would contradict the trusted-entry boundary established by ADR-0008.

## Decision

1. System dictionaries are one shared reference catalog, not tenant-owned business data. Dictionary types and values are stored once and are read consistently by all three authenticated backoffice applications.
2. PLATFORM exposes Dictionary Management and Dictionary Data. Operators with the matching permissions may create, update, and delete dictionary types and values. Dictionary Data remains a registered page but is hidden from left navigation and opened from the matching Dictionary Management row.
3. MERCHANT and AGENT expose no Dictionary Management or Dictionary Data page, route, menu, or button. Their authenticated applications retain only the read API and `dictionary-data:view` grant required by shared UI consumers such as status Select and Tag rendering; their composition roots register no dictionary write endpoint.
4. The PLATFORM data page has one route, `/system/dict/data`. Its optional `dictType` query selects a dictionary type only; it never selects a tenant, realm, portal, or account domain. The superseded `/system/dict/data/type/:dictType` route is retired by the append-only V30 migration, and the route is not part of MERCHANT or AGENT deployments after V31.
5. Dictionary type is globally unique. Dictionary value is unique within one dictionary type. A type with live data cannot be deleted, and all update/delete commands use optimistic row versions.
6. The server validates all fields and rechecks authorization. Frontend button visibility is presentation only.
7. Multi-type lookups use a shared Redis cache because the three backend services are independent processes. Every mutation advances a database catalog revision in the same transaction; cache keys include that revision, so a committed write cannot reuse an older value even when cache invalidation fails. Redis failure falls back to PostgreSQL and affects performance only.
8. Tenant bootstrap provisions only `dictionary-data:view` and its `TENANT_ALL` dimension for each new MERCHANT or AGENT protected administrator role in the same transaction. It creates no dictionary menu, page, button, or `role_menu` relation, and never provisions dictionary mutation permissions outside PLATFORM.
9. `SYS_COMMON_STATUS` is a built-in presentation dictionary with the only semantic values `1` and `0`. The dictionary controls ordering and tag color. Until dictionary labels have an explicit locale model, enabled/disabled labels remain application i18n keys; dictionary data cannot introduce a new status or alter authorization and lifecycle decisions.
10. `BELONG_SYSTEM` is also presentation-only. The application owns the fixed mapping `1 -> PLATFORM`, `2 -> MERCHANT`, `3 -> AGENT`; only those three entries may contribute color and order to account-domain Select/Tag rendering. Labels always come from the application's `zh-CN` and `en-US` i18n resources because dictionary rows have no locale dimension. Dictionary data cannot add a legal domain, change the account-domain value sent to an API, select an authorization workspace, or affect permission checks.

## Consequences

- Cross-domain reads are intentional for this bounded reference catalog and do not grant cross-domain access to users, roles, memberships, orders, money, or tenant-owned configuration. MERCHANT and AGENT consume the catalog through bounded batch lookups embedded in their product pages, not through a standalone dictionary browser.
- Dictionary writes remain auditable PLATFORM control-plane actions. A MERCHANT or AGENT request to a write path fails closed before repository code runs.
- Future tenant-specific settings require a separate model and API; they must not be smuggled into the system dictionary catalog.
- Missing or malformed `BELONG_SYSTEM` data degrades to fixed application color/order defaults while labels remain i18n-backed. Unknown dictionary values are ignored rather than becoming new account domains.
- Superseded Redis revision keys expire by TTL. JVM-local dictionary caching is not used because it cannot provide cross-service invalidation.

## Migration and rollback

The schema and permission catalog change are additive. A previous binary may ignore the new tables and permissions before dictionary data is used by product flows. The dynamic-route retirement uses a three-stage rollout: first deploy a compatible frontend that navigates with the landing route while temporarily accepting the legacy menu, then run V30, and only then deploy the contracted allowlist. V31 subsequently tombstones the MERCHANT and AGENT landing/button rows while preserving their read grants and historical `role_menu` records. V31 is backward-compatible with the pre-removal frontend because the server simply stops returning those menus, so release V31 before the final frontend that removes their page components. Rollback is a forward fix in a later migration; V28 through V31 are never edited. Destructive schema rollback is not supported.
