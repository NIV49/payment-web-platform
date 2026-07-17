# Payment Backend

The backend is a Maven multi-module reactor. It currently contains the permission-domain foundation and its database migration, but it is not yet a deployable Spring Boot HTTP application.

## What it proves

- one `RoleGrant` keeps permission, all scope dimensions, and fund constraints together;
- dimensions inside one Grant are ANDed; Grants are ORed without flattening correlated tuples;
- tenant and permission/session versions fail closed;
- FUND permissions require trusted catalog metadata and step-up authentication;
- approval enforces initiator/approver separation;
- list predicates use parameter values plus server-owned column whitelists;
- Redis keys are versioned and decoded snapshots are identity-checked;
- Sa-Token is behind a narrow session facade rather than becoming the business truth source.

## Current module boundaries

```text
backend/
├── applications/
│   └── identity-authorization/  composition and migration owner
├── modules/
│   └── identity-organization/   framework-free permission model, use cases, and ports
└── adapters/
    ├── persistence-postgres/    MyBatis persistence adapter
    ├── cache-redis/             versioned permission snapshot cache adapter
    └── auth-satoken/            trusted session-to-subject adapter
```

Dependencies point inward: applications compose modules and adapters; adapters implement module ports; the domain module does not depend on applications, MyBatis, Redis, or Sa-Token. Test-only fakes stay with the owning module until more than one module needs a shared `test-support` artifact.

## Verify

```bash
mvn -s maven-settings.xml clean verify
```

## Local PostgreSQL

From the repository root:

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

The first startup executes `applications/identity-authorization/src/main/resources/db/migration/V1__permission_schema.sql` against a fresh local volume.

## Deliberate production blockers

The modules do not provide credential login, a concrete `StpUtil` adapter, MFA freshness, approval workflow, a Redis codec/client, the atomic role-grant write adapter, relationship providers, generic MyBatis SQL rewriting, or Flyway runtime integration. The reference DDL has only been verified against the local PostgreSQL container.

Do not connect it to balance, ledger, payout, withdrawal, refund, or adjustment write paths until the gates in `../docs/ai-context/permission/09-migration-plan.md` pass.
