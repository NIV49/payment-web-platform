# Separate production migrations from local fixtures

Status: accepted.

Production Flyway migrations contain schema changes and required reference catalog data only. They must not create fixed users, passwords, tenant memberships or demonstration business records. Local examples and automated-test fixtures are loaded by explicit local/test bootstrap mechanisms that cannot activate under a production profile.

All committed or executed migrations are immutable, including migrations that violated this rule. A forward-only migration must neutralize any historical fixed credential or unsafe fixture, after which a profile-gated bootstrap may create disposable local data with an explicit, documented password. CI must prove that a production-profile migration into an empty database creates no usable bootstrap credential.

This keeps code generation deterministic without turning generated schemas into data fixtures, prevents development convenience from becoming a production backdoor, and preserves Flyway checksum integrity.
