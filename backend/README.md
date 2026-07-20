# Payment Backend

The backend is a runnable Spring Boot Maven reactor. `applications/admin-api` is the current deployment unit; Identity business rules and adapters remain owned by `modules/identity`.

## What it proves

- one `RoleGrant` keeps permission, all scope dimensions, and fund constraints together;
- dimensions inside one Grant are ANDed; Grants are ORed without flattening correlated tuples;
- tenant and permission/session versions fail closed;
- FUND permissions require trusted catalog metadata and step-up authentication;
- approval enforces initiator/approver separation;
- list predicates use parameter values plus server-owned column whitelists;
- Redis keys are versioned and decoded snapshots are identity-checked;
- Sa-Token is behind a narrow session facade rather than becoming the business truth source.
- the Admin API provides local credential login, cookie sessions, explicit permission-policy admission and the Vben user/menu/system-management contract;
- PostgreSQL migrations and Redis-backed login throttling are wired into the runnable application.

## Current module boundaries

```text
backend/
├── applications/
│   ├── README.md                 admission rules for runnable deployment units
│   └── admin-api/                Spring Boot composition root and HTTP adapters
└── modules/
    └── identity/
        ├── core/                 framework-free model, use cases, and ports
        ├── persistence-postgres/ identity-owned MyBatis adapter and migrations
        ├── cache-redis/          identity-owned permission snapshot cache
        └── session-satoken/      identity-owned trusted-session adapter
```

`applications` means executable composition roots only. Each bounded context owns its core and adapters under `modules`; infrastructure is not pooled into generic repository-wide modules. Dependencies point inward: Identity adapters implement Identity core ports, while `identity-core` does not depend on applications, MyBatis, Redis, or Sa-Token. Test-only fakes stay with the owning module until more than one module needs a shared `test-support` artifact.

## Verify

```bash
./mvnw -s maven-settings.xml clean verify
```

## Local PostgreSQL

From the repository root:

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

The first startup executes all Flyway migrations under `modules/identity/persistence-postgres/src/main/resources/db/migration` against a fresh local volume. Existing local volumes are upgraded forward; applied migrations must not be edited.

Run the current application from `backend/`:

```bash
./mvnw -s maven-settings.xml -pl applications/admin-api -am package -DskipTests
java -jar applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

The local API is available at `http://127.0.0.1:8080/api`. The local password initializer provides `admin / Admin@123456` and can be overridden through environment variables. The historical V2 migration still contains fixed-ID bootstrap rows; do not enable production Flyway until persistent-environment inventory and fixture separation are complete.

## Deliberate production blockers

The runnable Admin CRUD now uses the versioned Grant snapshot and full authorization service for same-tenant resources; `/api/auth/codes` remains UI-only. MFA freshness, approval evidence, atomic RoleGrant writes, relationship providers, business-list DataScopePlan application, permission-catalog validation for menu `authCode`, fixture separation, and production deployment hardening remain incomplete.

Do not connect it to balance, ledger, payout, withdrawal, refund, or adjustment write paths until the gates in `../docs/ai-context/permission/09-migration-plan.md` pass.
