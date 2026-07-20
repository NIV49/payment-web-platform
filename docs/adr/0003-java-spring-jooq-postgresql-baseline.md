# Adopt Java 25, Spring Boot 4.1, jOOQ 3.21 and PostgreSQL 18

Status: accepted.

The backend baseline is Java 25 LTS, Spring Boot 4.1.x, Maven Wrapper, jOOQ 3.21.x as managed by the Spring Boot BOM, PostgreSQL 18, and Flyway. MyBatis is not an allowed parallel persistence stack after the migration completes.

Flyway migrations are the only schema truth. jOOQ tables, records, keys and sequences are generated from a disposable PostgreSQL 18 instance after every committed migration has run, and the generated sources are committed so an ordinary build never depends on a live database. CI regenerates the model from a clean database and rejects drift. Generation from a developer, shared, or production database is forbidden; H2-based DDL replay is also forbidden because the schema uses PostgreSQL-specific JSONB, arrays, partial indexes, functions and triggers.

The baseline is upgraded as one tested unit. Modules cannot select a lower Java release, override jOOQ independently, or introduce another ORM. Explicit SQL remains reviewable through jOOQ conditions and generated fields; raw string SQL requires a documented reason and database integration coverage.

The existing Java 17/MyBatis/PostgreSQL 16 implementation is an architecture deviation to remove incrementally behind the existing Core ports. A migration slice is complete only when its MyBatis mapper and dead projections are deleted, PostgreSQL contract tests pass, and no behavior is silently weakened.
