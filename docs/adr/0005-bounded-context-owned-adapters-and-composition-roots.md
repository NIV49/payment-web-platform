# Keep composition roots executable and adapters context-owned

Status: accepted.

Every directory under `backend/applications` is an independently runnable and deployable composition root. Business capabilities such as identity, organization, roles and permissions live under `backend/modules/<bounded-context>`, even when the first deployment contains only one application.

A bounded context owns its Core ports and the adapters that implement them. PostgreSQL, Redis, messaging and external-provider adapters therefore remain below the owning context instead of being pooled into repository-wide infrastructure modules. Applications wire adapters to ports and expose transport contracts; they do not contain domain rules or persistence queries.

Dependencies point inward: application to module, adapter to Core port, never Core to framework or application. A shared contract or test-support artifact is introduced only after real cross-deployment or cross-context reuse exists. This prevents an `applications/identity-authorization` business module and a central `persistence-postgres` repository from becoming accidental coupling hubs.
