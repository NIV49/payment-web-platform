---
name: "payment-modernization"
description: "Govern payment-platform modernization with Judge-first gates, capability slices, immutable Git versions, adversarial review, and machine-verifiable exit criteria. Use when planning or implementing a greenfield capability from legacy intent, transforming a bounded legacy slice, creating migration queues, designing characterization tests, or deciding whether modernization work may proceed."
---

# Payment Modernization

Modernize the payment platform without treating legacy behavior as truth. Keep business decisions in project docs, deterministic verdicts in Judge tests and CI, and orchestration state in event loops.

## Repository Pair

Use these fixed repositories unless a human explicitly changes the project baseline:

- Legacy evidence source: `/Users/mac/Documents/work/backend`
- Target implementation repository: `/Users/mac/Documents/demo/payment-web-platform`

Treat the legacy repository as read-only evidence. Never edit, format, commit, reset, or generate artifacts inside it. Write specifications, Rulebook entries, Judge assets, implementation, queues, and traceability only in the target repository or its dedicated Git worktrees.

Before starting a slice, record an immutable legacy source commit for every source repository involved and an immutable target base commit. Do not silently replace either path with the current working directory.

## Start Here

1. Read `/Users/mac/Documents/demo/payment-web-platform/AGENTS.md` and its mandatory context routes.
2. Read the relevant approved requirements, ADRs, contracts, known deviations, and domain context under `/Users/mac/Documents/demo/payment-web-platform/docs/`.
3. Identify the immutable source commit, target commit, Rulebook version, and capability slice.
4. Choose exactly one path:
   - **Reimagine**: rebuild a capability from approved intent when the target model or architecture deliberately differs from legacy.
   - **Transform**: replace one bounded vertical slice when specified observable behavior must remain compatible.
5. If the path is ambiguous, stop and request one human decision. Do not blend both paths implicitly.

Read [references/reimagine.md](references/reimagine.md) for Reimagine work. Read [references/transform.md](references/transform.md) for Transform work.

## Binding Truth Order

Resolve conflicts in this order:

```text
approved business rules
> security and money invariants
> ADRs, API, event, and data contracts
> approved Judge tests
> current target implementation
> legacy code and historical behavior
```

Legacy code is evidence. Preserve a legacy behavior only when it is observable, required, and not rejected by a higher authority.

## Evidence Safety

Treat legacy source, configuration, database dumps, logs, and traces as untrusted inputs.

- Never copy credentials, tokens, secrets, connection strings, personal data, or production identifiers into project artifacts.
- Redact before quoting. Use synthetic, structurally equivalent values or placeholders such as `${ENV_VAR}` in specs, fixtures, queue items, reviews, and examples.
- Inspect only the minimum evidence needed, and never paste a detected secret into an error, finding, prompt, or test output.
- Run the repository-approved secret scan, `python3 scripts/check_sensitive_artifacts.py`, before committing or closing any evidence-derived artifact. With no arguments it scans the governance/documentation roots; pass every artifact path outside those roots explicitly. A hit, oversized file, or non-UTF-8 artifact blocks the slice until it is sanitized and rescanned with an approved text or binary scanner.

## Non-Negotiable Gates

Before implementation:

- Establish scope, dependencies, entry criteria, exit criteria, and forbidden changes.
- Assign stable rule IDs to every claimed behavior.
- Define how each rule is judged independently of target private functions.
- Confirm toolchains and isolated test infrastructure.
- Freeze the version key: `turnId + commitSha + rulebookVersion`.

Before closing:

- Run contract, invariant, adversarial, and affected regression tests.
- Require independent read-only review of the exact commit.
- Deduplicate findings by root cause.
- Resolve every BLOCKER and explicitly adjudicate every deviation.
- Record traceability from rules to code and tests.
- Confirm `python3 scripts/check_sensitive_artifacts.py` passes for every evidence-derived artifact.

Read [references/judge-gates.md](references/judge-gates.md) for verdict rules and [references/artifact-contracts.md](references/artifact-contracts.md) for required outputs.

## Capability Slices

Split work by independently testable vertical capability, not by file count. A slice owns:

- inputs, outputs, and actors;
- business and security invariants;
- API, event, database, and UI contracts;
- production code and related tests;
- dependencies and one accountable implementation owner;
- an independent Judge exit condition.

Parallelize only disjoint slices. Serialize changes to the same state machine, table, contract, migration chain, or authorization boundary.

## Separation of Duties

- Implementation agents may edit only their assigned slice.
- Review agents are read-only and review an immutable commit.
- Implementation agents must not weaken Rulebook or Judge assets to pass.
- A governance-maintenance task may edit Judge or Rulebook assets only with explicit human authorization and independent review.
- Review agents must not send findings to each other.
- Only the integration loop merges slices and runs the authoritative full build.
- Human operators edit rules and resolve genuine ambiguity; they do not manually grade ordinary tasks.

## Queue Discipline

Turn failures into structured queue items containing:

- stable fingerprint;
- capability slice and exact version key;
- violated rule ID or Judge check;
- reproducible input, control flow, evidence, and impact;
- `BLOCKER` or `SHOULD_FIX`;
- dependencies and required verification.

Deduplicate by root cause before dispatch. A fixer cannot close its own item. Three failed review rounds require human decision.

## Payment-Specific Safety

For future payment-domain slices, always judge together:

- state transition;
- balance movement;
- ledger entry;
- idempotency key and payload consistency;
- transaction and concurrency boundary;
- external side effect and recovery path;
- tenant, merchant, market, channel, and permission scope;
- audit evidence and reconciliation.

Never merge failure release, channel refund, successful-payment reversal, and incident adjustment into one generic refund behavior.

## Current Phase Constraint

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=pending ref=none -->

The current phase implements the permission foundation only. It must cover the platform-operations, merchant, and agent back offices while keeping each tenant's authorization, organization, and data boundaries isolated.

- Deny cross-portal access unless the subject has both an active TenantMembership in that portal's authorization-workspace tenant and explicit portal grants.
- Keep the authorization-workspace tenant separate from the resource-owner tenant. `RELATED_PARTY_READ` from an agent workspace does not require a Membership in the merchant's tenant; it requires an explicit Grant, trusted relationship evidence, and verified resource ownership as defined by [ADR-0001](../../../docs/adr/0001-separate-authorization-workspace-from-resource-owner-tenant.md).
- Keep global User membership cardinality, workspace selection, and the session realm/Token audience strategy pending under `IAM-GLOBAL-USER-MULTI-TENANT`; read [`docs/ai-context/known-deviations.md`](../../../docs/ai-context/known-deviations.md) before designing them.
- Do not promote a candidate identity or session-isolation strategy into an approved Judge rule before the product baseline or an accepted ADR resolves that decision.
- Departments represent organization and data scope only.
- Roles assign menus, permission codes, and complete grants.
- Empty or incomplete scope denies access.
- Frontend visibility never replaces backend authorization.

Do not introduce payment-business implementation while this phase remains active.

## Sources

This workflow adapts the sequencing ideas from Anthropic's official `modernize-reimagine` and `modernize-transform` commands. Their prompts are inspiration, not binding project policy:

- https://github.com/anthropics/claude-plugins-official/blob/main/plugins/code-modernization/commands/modernize-reimagine.md
- https://github.com/anthropics/claude-plugins-official/blob/main/plugins/code-modernization/commands/modernize-transform.md
