---
type: ADR
status: accepted
audience:
  - product
  - engineering
related_to:
  - "[[0013-separate-merchant-business-lifecycle-from-identity-tenancy]]"
  - "[[../product/merchant-management]]"
---

# ADR-0014: PLATFORM maintains Merchant profile and operating markets

Status: accepted.

Decision-ID: MCH-MERCHANT-PROFILE-MARKETS

Date: 2026-08-14

## Context

MCH-001 intentionally delivered the Merchant identity and review lifecycle before markets. The product now requires PLATFORM operators to maintain approved Merchant profile data and to show the countries in which a Merchant operates. A legal registration country and an operating market are related but different facts and cannot share one field.

## Decision

<!-- MCH-002-D1 -->

`registrationCountry` remains the Merchant's legal registration country, uses an assigned ISO 3166-1 alpha-2 code, remains bound to registration-number protection, and is not a market or a list filter labelled as market.

<!-- MCH-002-D2 -->

`marketCodes` is the Merchant's multiple operating-market set. Each value is an assigned ISO 3166-1 alpha-3 country code; the initial product allowlist is `BRA` and `PHL`. A V33 Merchant has no proven operating market, so V34 preserves it as an empty set and never guesses a value. A PLATFORM profile update requires at least one market.

<!-- MCH-002-D3 -->

`merchant:update` is a non-delegable PLATFORM permission. Only a protected PLATFORM system administrator whose one assigned protected PLATFORM system Role itself holds the exact permission, and whose Session has recent step-up, may execute it. MERCHANT and AGENT do not receive this capability.

<!-- MCH-002-D4 -->

The only new HTTP command is `PUT /api/platform/merchants/{merchantId}/profile`. It accepts the exact profile fields plus `expectedVersion` and `Idempotency-Key` semantics; it accepts no Tenant, Realm, account-domain, registration-country or registration-number selector.

<!-- MCH-002-D5 -->

Profile maintenance is legal only while the Merchant is `ACTIVE` or `DISABLED`. It does not change lifecycle status or `statusReasonCode`, rejects a semantic no-op, compares `expectedVersion`, and increments `rowVersion` exactly once on success. PENDING_REVIEW remains review-owned, REVIEW_REJECTED remains MERCHANT resubmission-owned, and TERMINATED remains terminal. Historical status reasons remain null when exact retained evidence is absent; they are never inferred from status.

<!-- MCH-002-D6 -->

The command type is `UPDATE_PROFILE`; its allowlisted audit reason is server-derived `PLATFORM_PROFILE_UPDATED`. Authorization recheck, idempotency result, profile/market replacement, row-version increment and audit event commit in the same PostgreSQL transaction.

<!-- MCH-002-D7 -->

The PLATFORM list provides Detail and Edit actions. Only `ACTIVE` and `DISABLED` use a status `Switch`; the other states use a `Tag`. Detail remains read-only except pending review and does not expose disable, enable or terminate actions. Select controls use Vben/antdv-next `allowClear`. Termination remains an API capability but is not a routine detail-page control.

<!-- MCH-002-D8 -->

The Merchant list and profile retain the approved business labels `merchantTypeCode` (Merchant Type), `legalPersonName` (Legal Person Name) and `authenticationType` (Authentication Type), while business email remains excluded. Merchant Type is limited to `DIRECT`, `INDIRECT`, `COMMISSION`, `SALES` or `PLATFORM`; Authentication Type is limited to `ENTERPRISE`, `NON_PROFIT_ORGANIZATIONS`, `CLIQUE`, `INDIVIDUAL` or `INDIVIDUAL_HOUSEHOLD`. These are profile classifications only: they never create or infer an IAM Tenant, AgentRelation, MerchantMarket or authorization scope. Existing rows may return null, but the first PLATFORM profile update must supply all three fields.

## Consequences

- V34 is append-only and adds the permission, Merchant remarks and normalized Merchant-to-market rows without changing V32/V33. Because V34 had already executed in local verification before the final field inventory was accepted, V35 is a second append-only migration for the three classification fields and their exact dictionary projections; V34 is never rewritten.
- V34 status-reason backfill accepts only one audit event for the Merchant's current aggregate version. A permanently frozen V33 preflight callback rejects any duplicate `(merchant_id, merchant_version)` evidence before the backfill, and append-only V36 establishes the matching database `UNIQUE` boundary for future audit writes. A V34/V35 database already containing duplicate audit versions remains blocked and requires a separately approved forward data-repair migration; append-only audit history must not be deleted or rewritten to make migration pass.
- List/detail responses allow `marketCodes: []` for pre-MCH-002 rows. The update request requires 1..2 distinct allowlisted values until the market catalog expands through a later accepted change.
- The V34 profile request decoder is retained only for authorized replay of an existing command-schema-1 deduplication result. It cannot execute a new write after V35; partial V35 shapes and legacy deduplication misses fail closed.
- This slice still excludes rates, limits, accounts, integration and payment behavior.

## Rollback

Disable the MCH-002 route and `merchant:update` grant while preserving V34/V35/V36 data and audit history. Do not downgrade to V33 writes, delete ambiguous audit history, or invent markets or classifications for existing Merchants; forward-fix the application/data through an approved migration or restore a complete pre-change snapshot.
