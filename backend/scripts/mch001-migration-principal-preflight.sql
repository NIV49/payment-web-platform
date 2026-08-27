-- Execute with: psql -X -v ON_ERROR_STOP=1 -f <this-file>
-- Run as the Flyway login immediately before merchant migrations. This script
-- is read-only and fails closed when the migration identity could create or use
-- the offline rotation capability role.

BEGIN READ ONLY;

DO $preflight$
DECLARE
    migration_principal RECORD;
    capability RECORD;
BEGIN
    IF current_user <> session_user THEN
        RAISE EXCEPTION 'MCH-001 migration preflight requires a direct login session';
    END IF;

    SELECT rolsuper, rolcreaterole
      INTO STRICT migration_principal
      FROM pg_roles
     WHERE rolname = session_user;
    IF migration_principal.rolsuper OR migration_principal.rolcreaterole THEN
        RAISE EXCEPTION
            'MCH-001 migration principal must be NOSUPERUSER and NOCREATEROLE';
    END IF;

    SELECT oid, rolcanlogin, rolsuper, rolinherit, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO STRICT capability
      FROM pg_roles
     WHERE rolname = 'payment_merchant_registration_rotation';
    IF capability.rolcanlogin OR capability.rolsuper OR NOT capability.rolinherit
       OR capability.rolcreaterole OR capability.rolcreatedb
       OR capability.rolreplication OR capability.rolbypassrls THEN
        RAISE EXCEPTION 'MCH-001 migration preflight found a noncanonical rotation role';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_auth_members
         WHERE roleid = capability.oid OR member = capability.oid
    ) THEN
        RAISE EXCEPTION
            'MCH-001 migration preflight requires the rotation role to have zero memberships';
    END IF;
    IF pg_has_role(session_user, capability.oid, 'MEMBER')
       OR pg_has_role(session_user, capability.oid, 'USAGE')
       OR pg_has_role(session_user, capability.oid, 'SET') THEN
        RAISE EXCEPTION
            'MCH-001 migration principal must not access the rotation role';
    END IF;
END
$preflight$;

COMMIT;
