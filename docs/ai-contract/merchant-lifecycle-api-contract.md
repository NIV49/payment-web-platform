---
type: Contract
status: accepted
audience:
  - engineering
  - product
belongs_to:
  - "[[product/merchant-management]]"
related_to:
  - "[[adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy]]"
  - "[[adr/0014-platform-maintained-merchant-profile-and-operating-markets]]"
  - "[[adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments]]"
  - "[[ai-context/merchant/README]]"
---

# Merchant Lifecycle API Contract

Decisions: [ADR-0013](../adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy.md),
[ADR-0014](../adr/0014-platform-maintained-merchant-profile-and-operating-markets.md) and
[ADR-0015](../adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md).

Status: accepted target contract. MCH-001/MCH-002 and MCH-003 have mutable Candidate implementations
and the local evidence described below. MCH-003 assisted onboarding, reviewed amendments and protected
documents now have integrated backend/frontend, persisted-volume and real-browser evidence. Production GO still
requires formal repository gates and signed author-independent reviews bound to one exact immutable
candidate SHA.

## 1. Scope and transport

This contract owns MCH-001 Merchant application/review/lifecycle governance, MCH-002 operating
profile maintenance compatibility, and the MCH-003 PLATFORM-assisted application, reviewed
amendment and protected-document target. None of these commands creates an Identity Tenant, User,
Membership, protected administrator or credential.

All endpoints:

- use the existing Cookie Session and CSRF/Origin protections appropriate to each browser request;
- return the existing `ApiResponse<T>` envelope `{code,data,error,message,traceId}`;
- encode every database Long ID as a JSON string;
- use UTC ISO-8601 timestamps;
- reject unknown JSON fields and malformed enum, UUID, ID, country, version or page values with `40001 INVALID_REQUEST`;
- never accept registration-number plaintext in a query, path or response.

## 2. Domain values

### 2.1 Merchant status

```text
PENDING_REVIEW
REVIEW_REJECTED
ACTIVE
DISABLED
TERMINATED
```

There is no DRAFT, `NOT_SUBMITTED` or generic numeric status. An unsubmitted Merchant application is represented by `merchant: null` in the self-service view.

### 2.2 Review decision

```text
APPROVE
REJECT
```

`APPROVE` maps `PENDING_REVIEW -> ACTIVE`; `REJECT` maps `PENDING_REVIEW -> REVIEW_REJECTED`. The server never accepts a requested target status in a review request.

### 2.3 Lifecycle reason codes

Every mutation audit uses exactly one action-specific code. Lifecycle commands never accept free text. The submission command type is derived only from the strict request shape: explicit JSON `expectedVersion: null` means first `submit`, while a non-negative integer means `resubmit`. It is never re-derived from whether a Merchant currently exists. Submission codes are server-derived and are not request fields. PLATFORM decision and governance commands require the client to choose a code allowed for that exact command:

| Command | Allowed `reasonCode` |
| --- | --- |
| submit | `APPLICATION_SUBMITTED` |
| resubmit | `APPLICATION_RESUBMITTED` |
| approve | `PROFILE_VERIFIED` |
| reject | `PROFILE_MISMATCH`, `REGISTRATION_UNVERIFIED`, `COMPLIANCE_REJECTED` |
| disable | `COMPLIANCE_HOLD`, `RISK_CONTROL` |
| enable | `COMPLIANCE_CLEARED`, `RISK_CLEARED` |
| terminate | `BUSINESS_CLOSED`, `COMPLIANCE_TERMINATION` |

The server derives `APPLICATION_SUBMITTED` or `APPLICATION_RESUBMITTED` before any idempotency or business write and rejects either field if a submission request attempts to supply `reasonCode`. For PLATFORM commands, the server validates the supplied code against the command before any idempotency or business write. Audit and responses store only the resolved code. Human investigation notes belong in a later access-controlled case-management capability, not MCH-001.

### 2.4 Registration-number protection

The request field is plaintext only at the authenticated write boundary. It is normalized and protected under ADR-0013 before persistence. Responses return only `registrationNumberMasked`, which contains at most the last four Unicode code points and uses `*` for earlier positions. A value of four or fewer code points is masked completely. The frontend contract parser fails closed unless the value has this masked shape; a plaintext-like or otherwise invalid value never reaches a page. The API never returns normalized plaintext, ciphertext, nonce, fingerprint, key ID or algorithm metadata.

Initial submission requires `registrationNumber`. Rejected resubmission may omit it to retain the current protected value only when `registrationCountry` is unchanged. Changing `registrationCountry` requires a present nonblank `registrationNumber`; omission returns `40001 INVALID_REQUEST` and changes no Merchant, fingerprint, audit or idempotency state. A present nonblank value replaces the protected value after normalization, encryption and uniqueness checks. Explicit JSON `null` is invalid.

Registration ciphertext is mandatory. The only MCH-001 AEAD suite is AES-256-GCM with a fixed 128-bit authentication tag. Encryption uses a CSPRNG 96-bit nonce that is unique for each AEAD key; persistence also enforces uniqueness of `(aeadKeyId, nonce)`. A nonce collision fails closed and rolls back the entire command; the implementation must not overwrite or retry with reused material. The authenticated AAD is a length-framed tuple of `MCH-REG-AEAD-v1`, Merchant ID, bound Tenant ID, registration country, field name `registrationNumber`, normalization version, algorithm and AEAD key ID. Ciphertext, tag or authenticated metadata copied to another Merchant, Tenant, country or key context must fail closed. Search-HMAC, idempotency-HMAC and AEAD keys are separate purposes and are never interchangeable.

Database active-key metadata identifies exactly one authoritative registration Search-HMAC key. Every first submission or registration replacement acquires the same transaction-level advisory registration-write fence before reading that metadata. Rotation holds the fence while one transaction decrypts and backfills every Merchant, proves country-plus-registration uniqueness and atomically switches the active-key metadata. A Search-HMAC rotation failure rolls back and leaves the old search key authoritative; writes never run concurrently between the backfill proof and cutover.

Separate database active-key metadata identifies exactly one AEAD key for new ciphertext. AEAD rotation acquires the same registration-write fence, decrypts each row using its recorded retained key, re-encrypts it with a fresh nonce under the proposed key, verifies authentication and complete row coverage, then atomically switches the active AEAD metadata in the same transaction. An AEAD rotation failure rolls back all re-encryption and metadata changes and leaves the old AEAD key authoritative. A decrypt key remains available until no row references it and the verified backup and rollback window has closed.

## 3. Shared response models

### 3.1 `MerchantDetail`

```json
{
  "merchantId": "720000000000000001",
  "tenantId": "620000000000000001",
  "merchantCode": "MCH_EXAMPLE_ALPHA",
  "legalName": "Example Payments Pte. Ltd.",
  "displayName": "Example Pay",
  "merchantTypeCode": "DIRECT",
  "legalPersonName": "Example Director",
  "authenticationType": "ENTERPRISE",
  "registrationCountry": "SG",
  "registrationNumberMasked": "******6789",
  "marketCodes": ["PHL"],
  "remarks": "Priority onboarding",
  "status": "PENDING_REVIEW",
  "statusReasonCode": "APPLICATION_SUBMITTED",
  "rowVersion": 0,
  "submittedAt": "2026-08-13T08:00:00Z",
  "reviewedAt": null,
  "lastDecision": null,
  "createdAt": "2026-08-13T08:00:00Z",
  "updatedAt": "2026-08-13T08:00:00Z"
}
```

`lastDecision`, when present, is:

```json
{
  "decision": "REJECT",
  "reasonCode": "PROFILE_MISMATCH",
  "decidedAt": "2026-08-13T09:00:00Z"
}
```

`registrationCountry` is the ISO 3166-1 alpha-2 legal-registration country used by registration-number protection; it is not an operating market. `marketCodes` is a sorted array of distinct ISO 3166-1 alpha-3 operating markets. Existing MCH-001 rows may return an empty array because V34 does not invent historical business facts. `remarks` is a required response string containing 0..300 Unicode code points; empty string means no Merchant remark. `merchantTypeCode`, `legalPersonName` and `authenticationType` are nullable only for rows created before V35; after a successful profile update all three are non-null. They are profile classifications and never select a Tenant, AgentRelation, MerchantMarket or authorization scope.

`statusReasonCode` is the reason for the current lifecycle status and is independent from `lastDecision`. Its exact values are the section 2.3 codes, including `APPLICATION_SUBMITTED` and `APPLICATION_RESUBMITTED`. It is nullable only for a historical row whose exact reason cannot be proved from retained audit/decision evidence; V34 and readers must not infer or guess a reason from current status. `UPDATE_PROFILE` preserves both status and `statusReasonCode`; `PLATFORM_PROFILE_UPDATED` belongs to the profile audit event and never overwrites the status reason.

The response does not expose reviewer User or Membership identifiers to MERCHANT callers. A PLATFORM detail may additionally return `decidedByMembershipId` as a string inside `lastDecision`; it must not return credentials or identity-provider claims.

### 3.2 `MerchantMutationResult`

```json
{
  "merchantId": "720000000000000001",
  "merchantCode": "MCH_EXAMPLE_ALPHA",
  "status": "ACTIVE",
  "rowVersion": 2
}
```

## 4. MERCHANT self-service

The MERCHANT composition root exposes only the following MCH-001 endpoints. Tenant, Membership and Merchant binding come from the trusted Session. A request containing a Tenant, Merchant, account-domain, Realm or portal selector is invalid.

Request bodies MUST NOT contain `tenantId`; merchant scope is derived from the trusted Session or the server-resolved path resource.

### 4.1 Read own application

```http
GET /api/merchant/application
Permission: merchant:self-view
```

Response data before first submission:

```json
{
  "merchant": null
}
```

After submission, `merchant` is `MerchantDetail`. A bound Merchant that is outside the current Session Tenant is never returned.

### 4.2 Submit or resubmit

```http
POST /api/merchant/application/submissions
Permission on first submission: merchant:submit
Permission on rejected resubmission: merchant:resubmit
```

Request:

```json
{
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f",
  "expectedVersion": null,
  "legalName": "Example Payments Pte. Ltd.",
  "displayName": "Example Pay",
  "registrationCountry": "SG",
  "registrationNumber": "2026-001234-Z"
}
```

Validation:

| Field | Rule |
| --- | --- |
| `idempotencyKey` | required UUID |
| `expectedVersion` | must be JSON `null` on first submission; required non-negative integer on resubmission |
| `legalName` | trimmed, 1..200 Unicode code points |
| `displayName` | trimmed, 1..128 Unicode code points |
| `registrationCountry` | exactly two uppercase ASCII letters and an assigned ISO 3166-1 alpha-2 code |
| `registrationNumber` | required string on first submission; on rejected resubmission it may be omitted only while `registrationCountry` is unchanged, otherwise a nonblank 1..128-code-point value is required; JSON `null` forbidden |

The strict request shape fixes the command before authorization: explicit JSON `expectedVersion: null` selects `submit` and `merchant:submit`; a non-negative integer selects `resubmit` and `merchant:resubmit`. The first submission atomically creates and binds the Merchant in `PENDING_REVIEW`. A resubmission is accepted only from `REVIEW_REJECTED`, updates the profile and returns to `PENDING_REVIEW`. `merchantCode` is generated by the server and treated by clients as an opaque stable value.

The request does not accept `reasonCode`. The server records `APPLICATION_SUBMITTED` for the first submission and `APPLICATION_RESUBMITTED` for an accepted rejected-profile resubmission.

Response data is `MerchantMutationResult`.

## 5. PLATFORM control plane

These paths exist only in the PLATFORM composition root. The actor must hold an ACTIVE assignment to an undeleted protected PLATFORM system Role, and that same Role must hold the exact non-delegable permission. An ordinary Role cannot contribute the permission to a protected-role authorization proof. These paths do not create a target Tenant Membership or impersonation context.

The `merchant:review`, `merchant:disable`, `merchant:enable`, `merchant:terminate` and MCH-002 `merchant:update` catalog entries require recent step-up. In `local` and `iam002-local` only, successful password login records Session `STEP_UP_AT=now` so these sensitive operations can be accepted during the following 10 minutes for local browser verification. That local timestamp is a reauthentication fixture, not LoA 2. A production OIDC login starts without `stepUpAt`; the actor must complete the independent OIDC step-up flow before these mutations are authorized.

### 5.1 Merchant list

```http
GET /api/platform/merchants
Permission: merchant:view
```

Query:

```text
merchantCode?          exact trimmed value
name?                  bounded legal/display-name search, 1..200
status?                one Merchant status
registrationCountry?   assigned ISO alpha-2 code
createdFrom?           UTC ISO-8601 inclusive instant
createdTo?             UTC ISO-8601 exclusive instant; must be after createdFrom
page?                  integer 1..2147483647, default 1
pageSize?              integer 1..100, default 20
```

The list does not accept `tenantId` as an authorization-workspace selector. Every row returns server-derived `merchantId`, `tenantId`, `merchantCode`, names, country, masked registration number, status, `reviewPending`, `rowVersion`, `submittedAt`, `createdAt` and `updatedAt`. `reviewPending` is a required boolean derived by the server: it is `true` only while the Merchant itself is `PENDING_REVIEW` or the Merchant has one exact `PENDING_REVIEW` amendment. It is `false` after review completes when no later amendment is pending. Response data is `{items,total}` and `total` is a JSON integer.

### 5.2 Merchant detail

```http
GET /api/platform/merchants/{merchantId}
Permission: merchant:view
```

`merchantId` is a positive decimal Long encoded in the URL. Response data is `MerchantDetail` with the PLATFORM-only decision actor field described above.

### 5.3 Review decision

```http
POST /api/platform/merchants/{merchantId}/review-decisions
Permission: merchant:review
```

```json
{
  "decision": "APPROVE",
  "reasonCode": "PROFILE_VERIFIED",
  "expectedVersion": 0,
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f"
}
```

`reasonCode` must be one of the action-specific codes in section 2.3. Review accepts only `PENDING_REVIEW`. The actor and target binding are rechecked after all required database locks are acquired.

### 5.4 Disable, enable and terminate

```http
POST /api/platform/merchants/{merchantId}/disable
Permission: merchant:disable

POST /api/platform/merchants/{merchantId}/enable
Permission: merchant:enable

POST /api/platform/merchants/{merchantId}/terminate
Permission: merchant:terminate
```

All three accept:

```json
{
  "reasonCode": "COMPLIANCE_HOLD",
  "expectedVersion": 2,
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f"
}
```

`reasonCode` is required and must be allowed for the exact command by section 2.3. Disable accepts only `ACTIVE`; enable accepts only `DISABLED`; terminate accepts `ACTIVE`, `DISABLED` or `REVIEW_REJECTED`. None of these commands mutates Identity state.

### 5.5 PLATFORM profile update (MCH-002)

```http
PUT /api/platform/merchants/{merchantId}/profile
Permission: merchant:update
```

```json
{
  "expectedVersion": 4,
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f",
  "legalName": "Example Payments Pte. Ltd.",
  "displayName": "Example Pay",
  "merchantTypeCode": "DIRECT",
  "legalPersonName": "Example Director",
  "authenticationType": "ENTERPRISE",
  "remarks": "Priority onboarding",
  "marketCodes": ["BRA", "PHL"]
}
```

The strict request accepts exactly these fields. `expectedVersion` is a required non-negative integer. `Idempotency-Key` is the command concept carried only as the required UUID `idempotencyKey` JSON field. Names retain the MCH-001 limits; `legalPersonName` is Unicode-trimmed and contains 1..200 code points; `remarks` is a required JSON string, Unicode-trimmed and 0..300 code points, where empty string means no Merchant remark; `marketCodes` contains 1..2 distinct values from the current exact allowlist `BRA`, `PHL`, sorted by the server. `merchantTypeCode` is exactly one of `DIRECT`, `INDIRECT`, `COMMISSION`, `SALES`, `PLATFORM`; `authenticationType` is exactly one of `ENTERPRISE`, `NON_PROFIT_ORGANIZATIONS`, `CLIQUE`, `INDIVIDUAL`, `INDIVIDUAL_HOUSEHOLD`. The request does not accept `tenantId`, registration country, registration number, status, status reason, reason code, Realm, portal or account-domain selectors.

Compatibility is replay-only. A complete V34 profile body in which all three V35 fields are absent may pass HTTP decoding only so the fully reauthorized command can recompute command schema 1 and return an already stored matching deduplication result. It can never execute a new profile write: a deduplication miss fails with no Merchant, market, audit or deduplication mutation. A body with only some V35 fields missing is invalid. A complete V35 body uses command schema 2. Both decoders and digest implementations remain retained for the lifetime of their deduplication rows.

Before the MCH-003 cutover, the command accepts only `ACTIVE` or `DISABLED`, never changes status or `statusReasonCode`, rejects a semantic no-op with `40910 MERCHANT_STATE_CONFLICT`, compares `expectedVersion`, and increments `rowVersion` exactly once. Its command type is `UPDATE_PROFILE`; the server derives audit reason `PLATFORM_PROFILE_UPDATED`. The same permanent deduplication, lock-complete authorization recheck and one-transaction rules in section 6 apply.

After the MCH-003 cutover this endpoint is receipt replay-only for both retained command schema 1
and command schema 2. A currently authorized caller may recover an already stored matching result;
a deduplication miss returns `40910 MERCHANT_STATE_CONFLICT` without changing Merchant, market,
document, amendment, audit or deduplication state. `merchant:update` remains only for that authorized
historical replay. The PLATFORM UI no longer calls this endpoint and submits the reviewed amendment
command in section 12 instead.

## 6. Idempotency and concurrency

- `Idempotency-Key` is the lifecycle command concept carried in the strict JSON body as `idempotencyKey`; it is not accepted as a second competing header value.
- The idempotency identity is the exact source account domain, actor Membership, command type and UUID key.
- The canonical request digest is HMAC-SHA-256 over a length-framed normalized command payload using a dedicated versioned idempotency key purpose. It includes the path Merchant ID where applicable, command type, normalized non-sensitive fields, `expectedVersion` and the normalized registration number when supplied. It never reuses the registration search fingerprint or search key. The deduplication row stores only the final digest, idempotency HMAC key ID, command-schema version, canonical-digest-scheme version, registration-normalization version where applicable, exact required permission and bounded original `MerchantMutationResult`; it stores no registration plaintext, search fingerprint or canonical payload.
- A command locks, in a fixed order, the source Tenant, source Membership, User, Credential, Membership-to-Role assignment, protected Role, the same Role's exact Grant, Grant dimensions/targets and Permission rows. After every lock is held, a later database statement obtains current database time and the command revalidates Tenant `ACTIVE` status and account domain, the live Cookie Session, identity/session versions, assignment, protected-role attributes, exact same-role permission, Grant status, dimensions and finite `validUntil`. Only after this final proof does the server look up permanent deduplication by the already-fixed actor, command type and UUID. A matching replay is verified by its stored command schema, required permission and digest versions and returns before any current Merchant state check. Only a deduplication miss may read the Merchant and validate that `submit` sees no binding or `resubmit` sees the locked `REVIEW_REJECTED` version. A valid replay does not reapply the target transition or compare the old `expectedVersion`; revoked, expired or disabled actors cannot retrieve a prior success by replaying its key.
- MCH-001 never deletes deduplication rows. Every referenced HMAC key, command decoder, canonical-digest implementation and registration-normalization implementation remains available for the lifetime of its rows. Replay locates the row by the server-derived idempotency identity and recomputes by the row's recorded versions and key ID. Search-key rotation, normalization evolution and active-idempotency-key rotation do not change equality for an existing request. Missing or unknown recorded key/version returns `50301 PROTECTED_FIELD_UNAVAILABLE` and commits nothing.
- Same identity and same canonical request returns the original `MerchantMutationResult`, including after a client timeout.
- A first-submit response-loss retry keeps command type `submit` because its original request still carries explicit JSON `expectedVersion: null`; the now-present `PENDING_REVIEW` Merchant cannot turn that replay into `resubmit` or a state conflict.
- Same identity with a different canonical request returns `40911 IDEMPOTENCY_CONFLICT` and changes nothing.
- The first submission's permanent unique Tenant binding prevents two concurrent keys from creating two Merchants. The losing distinct command returns `40901 DATA_CONFLICT`.
- Existing-resource commands compare `expectedVersion` in the final guarded state update. A stale version returns `40902 OPTIMISTIC_LOCK_CONFLICT`; the frontend reloads rather than automatically retrying a state decision.
- Every successful mutation writes its lifecycle audit event in the same transaction as the Merchant and deduplication result.
- MCH-002 profile replacement includes normalized `marketCodes`, `remarks`, `merchantTypeCode`, `legalPersonName` and `authenticationType` in the versioned canonical digest. A successful `UPDATE_PROFILE` atomically replaces the market set, updates the profile, increments `rowVersion`, stores the deduplication result and writes `PLATFORM_PROFILE_UPDATED`; partial replacement is impossible.

## 7. Error contract

| HTTP | code | error | Meaning |
| --- | ---: | --- | --- |
| 400 | 40001 | `INVALID_REQUEST` | malformed/unknown field, invalid country, enum, UUID, ID, version, page or time range |
| 401 | 40101 | `AUTH_REQUIRED` | no valid Cookie Session |
| 401 | 40102 | `SESSION_INVALID` | Session account domain, Tenant, Membership, identity or version is no longer valid |
| 403 | 40301 | `PERMISSION_DENIED` | missing exact permission, wrong composition root, untrusted applicable browser request or non-protected PLATFORM actor |
| 404 | 40401 | `RESOURCE_NOT_FOUND` | Merchant does not exist in the endpoint's allowed resource scope |
| 409 | 40901 | `DATA_CONFLICT` | Tenant already bound, registration fingerprint duplicate or another invariant/unique conflict |
| 409 | 40902 | `OPTIMISTIC_LOCK_CONFLICT` | expected version is stale |
| 409 | 40910 | `MERCHANT_STATE_CONFLICT` | the command is not legal from the current Merchant state |
| 409 | 40911 | `IDEMPOTENCY_CONFLICT` | idempotency key was already used with a different canonical request |
| 503 | 50301 | `PROTECTED_FIELD_UNAVAILABLE` | required registration-number protection key/service is unavailable; no write committed |

`OPTIMISTIC_LOCK_CONFLICT` is code `40902`; `MERCHANT_STATE_CONFLICT` is code `40910`; `IDEMPOTENCY_CONFLICT` is code `40911`.

Messages remain bounded and do not reveal registration plaintext, fingerprint, encryption metadata, whether another Tenant owns the registration number, or internal stack details. Cross-scope reads use `RESOURCE_NOT_FOUND`; authentication and permission failures do not disclose target existence.

## 8. Permission catalog

| Permission | Composition root | Role boundary | Operation |
| --- | --- | --- | --- |
| `merchant:self-view` | MERCHANT | protected MERCHANT system Role | view bound Merchant/application state |
| `merchant:submit` | MERCHANT | protected MERCHANT system Role | first submission |
| `merchant:resubmit` | MERCHANT | protected MERCHANT system Role | rejected profile correction and resubmission |
| `merchant:view` | PLATFORM | protected PLATFORM system Role, non-delegable | list/detail |
| `merchant:review` | PLATFORM | protected PLATFORM system Role, non-delegable | approve/reject |
| `merchant:disable` | PLATFORM | protected PLATFORM system Role, non-delegable | ACTIVE to DISABLED |
| `merchant:enable` | PLATFORM | protected PLATFORM system Role, non-delegable | DISABLED to ACTIVE |
| `merchant:terminate` | PLATFORM | protected PLATFORM system Role, non-delegable | terminal lifecycle command |

MCH-002 adds exactly one protected permission outside the frozen MCH-001 permission set:

| Permission | Composition root | Role boundary | Operation |
| --- | --- | --- | --- |
| `merchant:update` | PLATFORM | protected PLATFORM system Role, non-delegable, recent step-up | update ACTIVE/DISABLED profile and operating markets |

AGENT exposes no MCH-001 path or permission. Frontend route and button visibility are presentation only; backend composition roots, permission policy, protected-role check, trusted Session scope and database predicates all remain mandatory.

## 9. Exact endpoint inventory

The complete MCH-001 HTTP surface is exactly:

- `GET /api/merchant/application`
- `POST /api/merchant/application/submissions`
- `GET /api/platform/merchants`
- `GET /api/platform/merchants/{merchantId}`
- `POST /api/platform/merchants/{merchantId}/review-decisions`
- `POST /api/platform/merchants/{merchantId}/disable`
- `POST /api/platform/merchants/{merchantId}/enable`
- `POST /api/platform/merchants/{merchantId}/terminate`

## 10. Compatibility and implementation status

This is a new API, so there is no legacy HTTP compatibility alias and no fallback to Identity Tenant CRUD. The current working tree includes the endpoint, permission and migration implementation. A partial deployment that lacks any of them must keep navigation disabled and must not reinterpret `404` as an empty implemented capability.

Focused contract, state, permission, tenant-isolation, registration-protection, PostgreSQL concurrency/idempotency and frontend tests exist. Local real-browser acceptance confirmed MERCHANT submit to `PENDING_REVIEW`, PLATFORM approve to `ACTIVE`, disable to `DISABLED`, enable back to `ACTIVE`, synchronized PLATFORM/MERCHANT state, localized timestamps and reason labels, recovery from the previous MERCHANT profile-page deadlock and no AGENT Merchant menu. The frontend rejects invalid `registrationNumberMasked` shapes and queues the latest PLATFORM list query so stale responses cannot overwrite the newest result. Rejected resubmission, termination and production identity/infrastructure evidence remain outside this local browser run. Exact-SHA repository gates and signed author-independent review results decide formal closure; this contract alone remains Candidate / Production NO-GO.

## 11. MCH-002 extension inventory and compatibility

MCH-002 adds exactly one endpoint, `PUT /api/platform/merchants/{merchantId}/profile`, and no MERCHANT or AGENT write path. V34 is append-only: it adds `remarks`, normalized operating-market rows, the protected `merchant:update` permission and its audit/idempotency support without modifying V32/V33. V35 is also append-only and adds nullable historical storage for `merchantTypeCode`, `legalPersonName` and `authenticationType`, plus exact dictionary projections for the two classification selects; V34 is not rewritten. V36 adds the named `UNIQUE (merchant_id, merchant_version)` audit boundary. Existing rows expose `marketCodes: []` and nullable classification fields because neither profile migration invents historical business facts.

The current worktree contains Candidate implementation and local browser/migration evidence, but no immutable exact-SHA conclusion yet. The Flyway callbacks `beforeEachMigrate__prepare_v34_status_reason_backfill.sql` and `beforeEachMigrate__reject_ambiguous_v34_status_reason_evidence.sql` are permanently frozen migration resources. Only canonical V33 immediately before V34 may install the narrow bridge, and the audit preflight first requires exact audit columns/constraints and at most one event per `(merchant_id, merchant_version)`; either callback failure rolls the whole V34 transaction back. V34 then replaces the bridge with its strict function, while V36 enforces the same audit-version uniqueness for later writes. Because SQL callbacks have no `flyway_schema_history` checksum row, the MCH-002 checker pins both callback paths, SHA-256 values, fail-closed guards and the V36 migration boundary. Any callback change requires a new reviewed contract decision. A database already stopped at V34/V35 with duplicate audit versions remains unavailable until a separately approved forward repair; operators must not delete or rewrite append-only audit rows and retry.

## 12. MCH-003 assisted onboarding, amendments and documents

The earlier sentence `MCH-003 is an accepted target with implementation pending` is historical and
no longer describes this worktree. MCH-003 now has a mutable Candidate implementation and focused
frontend/backend/migration/security evidence. Three composition roots plus blackbox, frontend full
gates, persisted V39 local-volume compatibility and the real-browser create/review/edit workflow
have been exercised. Unified backend `clean verify`: PASS. 95 XML reports / 678 tests / 0 failures / 0 errors. Immutable exact-SHA gates and signed
review still decide formal closure, so it is still Production NO-GO. A `404` must never be reinterpreted as an empty
successful capability.

### 12.1 Domain values and permissions

Amendment status is separate from Merchant lifecycle status and is exactly:

```text
PENDING_REVIEW
APPROVED
REJECTED
STALE
```

Document kind is exactly:

```text
BRAND_LOGO
BUSINESS_LICENSE
LEGAL_ID_FRONT
LEGAL_ID_BACK
LEGAL_ID_HOLDING
```

MCH-003 adds four PLATFORM-only, non-delegable protected permissions. One assigned protected
PLATFORM system Role must itself hold the exact permission; combining Roles is invalid. All four
commands require recent step-up:

| Permission | Operation |
| --- | --- |
| `merchant:create` | list eligible target Tenants and submit a PLATFORM-assisted application |
| `merchant:amend` | submit a full reviewed amendment for an ACTIVE/DISABLED Merchant |
| `merchant:document:upload` | create/read/delete the actor's temporary protected document |
| `merchant:document:view` | stream an attached Merchant document after target authorization |

The existing `merchant:review` permission and recent step-up authorize both create-application and
amendment review, subject to the exact author-Membership separation below. MERCHANT and AGENT never
receive an MCH-003 permission.

### 12.2 Eligible Tenant directory

```http
GET /api/platform/merchant-onboarding/eligible-tenants
Permission: merchant:create
```

The query accepts optional bounded `tenantCode`, `tenantName`, `page` and `pageSize`. Each result is
limited to `tenantId`, `tenantCode` and `tenantName`; every ID is a JSON string. The repository query
must require the Tenant to be `ACTIVE`, account-domain `MERCHANT` and absent from the permanent
Merchant binding. It returns `{items,total}`. This read is a convenience only: create locks and
revalidates the target Tenant and unique binding in its own transaction.

Exact response data:

```json
{
  "items": [
    {
      "tenantId": "620000000000000001",
      "tenantCode": "merchant-example",
      "tenantName": "Example Merchant Workspace"
    }
  ],
  "total": 1
}
```

### 12.3 Exact 23-input profile

Create and amendment use one strict nested `profile` object with these 23 business inputs:

```json
{
  "displayName": "Example Pay",
  "brandName": "Example",
  "authenticationType": "ENTERPRISE",
  "merchantTypeCode": "PLATFORM",
  "industryCode": "FINANCIAL_SERVICES",
  "brandLogoDocumentId": "740000000000000006",
  "legalName": "Example Payments Ltd.",
  "registrationCountry": "BR",
  "marketCodes": ["BRA", "PHL"],
  "registeredAddress": "Registered address",
  "operatingAddress": "Operating address",
  "businessLicenseDocumentId": "740000000000000002",
  "legalPersonName": "Example Director",
  "contactEmail": "merchant-contact@example.test",
  "contactPhone": "+551100000000",
  "legalIdTypeCode": "NATIONAL_ID",
  "legalIdNo": {"mode": "REPLACE", "value": "SYNTHETIC-ID-0001"},
  "legalIdValidity": {"validFrom": "2026-01-01", "validTo": "2036-01-01"},
  "legalIdFrontDocumentId": "740000000000000003",
  "legalIdBackDocumentId": "740000000000000004",
  "legalIdHoldingDocumentId": "740000000000000005",
  "remarks": "Synthetic onboarding fixture",
  "registrationNumber": {"mode": "REPLACE", "value": "SYNTHETIC-REG-0001"}
}
```

Unknown, missing or JSON `null` fields fail strict decoding. All 23 inputs are required; `remarks`
is the only input that may be the empty string. `receiveEmail` is not accepted. The string limits
from MCH-001/MCH-002 continue to apply; `brandName` is 1..128 code points, `industryCode` and
`legalIdTypeCode` are fixed application allowlists, both addresses are 1..300 code points,
`contactEmail` is a syntactically valid 3..254-code-point address, `contactPhone` is an E.164 value,
and `legalIdValidity.validFrom <= legalIdValidity.validTo` uses ISO calendar dates. `remarks` is the
one field allowed to be an empty string. Document IDs are positive Long strings and must match the
temporary/attached rules below.

For create, all five document IDs must identify unexpired same-actor, same-target, same-kind
`TEMPORARY` uploads and are consumed exactly once. For amendment, each required document ID is
classified independently: it either equals the exact current same-kind Merchant binding (`RETAIN`)
or identifies an unexpired same-actor, same-target, same-kind temporary upload (`REPLACE`).
Zero, one or multiple replacements are valid, but a superseded Merchant document, another Merchant's
document, another actor's temporary upload, a kind mismatch or any arbitrary permanent document is
rejected with `40913 DOCUMENT_ATTACHMENT_CONFLICT`.

The initial exact `industryCode` values are `FINANCIAL_SERVICES`, `ECOMMERCE`, `RETAIL`, `TRAVEL`,
`EDUCATION` and `OTHER`. The initial exact `legalIdTypeCode` values are `NATIONAL_ID`, `PASSPORT` and
`DRIVER_LICENSE`. Application code owns both allowlists and bilingual labels; dictionary rows may
only supply order/color and may not make another value legal.

The additive seed dictionary types are exactly `MERCHANT_INDUSTRY_CODE` (`商户-行业`) and
`MERCHANT_LEGAL_ID_TYPE` (`商户-法人证件类型`), both with type sort `0` and empty remark. Their exact
initial data rows are:

| Dictionary type | value | seed label | color | sort |
| --- | --- | --- | --- | ---: |
| `MERCHANT_INDUSTRY_CODE` | `FINANCIAL_SERVICES` | 金融服务 | `processing` | 1 |
| `MERCHANT_INDUSTRY_CODE` | `ECOMMERCE` | 电商 | `success` | 2 |
| `MERCHANT_INDUSTRY_CODE` | `RETAIL` | 零售 | `purple` | 3 |
| `MERCHANT_INDUSTRY_CODE` | `TRAVEL` | 旅行 | `warning` | 4 |
| `MERCHANT_INDUSTRY_CODE` | `EDUCATION` | 教育 | `default` | 5 |
| `MERCHANT_INDUSTRY_CODE` | `OTHER` | 其他 | `default` | 6 |
| `MERCHANT_LEGAL_ID_TYPE` | `NATIONAL_ID` | 国民身份证 | `processing` | 1 |
| `MERCHANT_LEGAL_ID_TYPE` | `PASSPORT` | 护照 | `success` | 2 |
| `MERCHANT_LEGAL_ID_TYPE` | `DRIVER_LICENSE` | 驾驶证 | `warning` | 3 |

The forward migration is absent-or-exact and increments the dictionary catalog revision once when
it inserts or converges any MCH-003 projection. Partial, duplicate, modified or tombstoned
collisions fail closed. API/frontend validation never trusts the seed label, color or presence to
expand either allowlist.

Create accepts only `{mode:"REPLACE",value}` for both protected-number objects. Amendment accepts
exactly `{mode:"RETAIN"}` or `{mode:"REPLACE",value}`. JSON `null`, an empty value, extra object
keys or a value accompanying `RETAIN` is invalid. Changing `registrationCountry` requires
registration-number `REPLACE`; changing `legalIdTypeCode` requires legal-ID `REPLACE`.
Responses return `registrationNumberMasked` and `legalIdNoMasked` only. Neither plaintext value is
returned to or persisted by the browser.

`registrationCountry` is ISO 3166-1 alpha-2. `marketCodes` is a sorted distinct 1..2-element set of
ISO 3166-1 alpha-3 `BRA` and `PHL`. New `merchantTypeCode` writes and responses are exactly
`PLATFORM`, `INDIRECT`, `COMMISSION` or `SALES`; DIRECT is replay-only and `PLATFORM` is canonical.

### 12.4 PLATFORM-assisted create

```http
POST /api/platform/merchants
Permission: merchant:create
```

```json
{
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f",
  "targetTenantId": "620000000000000001",
  "profile": {"...": "the exact section 12.3 object"}
}
```

The target is always an existing `ACTIVE` MERCHANT Tenant with no Merchant binding. The command
creates no IAM row and grants no target Membership to the PLATFORM actor. After complete source
authorization, it locks and revalidates the target Tenant, acquires the existing registration-write
fence, applies registration/legal-ID/document protection, consumes every document exactly once,
and atomically creates the Merchant in `PENDING_REVIEW`, markets, immutable application-author
Membership, lifecycle audit and permanent deduplication result. The status reason is server-derived
`PLATFORM_APPLICATION_SUBMITTED`.

The existing create-application review endpoint continues to accept `APPROVE` with
`PROFILE_VERIFIED` and `REJECT` with its MCH-001 rejection reasons. Before deduplication or decision
write it rejects the same Membership recorded as the create author with `40301 PERMISSION_DENIED`.
A different Membership may approve to `ACTIVE` or reject to `REVIEW_REJECTED`. The numeric identity
of a Membership, not an inferred natural person, is the enforceable boundary.

Create response data remains the exact `MerchantMutationResult` shape:

```json
{
  "merchantId": "720000000000000001",
  "merchantCode": "MCH_EXAMPLE_ALPHA",
  "status": "PENDING_REVIEW",
  "rowVersion": 0
}
```

### 12.5 Current effective PLATFORM detail

MCH-003 extends the existing endpoint; it does not add a parallel detail route:

<!-- MCH-003-CURRENT-EFFECTIVE-DETAIL -->

```http
GET /api/platform/merchants/{merchantId}
Permission: merchant:view
```

Its response remains the section 3.1 `MerchantDetail`, extended with the complete current effective
profile needed to initialize edit. The exact PLATFORM response data shape is:

```json
{
  "merchantId": "720000000000000001",
  "tenantId": "620000000000000001",
  "merchantCode": "MCH_EXAMPLE_ALPHA",
  "legalName": "Example Payments Ltd.",
  "displayName": "Example Pay",
  "brandName": "Example",
  "authenticationType": "ENTERPRISE",
  "merchantTypeCode": "PLATFORM",
  "industryCode": "FINANCIAL_SERVICES",
  "brandLogoDocument": {"documentId": "740000000000000006", "kind": "BRAND_LOGO", "mediaType": "image/png", "width": 512, "height": 512, "sizeBytes": 65536},
  "registrationCountry": "BR",
  "registrationNumberMasked": "********0001",
  "marketCodes": ["BRA", "PHL"],
  "registeredAddress": "Registered address",
  "operatingAddress": "Operating address",
  "businessLicenseDocument": {"documentId": "740000000000000002", "kind": "BUSINESS_LICENSE", "mediaType": "image/jpeg", "width": 1600, "height": 1200, "sizeBytes": 524288},
  "legalPersonName": "Example Director",
  "contactEmail": "merchant-contact@example.test",
  "contactPhone": "+551100000000",
  "legalIdTypeCode": "NATIONAL_ID",
  "legalIdNoMasked": "********0001",
  "legalIdValidity": {"validFrom": "2026-01-01", "validTo": "2036-01-01"},
  "legalIdFrontDocument": {"documentId": "740000000000000003", "kind": "LEGAL_ID_FRONT", "mediaType": "image/jpeg", "width": 1200, "height": 800, "sizeBytes": 262144},
  "legalIdBackDocument": {"documentId": "740000000000000004", "kind": "LEGAL_ID_BACK", "mediaType": "image/jpeg", "width": 1200, "height": 800, "sizeBytes": 262144},
  "legalIdHoldingDocument": {"documentId": "740000000000000005", "kind": "LEGAL_ID_HOLDING", "mediaType": "image/jpeg", "width": 1200, "height": 1600, "sizeBytes": 393216},
  "remarks": "Current effective profile",
  "status": "ACTIVE",
  "statusReasonCode": "PROFILE_VERIFIED",
  "rowVersion": 7,
  "submittedAt": "2026-08-15T08:00:00Z",
  "reviewedAt": "2026-08-15T09:00:00Z",
  "lastDecision": {"decision": "APPROVE", "reasonCode": "PROFILE_VERIFIED", "decidedAt": "2026-08-15T09:00:00Z"},
  "createdAt": "2026-08-15T08:00:00Z",
  "updatedAt": "2026-08-15T09:00:00Z"
}
```

All five document members use the exact `MerchantDocumentMetadata` shape shown above and are never
URLs. A Merchant created under MCH-003, or successfully updated by an MCH-003 amendment, returns
non-null values for every MCH-003 profile member. A pre-V37 historical row may return JSON `null`
only for `brandName`, `industryCode`, `registeredAddress`, `operatingAddress`, `contactEmail`,
`contactPhone`, `legalIdTypeCode`, `legalIdValidity`, `legalIdNoMasked` and any of the five document
members when the source fact is unavailable. Existing MCH-002 nullable-field compatibility still
applies. `registrationNumberMasked` remains required and masked. The edit form must require
completion or replacement of every missing required field; absent protected numbers require
`REPLACE`, and absent documents require a new temporary upload. The server never invents values.

<!-- /MCH-003-CURRENT-EFFECTIVE-DETAIL -->

### 12.6 Reviewed amendment

```http
POST /api/platform/merchants/{merchantId}/amendments
Permission: merchant:amend
```

```json
{
  "expectedMerchantVersion": 7,
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f",
  "profile": {"...": "the exact section 12.3 object"}
}
```

Only `ACTIVE` or `DISABLED` may submit. One Merchant may have at most one `PENDING_REVIEW`
amendment. The transaction compares the current version, verifies no pending amendment, consumes
only `REPLACE` temporary documents into the amendment, records all five immutable
`RETAIN`/`REPLACE` document references, protects replacement values and records immutable
`originMerchantVersion`, `originStatus`, author Membership, full proposed profile, status
`PENDING_REVIEW`, `PLATFORM_AMENDMENT_SUBMITTED` audit and permanent idempotency. It does not modify
the Merchant row, markets, current documents, lifecycle status, status reason or `rowVersion`.
Approval promotes and binds only `REPLACE` references; `RETAIN` references must still resolve to the
same current Merchant/kind binding and are not reattached or superseded. Pending detail and content
reads resolve the amendment's exact five references, so a reviewer sees retained current evidence
for unchanged fields and amendment-owned evidence for replacements without fallback.

Amendment submission response data is:

```json
{
  "amendmentId": "750000000000000001",
  "merchantId": "720000000000000001",
  "status": "PENDING_REVIEW",
  "rowVersion": 0,
  "originMerchantVersion": 7,
  "originStatus": "ACTIVE",
  "createdAt": "2026-08-15T08:00:00Z"
}
```

```http
GET /api/platform/merchants/{merchantId}/amendments/pending
Permission: merchant:view

POST /api/platform/merchants/{merchantId}/amendments/{amendmentId}/review-decisions
Permission: merchant:review
```

When the Merchant exists in the actor's authorized scope but has no `PENDING_REVIEW` amendment, the
pending read returns HTTP `200` with `data:null` in the ordinary `ApiResponse` envelope. A nonexistent
or cross-scope Merchant still returns `40401 RESOURCE_NOT_FOUND`; absence is not used to reveal scope.

The pending read returns this exact data shape. All five document members are required
`MerchantDocumentMetadata` objects and are never URL strings. `authorMembershipId` is visible only
inside this protected PLATFORM review response; `canCurrentActorReview` is presentation help and
never replaces backend enforcement.

```json
{
  "amendmentId": "750000000000000001",
  "merchantId": "720000000000000001",
  "status": "PENDING_REVIEW",
  "rowVersion": 0,
  "originMerchantVersion": 7,
  "originStatus": "ACTIVE",
  "authorMembershipId": "630000000000000001",
  "canCurrentActorReview": false,
  "profile": {
    "displayName": "Example Pay",
    "brandName": "Example",
    "authenticationType": "ENTERPRISE",
    "merchantTypeCode": "PLATFORM",
    "industryCode": "FINANCIAL_SERVICES",
    "brandLogoDocument": {"documentId": "740000000000000006", "kind": "BRAND_LOGO", "mediaType": "image/png", "width": 512, "height": 512, "sizeBytes": 65536},
    "legalName": "Example Payments Ltd.",
    "registrationCountry": "BR",
    "registrationNumberMasked": "********0001",
    "marketCodes": ["BRA", "PHL"],
    "registeredAddress": "Registered address",
    "operatingAddress": "Operating address",
    "businessLicenseDocument": {"documentId": "740000000000000002", "kind": "BUSINESS_LICENSE", "mediaType": "image/jpeg", "width": 1600, "height": 1200, "sizeBytes": 524288},
    "legalPersonName": "Example Director",
    "contactEmail": "merchant-contact@example.test",
    "contactPhone": "+551100000000",
    "legalIdTypeCode": "NATIONAL_ID",
    "legalIdNoMasked": "********0001",
    "legalIdValidity": {"validFrom": "2026-01-01", "validTo": "2036-01-01"},
    "legalIdFrontDocument": {"documentId": "740000000000000003", "kind": "LEGAL_ID_FRONT", "mediaType": "image/jpeg", "width": 1200, "height": 800, "sizeBytes": 262144},
    "legalIdBackDocument": {"documentId": "740000000000000004", "kind": "LEGAL_ID_BACK", "mediaType": "image/jpeg", "width": 1200, "height": 800, "sizeBytes": 262144},
    "legalIdHoldingDocument": {"documentId": "740000000000000005", "kind": "LEGAL_ID_HOLDING", "mediaType": "image/jpeg", "width": 1200, "height": 1600, "sizeBytes": 393216},
    "remarks": "Synthetic amendment fixture"
  },
  "decision": null,
  "createdAt": "2026-08-15T08:00:00Z",
  "updatedAt": "2026-08-15T08:00:00Z"
}
```

`decision`, when non-null, is exactly
`{"decision":"REJECT","reasonCode":"PROFILE_AMENDMENT_MISMATCH","decidedAt":"2026-08-15T09:00:00Z"}`.
The review request is:

```json
{
  "decision": "APPROVE",
  "reasonCode": "PROFILE_AMENDMENT_VERIFIED",
  "expectedVersion": 0,
  "idempotencyKey": "8ce154cf-4f13-4aac-b0de-74922513a14f"
}
```

Approve allows only `PROFILE_AMENDMENT_VERIFIED`. Reject allows exactly
`PROFILE_AMENDMENT_MISMATCH`, `DOCUMENT_UNVERIFIED` or `COMPLIANCE_REJECTED`. The author Membership
cannot decide its own amendment. Approval locks the pending amendment and Merchant, proves the
origin version/status still match, applies the complete proposal, supersedes replaced documents,
increments Merchant `rowVersion` exactly once, preserves `ACTIVE` or `DISABLED`, changes amendment
status to `APPROVED`, and commits Merchant/amendment audit and idempotency together. Rejection sets
only amendment status `REJECTED` and its decision/audit/idempotency; Merchant data and version do not
change. Origin drift atomically changes amendment status to `STALE`, records
`PLATFORM_AMENDMENT_STALE`, leaves Merchant unchanged and returns `40902 OPTIMISTIC_LOCK_CONFLICT`.
The author must submit a new amendment. MERCHANT self-submit/resubmit never consumes a PLATFORM
amendment.

Approve/reject response data is exact and does not echo the profile:

```json
{
  "amendmentId": "750000000000000001",
  "merchantId": "720000000000000001",
  "status": "APPROVED",
  "rowVersion": 1,
  "merchantStatus": "ACTIVE",
  "merchantRowVersion": 8
}
```

For `REJECTED`, `merchantRowVersion` equals the unchanged origin version. A stale decision commits
the amendment's `STALE` transition and audit, then returns the ordinary error envelope with
`data:null`, code `40902` and error `OPTIMISTIC_LOCK_CONFLICT`; clients reload and start a new
amendment rather than retrying the old decision automatically.

### 12.7 Protected document protocol

```http
POST /api/platform/merchant-document-uploads
Permission: merchant:document:upload
Content-Type: multipart/form-data

GET /api/platform/merchant-document-uploads/{documentId}/content
Permission: merchant:document:upload

DELETE /api/platform/merchant-document-uploads/{documentId}
Permission: merchant:document:upload

GET /api/platform/merchants/{merchantId}/documents/{kind}/content
Permission: merchant:document:view
Optional query: amendmentId={positive Long string}
```

This remains one endpoint and does not add a tenth MCH-003 path. With `amendmentId` omitted, it
resolves only the document attached to the current effective Merchant profile for `{kind}`. With
`?amendmentId={amendmentId}`, it resolves only the pending amendment attachment for the exact
`merchantId`, positive Long-string `amendmentId` and `{kind}` tuple. The supplied amendment must
belong to that Merchant and be `PENDING_REVIEW`. Its immutable reference must resolve either the
exact current same-kind Merchant binding marked `RETAIN`, or an amendment-owned same-kind attachment
marked `REPLACE`; Tenant, Merchant, amendment, kind and mode must all match.

Both variants require `merchant:document:view`, recent step-up and the same assigned protected
PLATFORM system Role holding that exact permission. A missing amendment, cross-Merchant amendment,
wrong kind, non-`PENDING_REVIEW` amendment or mismatched document scope returns the same `40401
RESOURCE_NOT_FOUND` without revealing which predicate failed. The pending amendment review UI must
pass `amendmentId` and must not fall back to the current effective document on any error. Reviewers
therefore inspect the immutable proposed evidence, never the currently approved evidence by accident.
Binary response bytes and headers are identical for current and amendment attachments.

Multipart accepts exactly `targetTenantId`, `kind` and `file`. Only PNG and JPEG magic bytes are
accepted. Client media type, extension and original filename are untrusted and never persisted.
Both input and sanitized output are at most 2 MiB; width/height are each 1..4096 and decoded pixels
are at most 12,000,000. Before allocating a full raster, ImageIO reads dimensions and rejects limits;
it then fully decodes and re-encodes the image, removing original metadata and hidden payloads.
Decode, truncation, dimensions, pixel, magic or re-encoded-size failure returns `40001
INVALID_REQUEST`. Original bytes are discarded.

The sanitized bytes are encrypted with AES-256-GCM under an external Merchant-document-only key
purpose, a unique 96-bit nonce generated by a CSPRNG, fixed 128-bit tag and authenticated AAD over document ID, target Tenant,
actor Membership, kind, sanitized media type, dimensions, size, protection version, algorithm and
key ID. Persistence is private `BYTEA` ciphertext plus metadata and a unique `(key_id, nonce)`
constraint. There is no URL/path/object-key field.

Upload returns `{documentId,kind,mediaType,width,height,sizeBytes,expiresAt}` with the Long ID as a
string and a server expiry exactly 30 minutes after successful upload. Temporary preview/delete
requires the same currently authorized actor Membership, target Tenant and kind binding. Create may
attach it once and only for its bound field kind. Amendment may either retain
the exact current same-kind Merchant binding or attach a same-actor temporary replacement once;
attachment/reference creation is part of the business transaction. Expired, mismatched or already
consumed replacement IDs fail closed.
Cleanup deletes only expired temporary rows. Attached and rejected-amendment documents remain
private evidence and are never deleted by temporary cleanup.

Attached reads resolve the document only through the Merchant/kind path, reauthorize the actor and
target, and when supplied the pending amendment context, decrypt and stream the sanitized bytes with exact server-owned media type,
`Cache-Control: no-store` and no original filename. Temporary and attached reads never return JSON
containing bytes or an indirect public location.

Exact upload response data:

```json
{
  "documentId": "740000000000000006",
  "kind": "BRAND_LOGO",
  "mediaType": "image/png",
  "width": 512,
  "height": 512,
  "sizeBytes": 65536,
  "expiresAt": "2026-08-15T08:30:00Z"
}
```

Exact delete response data is:

```json
{"documentId": "740000000000000006", "status": "DELETED"}
```

Both temporary and attached content responses use only server-owned
`Content-Type: image/png|image/jpeg`, exact `Content-Length`, `Content-Disposition: inline`,
`Cache-Control: no-store`, `Pragma: no-cache`, `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: sandbox; default-src 'none'`. Success contains raw sanitized image bytes,
not an `ApiResponse` envelope. Errors before streaming use the normal JSON error envelope. A
decrypt/stream failure terminates the response, emits no fallback object and records only bounded
server-side metadata without ciphertext or protected values.

### 12.8 Legal-ID protection

`legalIdNo` uses AES-256-GCM with a key purpose separate from registration, document and idempotency
keys, a unique 96-bit nonce generated by a CSPRNG, fixed 128-bit tag, unique key-ID/nonce and AAD binding Merchant, Tenant,
`legalIdTypeCode`, field name, protection version, algorithm and key ID. Only
`legalIdNoMasked` reaches list/detail/amendment responses. Plaintext is absent from URL, browser
storage, logs, errors, ordinary audit and stored canonical payloads. Versioned idempotency includes
the normalized replacement only inside the final HMAC digest.

### 12.9 Offline protected-field rotation

V39 extends the isolated offline `payment_merchant_registration_rotation` capability to the four
protection purposes `REGISTRATION_SEARCH_HMAC`, `REGISTRATION_AEAD`, `LEGAL_ID_AEAD` and
`DOCUMENT_AEAD`. Web runtimes cannot assume or invoke this capability. Rotation is cryptographic
maintenance, not a Merchant or amendment business mutation: proposal business values, masked values,
status, `rowVersion`, origin version/status, author, decision and audit history remain unchanged.
The guarded offline transaction may replace ciphertext, nonce, authentication tag, fingerprint,
key ID, algorithm/protection metadata only as needed to rewrap the same plaintext. It creates no
business audit event or idempotency receipt.

AAD semantics remain unchanged. Merchant, Tenant, amendment/document identity, registration country,
legal-ID type, field name, document kind/media metadata and protection-purpose bindings stay the same;
only the cryptographic key/version members of the authenticated tuple may advance. Rewrap must
decrypt/authenticate under the recorded old key, produce the same mask and normalized business value,
allocate a new globally registered nonce, re-encrypt under the target key and prove complete coverage
before active-key cutover. Plaintext is never returned, logged or persisted outside the protected
write boundary.

Registration Search-HMAC/AEAD rotation covers every current Merchant and every retained amendment
registration value before active-key cutover or old-key retirement. Legal-ID rotation covers every
non-null current and retained amendment legal-ID value. Document rotation covers every non-null
temporary, current-effective, pending, rejected or otherwise retained evidence row. A historical
legal-ID or document field that is JSON/database null remains null; rotation never invents evidence.
No old key may retire while any retained row still references it.

Canonical serialization is fail closed. Runtime writes and full rotation acquire fences in this
order: `mch-registration-write`, `mch-registration-key-rotation`,
`mch-legal-id-key-rotation`, `mch-document-key-rotation`, taking only the relevant ordered prefix for
a narrower write. Rotation then locks key metadata by `(key_purpose,key_id)` and protected rows by
stable ID before rewrap and atomic cutover. Business authorization independently locks the source
Tenant and Membership with `FOR SHARE` before User/Credential/assignment/Role/Grant/Dimension locks
and later database-time revalidation. Create, amendment, approval, cleanup and rotation concurrency
must serialize through these same fences so a committed row cannot restore a retired key, escape
cleanup ownership or observe a mixed key set.

### 12.10 MCH-003 errors, compatibility and UI

In addition to section 7:

| HTTP | code | error | Meaning |
| --- | ---: | --- | --- |
| 409 | 40912 | `AMENDMENT_ALREADY_PENDING` | another pending amendment owns the review slot |
| 409 | 40913 | `DOCUMENT_ATTACHMENT_CONFLICT` | document is neither the exact current same-kind binding nor an attachable same-actor temporary replacement |

Cross-scope document and amendment reads return `40401 RESOURCE_NOT_FOUND`; author-review and
missing exact permissions return `40301 PERMISSION_DENIED`. Origin version/status drift uses
`40902 OPTIMISTIC_LOCK_CONFLICT`. All errors are bounded and never disclose plaintext, ciphertext,
document existence across scope or another Tenant's registration identity.

V35 and its dictionary seed are immutable. V37 or later forward-maps target `DIRECT` classifications
to canonical `PLATFORM`, retires `DIRECT` from the active dictionary projection and prevents new
DIRECT writes without deleting historical receipt decoders. DIRECT is replay-only. After cutover,
the MCH-002 profile endpoint is an authorized existing-receipt replay surface only; a deduplication
miss returns `40910 MERCHANT_STATE_CONFLICT` and the UI never calls it.

The PLATFORM list routes create and edit to one full-page form component. Create uses section 12.4;
edit initializes from section 12.5, submits through section 12.6 and displays pending amendment
state without replacing the effective profile. Detail and review use separate hidden full-page routes
backed by one read-only 23-field/five-document presentation. The review route renders either the
exact pending create profile or the exact pending amendment profile and exposes one `Review` action.
That action opens a modal for approve/reject plus the decision-compatible bounded reason
code and submits through section 5.3 or section 12.6 as appropriate. The ordinary detail route has
no review action. Neither route exposes edit, disable or terminate as review shortcuts or reuses
upload controls. Both pages place the back icon in the Vben `Page` title slot; the review action, when
available, occupies the page-header extra slot rather than a separate content toolbar. The list renders
the review entry only when the row's server-derived `reviewPending` is `true`.
Market columns are centered and display only localized country names. Effective visible operation
counts up to three render inline; a fourth or later action uses the existing click-triggered overflow
menu. All Select controls remain clearable; clearing a required field must fail form validation
rather than submit a partial profile.

The `registrationCountry` Select contains assigned ISO 3166-1 alpha-2 codes only. In amendment edit,
changing `registrationCountry` forces `registrationNumber` to `REPLACE`, and changing
`legalIdTypeCode` forces `legalIdNo` to `REPLACE`; the UI keeps that mode locked until the original
context value is restored. This presentation guard mirrors the server contract and never replaces
server validation.

Current mutable verification evidence includes Node.js 24 frontend full gates (128 files/975 tests,
five typechecks, 31 production-safety tests and all three application artifacts). Live UI verification
confirmed three direct list actions with no overflow, centered localized markets, matching full-page
detail/review evidence, the single review modal action, and complete create/edit forms; page-internal
timing measured about 335ms for create mount and 460ms for edit hydration with no new business console
errors or warnings. Unmount invalidates late protected-document previews and create/amend responses
before Blob URL creation, success notification or navigation. Temporary uploads taking part in an
in-flight mutation are not deleted by component teardown while server-side attachment is unresolved.
The same pending mutation disables Tenant, form, protected-value mode and upload controls; component
methods reject delete, replacement and Tenant invalidation until the request resolves.
A fresh author-independent review remains required after freeze. The latest Maven three-composition-root plus blackbox
`verify -am` passed 17/17 reactor projects with `BUILD SUCCESS`; the IAM blackbox passed 10/10 and
the run completed in 07:56. `LocalIdentityFixtureBootstrapIntegrationTest` passed 61/61 with JDK 25 and PostgreSQL
18. Its automated persistence sequence shares the lineage across direct `local` and persisted
`iam002-local`, consumes candidate1 through candidate9 before creating candidate10, preserves all
nine Merchant aggregate digests on repeated restart, and accepts only canonical decimal replacement
ordinals `2..999999`; leading-zero, `1`, `1000000` and non-digit lineage state fail closed with no
repair. In the real HTTP restart proof, Merchant `10802` consumed Tenant `4000`; the same-volume
restart preserved its aggregate digest and made exact empty Tenant `10805`
(`local-merchant-candidate-2`) the sole eligible replacement, with zero Department, Membership, Role
and Merchant rows. The local runtime remained healthy. The real browser created Merchant `10786` in
`PENDING_REVIEW` with `PLATFORM_APPLICATION_SUBMITTED`, reviewer Membership `1001` approved it to
`ACTIVE` with `PROFILE_VERIFIED`, and the edit page hydrated all 23 inputs and five documents with
zero console errors or warnings during acceptance. MCH-001/MCH-002/MCH-003 checkers, documentation
decision tests and `git diff --check` passed. Unified backend `clean verify`: PASS. 95 XML reports / 678 tests / 0 failures / 0 errors. Earlier blackbox
and Valkey Testcontainers infrastructure failures remain failed attempts; none of these mutable
results is immutable Judge or Production GO evidence.
