# Backend Applications

This directory is reserved for independently runnable and deployable applications.

An application belongs here only when it has:

- an executable entry point;
- runtime configuration;
- explicit module and adapter composition;
- a buildable deployment artifact;
- health checks and operational ownership.

Business rules, persistence adapters, cache adapters, and migrations do not qualify on their own. Until an Identity deployment unit meets these conditions, its code remains under `modules/identity`.
