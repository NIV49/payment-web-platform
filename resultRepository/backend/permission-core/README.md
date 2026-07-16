# Payment Permission Core

Executable reference implementation for the target permission model. It is a framework boundary module, not a deployable Spring Boot application.

## What it proves

- one `RoleGrant` keeps permission, all scope dimensions, and fund constraints together;
- dimensions inside one Grant are ANDed; Grants are ORed without flattening correlated tuples;
- tenant and permission/session versions fail closed;
- FUND permissions require trusted catalog metadata and step-up authentication;
- approval enforces initiator/approver separation;
- list predicates use parameter values plus server-owned column whitelists;
- Redis keys are versioned and decoded snapshots are identity-checked;
- Sa-Token is behind a narrow session facade rather than becoming the business truth source.

## Package boundaries

```text
domain/       framework-free authorization model
application/  authorization, grant loading, and data-scope orchestration
datascope/    structured plans and parameterized predicate compiler
port/         database and business-relation contracts
cache/        in-memory and Redis cache boundaries
security/     Sa-Token session bridge boundary
persistence/  reference entities, MyBatis mappers, and read repositories
service/      role-grant administration transaction boundary
```

## Verify

```bash
mvn -s maven-settings.xml clean verify
```

## Deliberate production blockers

The module does not provide credential login, a concrete `StpUtil` adapter, MFA freshness, approval workflow, a Redis codec/client, the atomic role-grant write adapter, relationship providers, or generic MyBatis SQL rewriting. The reference DDL has not been executed against a real PostgreSQL instance in this workspace.

Do not connect it to balance, ledger, payout, withdrawal, refund, or adjustment write paths until the gates in `docs/ai-context/permission/09-migration-plan.md` pass.
