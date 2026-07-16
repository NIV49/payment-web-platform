# Payment Permission Reference Design

This directory contains an independent permission-system design and executable reference core.

It is intentionally isolated from both source projects:

- `sourceRepository/RuoYi-Vue` is used for the complete RBAC/data-scope business chain.
- `sourceRepository/continew-admin-dev` is used for structure, Sa-Token integration patterns, DTO/DO separation, cache invalidation, and data-permission extension points.

## Contents

```text
docs/ai-context/permission/   analysis and target design
database/migration/           PostgreSQL reference DDL
backend/permission-core/      executable Java reference core
```

## Verify

```bash
cd resultRepository/backend/permission-core
mvn -s maven-settings.xml test
```

This is not production-ready payment authorization. It deliberately excludes real order-table SQL rewriting, credential/MFA implementation, approval workflows, and production migrations.
