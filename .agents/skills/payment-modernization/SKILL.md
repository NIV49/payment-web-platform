---
name: "payment-modernization"
description: "Govern payment-platform modernization with Judge-first gates, capability slices, immutable Git versions, adversarial review, and machine-verifiable exit criteria. Use when planning or implementing a greenfield capability from legacy intent, transforming a bounded legacy slice, creating migration queues, designing characterization tests, or deciding whether modernization work may proceed."
---

# Payment Modernization

Modernize the payment platform without treating legacy behavior as truth. Keep business decisions in project docs, deterministic verdicts in Judge tests and CI, and orchestration state in event loops.

## Repository Baseline

Use these fixed locations unless a human explicitly changes the project baseline:

- Legacy evidence workspace (multi-repository workspace): `/Users/mac/Documents/work/backend`
- Target implementation repository: `/Users/mac/Documents/demo/payment-web-platform`

The legacy workspace is a container, not one Git repository. Its canonical source repositories are direct child directories that resolve beneath the workspace root and own a `.git` entry. Always exclude `_worktrees`, nested worktrees, and non-Git files at the workspace root unless a human explicitly adds a specific source to the slice baseline.

Treat every canonical source repository as read-only evidence. Never edit, format, commit, reset, or generate artifacts inside the legacy workspace. Write specifications, Rulebook entries, Judge assets, implementation, queues, and traceability only in the target repository or its dedicated Git worktrees.

Before starting a slice:

1. Record the canonical repository path, full `sourceCommitSha`, and exact `evidencePaths` for every source repository involved, plus the target repository path and full `targetBaseSha`.
2. Inspect every component of each `evidencePath` in the declared Git tree before reading it. Reject Git mode `120000` (symbolic link), mode `160000` (gitlink/submodule), missing paths, and non-blob final entries. Read an accepted blob by object ID; never let the operating system follow a tracked link.
3. Use `python3 -I scripts/check_modernization_evidence.py --repository <repositoryPath> --commit <sourceCommitSha> --path <evidencePath>`. Its Git plumbing disables replace objects, uses literal pathspecs, checks every tree component and reads the validated blob object directly. Do not substitute a raw `git show`, checkout path, or archive member read.
4. If a filesystem is required, materialize that commit outside the legacy workspace with `git archive`, or use an isolated clone whose worktree is detached and pinned to `sourceCommitSha`. Read materialized evidence through directory file descriptors with `openat` semantics (`dir_fd`, `O_DIRECTORY`, and `O_NOFOLLOW`), compare the opened root inode with its `lstat`, require a regular final file, enforce the size limit, and verify the opened bytes equal the declared Git blob. Any unsupported platform or mismatch fails closed. Never run `git worktree add` against a legacy repository because it mutates that repository's Git metadata. Keep the isolated checkout clean and verify its HEAD before and after evidence collection.
5. Never read frozen evidence from a live checkout. Ignore its tracked modifications and untracked files. Accept non-Git evidence only when a human explicitly scopes the exact file and its SHA-256 digest is recorded before inspection.
6. Store this source snapshot manifest in the Capability Slice contract described in [references/artifact-contracts.md](references/artifact-contracts.md). Do not silently replace a baseline path or SHA with the current working directory or HEAD.

Do not execute a legacy build, test, hook, or script unless a human explicitly approves it and it runs in a disposable least-privilege sandbox without credentials or access to target write paths. Static evidence collection is the default.

## Start Here

1. Load the Capability Slice supplied by the human or integration loop. Resolve the fixed `.agents/payment-modernization-policy.json` bootstrap object from its full `targetBaseSha`; verify the policy's stable `targetRepositoryId`, declared canonical project path, and the runtime `--repository-root` mapping before accepting any target policy.
2. Resolve every registered Rulebook and Judge path from `targetBaseSha`, using the same Git mode validation and object reads required for source evidence. The immutable policy registry includes `AGENTS.md`, mandatory requirements, ADRs, contracts, known deviations, domain context, this skill, checker code, and tests. Never read target rules from the mutable canonical checkout or slice worktree. A missing object, mode `120000`, mode `160000`, or checkout/SHA substitution blocks the slice.
3. Compute the baseline `rulebookDigest` and `judgeDigest` from length-framed, sorted path-and-byte manifests resolved from `targetBaseSha`; labels such as `rulebookVersion` are display metadata only and are excluded from identity. Record the complete normative Capability Slice and derive its pre-implementation `taskIdentityKey` with the namespaced canonical-JSON algorithm implemented by `scripts/check_modernization_artifacts.py`.
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
- Run the repository-approved secret scan in isolated mode. The authoritative form is `python3 -I scripts/check_sensitive_artifacts.py --repository-root <target> --base-commit <trusted-base-SHA> --commit <target-SHA>`; it rejects shallow or grafted history plus Git environment overrides that can redirect repository, object, replacement, graft, shallow, or namespace state; strictly decodes Git metadata lines; verifies reported parents against raw commit headers; walks every commit reachable in `base..target` and every real parent edge; then scans the complete immutable blob for each added, modified, or type-changed path. This catches secrets added and later deleted, including on merged branches, without relying on directory names, Git diff attributes, or submodule ignore settings. A hit, symlink/gitlink mode, oversized file, non-UTF-8 blob, or malformed/unsafe structured YAML, JSON, or XML blocks the slice without echoing the candidate value. JSON `{key|name, value}` and XML child-node descriptors are covered; namespace-local XML attribute collisions cannot overwrite an earlier value, and a safe XML `value` attribute cannot hide unsafe descriptor text.

## Non-Negotiable Gates

Before implementation:

- Establish scope, dependencies, entry criteria, exit criteria, and forbidden changes.
- Assign stable rule IDs to every claimed behavior.
- Define how each rule is judged independently of target private functions.
- Confirm toolchains and isolated test infrastructure.
- Freeze `taskIdentityKey` before implementation from the complete normative Capability Slice: `turnId`, `sliceId`, path, stable target repository ID, target base, source and non-Git evidence manifests, baseline Rulebook/Judge paths and digests, actors, inputs, outputs, rule IDs, dependencies, owned paths, forbidden changes, entry/exit criteria, and Judge commands. It deliberately excludes only the key itself, display labels, the host-specific runtime mapping, and the not-yet-created output commit.

Before closing:

- Run contract, invariant, adversarial, and affected regression tests.
- After the output exists, resolve a second Rulebook/Judge manifest from full `targetCommitSha` and derive `evaluatedVersionKey` from `taskIdentityKey`, the output SHA, and those evaluated content digests.
- Require two independent read-only reviews of that exact evaluated version. Each Review Result is an Ed25519-signed strict canonical JSON object whose reviewer key and role are pinned by the externally anchored baseline policy. Its purpose, exact-schema findings, successful Judge execution proofs, approval subjects, and `queueDigest` are signed. Every closed slice declares at least one evaluated Judge registry check ID and both reviewers sign one successful execution for each, regardless of Rule status. Queue state is replayed against real Git parents; divergent merge parents require a later single-parent signed reconciliation, and a third unsuccessful review enters `human-decision`. Deduplicate with the namespaced `reviewIdempotencyKey` over `evaluatedVersionKey`, `reviewerId`, and `reviewerRole`; never suppress the second reviewer merely because the evaluated version matches.
- Deduplicate findings by root cause.
- Resolve every BLOCKER and explicitly adjudicate every deviation.
- Record traceability from rules to code and tests.
- Confirm the isolated immutable-diff sensitive scan passes for every changed artifact.
- Run the authoritative repository gate as `python3 -I scripts/check_modernization_artifacts.py --repository-root <runtime-target-repository> --commit <full-target-SHA> --trusted-policy-commit <protected-base-SHA>`. The explicit external policy anchor is mandatory; repository mode never infers it from a parent. The positional bundle mode may validate a `draft` as local preflight; the tracked canonical artifact root accepts only `closed` bundles with exactly two independent signed PASS reviews.

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

Rule approval uses detached signatures and therefore has two immutable commits. Commit **B** records a Rule payload with requested `status: approved`; B is still ineffective and the repository gate intentionally fails closed without proof. Two trusted reviewers independently sign Review Results bound to B and its payload digest. A later single-parent commit **C** records only regular `*.json` detached approval envelopes under the canonical artifact root, without any Rule, Judge, workflow, application, or unrelated tree change. Only the valid envelope at C makes the rule effectively approved. Do not squash, rebase, or amend B after signing. Removing or downgrading an effectively approved rule is blocked until a trusted retirement protocol exists.

The initial policy intentionally contains no trusted reviewer keys, so approval fails closed. Key bootstrap or rotation requires an externally authorized governance procedure and a protected policy anchor; a normal change cannot register its own keys. Repository-local checks also require external protection. Follow [the CODEOWNERS bootstrap runbook](../../../docs/governance/codeowners-bootstrap.md); a PR-controlled workflow or newly introduced CODEOWNERS file cannot be its own root of trust.

## Queue Discipline

Turn failures into structured queue items containing:

- stable fingerprint;
- capability slice and exact version key;
- violated rule ID or Judge check;
- reproducible input, control flow, evidence, and impact;
- `BLOCKER` or `SHOULD_FIX`;
- dependencies and required verification.

Deduplicate by root cause before dispatch. `findingId` is bundle-global and must be unique across reviewers. Every valid Review finding whose status is not `closed`, including `SHOULD_FIX`, maps to exactly one Queue Item with a unique `failureSource: {type: review, checkId: review:<findingId>}` and identical severity, status, trigger, control flow, evidence, impact, and verification; every review-sourced Queue Item must map back to a real finding, so orphan or duplicate mappings fail closed. A fixer cannot close its own item. Three failed review rounds require human decision. The complete `queueItems` array is content-addressed by `queueDigest` in both signed reviews. Repository replay is append-only by fingerprint: immutable root-cause fields cannot change, deletion is rejected, transitions must follow the declared state machine, and a status change requires a new `evaluatedVersionKey` plus two fresh independent signatures. Historical parse or signature errors fail closed even when the bad envelope is later deleted.

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
