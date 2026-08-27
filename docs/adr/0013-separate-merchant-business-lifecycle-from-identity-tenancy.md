# Separate merchant business lifecycle from identity tenancy

Status: accepted.

Decision-ID: MCH-MERCHANT-LIFECYCLE

## Context

The platform needs a merchant onboarding and lifecycle capability, but Identity `Tenant` already means an authorization workspace. Reusing `iam_tenant` as the merchant profile would couple authentication, membership and business-party state, while creating a second tenant selector would violate the trusted Session boundary established by [ADR-0008](0008-isolate-three-backoffice-account-domains-and-sessions.md).

The legacy system is evidence for merchant vocabulary only. Its unrestricted status writes, review-time account and credential creation, and synchronous integration provisioning are explicitly rejected. MCH-001 therefore reimagines one bounded capability: a merchant submits its legal profile from an existing MERCHANT authorization workspace, and PLATFORM reviews and governs the resulting Merchant business entity.

## Decision

<!-- MCH-001-D1 -->

1. `Merchant` is a business entity owned by the merchant lifecycle context. Identity `Tenant` remains an authorization workspace owned by Identity. They are distinct aggregates; neither model absorbs the other's profile, membership, credential or lifecycle fields.

<!-- MCH-001-D2 -->

2. One Merchant has exactly one MERCHANT Tenant, and one MERCHANT Tenant has at most one Merchant. This one-to-one binding is immutable and permanent: it cannot be reassigned, swapped or reused after rejection, disablement or termination. A Tenant may exist unbound before its first merchant application.

<!-- MCH-001-D3 -->

3. The first submission is the first application: it atomically creates the Merchant and binds it to the MERCHANT Tenant resolved from the authenticated Session. MCH-001 never creates IAM records, including a Tenant, User, Membership, protected administrator, Realm or credential. The initial MERCHANT administrator and its authorization workspace continue to be established under [ADR-0010](0010-centralize-tenant-administrator-provisioning-with-delegated-user-governance.md).

<!-- MCH-001-D4 -->

4. The only persisted Merchant states are `PENDING_REVIEW`, `REVIEW_REJECTED`, `ACTIVE`, `DISABLED` and `TERMINATED`. There is no `DRAFT` state. An unsubmitted Tenant has no Merchant row; an unsaved browser form is not business state and must not persist the registration number in browser storage.

<!-- MCH-001-D5 -->

5. Legal transitions are exact: absent to `PENDING_REVIEW`; `PENDING_REVIEW` to `ACTIVE` or `REVIEW_REJECTED`; `REVIEW_REJECTED` to `PENDING_REVIEW`; `ACTIVE` to `DISABLED` or `TERMINATED`; `DISABLED` to `ACTIVE` or `TERMINATED`; and `REVIEW_REJECTED` to `TERMINATED`. `TERMINATED` is terminal and final. Every other transition fails before any profile, state, audit or idempotency record changes.

<!-- MCH-001-D6 -->

6. The first submission supplies the profile. Only `REVIEW_REJECTED` may update the profile and resubmit it atomically to `PENDING_REVIEW` without replacing the Merchant identity. `PENDING_REVIEW`, `ACTIVE`, `DISABLED` and `TERMINATED` cannot use the submission endpoint as an edit path. Active-profile amendments require a later reviewed capability slice.

<!-- MCH-001-D7 -->

7. Only a MERCHANT-domain actor may submit or resubmit its own application, and only a PLATFORM-domain actor may review or govern Merchant lifecycle state. This is account-domain and role separation of duties, not proof that two accounts belong to different natural persons: the three Realms intentionally do not link natural-person identity, so MCH-001 must not claim a natural-person four-eyes guarantee.

<!-- MCH-001-D8 -->

8. Merchant self-service derives account domain, Tenant, Membership and actor exclusively from the trusted server Session. It accepts no `tenantId`, `merchantId`, account domain, Realm or portal selector. PLATFORM control-plane commands resolve a Merchant by its server-owned identifier and recheck the target's bound MERCHANT Tenant; they do not grant the PLATFORM actor a target Membership or impersonation session.

<!-- MCH-001-D9 -->

9. Backend permission checks, not frontend visibility, enforce every action. Dedicated `merchant:view`, `merchant:review`, `merchant:disable`, `merchant:enable` and `merchant:terminate` permissions are PLATFORM-only and non-delegable. Each exact permission must be granted by the same protected PLATFORM system Role whose assignment authorizes the actor; a protected Role and an ordinary Role cannot be combined into one authorization proof. MERCHANT self-service applies the same-role rule to its protected MERCHANT system Role and `merchant:self-view`, `merchant:submit` and `merchant:resubmit`. AGENT receives no MCH-001 permission or endpoint. This control plane is neither ordinary `RELATED_PARTY_READ` nor a general cross-tenant grant.

<!-- MCH-001-D10 -->

10. Every mutation carries a UUID `idempotencyKey`; every mutation of an existing Merchant also carries its current non-negative `expectedVersion` and performs an atomic optimistic comparison. For the shared submission endpoint, explicit JSON `expectedVersion: null` permanently selects `submit`, `merchant:submit` and `APPLICATION_SUBMITTED`; a non-negative integer selects `resubmit`, `merchant:resubmit` and `APPLICATION_RESUBMITTED`. Command type is never re-derived from current Merchant existence. The Merchant row, legal transition, version, idempotency result and append-only audit event commit in one PostgreSQL transaction. Reusing a key with the same actor, command and canonical payload returns the original result; a different payload fails closed. Idempotency uses a dedicated versioned HMAC key, never the registration search fingerprint or search key. A command locks the source Tenant first, then the Membership, User, Credential, Membership-to-Role assignment, protected Role, that same Role's exact Grant, Grant dimensions/targets and Permission rows in one fixed order. Only after all locks are held does a later database statement obtain current database time; the command then revalidates Tenant `ACTIVE` status and account domain, live Session and identity versions, Role assignment, Role protection, exact permission, Grant status, dimensions and finite validity. After that proof it checks permanent deduplication for the already-fixed actor, command and UUID before reading current Merchant state; a verified replay returns its original result. Only a deduplication miss may validate absent Merchant for `submit` or locked `REVIEW_REJECTED` state/version for `resubmit`. The deduplication row records immutable command-schema, canonical-digest-scheme and registration-normalization versions in addition to the HMAC key ID; replay selects retained implementations by those recorded versions, and an unknown version fails closed. The audit event records server-derived actor, source and target state, allowlisted `reasonCode`, trace and target. Submission requests never accept a reason; PLATFORM review and governance accept only their action-specific allowlist. Free-text reason and registration plaintext never enter the command, audit or deduplication storage.

<!-- MCH-001-D11 -->

11. `merchantCode` is generated by the server, stable and opaque. The profile contains `legalName`, `displayName`, `registrationCountry` and protected `registrationNumber`. Registration-number plaintext never enters responses, logs, errors, ordinary audit or idempotency records. Persistence is mandatory AES-256-GCM authenticated encryption with a CSPRNG 96-bit nonce, a fixed 128-bit authentication tag, per-key nonce uniqueness and authenticated AAD that binds the Merchant, Tenant, country, field, normalization version, algorithm and key identifier. Uniqueness separately uses versioned HMAC-SHA-256 over country plus normalized registration number rather than plaintext or an unkeyed digest. Search-key rotation is serialized with every registration write by one database transaction-level advisory write fence: under that fence, one transaction decrypts and backfills all fingerprints, proves global uniqueness, atomically switches database active-key metadata and then commits. Failure rolls back and leaves the old key authoritative. Search, idempotency and AEAD keys are separate cryptographic purposes. Keys never enter the database, repository, API, log, audit or deduplication row.

<!-- MCH-001-D12 -->

12. MCH-001 contains only the Merchant entity, permanent Tenant binding, application, review, lifecycle governance, audit and the PLATFORM/MERCHANT product surfaces. It excludes Market and country-market activation, MerchantMarket, Agent and AgentRelation, rates, limits, settlement accounts, fund accounts, integration configuration, credentials, IP policies, payment, orders, ledger and money behavior. Merchant lifecycle records are not physically deleted: `TERMINATED` preserves immutable audit history and the permanent binding, and Merchant state never implicitly rewrites IAM lifecycle state.

## Data and security consequences

- The persistence model must enforce a unique non-null `tenant_id`, a unique immutable `merchant_code`, a fixed `account_domain = 'MERCHANT'`, and a composite foreign key to the Identity Tenant's ID and account domain. Application checks alone are insufficient.
- Registration-number normalization is versioned. Version 1 applies Unicode NFKC, trims leading and trailing Unicode whitespace, removes internal Unicode whitespace and ASCII `-`, `.`, and `/` separators, then uppercases with locale-independent semantics. The normalized value must remain non-empty and at most 128 characters.
- Version 1 uniqueness fingerprint is HMAC-SHA-256 over the length-framed tuple `MCH-REG-v1`, country and normalized registration number using an externally supplied search key. The database stores the fingerprint and algorithm/key version, with a unique constraint over country and the active fingerprint. Database active-key metadata selects exactly one authoritative search key. Every first submission and registration replacement acquires the same transaction-level advisory registration-write fence before reading that metadata. Rotation acquires the fence, decrypts and backfills every Merchant to the proposed key, proves global country-plus-registration uniqueness, atomically changes active-key metadata in the same transaction and commits before writes resume. A crash, decryption error, duplicate or proof failure rolls back the backfill and switch, leaving the old key authoritative; two unrelated active fingerprints are never accepted as equivalent uniqueness domains.
- A rejected resubmission may retain the protected registration value only when `registrationCountry` is unchanged. Changing country requires a present nonblank `registrationNumber`; omission fails atomically before profile, fingerprint, audit or idempotency state changes.
- Registration ciphertext is mandatory. It uses only AES-256-GCM with externally managed key material and stores ciphertext, 96-bit nonce, fixed 128-bit authentication tag, key identifier, algorithm metadata and an independently safe masked display value. A database uniqueness constraint over AEAD key ID plus nonce provides a final nonce-reuse guard; a collision fails closed and rolls back the entire command rather than overwriting or reusing the nonce. AAD uses a length-framed tuple containing `MCH-REG-AEAD-v1`, Merchant ID, Tenant ID, registration country, field name, normalization version, algorithm and key ID; swapping ciphertext or metadata across rows must fail authentication. Database active-key metadata also selects exactly one AEAD key for new writes. AEAD rotation acquires the same transaction-level advisory registration-write fence, decrypts every row with its recorded retained key, re-encrypts with fresh nonces under the proposed key, verifies authentication and row coverage, atomically switches the active AEAD key and commits. A failure rolls back all ciphertext and metadata changes and leaves the old key authoritative. Every decrypt key remains available until no row references it and a verified backup/rollback window has closed. Keys never enter the database, repository, response, log or audit event. A deployment without required search, idempotency or AEAD protection material refuses registration writes.
- The canonical request digest is HMAC-SHA-256 over a length-framed normalized command payload using a dedicated idempotency key purpose. The deduplication row stores only the final digest, its HMAC key ID, command-schema version, canonical-digest-scheme version, registration-normalization version where applicable, exact required permission and bounded original result. MCH-001 never deletes deduplication rows. Every referenced key plus decoder, canonicalizer and normalization implementation remains available for the lifetime of those rows. Replay resolves the row only after current actor authorization has been locked and revalidated, recomputes by the row's recorded versions and key ID, and fails closed if any version or key is unavailable. Search-key, schema or active-idempotency-key rotation therefore cannot change replay equality.
- Merchant lifecycle state does not implicitly disable, terminate or restore the bound IAM Tenant, User, Membership, Credential or Session. Conversely, an invalid Session prevents commands but does not silently rewrite Merchant state.
- Audit records contain actor domain, source Tenant and Membership, target Merchant and bound Tenant, action, previous and next state, allowlisted `reasonCode`, trace ID, version and timestamp. Submit and resubmit use the server-derived `APPLICATION_SUBMITTED` and `APPLICATION_RESUBMITTED`; PLATFORM mutations use their exact accepted code. MCH-001 accepts no free-text lifecycle reason. Registration-number plaintext, ciphertext and fingerprint are excluded; profile changes record an allowlisted field-name set rather than values.

## Compatibility, rollout and rollback

This is a new target contract; there is no current MCH-001 HTTP or database implementation to preserve. Implementation is additive and uses only new Flyway migrations. A migration already applied in any environment is never edited.

Release order is schema and protected-key readiness, backend state/permission enforcement, PLATFORM and MERCHANT frontend surfaces, then integration and browser verification. Until all stages are deployed, the Merchant navigation and commands remain unavailable. After Merchant rows are written, an old binary that does not understand this context is not a writable rollback target: stop Merchant writes, keep Identity and existing system management available, and forward-fix or restore a coordinated snapshot. Disabling Merchant UI alone is not data rollback.

## Consequences

- Identity can continue provisioning the MERCHANT authorization workspace and its initial administrator without inventing a Merchant business row.
- A rejected applicant can correct and resubmit one stable Merchant instead of creating duplicates.
- PLATFORM receives explicit review and lifecycle authority without being added to the merchant Tenant.
- Natural-person-level four-eyes enforcement remains a future identity-governance decision and cannot be inferred from separated Realms.
- Market, pricing, accounts and integration can evolve as later bounded contexts without expanding MCH-001's state machine.
