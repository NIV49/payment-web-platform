# Payment Backend

The backend is a runnable Spring Boot Maven reactor with independent `platform-admin-api`, `merchant-admin-api`, and `agent-admin-api` deployment units. Identity business rules and adapters remain owned by `modules/identity`.

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
- all three backoffice APIs have explicit account-domain OIDC client composition, cookie sessions, back-channel logout, identity-version checks and independent CSRF; local credential login is restricted to the `local` profile;
- the platform Admin API provides explicit permission-policy admission and the Vben user/menu/system-management contract;
- PostgreSQL migrations and Redis-backed login throttling are wired into the runnable application.

## Current module boundaries

```text
backend/
├── applications/
│   ├── README.md                 admission rules for runnable deployment units
│   ├── platform-admin-api/       PLATFORM composition root and HTTP adapters
│   ├── merchant-admin-api/       MERCHANT composition root
│   └── agent-admin-api/          AGENT composition root
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

The first startup executes all Flyway migrations under `modules/identity/persistence-postgres/src/main/resources/db/migration` against a fresh local volume. Existing Flyway-managed local volumes receive forward schema migrations; applied migrations must not be edited. The image reads `POSTGRES_PASSWORD` only while it initializes a fresh data directory. Changing that initialization variable later does not rotate the password of an existing PostgreSQL role, so schema upgrade and role credential alignment are separate procedures. A legacy volume with manually applied V1 and no `flyway_schema_history` is unsupported and must be backed up if needed, then rebuilt from an empty local database. The local profile does not infer a baseline.

Production keeps `spring.flyway.enabled=false`: schema migration belongs to a separate deployment Job, not the web process. That Job/CD orchestration is not implemented in this repository, so production remains **NO-GO**. The web application still installs a read-only startup guard before the server can accept traffic. It validates every versioned migration packaged in the current binary and refuses to start when one is pending, missing, failed, future, renamed, type-changed, or has a checksum mismatch. Rejections expose a stable sanitized reason code only. The guard never calls `migrate`, `repair`, or modifies `flyway_schema_history`. Successful future migrations may be reconsidered only after expand/contract rules and an N/N-1 mixed-version compatibility gate exist; rolling rollback is not currently guaranteed.

The `local` profile enables Flyway for developer convenience. Spring Boot's database-initialization dependency makes local migration finish before the same read-only guard runs.

Run the current application from `backend/`:

```bash
./mvnw -s maven-settings.xml -pl applications/platform-admin-api -am package -DskipTests
printf 'Local bootstrap password: '
read -r -s PAYMENT_BOOTSTRAP_PASSWORD
printf '\n'
export PAYMENT_BOOTSTRAP_PASSWORD
java -jar applications/platform-admin-api/target/platform-admin-api-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
unset PAYMENT_BOOTSTRAP_PASSWORD
```

The local API is available at `http://127.0.0.1:8080/api`. After the production migration chain completes, the `local` profile separately provisions the `admin` username. Startup requires the local operator to supply `PAYMENT_BOOTSTRAP_PASSWORD`; no default identity credential is committed. V8 removes only the reserved V2/V3 fixture footprint while preserving the global and extended permission catalog plus unrelated tenants, users, audit events, and outbox events. Reserved-key collisions, modified fixture rows, or extra relationships attached to tenant `1` abort the transaction and require the [V8 migration runbook](../docs/runbooks/iam-v8-fixture-isolation.md).

### Existing local volume credential alignment

Run all compose and volume commands in this subsection from the repository root. The compose contract uses `disabled` as a public, non-secret sentinel for loopback-only PostgreSQL and Valkey. If the exact named volume `payment-web-platform-postgres18-data` already exists with a different PostgreSQL role password, first back up all databases from the running container and verify that the backup is non-empty. A `pg_dumpall` archive contains application data plus cluster-wide role definitions and credential verifier metadata: keep it outside the repository with owner-only permissions, never attach it to tickets or commits, and apply the approved retention and secure-destruction procedure to this exact file when it is no longer required.

```bash
umask 077
mkdir -p ../payment-web-platform-local-backups
docker compose -f infra/docker-compose.local.yml exec -T postgres \
  pg_dumpall -U payment_dev \
  > ../payment-web-platform-local-backups/postgres-before-role-password-rotation.sql
test -s ../payment-web-platform-local-backups/postgres-before-role-password-rotation.sql
```

Then use the container-local trusted Unix socket to rotate the existing role in place. Omitting `-h` is intentional; this command must not depend on the stale TCP password:

```bash
docker compose -f infra/docker-compose.local.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U payment_dev -d payment_platform \
  -c "ALTER ROLE payment_dev WITH PASSWORD 'disabled';"
```

After the role is aligned, start the application with an explicit local fixture password. The prompt avoids placing that value in the command history:

```bash
printf 'Local bootstrap password: '
read -r -s PAYMENT_BOOTSTRAP_PASSWORD
printf '\n'
export PAYMENT_BOOTSTRAP_PASSWORD
java -jar backend/applications/platform-admin-api/target/platform-admin-api-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
unset PAYMENT_BOOTSTRAP_PASSWORD
```

Deleting the PostgreSQL volume is an irreversible fallback, not a password-rotation procedure. Use it only when the data is explicitly disposable and the backup above already exists and has been checked. These commands stop both local compose services, inspect and delete only the exact PostgreSQL named volume, and then create a fresh one:

```bash
test -s ../payment-web-platform-local-backups/postgres-before-role-password-rotation.sql
docker compose -f infra/docker-compose.local.yml down
docker volume inspect payment-web-platform-postgres18-data
docker volume rm payment-web-platform-postgres18-data
docker compose -f infra/docker-compose.local.yml up -d
```

Removing `payment-web-platform-postgres18-data` permanently deletes every database stored in that volume unless restored from backup. Valkey is different: compose supplies `--requirepass disabled` on every process start, so changing to the current sentinel does not require a data-volume migration.

V9 makes tenant-local route names (case-insensitive) and non-null route paths unique, and V10 enforces the Core dimension/scope-mode matrix in PostgreSQL. Both migrations refuse ambiguous legacy data instead of choosing which route or permission to keep.

Forwarded headers are disabled by default (`PAYMENT_FORWARD_HEADERS_STRATEGY=NONE`). Enable them only behind a trusted boundary proxy that strips all client-supplied `Forwarded`/`X-Forwarded-*` values before writing its own; login throttling depends on this trust boundary.

## Deliberate production blockers

The runnable Admin CRUD now uses the versioned Grant snapshot and full authorization service for same-tenant resources; `/api/auth/codes` remains UI-only. A finite Grant `valid_until` can still expire while a request waits for the Admin transaction locks, because the complete Grant decision is not re-evaluated after lock acquisition. This is a production blocker, not a closed issue; until transaction-time authorization exists, Admin write permissions must not use finite `valid_until`. MFA freshness, trusted approval evidence, external IdP integration, atomic RoleGrant writes, relationship providers, business-list DataScopePlan application, permission-catalog validation for menu `authCode`, production provisioning, and production observability also remain incomplete.

Do not connect it to balance, ledger, payout, withdrawal, refund, or adjustment write paths until the gates in `../docs/ai-context/permission/09-migration-plan.md` pass.
