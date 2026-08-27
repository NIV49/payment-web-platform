---
type: ADR
status: accepted
audience:
  - product
  - engineering
related_to:
  - "[[0013-separate-merchant-business-lifecycle-from-identity-tenancy]]"
  - "[[0014-platform-maintained-merchant-profile-and-operating-markets]]"
  - "[[../product/merchant-management]]"
---

# ADR-0015: PLATFORM-assisted Merchant onboarding and reviewed amendments

Status: accepted.

Decision-ID: MCH-PLATFORM-ASSISTED-ONBOARDING

Date: 2026-08-15

## Context

The operations team needs one full-page create/edit Merchant experience that carries the useful
business vocabulary from the legacy Store form. The legacy implementation remains evidence only:
its direct Merchant insert, review-time account/password creation, public upload paths and
unrestricted profile overwrite are not target behavior.

MCH-001 correctly separated Merchant from Identity Tenant and made the binding permanent. MCH-003
extends that model without creating IAM records: a protected PLATFORM operator may submit an
application only for an existing eligible MERCHANT Tenant. MCH-003 also replaces immediate
ACTIVE/DISABLED profile overwrite with a separately reviewed amendment and introduces the protected
document capability required by the full form.

## Decision

<!-- MCH-003-D1 -->

1. PLATFORM-assisted creation selects one existing `ACTIVE`, account-domain `MERCHANT` Tenant that
   has no Merchant binding. `GET /api/platform/merchant-onboarding/eligible-tenants` returns only
   eligible candidates to an actor holding `merchant:create` with recent step-up. The create
   transaction locks and revalidates the selected `targetTenantId`; the directory result is never
   authorization evidence. MCH-003 does not create or modify an IAM Tenant, User, Membership, Role,
   credential, Realm or administrator.

<!-- MCH-003-D2 -->

2. `POST /api/platform/merchants` is the only PLATFORM create command. It requires the same assigned
   protected PLATFORM system Role to hold the exact non-delegable `merchant:create` permission and a
   recent step-up. It atomically creates one Merchant in `PENDING_REVIEW`, binds the target Tenant,
   protects sensitive values, attaches the submitted documents, writes permanent idempotency and
   audit evidence, and records the immutable application-author Membership. That Membership may not
   approve or reject the same pending create application through the existing Merchant review
   command. This is Membership-level separation and does not claim natural-person four-eyes proof.

<!-- MCH-003-D3 -->

3. The create and amendment forms share exactly these 23 business inputs: `displayName`, `brandName`,
   `authenticationType`, `merchantTypeCode`, `industryCode`, `brandLogoDocumentId`, `legalName`,
   `registrationCountry`, `marketCodes`, `registeredAddress`, `operatingAddress`,
   `businessLicenseDocumentId`, `legalPersonName`, `contactEmail`, `contactPhone`, `legalIdTypeCode`,
   `legalIdNo`, `legalIdValidity`, `legalIdFrontDocumentId`, `legalIdBackDocumentId`,
   `legalIdHoldingDocumentId`, `remarks` and `registrationNumber`. `legalIdValidity` is the one
   business input containing `validFrom` and `validTo`. The legacy `receiveEmail` field is excluded;
   `contactEmail` is Merchant profile data and never creates or selects an Identity login.
   `registrationNumber` and `legalIdNo` remain separate protected facts and are never conflated.

<!-- MCH-003-D4 -->

4. `registrationCountry` remains the ISO 3166-1 alpha-2 legal-registration fact, while `marketCodes`
   remains the multiple ISO 3166-1 alpha-3 operating-market set initially limited to `BRA` and
   `PHL`. New writes and responses use only `PLATFORM`, `INDIRECT`, `COMMISSION` or `SALES` for
   `merchantTypeCode`. MCH-003 deliberately redefines the target classification so `DIRECT` maps
   forward to the sole canonical `PLATFORM` value. V35 is immutable; V37 or later performs the
   forward data/dictionary/constraint convergence. `DIRECT` is retained only by historical
   decoders needed to verify an existing idempotency receipt and is never a new-write value.

<!-- MCH-003-D5 -->

5. `POST /api/platform/merchants/{merchantId}/amendments` requires protected non-delegable
   `merchant:amend`, recent step-up, `expectedMerchantVersion`, one UUID `idempotencyKey` and the full
   23-input profile. It accepts only an `ACTIVE` or `DISABLED` Merchant with no other pending
   amendment. Submission creates a separate amendment in `PENDING_REVIEW` with immutable
   `originMerchantVersion`, `originStatus` and author Membership. It does not modify the current
   Merchant profile, document references, `rowVersion`, `ACTIVE`/`DISABLED` runtime state or current
   status reason. Each of the five document IDs is recorded as an immutable reference: the exact
   current same-kind Merchant binding is `RETAIN`, while an unexpired same-actor, same-target,
   same-kind temporary upload is `REPLACE`. Zero or partial replacements are valid; arbitrary,
   superseded, cross-Merchant or cross-actor document reuse fails closed.

<!-- MCH-003-D6 -->

6. Amendment states are exactly `PENDING_REVIEW`, `APPROVED`, `REJECTED` and `STALE`.
   `POST /api/platform/merchants/{merchantId}/amendments/{amendmentId}/review-decisions` requires the
   existing protected `merchant:review` permission, recent step-up and an actor Membership different
   from that amendment's author. Approval rechecks the immutable origin version and status, then in
   one transaction applies every profile/market/protected-value/document change, increments the
   Merchant `rowVersion` exactly once, preserves the original `ACTIVE` or `DISABLED` status, records
   amendment decision/audit/idempotency and supersedes replaced documents. Rejection changes only
   the amendment decision/audit state. Origin drift atomically marks the amendment `STALE`, leaves
   the Merchant unchanged and returns `40902 OPTIMISTIC_LOCK_CONFLICT`; a new amendment is required.
   MERCHANT self-submit/resubmit never reads, applies or resubmits a PLATFORM amendment.

<!-- MCH-003-D7 -->

7. PLATFORM create and edit use one full-page form component rather than separate drawers. Edit
   submits an amendment; it never calls the MCH-002 immediate profile-update command. After MCH-003
   cutover, `PUT /api/platform/merchants/{merchantId}/profile` and `merchant:update` are retained only
   to authorize and return an already persisted MCH-002 schema-1/schema-2 idempotency receipt. A
   deduplication miss performs no write and returns `40910 MERCHANT_STATE_CONFLICT`; the UI never
   calls that endpoint. Merchant-list market cells are centered and show localized `Brazil`/`巴西`
   and `Philippines`/`菲律宾` labels without codes. An operation cell shows all actions when the
   effective visible count is at most three; above three it shows the first three and places the
   rest in the existing click-triggered overflow menu. Merchant detail and review are separate
   hidden full-page routes backed by one read-only 23-field/five-document presentation. Review adds
   exactly one `Review` action; that action opens a modal for approve/reject plus the bounded reason
   code required by the existing review contract. The read-only detail route exposes no review
   command, and neither route reuses the editable form or document-upload controls.

<!-- MCH-003-D8 -->

8. Documents use the protected PLATFORM endpoints
   `POST /api/platform/merchant-document-uploads`,
   `GET /api/platform/merchant-document-uploads/{documentId}/content`,
   `DELETE /api/platform/merchant-document-uploads/{documentId}` and
   `GET /api/platform/merchants/{merchantId}/documents/{kind}/content`. Upload requires the exact
   protected non-delegable `merchant:document:upload` permission and recent step-up; attached content
   requires `merchant:document:view` and recent step-up. Temporary read/delete additionally requires
   the same upload actor and target Tenant. Document kinds are exactly `BRAND_LOGO`,
   `BUSINESS_LICENSE`, `LEGAL_ID_FRONT`, `LEGAL_ID_BACK` and `LEGAL_ID_HOLDING`. No endpoint returns a
   public URL, filesystem path, object-store key or original filename.
   The attached-content endpoint keeps the same path and accepts optional positive Long-string query
   `amendmentId`. Omission resolves the current effective Merchant attachment; presence resolves only
   the exact Merchant plus `PENDING_REVIEW` amendment plus kind attachment. A pending-review UI must
   pass its amendment ID and must not fall back to the current effective document. Any Merchant,
   amendment, kind, state or document-scope mismatch returns `40401 RESOURCE_NOT_FOUND`.

<!-- MCH-003-D9 -->

9. The multipart upload accepts exactly `targetTenantId`, `kind` and `file`. Only PNG and JPEG are
   accepted. Both the received file and sanitized output must be at most 2 MiB; width and height must
   each be 1..4096 pixels and the decoded image must be at most 12,000,000 pixels. The backend checks
   magic bytes, asks ImageIO for dimensions before allocation, fully decodes the image, rejects
   decode/truncation/limit failures, and re-encodes it through ImageIO to remove metadata and hidden
   payloads. Client MIME type and extension never select the decoder. Original bytes and filename
   are discarded and never enter logs, audit or persistence.

<!-- MCH-003-D10 -->

10. Sanitized documents are encrypted with AES-256-GCM under a Merchant-document-only external key
    purpose, a CSPRNG 96-bit nonce, fixed 128-bit tag and authenticated AAD binding the document ID,
    target Tenant, upload actor Membership, kind and sanitized media metadata. The database stores
    only private `BYTEA` ciphertext and metadata with a unique key-ID/nonce constraint. A temporary
    document is bound to one target Tenant, actor Membership and kind, expires after 30 minutes, and
    may be attached exactly once by that same actor and target context. Create attaches it to the new
    Merchant. Amendment submission leaves `RETAIN` evidence on the current Merchant and attaches only
    `REPLACE` uploads to the amendment; approval promotes only those replacements. Cleanup deletes only
    expired unattached temporary rows. Attached or rejected-amendment evidence is not reachable by a
    public URL and is not removed by temporary cleanup. Authorized reads stream decrypted sanitized
    bytes with `Cache-Control: no-store` after rechecking the current actor and Merchant scope.

<!-- MCH-003-D11 -->

11. `legalIdNo` uses a separate AES-256-GCM key purpose from registration numbers, documents and
    idempotency. It has its own 96-bit nonce, fixed 128-bit tag, key ID and AAD binding Merchant,
    Tenant, `legalIdTypeCode`, field name and protection version. Responses contain only
    `legalIdNoMasked`; plaintext never enters list/detail responses, URLs, browser persistence,
    logs, errors, ordinary audit or stored canonical payloads. Create requires `REPLACE`; an
    amendment uses the strict tagged form `RETAIN` or `REPLACE`, and changing `legalIdTypeCode`
    requires `REPLACE`. `registrationNumber` independently uses the same RETAIN/REPLACE interaction
    with its existing registration-protection rules.

<!-- MCH-003-D12 -->

12. Create, amendment submission and both review paths retain the MCH-001 lock-complete
    authorization, permanent versioned-HMAC idempotency and append-only audit model. Temporary upload
    is not a Merchant mutation; one-time attach occurs only inside the create/amendment transaction.
    New migrations are append-only and must preserve the historical MCH-001/MCH-002 decoders and
    receipts. AGENT receives no Merchant endpoint or permission. MCH-003 still excludes rates,
    limits, settlement/fund accounts, integration credentials, IP policy, payment, order and ledger
    behavior.

## Consequences

- Assisted creation is possible without reviving legacy review-time user/password provisioning.
- ACTIVE/DISABLED Merchants keep their current effective profile while a proposed amendment is
  reviewed; reviewers see the immutable proposal and its origin version rather than a mutable form.
- The full form becomes a real protected capability. Image fields cannot be implemented as strings,
  browser-local blobs, public upload URLs or generic file paths.
- MCH-003 changes the accepted target but is not implementation evidence. Navigation and commands
  remain unavailable until migrations, backend, frontend, browser tests and immutable gates pass.

## Rollback

Before the first MCH-003 write, disable its routes and UI while retaining unused additive schema.
After a create, amendment or document attachment commits, stop MCH-003 writes and forward-fix or
restore a coordinated snapshot. Do not re-enable immediate MCH-002 writes, rewrite V35, map
`PLATFORM` back to `DIRECT`, expose encrypted document bytes, or delete amendment/audit history as a
rollback shortcut.
