# Payment Backend

The backend is a runnable Spring Boot Maven reactor. `applications/admin-api` is the current deployment unit; Identity business rules and adapters remain owned by `modules/identity`.

The enforced baseline is Java 25, Spring Boot 4.1, jOOQ 3.21, Flyway 12.4, PostgreSQL 18.4, Valkey 7.2 and Sa-Token 1.45. MyBatis is not part of the runtime persistence stack.

## What it proves

- one `RoleGrant` keeps permission, all scope dimensions, and fund constraints together;
- dimensions inside one Grant are ANDed; Grants are ORed without flattening correlated tuples;
- tenant and permission/session versions fail closed;
- each Admin write carries a trusted `AdministrationActor` and, after locking the tenant plus membership/user/credential tuple, rechecks active states, a non-null password hash, and both permission/session versions;
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
        ├── persistence-postgres/ identity-owned jOOQ adapter, generated model and migrations
        ├── cache-redis/          identity-owned permission snapshot cache
        └── session-satoken/      identity-owned trusted-session adapter
```

`applications` means executable composition roots only. Each bounded context owns its core and adapters under `modules`; infrastructure is not pooled into generic repository-wide modules. Dependencies point inward: Identity adapters implement Identity core ports, while `identity-core` does not depend on applications, jOOQ, Redis, or Sa-Token. Test-only fakes stay with the owning module until more than one module needs a shared `test-support` artifact.

## Verify

```bash
./mvnw -s maven-settings.xml clean verify
```

## Local PostgreSQL

From the repository root:

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

The first startup executes all Flyway migrations under `modules/identity/persistence-postgres/src/main/resources/db/migration` against a fresh local volume. Existing Flyway-managed local volumes are upgraded forward; applied migrations must not be edited. A legacy volume with manually applied V1 and no `flyway_schema_history` is unsupported and must be backed up if needed, then rebuilt from an empty local database. The local profile does not infer a baseline.

Production keeps `spring.flyway.enabled=false`: schema migration belongs to a separate deployment Job, not the web process. That Job/CD orchestration is not implemented in this repository, so production remains **NO-GO**. The web application still installs a read-only startup guard before the server can accept traffic. It validates every versioned migration packaged in the current binary and refuses to start when one is pending, missing, failed, future, renamed, type-changed, or has a checksum mismatch. Rejections expose a stable sanitized reason code only. The guard never calls `migrate`, `repair`, or modifies `flyway_schema_history`. Successful future migrations may be reconsidered only after expand/contract rules and an N/N-1 mixed-version compatibility gate exist; rolling rollback is not currently guaranteed.

The `local` profile enables Flyway for developer convenience. Spring Boot's database-initialization dependency makes local migration finish before the same read-only guard runs.

Run the current application from `backend/`:

```bash
./mvnw -s maven-settings.xml -pl applications/admin-api -am package -DskipTests
java -jar applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

The local API is available at `http://127.0.0.1:8080/api`. After the production migration chain completes, the `local` profile separately provisions `admin / Admin@123456`; the password can be overridden through `PAYMENT_BOOTSTRAP_PASSWORD`. V8 removes only the reserved V2/V3 fixture footprint while preserving the global and extended permission catalog plus unrelated tenants, users, audit events, and outbox events. Reserved-key collisions, modified fixture rows, or extra relationships attached to tenant `1` abort the transaction and require the [V8 migration runbook](../docs/runbooks/iam-v8-fixture-isolation.md).

V9 makes tenant-local route names (case-insensitive) and non-null route paths unique, and V10 enforces the Core dimension/scope-mode matrix in PostgreSQL. Both migrations refuse ambiguous legacy data instead of choosing which route or permission to keep.

Forwarded headers are disabled by default (`PAYMENT_FORWARD_HEADERS_STRATEGY=NONE`). Enable them only behind a trusted boundary proxy that strips all client-supplied `Forwarded`/`X-Forwarded-*` values before writing its own; login throttling depends on this trust boundary.

## Deliberate production blockers

The runnable Admin CRUD now uses the versioned Grant snapshot and full authorization service for same-tenant resources; `/api/auth/codes` remains UI-only. A finite Grant `valid_until` can still expire while a request waits for the Admin transaction locks, because the complete Grant decision is not re-evaluated after lock acquisition. This is a production blocker, not a closed issue; until transaction-time authorization exists, Admin write permissions must not use finite `valid_until`. MFA freshness, trusted approval evidence, external IdP integration, atomic RoleGrant writes, relationship providers, business-list DataScopePlan application, permission-catalog validation for menu `authCode`, production provisioning, and production observability also remain incomplete.

Do not connect it to balance, ledger, payout, withdrawal, refund, or adjustment write paths until the gates in `../docs/ai-context/permission/09-migration-plan.md` pass.
