-- Execute with: psql -X -v ON_ERROR_STOP=1 -f <this-file>
-- Run once per PostgreSQL cluster as a cluster administrator before Flyway V33.
-- The capability role is deliberately created outside Flyway so PostgreSQL 18
-- cannot grant the migration principal implicit creator membership.

BEGIN;

SELECT pg_advisory_xact_lock(
    hashtextextended('mch001-bootstrap-registration-rotation-role', 0));

DO $bootstrap$
DECLARE
    executor_is_superuser BOOLEAN;
    capability RECORD;
BEGIN
    SELECT rolsuper INTO STRICT executor_is_superuser
      FROM pg_roles
     WHERE rolname = session_user;
    IF current_user <> session_user OR NOT executor_is_superuser THEN
        RAISE EXCEPTION
            'MCH-001 cluster bootstrap requires a direct superuser session';
    END IF;

    SELECT rolcanlogin, rolsuper, rolinherit, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO capability
      FROM pg_roles
     WHERE rolname = 'payment_merchant_registration_rotation';
    IF NOT FOUND THEN
        CREATE ROLE payment_merchant_registration_rotation
            NOLOGIN NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    ELSIF capability.rolcanlogin OR capability.rolsuper OR NOT capability.rolinherit
       OR capability.rolcreaterole OR capability.rolcreatedb
       OR capability.rolreplication OR capability.rolbypassrls THEN
        RAISE EXCEPTION
            'MCH-001 cluster bootstrap found a noncanonical rotation role';
    END IF;
END
$bootstrap$;

DO $membership$
DECLARE
    capability_oid OID;
BEGIN
    SELECT oid INTO STRICT capability_oid
      FROM pg_roles
     WHERE rolname = 'payment_merchant_registration_rotation';
    IF EXISTS (
        SELECT 1 FROM pg_auth_members
         WHERE roleid = capability_oid OR member = capability_oid
    ) THEN
        RAISE EXCEPTION
            'MCH-001 cluster bootstrap requires the rotation role to have zero memberships';
    END IF;
END
$membership$;

COMMIT;
