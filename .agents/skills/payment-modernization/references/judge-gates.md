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
- A fix requires a new commit and independent re-review.
- After three unsuccessful valid review rounds, stop and request human decision.
