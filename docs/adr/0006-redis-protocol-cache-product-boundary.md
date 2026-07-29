# Separate the Redis protocol boundary from the cache product

Status: accepted.

Application code depends on the Spring Data Redis protocol abstraction and on Identity Core cache ports, not on product-specific commands. Valkey is the default local container. A production environment must explicitly select and pin Redis or Valkey, validate the commands, serialization, eviction and failure behavior used by the application, and document backup and operational ownership.

Neither product is a source of authorization truth. Cached permission snapshots are versioned and bounded by the earliest relevant grant expiry; session restoration revalidates authoritative database state. Cache loss may reduce performance or invalidate sessions, but must not grant access. Documentation must use “Redis protocol” when describing the code boundary and name the concrete product only when describing a deployed environment.
