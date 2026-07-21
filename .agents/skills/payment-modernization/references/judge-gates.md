# Judge Gates

## Verdict Order

Evaluate the exact commit against the exact Rulebook version.

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

- absolute path and exact line;
- symbol and reachable control flow;
- trigger and observable impact;
- code, test, data, or configuration evidence;
- reproducible verification;
- target commit and Rulebook version.

## Automatic PASS

```text
snapshot valid
+ all required builds and tests pass
+ all included rules have Judge coverage
+ no unresolved BLOCKER
+ all deviations adjudicated
+ traceability complete
= PASS
```

An implementation agent's report is evidence, never the verdict.

## Loop Control

- Fingerprint findings by normalized root cause, not wording.
- Do not review the same version key twice.
- A fix requires a new commit and independent re-review.
- After three unsuccessful valid review rounds, stop and request human decision.
