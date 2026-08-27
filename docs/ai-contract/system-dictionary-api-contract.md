# System Dictionary API Contract

Status: implemented catalog contract with an ADR-0012 candidate presentation extension.

Decision: [ADR-0011](../adr/0011-centralize-system-dictionaries-with-cross-domain-read-only-access.md).

## Scope

This contract owns the shared system dictionary catalog. It does not model tenant settings or identity data.

## Rules

- `DICT-001`: PLATFORM is the only account domain that can mutate dictionary types or dictionary data.
- `DICT-002`: PLATFORM dictionary management reads use `dictionary:view`; shared batch consumption and MERCHANT/AGENT read projections use `dictionary-data:view`.
- `DICT-003`: MERCHANT and AGENT composition roots expose no dictionary type management or dictionary write endpoint.
- `DICT-004`: `dictType` is globally unique; `(dictType, value)` is unique for live dictionary data.
- `DICT-005`: deleting a type with live data returns `40901 DATA_CONFLICT`; stale update/delete returns `40902 OPTIMISTIC_LOCK_CONFLICT`.
- `DICT-006`: Long identifiers are JSON strings and list responses use `{ items, total }` inside the standard envelope.
- `DICT-007`: `/system/dict/data?dictType=TYPE` is the only Dictionary Data frontend route and belongs only to the PLATFORM deployment. The optional query selects a type only and cannot select tenant, realm, portal, or account domain.
- `DICT-008`: batch lookup is bounded to 64 unique types and returns only `value`, `label`, and `color`; a missing type maps to an empty array.
- `DICT-009`: Redis is a versioned shared acceleration layer. PostgreSQL remains authoritative and a cache outage must fall back to one bounded database query.
- `DICT-010`: the catalog has at most 1,000 live types, one type has at most 1,000 live values, and one batch response has at most 5,000 values; overflow fails without truncating or returning partial truth.
- `DICT-011`: new MERCHANT and AGENT tenant bootstrap atomically provisions only `dictionary-data:view` and its `TENANT_ALL` dimension for the protected administrator role; it provisions no dictionary menu, button, page route, or `role_menu` relation.
- `DICT-012`: PLATFORM hides the Dictionary Data route from left navigation and reaches it from the Dictionary Management row action; MERCHANT and AGENT expose no standalone dictionary page or navigation entry and consume dictionary values only through authenticated read APIs embedded in other product pages.
- `DICT-013`: `SYS_COMMON_STATUS` contains only values `1` and `0`. Dictionary order and color are presentation inputs, while localized enabled/disabled labels and all status decisions remain fixed application semantics.
- `DICT-014`: Dictionary Data is not a separate role-management capability. PLATFORM data create/update/delete endpoints require `dictionary:update`; `dictionary-data:create|update|delete` are stored compatibility codes only, do not authorize current endpoints, and are excluded from the grantable catalog. Selecting a PLATFORM dictionary permission implicitly preserves `dictionary-data:view` for shared batch consumption.
- `DICT-015`: `BELONG_SYSTEM` is a display projection over the application-owned mapping `1 -> PLATFORM`, `2 -> MERCHANT`, `3 -> AGENT`. Only those values can provide color and order. Labels always come from the application's `zh-CN` and `en-US` i18n resources because dictionary rows have no locale dimension. Legal `accountDomain` values, target Tenant validation, composition-root registration, and authorization remain fixed application/server rules; dictionary mutation cannot change them.
- `DICT-016`: `MERCHANT_TYPE_CODE` and `MERCHANT_AUTH_TYPE` are display projections over the application-owned Merchant profile allowlists. Dictionary rows may provide order and color only; labels come from application i18n. Unknown, duplicate, missing or failed values fall back to the complete fixed options and never become legal API values or Tenant, AgentRelation, MerchantMarket or authorization facts.

## Current contract

The shared tables, permission catalog, three composition-root read APIs, PLATFORM mutation APIs, Redis revision cache, and PLATFORM product views are implemented. V28 backfills the original landing and dynamic routes, V29 hides PLATFORM Dictionary Data navigation, and V30 retires the dynamic route while preserving the single landing route. V31 removes the MERCHANT and AGENT standalone landing/button rows while preserving their read grant and historical `role_menu`; later MERCHANT and AGENT tenant bootstrap provisions only the read grant. V30 also seeds `SYS_COMMON_STATUS` only when absent, increments the catalog revision exactly once for that insert, accepts an already exact built-in dictionary without a revision change, and fails closed for partial, modified, or tombstoned collisions.

The local-only browser sample seeds `BELONG_SYSTEM` with `1/2/3` only when that type has never existed. This sample proves local presentation data, not a production system-dictionary migration. The Candidate source baseline includes a shared `useAccountDomainDictionary` that fixes the three mappings, ignores unknown values, consumes dictionary color/order, fills missing values from application defaults, and always renders the application i18n label. Every change requires a new immutable Candidate gate; production seed/backfill and dedicated cross-domain permissions remain separate rollout work.

V35 adds the exact absent-or-canonical `MERCHANT_TYPE_CODE` and `MERCHANT_AUTH_TYPE` seeds. It increments the catalog revision once if either type is inserted, leaves the revision unchanged when both exact types already exist, and fails closed for partial, modified or tombstoned collisions. The fixed values are defined by the Merchant Lifecycle Contract; the dictionary does not extend them.

## Target contract

All endpoints require an authenticated Cookie session and the normal CSRF protection for mutations. Unknown methods and paths are denied.

| Application | Method and path | Permission | Result |
| --- | --- | --- | --- |
| PLATFORM | `GET /api/system/dictionaries` | `dictionary:view` | paged dictionary types |
| PLATFORM | `POST /api/system/dictionaries` | `dictionary:create` | `{ id: string }` |
| PLATFORM | `PUT /api/system/dictionaries/{dictId}` | `dictionary:update` | `null` |
| PLATFORM | `DELETE /api/system/dictionaries/{dictId}?expectedVersion=n` | `dictionary:delete` | `null` |
| PLATFORM | `GET /api/system/dictionary-types/options` | `dictionary:view` | live `{ dictType, dictName }` options |
| PLATFORM | `GET /api/system/dictionary-data` | `dictionary:view` | paged values for one required `dictType` |
| MERCHANT, AGENT | `GET /api/system/dictionary-types/options` | `dictionary-data:view` | read-only live options |
| MERCHANT, AGENT | `GET /api/system/dictionary-data` | `dictionary-data:view` | read-only values for one required type |
| all three | `POST /api/dict/queryBatch` | `dictionary-data:view` | map of requested types to display values |
| PLATFORM | `POST /api/system/dictionary-data` | `dictionary:update` | `{ id: string }` |
| PLATFORM | `PUT /api/system/dictionary-data/{dictCode}` | `dictionary:update` | `null` |
| PLATFORM | `DELETE /api/system/dictionary-data/{dictCode}?expectedVersion=n` | `dictionary:update` | `null` |

MERCHANT and AGENT must return the standard denied/not-found behavior for every dictionary write path and for `/api/system/dictionaries`; those routes are not product capabilities in those composition roots. The options endpoint is a read projection only and never returns identifiers, row versions, audit fields, or mutation capabilities.

### Dictionary type

```json
{
  "dictId": "28",
  "dictType": "BELONG_SYSTEM",
  "dictName": "系统-归属系统",
  "sort": 0,
  "remark": "",
  "rowVersion": 0,
  "createTime": "2024-02-21T04:28:32Z"
}
```

Create accepts `dictType`, `dictName`, `sort`, and optional `remark`. Update accepts the same fields plus required `expectedVersion`.

### Dictionary data

```json
{
  "dictCode": "46",
  "dictType": "BELONG_SYSTEM",
  "label": "运维",
  "value": "1",
  "color": "processing",
  "sort": 1,
  "remark": "",
  "rowVersion": 0,
  "createTime": "2024-03-06T10:12:27Z"
}
```

Create accepts `dictType`, `label`, `value`, optional `color`, `sort`, and optional `remark`. Update accepts the same fields plus required `expectedVersion`. `GET /api/system/dictionary-data` requires `dictType`; optional `label`, `value`, `page`, and `pageSize` only narrow the result.

### Validation

- `dictType`: 1 to 64 ASCII letters, digits, or underscores, starting with a letter. The server canonicalizes it to uppercase with `Locale.ROOT`; lowercase query values such as `sys_user_sex` therefore resolve to `SYS_USER_SEX`.
- `dictName`, `label`, `value`: non-blank, at most 100 characters.
- `color`: one of `default`, `processing`, `success`, `warning`, `error`, or `purple`. Missing or blank input is normalized to `default`.
- `sort`: integer from 0 through 9999.
- `remark`: optional, at most 500 characters.
- `pageSize`: 1 through 200. The default page is 1 and default size is 20.

### Batch lookup

Request:

```json
{
  "dictTypes": ["PAY_CHANNEL", "CASH_MODEL"]
}
```

Response data:

```json
{
  "PAY_CHANNEL": [
    { "value": "bank", "label": "Bank", "color": "processing" }
  ],
  "CASH_MODEL": []
}
```

`dictTypes` contains 1 through 64 entries. The server validates, uppercase-canonicalizes, and then deduplicates them; response keys use the canonical uppercase form. It preserves first-requested canonical key order, includes every requested canonical type in the response, and sorts each value list by `sort` and then stable identifier. One database revision read and at most one bounded bulk value query are allowed on a cache miss; per-type database queries are forbidden.

The Redis key includes the database catalog revision advanced atomically by every dictionary mutation. Cache entries contain only `value`, `label`, and `color`, have a finite TTL, and are shared by the three composition roots. Redis timeout, decode failure, or unavailability falls back to PostgreSQL without returning partial cached truth.

Dictionary mutations serialize on the singleton revision row. Type creation rejects a 1,001st live type, and data creation serializes against the owning type and rejects its 1,001st live value, all with `40901 DATA_CONFLICT`. Cache decode rejects malformed fields or more than 1,000 values for one type. After cached and database values are combined, more than 5,000 total values returns `40901` without truncation.

Batch assembly reads the catalog revision before and after combining cache and PostgreSQL results. A changed revision discards the whole attempt and retries a bounded number of times; continuous concurrent mutation fails explicitly instead of returning values from mixed catalog revisions.

### Frontend consumption

The shared backoffice runtime exposes `useSystemDictionaries(...dictTypes)` as the frontend seam for page-level dictionary consumption. A caller declares 1 through 64 types once and receives reactive loading/error state, `getOptions`, `getItem`, `getLabel`, and `reload`. The module canonicalizes and deduplicates declared types before one batch request, initializes every declared type to an empty array, and ignores an older request result when a newer reload has already completed.

This module deliberately has no process-wide browser cache: PostgreSQL and the versioned Redis adapter remain authoritative, while a mounted page may explicitly reload after a mutation. It also does not render Antdv tags; callers choose presentation from the returned `label` and `color` so data access remains independent of a UI component.

Account-domain consumers use an application-owned bidirectional mapping rather than treating dictionary values as domain identifiers:

```text
PLATFORM -> 1
MERCHANT -> 2
AGENT    -> 3
```

The filter always submits `PLATFORM|MERCHANT|AGENT` to the Identity API. It may order and color the three fixed options from the matching `BELONG_SYSTEM` items, but it ignores every other value and never renders the dictionary `label` for this type. Labels always resolve through application i18n. A missing, duplicate, malformed, or failed dictionary result retains the three fixed options with application-owned color/order defaults; it never removes a legal domain, creates a fourth domain, or silently changes the selected target.

The role tree renders only Dictionary Management and its `dictionary:view|create|update|delete` actions. The hidden Dictionary Data route and its compatibility BUTTON rows are not role-tree choices. `dictionary:update` is the single mutation authority for both type edits and data-row CRUD; `dictionary-data:view` may be carried as an implicit internal grant for batch-backed UI enums but is not shown as a standalone product permission.

### Errors

The standard envelope is `{ code, data, error, message, traceId }`. Validation uses `40001`; missing resources use `40401`; duplicate/live-dependent data uses `40901`; stale versions use `40902`; denied requests use `40301`.

## Compatibility plan

The initial catalog is additive. Dynamic-route retirement requires a compatibility window: deploy a frontend that already navigates through `SystemDictionaryDataIndex` query parameters but still accepts the legacy backend route, apply V30, then remove `SystemDictionaryData` and `/system/dict/data/type/:dictType` from the final frontend allowlist. To remove the standalone MERCHANT/AGENT browser, apply V31 first; the old frontend remains compatible because its menu is no longer returned. Then deploy the final frontend without the MERCHANT/AGENT route or page component. PLATFORM keeps its hidden landing. Rollback after V31 requires a later append-only forward migration and a compatible frontend; no executed migration is edited.

New MERCHANT and AGENT tenant creation must fail closed when the canonical `dictionary-data:view` permission is unavailable. A partial tenant, role, grant, invitation, or audit record must not remain after such a failure, and no dictionary menu record may be created.

`BELONG_SYSTEM` rollout is presentation-compatible: deploy the fixed allowlist, i18n labels, and color/order fallback before relying on dictionary decoration. A production data migration may then add the exact `1/2/3` rows without changing Identity API request values or labels. Removing or editing those rows only changes/falls back color/order presentation and is never a security rollback mechanism.

## MCH-003 Merchant projections

MCH-003 adds absent-or-exact `MERCHANT_INDUSTRY_CODE` and `MERCHANT_LEGAL_ID_TYPE` projections in
V37 or later. Application allowlists and bilingual labels remain authoritative; dictionary rows
only provide order/color. Industry seeds are `FINANCIAL_SERVICES/processing/1`,
`ECOMMERCE/success/2`, `RETAIL/purple/3`, `TRAVEL/warning/4`, `EDUCATION/default/5` and
`OTHER/default/6`. Legal-ID seeds are `NATIONAL_ID/processing/1`, `PASSPORT/success/2` and
`DRIVER_LICENSE/warning/3`. Exact Chinese labels are frozen in Merchant Contract section 12.3.

The same forward migration retires `DIRECT` from the active Merchant-type projection and converges
it to canonical `PLATFORM` without rewriting V35. Extra, duplicate, missing or malformed dictionary
rows never extend API legality; absence uses the complete fixed application fallback.
