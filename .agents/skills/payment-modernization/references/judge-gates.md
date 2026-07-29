# Judge Gates

## Verdict Order

Evaluate the exact commit against the evaluated Rulebook and Judge content digests declared by `evaluatedVersionKey`. Resolve both manifests from immutable Git objects; a version label alone is not identity.

1. Snapshot integrity.
2. Build and static checks.
3. API, event, database, and UI contracts.
4. Domain and security invariants.
5. Adversarial tests.
6. Affected regression suite.
7. Independent review findings.
8. Migration, configuration, and operational gates when applicable.

## Severity

`BLOCKER`:

- authentication, authorization, tenant, realm, or data isolation failure;
- money, ledger, idempotency, state, transaction, or irreversible consistency failure;
- incompatible migration or contract break;
- inability to build, start, deploy, or recover the capability safely.

`SHOULD_FIX`:

- verified reachable correctness, reliability, compatibility, observability, or test weakness that does not meet BLOCKER impact.

`NOT_AN_ISSUE`:

- unreachable path, contradicted evidence, reliable existing protection, duplicate root cause, or behavior outside the approved slice.

Do not classify uncertain claims as findings. Send genuine ambiguity to the decision queue.

## Evidence Standard

Each finding must include:

- repository-relative path and exact positive line;
- symbol and reachable control flow;
- trigger and observable impact;
- code, test, data, or configuration evidence;
- reproducible verification;
- target commit, `evaluatedVersionKey`, `rulebookDigest`, and `judgeDigest`.

## Automatic PASS

```text
snapshot valid
+ all required builds and tests pass
+ all included rules have Judge coverage
+ every requested-approved Rule Card has one unique valid detached envelope containing two independently signed PASS approval reviews
+ no unresolved BLOCKER
+ all deviations adjudicated
+ traceability complete
= PASS
```

An implementation agent's report is evidence, never the verdict.

## Loop Control

- Fingerprint findings by normalized root cause, not wording.
- Recompute `taskIdentityKey`, `evaluatedVersionKey`, and `reviewIdempotencyKey` with the versioned, namespaced canonical-JSON functions in `scripts/check_modernization_artifacts.py`; never implement them as raw string concatenation.
- Reject a duplicate `reviewIdempotencyKey`. The same evaluated version must still receive the required independent reviews from distinct `reviewerId` values; different `reviewerRole` values are recorded, not inferred.
- Require `startCommitSha == endCommitSha == targetCommitSha`; invalidate the result on any checkout, Rulebook, or Judge digest drift.
- Verify every Review Result's Ed25519 signature against the reviewer ID, role, and key anchored in the protected baseline policy. A bundle-selected trust registry is not authoritative.
- Require every approved Rule `judgeTests` ID to resolve through the evaluated `.agents/payment-modernization-judge-registry.json`; each approval review must sign one matching command, target SHA, zero exit code, and result digest.
- Validate every finding with the exact schema and strict severity/status/resolution enums, including SHOULD_FIX and FAIL results; unknown fields or enum values fail closed.
- Resolve every Rule Queue source through both the evaluated Rule Card registry and the exact Rule Card included by the current Capability Slice. Reject unregistered IDs, ambiguous duplicate IDs, and globally registered Rules owned by another slice.
- Resolve every Judge Queue source through both the evaluated Judge registry and a valid signed exact-command execution.
- Require the exact Queue Item schema and JSON integer schema version 2. Its immutable `initialStateHistory` starts at `open` with zero failed rounds, uses a transcript-wide unique evaluated snapshot key for every state, and replays legal transitions to the first persisted state. Reject non-adjacent key reuse such as `A -> B -> A`; each key denotes the recomputable evaluated snapshot for that transition, not a counter or display label. A first-seen resolved BLOCKER may be persisted in a closed bundle only through that signed bootstrap transcript; unresolved BLOCKERs and non-closed canonical bundles remain invalid.
- Require exact `queueHistoryEvidence` for every non-current bootstrap key: an immutable historical target, recomputed Rulebook/Judge manifests and version key from the retained task identity, a matching Queue state, and exactly two independent trusted signed PASS implementation reviews bound to that snapshot and Queue digest. A one-state current-only transcript may omit the field; prior placeholder digests receive no migration exemption. Retained targets must form a strict ancestor chain, and a merge transition is valid only through a later single-parent newly evaluated reconciliation.
- Compare Queue immutable roots, nested evidence/dependencies, duplicate fingerprints across bundles, merge-parent states, and reconciliations with type-strict canonical JSON identity. Never let host-language numeric equality collapse JSON integer `1`, boolean `true`, or number `1.0`.
- Require every Queue Item to sign an explicit status-compatible `resolution`: active work is `unresolved`, human decision is `deferred`, and closed is `fixed`, `flaky`, or `rejected`; `flaky` is build/test-only. Non-fix adjudications must remain explicit and cannot be reported as fixed.
- Resolve build/test Queue sources only through valid signed executions that bind the same `queueDigest`; untyped free text is not a gate. Each source also carries an immutable original failure execution with the same `checkId`, original command, failure commit, non-zero exit code, and result digest. A closed item's failure commit must be a strict ancestor of the evaluated target, never the same commit. Current verification executions must keep that command, and later evaluated versions must not rewrite the original result.
- Reject orphan, Judge-ID alias, duplicate, command-ambiguous, or origin-reinterpreting Queue sources.
- Replay every relevant immutable Git commit against its real direct parent or parents. When the signed Queue state changes, reject any `evaluatedVersionKey` already observed for that fingerprint; unchanged carried state may retain its key. A restored final tree cannot hide `A -> tampered B -> restored C`; report the exact failing historical commit SHA while still accepting legal linear transitions and a later signed single-parent reconciliation of a fork.
- Activate every new or changed signed Queue state in a single-parent commit whose delta contains only canonical JSON envelopes and whose changed Queue bundle targets that direct parent tree. Reject source co-changes, merge activations, and stale evaluated targets.
- A fixed resolution requires a new commit and independent re-review; `flaky` or `rejected` is an explicit non-fix closure.
- After three unsuccessful valid review rounds, stop and request human decision.
