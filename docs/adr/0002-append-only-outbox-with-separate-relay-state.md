# Keep outbox events append-only and relay state separate

Status: accepted.

Outbox rows are immutable event facts containing event ID, aggregate/schema versions, partition key, payload, and trace context. Polling delivery status, leases, retries, errors, and publication time live in a separate mutable relay-state table; inserting an event initializes its relay state in the same database transaction.

This keeps the first polling implementation operationally simple without coupling event history to polling mutations, and preserves a later CDC/Debezium path. Payment contexts must follow this shape rather than copying the original mutable IAM outbox. A relay process is still required before any module may claim events are being published.
