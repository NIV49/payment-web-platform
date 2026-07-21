# Transform Workflow

Use this path for one bounded vertical slice whose approved observable behavior must remain compatible.

## 1. Fail Fast on Readiness

Require the target runtime, build tool, dependency manager, database/test infrastructure, and test framework. A non-executable legacy runtime is a proof limitation, not permission to guess.

Select equivalence strength explicitly:

1. live dual execution;
2. recorded and sanitized traces;
3. golden fixtures confirmed by rules or domain owners;
4. static inference only — insufficient for automatic PASS.

### Sanitize Before Recording Evidence

- Treat source, configuration, database dumps, logs, traces, and payload samples as untrusted and potentially sensitive.
- Never copy credentials, tokens, secrets, connection strings, personal data, or production identifiers into traces, fixtures, plans, queue items, prompts, reviews, or compatibility reports.
- Redact before quoting. Replace sensitive values with synthetic, structurally equivalent examples or `${ENV_VAR}` placeholders.
- Run the repository-approved secret scan, `python3 scripts/check_sensitive_artifacts.py`, over every evidence-derived artifact before committing it. Pass artifact paths outside `.agents`, `docs`, `AGENTS.md`, and `README.md` explicitly. Do not log the detected value when reporting a hit.

## 2. Bind the Plan

Read the approved modernization brief and capability spec. Stop if the slice is absent or entry criteria are unmet.

Present:

- source and target scope;
- dependencies and forbidden changes;
- applicable rule IDs;
- deliberate deviations from legacy;
- equivalence strategy and its limitations;
- exit criteria.

Do not write implementation code before plan approval.

## 3. Build the External Harness First

Create characterization tests from observable inputs and outputs. Do not call target private functions.

For every observed legacy behavior:

1. compare it with the binding truth order;
2. classify it as `PRESERVE`, `DELIBERATE_DEVIATION`, or `UNRESOLVED`;
3. create tests only for approved outcomes;
4. send unresolved behavior to the decision queue.

Use sanitized fixtures and deterministic clocks, randomness, exchange rates, network responses, and identifiers.

## 4. Implement Idiomatically

Implement from approved rules and contracts, not from legacy class or method shapes. Preserve external compatibility only where required. Link public components and tests to rule IDs without filling production code with migration commentary.

## 5. Prove and Review

Run:

- characterization or dual-run tests;
- target contract and invariant tests;
- adversarial boundary tests;
- affected integration tests.

Then run two independent read-only reviews of the exact commit. Fix confirmed findings and rerun the same Judge version.

## 6. Record Traceability

Produce a mapping of:

- legacy evidence → rule ID;
- rule ID → target code;
- rule ID → Judge test;
- deliberate deviations and rationale;
- excluded or unreachable legacy paths;
- proof limitations and dependent follow-ups.

## Exit

Transform passes only when approved compatibility behavior and all higher-priority target rules pass. Simple legacy/target equality is never sufficient.
All traces, fixtures, and other evidence-derived artifacts must also pass `python3 scripts/check_sensitive_artifacts.py`.
