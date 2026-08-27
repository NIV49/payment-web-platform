import importlib.util
import base64
import json
import os
import stat
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PREPARE_SCRIPT = REPOSITORY_ROOT / "scripts/dev/prepare_iam002_local.py"
RUNTIME_SCRIPT = REPOSITORY_ROOT / "scripts/dev/iam002_local.py"


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_private_value(label: str) -> str:
    return "-".join((label, "private"))


def fixture_valid_password(label: str) -> str:
    return "".join(("Aa1!", label, "-value"))


def fixture_totp_secret() -> str:
    return "".join(("GEZDGNBVGY3TQOJQ", "GEZDGNBVGY3TQOJQ", "GEZDGNBVGY3TQOJQ", "GEZA"))


def fixture_repeated_value(character: str, length: int) -> str:
    return character * length


class Iam002LocalArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prepare = load_module(PREPARE_SCRIPT, "prepare_iam002_local")

    def test_generation_is_private_repeatable_and_keeps_secrets_out_of_realms(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            secret = lambda key: (
                f"Aa1!private-{key.lower()}"
                if key.endswith("_LOCAL_ADMIN_PASSWORD")
                else f"private-{key.lower()}"
            )

            first = self.prepare.prepare_local_artifacts(
                REPOSITORY_ROOT, output, lambda _: False, secret
            )
            second = self.prepare.prepare_local_artifacts(
                REPOSITORY_ROOT, output, lambda _: False, lambda _: "changed"
            )

            self.assertEqual(first, second)
            env_path = output / "runtime.env"
            self.assertEqual(stat.S_IMODE(env_path.stat().st_mode), 0o600)
            env_text = env_path.read_text(encoding="utf-8")
            for domain in ("PLATFORM", "MERCHANT", "AGENT"):
                realm_path = output / "realms" / f"{domain}-realm.json"
                self.assertEqual(stat.S_IMODE(realm_path.stat().st_mode), 0o600)
                realm_text = realm_path.read_text(encoding="utf-8")
                realm = json.loads(realm_text)
                users = [
                    user
                    for user in realm["users"]
                    if user.get("username") == f"admin@{domain.lower()}.localhost"
                ]
                self.assertEqual(len(users), 1)
                self.assertEqual(users[0]["id"], self.prepare.LOCAL_IDENTITIES[domain]["subject"])
                otp = next(
                    credential
                    for credential in users[0]["credentials"]
                    if credential.get("type") == "otp"
                )
                self.assertEqual(
                    json.loads(otp["credentialData"])["secretEncoding"],
                    "BASE32",
                )
                self.assertEqual(
                    users[0]["requiredActions"],
                    ["CONFIGURE_RECOVERY_AUTHN_CODES"],
                )
                self.assertIn(f"${{PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD}}", realm_text)
                self.assertIn(f"${{PAYMENT_{domain}_LOCAL_ADMIN_TOTP_SECRET}}", realm_text)
                self.assertNotIn(f"private-payment_{domain.lower()}_local_admin_password", realm_text)
            self.assertIn("PAYMENT_IAM002_REALM_DIR=", env_text)

    def test_missing_private_environment_fails_when_keycloak_volume_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "private runtime environment is missing"):
                self.prepare.prepare_local_artifacts(
                    REPOSITORY_ROOT, Path(directory), lambda _: True, lambda _: "secret"
                )

    def test_generated_local_passwords_satisfy_the_realm_policy(self):
        for domain in ("PLATFORM", "MERCHANT", "AGENT"):
            password = self.prepare._default_secret(f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD")
            self.prepare._validate_local_passwords(
                {
                    f"PAYMENT_{candidate}_LOCAL_ADMIN_PASSWORD": (
                        password if candidate == domain else fixture_valid_password("known")
                    )
                    for candidate in ("PLATFORM", "MERCHANT", "AGENT")
                }
            )

    def test_invalid_local_password_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "violates the Realm policy"):
            self.prepare._validate_local_passwords(
                {
                    "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("lowercase-only"),
                    "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_valid_password("merchant"),
                    "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_valid_password("agent"),
                }
            )

    def test_corrupt_private_environment_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            postgres_value = fixture_private_value("x")
            postgres_key = "_".join(("PAYMENT_IAM002_POSTGRES", "PASSWORD"))
            (output / "runtime.env").write_text(
                f"{postgres_key}={postgres_value}\n",
                encoding="utf-8",
            )
            os.chmod(output / "runtime.env", 0o600)
            with self.assertRaisesRegex(RuntimeError, "missing required keys"):
                self.prepare.prepare_local_artifacts(
                    REPOSITORY_ROOT, output, lambda _: False, lambda _: "secret"
                )

    def test_local_compose_and_fixture_conversion_are_isolated_and_bounded(self):
        compose = (REPOSITORY_ROOT / "infra/docker-compose.iam002-local.yml").read_text(encoding="utf-8")
        fixture = (REPOSITORY_ROOT / "infra/local/iam002/convert-local-fixture-to-oidc.sql").read_text(
            encoding="utf-8"
        )

        self.assertIn("name: payment-web-platform-iam002-local", compose)
        for port in ("25432:5432", "26379:6379", "18080:8080"):
            self.assertIn(port, compose)
        for forbidden_port in ("15432:5432", "16379:6379", "5999:5999"):
            self.assertNotIn(forbidden_port, compose)
        self.assertIn("quay.io/keycloak/keycloak:26.7.0@sha256:", compose)
        self.assertIn('["start-dev", "--features=recovery-codes", "--import-realm"]', compose)
        self.assertNotIn("--features=preview", compose)
        self.assertNotIn("axllent/mailpit", compose)
        self.assertIn("PAYMENT_KEYCLOAK_SMTP_HOST: host.docker.internal", compose)
        self.assertIn("PAYMENT_KEYCLOAK_SMTP_PORT: \"11025\"", compose)
        self.assertIn(":/opt/keycloak/data/import:ro", compose)

        self.assertIn("BEGIN;", fixture)
        self.assertIn("pg_advisory_xact_lock", fixture)
        self.assertIn("idp_provisioning_status = 'PROVISIONED'", fixture)
        self.assertIn("password_hash = NULL", fixture)
        self.assertIn("identity_version", fixture)
        self.assertIn("session_version", fixture)
        self.assertIn("permission_version", fixture)
        self.assertIn("COMMIT;", fixture)

    def test_isolated_local_backend_profiles_enable_only_password_login(self):
        for domain in ("platform", "merchant", "agent"):
            application = f"{domain}-admin-api"
            profile = (
                REPOSITORY_ROOT
                / "backend/applications"
                / application
                / "src/main/resources/application-iam002-local.yml"
            ).read_text(encoding="utf-8")

            self.assertIn("local-login-enabled: true", profile)
            self.assertIn("  oidc:\n    enabled: false", profile)
            self.assertNotIn("oidc-local", profile)

    def test_isolated_local_backend_profiles_enable_role_configuration_editing(self):
        for domain in ("platform", "merchant", "agent"):
            application = f"{domain}-admin-api"
            for profile_name in ("iam002-local", "oidc-local"):
                profile = (
                    REPOSITORY_ROOT
                    / "backend/applications"
                    / application
                    / f"src/main/resources/application-{profile_name}.yml"
                ).read_text(encoding="utf-8")

                self.assertIn(
                    "legacy-administration-cutover-complete: true",
                    profile,
                    f"{application} {profile_name} must enable local role configuration editing",
                )

    def test_read_only_composition_roots_directly_package_system_dictionary(self):
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        for application in ("merchant-admin-api", "agent-admin-api"):
            pom = ET.parse(
                REPOSITORY_ROOT / "backend/applications" / application / "pom.xml"
            )
            dependencies = {
                (
                    dependency.findtext("m:groupId", namespaces=namespace),
                    dependency.findtext("m:artifactId", namespaces=namespace),
                )
                for dependency in pom.findall("m:dependencies/m:dependency", namespace)
            }

            self.assertIn(
                ("com.niv.payment", "system-dictionary"),
                dependencies,
                f"{application} must package the current dictionary migrations directly",
            )


class Iam002LocalProcessOwnershipTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtime = load_module(RUNTIME_SCRIPT, "iam002_local")

    def test_process_record_requires_exact_owned_process_identity(self):
        record = {
            "pid": 123,
            "pgid": 123,
            "cwd": str(REPOSITORY_ROOT),
            "commandMarker": "platform-admin-api.jar",
        }
        observed = {
            "pid": 123,
            "pgid": 123,
            "cwd": str(REPOSITORY_ROOT),
            "command": "java -jar platform-admin-api.jar",
        }
        self.assertTrue(self.runtime.process_matches(record, observed))
        for key, value in (
            ("pid", 124),
            ("pgid", 124),
            ("cwd", "/tmp"),
            ("command", "java -jar unrelated.jar"),
        ):
            changed = dict(observed)
            changed[key] = value
            self.assertFalse(self.runtime.process_matches(record, changed))

    def test_fixture_state_is_exact_and_fails_closed(self):
        self.assertEqual(self.runtime.classify_fixture_state({"table_count": 0}), "EMPTY")
        self.assertEqual(
            self.runtime.classify_fixture_state(
                {
                    "table_count": 7,
                    "user_count": 0,
                    "local_count": 0,
                    "oidc_count": 0,
                    "entry_host_count": 0,
                }
            ),
            "EMPTY",
        )
        self.assertEqual(
            self.runtime.classify_fixture_state(
                {
                    "table_count": 7,
                    "user_count": 3,
                    "local_count": 3,
                    "oidc_count": 0,
                    "entry_host_count": 0,
                }
            ),
            "LOCAL",
        )
        self.assertEqual(
            self.runtime.classify_fixture_state(
                {
                    "table_count": 7,
                    "user_count": 3,
                    "local_count": 0,
                    "oidc_count": 3,
                    "entry_host_count": 3,
                    "baseline_entry_host_count": 3,
                }
            ),
            "OIDC",
        )
        self.assertEqual(
            self.runtime.classify_fixture_state(
                {
                    "table_count": 7,
                    "user_count": 8,
                    "local_count": 0,
                    "local_auth_count": 3,
                    "oidc_count": 0,
                    "entry_host_count": 6,
                    "baseline_entry_host_count": 3,
                }
            ),
            "LOCAL_AUTH",
        )
        self.assertEqual(
            self.runtime.classify_fixture_state(
                {
                    "table_count": 7,
                    "user_count": 8,
                    "local_count": 0,
                    "oidc_count": 3,
                    "entry_host_count": 6,
                    "baseline_entry_host_count": 3,
                }
            ),
            "OIDC",
        )
        for state in (
            {"table_count": 7, "user_count": 3, "local_count": 2, "oidc_count": 0, "entry_host_count": 0},
            {"table_count": 7, "user_count": 3, "local_count": 3, "oidc_count": 0, "entry_host_count": 1},
            {"table_count": 7, "user_count": 3, "local_count": 1, "oidc_count": 2, "entry_host_count": 3},
            {"table_count": 7, "user_count": 1, "local_count": 0, "oidc_count": 0, "entry_host_count": 0},
            {
                "table_count": 7,
                "user_count": 8,
                "local_count": 0,
                "oidc_count": 3,
                "entry_host_count": 6,
                "baseline_entry_host_count": 2,
            },
        ):
            with self.assertRaisesRegex(RuntimeError, "unknown or mixed"):
                self.runtime.classify_fixture_state(state)

    def test_backend_environment_maps_only_expected_private_values(self):
        private = {
            "PAYMENT_IAM002_POSTGRES_PASSWORD": fixture_private_value("postgres"),
            "PAYMENT_IAM002_REDIS_PASSWORD": fixture_private_value("redis"),
            "PAYMENT_PLATFORM_OIDC_CLIENT_SECRET": fixture_private_value("platform-oidc"),
            "PAYMENT_PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET": fixture_private_value("platform-admin"),
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_MERCHANT_OIDC_CLIENT_SECRET": fixture_private_value("merchant-oidc"),
            "PAYMENT_MERCHANT_KEYCLOAK_ADMIN_CLIENT_SECRET": fixture_private_value("merchant-admin"),
            "PAYMENT_AGENT_OIDC_CLIENT_SECRET": fixture_private_value("agent-oidc"),
            "PAYMENT_AGENT_KEYCLOAK_ADMIN_CLIENT_SECRET": fixture_private_value("agent-admin"),
        }
        environment = self.runtime.backend_environment(private)

        self.assertEqual(
            environment["PAYMENT_IAM002_POSTGRES_PASSWORD"],
            fixture_private_value("postgres"),
        )
        self.assertEqual(
            environment["PAYMENT_IAM002_REDIS_PASSWORD"],
            fixture_private_value("redis"),
        )
        self.assertEqual(
            environment["PAYMENT_PLATFORM_OIDC_CLIENT_SECRET"],
            fixture_private_value("platform-oidc"),
        )
        self.assertEqual(
            environment["PAYMENT_BOOTSTRAP_PASSWORD"],
            fixture_private_value("platform-login"),
        )
        merchant_keys = {
            environment["MCH_SEARCH_HMAC_KEYS"],
            environment["MCH_IDEMPOTENCY_HMAC_KEYS"],
            environment["MCH_REGISTRATION_AEAD_KEYS"],
            environment["MCH_LEGAL_ID_AEAD_KEYS"],
            environment["MCH_DOCUMENT_AEAD_KEYS"],
        }
        self.assertEqual(len(merchant_keys), 5)
        self.assertTrue(
            environment["MCH_LEGAL_ID_AEAD_KEYS"].startswith("mch-legal-id-aead-v1=")
        )
        self.assertTrue(
            environment["MCH_DOCUMENT_AEAD_KEYS"].startswith("mch-document-aead-v1=")
        )
        for configured in merchant_keys:
            key_id, encoded = configured.split("=", 1)
            self.assertTrue(key_id.startswith("mch-"))
            self.assertEqual(len(base64.b64decode(encoded, validate=True)), 32)
            self.assertNotIn(fixture_private_value("postgres"), configured)
        self.assertEqual(
            environment,
            self.runtime.backend_environment(private),
            "local merchant keys must remain stable for an existing private runtime",
        )
        self.assertNotIn("KC_BOOTSTRAP_ADMIN_PASSWORD", environment)
        self.assertNotIn("PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD", environment)

    def test_totp_matches_rfc6238_sha256_vector(self):
        secret = fixture_totp_secret()

        self.assertEqual(self.runtime._totp_at(secret, timestamp=59, digits=8), "46119246")

    def test_login_records_expose_only_local_browser_accounts(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_PLATFORM_LOCAL_ADMIN_TOTP_SECRET": fixture_totp_secret(),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_TOTP_SECRET": fixture_totp_secret(),
            "PAYMENT_IAM002_POSTGRES_PASSWORD": fixture_private_value("database"),
        }
        records = self.runtime.local_login_records(
            private,
            {
                "password": fixture_private_value("merchant-login"),
                "totpSecret": fixture_totp_secret(),
            },
            timestamp=59,
        )

        self.assertEqual([record["domain"] for record in records], ["PLATFORM", "MERCHANT", "AGENT"])
        self.assertTrue(all(record["available"] for record in records))
        self.assertEqual(records[1]["username"], self.runtime.MERCHANT_VERIFICATION_USERNAME)
        self.assertEqual(records[1]["password"], fixture_private_value("merchant-login"))
        self.assertNotIn(fixture_private_value("database"), json.dumps(records))

    def test_login_records_explain_when_merchant_fixture_is_not_ready(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_PLATFORM_LOCAL_ADMIN_TOTP_SECRET": fixture_repeated_value("A", 32),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_TOTP_SECRET": fixture_repeated_value("B", 32),
        }

        records = self.runtime.local_login_records(private, None, timestamp=59)

        self.assertFalse(records[1]["available"])
        self.assertNotIn("password", records[1])
        self.assertNotIn("totp", records[1])

    def test_local_password_login_records_include_independent_platform_reviewer(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_private_value("merchant-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
        }

        records = self.runtime.local_password_login_records(private)

        self.assertEqual(
            [record["username"] for record in records],
            [
                "admin@platform.localhost",
                "reviewer@platform.localhost",
                "admin@merchant.localhost",
                "admin@agent.localhost",
            ],
        )
        self.assertEqual(records[1]["password"], fixture_private_value("platform-login"))

    def test_mailpit_release_is_versioned_and_digest_pinned(self):
        self.assertEqual(self.runtime.MAILPIT_VERSION, "1.30.0")
        self.assertRegex(self.runtime.MAILPIT_ARCHIVES["arm64"][1], r"^[0-9a-f]{64}$")

    def test_frontend_processes_have_independent_application_roots(self):
        self.assertEqual(
            {application["directory"] for application in self.runtime.FRONTENDS},
            {"platform-admin", "merchant-admin", "agent-admin"},
        )

    def test_all_composition_roots_import_identity_governance_queries(self):
        sources = (
            REPOSITORY_ROOT
            / "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/IdentityConfiguration.java",
            REPOSITORY_ROOT
            / "backend/applications/merchant-admin-api/src/main/java/com/niv/payment/merchantadminapi/MerchantAdminApiApplication.java",
            REPOSITORY_ROOT
            / "backend/applications/agent-admin-api/src/main/java/com/niv/payment/agentadminapi/AgentAdminApiApplication.java",
        )

        for source in sources:
            self.assertIn(
                "IdentityGovernanceQueryConfiguration.class",
                source.read_text(encoding="utf-8"),
            )

    def test_local_frontend_environment_prefills_each_domain_credential(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_private_value("merchant-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
        }

        for application in self.runtime.FRONTENDS:
            environment = self.runtime.frontend_environment(
                {"PATH": "/opt/node/bin"}, application, private, "local"
            )
            domain = application["account_domain"]
            self.assertEqual(environment["VITE_AUTH_MODE"], "local")
            self.assertEqual(
                environment["VITE_LOCAL_ADMIN_USERNAME"],
                application["local_username"],
            )
            self.assertEqual(
                environment["VITE_LOCAL_ADMIN_PASSWORD"],
                private[f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD"],
            )

    def test_oidc_frontend_environment_never_receives_local_passwords(self):
        environment = self.runtime.frontend_environment(
            {"PATH": "/opt/node/bin"},
            self.runtime.FRONTENDS[0],
            {"PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("hidden")},
            "oidc",
        )

        self.assertEqual(environment["VITE_AUTH_MODE"], "oidc")
        self.assertNotIn("VITE_LOCAL_ADMIN_USERNAME", environment)
        self.assertNotIn("VITE_LOCAL_ADMIN_PASSWORD", environment)
        self.assertNotIn(fixture_private_value("hidden"), json.dumps(environment))

    def test_authentication_mode_selects_an_explicit_backend_profile(self):
        self.assertEqual(self.runtime.backend_profile("local"), "iam002-local")
        self.assertEqual(self.runtime.backend_profile("oidc"), "oidc-local")
        with self.assertRaisesRegex(RuntimeError, "authentication mode"):
            self.runtime.backend_profile("mixed")

    def test_runtime_authentication_mode_must_be_consistent(self):
        with mock.patch.object(
            self.runtime,
            "_read_records",
            return_value=[
                {"authenticationMode": "local"},
                {"authenticationMode": "local"},
            ],
        ):
            self.assertEqual(self.runtime.current_authentication_mode(), "local")

        with mock.patch.object(
            self.runtime,
            "_read_records",
            return_value=[
                {"authenticationMode": "local"},
                {"authenticationMode": "oidc"},
            ],
        ):
            with self.assertRaisesRegex(RuntimeError, "mixed authentication modes"):
                self.runtime.current_authentication_mode()

    def test_local_password_sync_is_exact_and_rejects_unsafe_values(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform'apostrophe"),
            "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_private_value("merchant-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
        }
        copy_data = self.runtime._local_password_copy_data(private)
        statement = self.runtime.local_password_login_statement(
            enabled=True,
            password_copy_data=copy_data,
        )

        self.assertIn("CREATE EXTENSION IF NOT EXISTS pgcrypto", statement)
        self.assertIn("\\copy iam002_local_passwords", statement)
        self.assertIn("FROM STDIN", statement)
        for password in private.values():
            self.assertNotIn(password, statement)
        self.assertIn("SET username = expected.username", statement)
        self.assertIn("idp_issuer = expected.local_issuer", statement)
        self.assertIn("idp_subject = expected.username", statement)
        self.assertIn("idp_provisioning_status = 'LOCAL_ONLY'", statement)
        self.assertIn("'local:merchant'", statement)
        self.assertIn("changed <> 3", statement)
        self.assertIn("identity_version = identity_version + 1", statement)
        self.assertIn("session_version = session_version + 1", statement)
        with self.assertRaisesRegex(RuntimeError, "password COPY data"):
            self.runtime.local_password_login_statement(
                enabled=True,
                password_copy_data="100\tPLATFORM\tadmin@platform.localhost\tunsafe\\.\n",
            )

        oidc_statement = self.runtime.local_password_login_statement(
            enabled=False,
        )
        self.assertIn("idp_issuer = expected.oidc_issuer", oidc_statement)
        self.assertIn("idp_subject = expected.oidc_subject", oidc_statement)
        self.assertIn("idp_provisioning_status = 'PROVISIONED'", oidc_statement)
        self.assertIn(
            "'http://127.0.0.1:18080/realms/MERCHANT'", oidc_statement
        )

    def test_local_password_copy_uses_encoded_stdin_outside_the_do_query(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform'apostrophe"),
            "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_private_value("merchant-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
        }
        captured_sql = []

        def capture(statement, *, capture):
            captured_sql.append(statement)
            copy_section, do_query = statement.split("DO $iam002_local$", 1)
            encoded_rows = [
                line for line in copy_section.splitlines()
                if line.startswith(("100\t", "200\t", "300\t"))
            ]
            self.assertEqual(len(encoded_rows), 3)
            self.assertEqual(
                base64.b64decode(encoded_rows[0].split("\t", 3)[3]).decode("utf-8"),
                fixture_private_value("platform'apostrophe"),
            )
            for password in private.values():
                self.assertNotIn(password, statement)
            for encoded_row in encoded_rows:
                self.assertNotIn(encoded_row.rsplit("\t", 1)[-1], do_query)
            return mock.Mock()

        with mock.patch.object(self.runtime, "_psql", side_effect=capture):
            self.runtime._synchronize_local_password_login(private, "local")

        self.assertEqual(len(captured_sql), 1)

    def test_local_password_sync_failure_does_not_expose_passwords(self):
        private = {
            "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD": fixture_private_value("platform-login"),
            "PAYMENT_MERCHANT_LOCAL_ADMIN_PASSWORD": fixture_private_value("merchant-login"),
            "PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD": fixture_private_value("agent-login"),
        }
        captured_statement = None

        def fail(statement, *, capture):
            nonlocal captured_statement
            captured_statement = statement
            raise self.runtime.subprocess.CalledProcessError(1, ["psql"])

        with mock.patch.object(self.runtime, "_psql", side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, "credential synchronization failed") as error:
                self.runtime._synchronize_local_password_login(private, "local")
        self.assertIsNotNone(captured_statement)
        for password in private.values():
            self.assertNotIn(password, captured_statement)
            self.assertNotIn(password, str(error.exception))

    def test_local_delegated_governance_convergence_is_scoped_and_fail_closed(self):
        statement = self.runtime.DELEGATED_GOVERNANCE_CONVERGENCE_PATH.read_text(
            encoding="utf-8"
        )

        self.assertIn("Local-only convergence for IAM-003", statement)
        self.assertIn("(2::bigint,2200::bigint)", statement)
        self.assertIn("(3::bigint,3200::bigint)", statement)
        self.assertIn("'user:create'", statement)
        self.assertIn("'department:view'", statement)
        self.assertIn("'menu:view'", statement)
        self.assertIn("'role:grant-update'", statement)
        self.assertIn("RAISE EXCEPTION", statement)
        self.assertNotIn("DELETE FROM", statement.upper())

    def test_local_dictionary_sample_is_one_time_and_uses_the_product_colors(self):
        statement = self.runtime.DICTIONARY_SAMPLE_PATH.read_text(encoding="utf-8")

        self.assertIn(
            "SELECT 1 FROM sys_dictionary_type WHERE dict_type = 'BELONG_SYSTEM'",
            statement,
        )
        self.assertIn("'BELONG_SYSTEM'", statement)
        self.assertIn("('运维','1','processing',1)", statement)
        self.assertIn("('商户','2','success',2)", statement)
        self.assertIn("('代理','3','purple',3)", statement)
        self.assertIn("revision=revision+1", statement)
        self.assertNotIn("DELETE FROM", statement.upper())

    def test_frontend_tooling_rebuilds_the_shared_vite_config(self):
        with mock.patch.object(self.runtime, "_run") as run:
            self.runtime._prepare_frontend_tooling("/opt/node/bin/pnpm", {"PATH": "/opt/node/bin"})

        run.assert_called_once_with(
            [
                "/opt/node/bin/pnpm",
                "--filter",
                "@vben/vite-config",
                "run",
                "stub",
            ],
            cwd=self.runtime.FRONTEND_ROOT,
            environment={"PATH": "/opt/node/bin"},
        )

    def test_local_test_commands_cover_artifacts_and_browser_helpers(self):
        commands = self.runtime.local_test_commands(Path("/opt/node"))

        self.assertEqual(
            commands,
            [
                [
                    self.runtime.sys.executable,
                    "-m",
                    "unittest",
                    "scripts.tests.test_iam002_keycloak_realms",
                    "scripts.tests.test_iam002_local_runtime",
                ],
                [
                    "/opt/node/bin/node",
                    "--test",
                    str(REPOSITORY_ROOT / "scripts/tests/verify_iam002_local.test.cjs"),
                ],
            ],
        )

    def test_browser_verification_requires_a_healthy_runtime(self):
        with mock.patch.object(self.runtime, "status", return_value=False), \
             mock.patch.object(self.runtime, "_run") as run:
            with self.assertRaisesRegex(RuntimeError, "is not ready"):
                self.runtime.verify()
        run.assert_not_called()

    def test_browser_verification_uses_the_pinned_node_runtime(self):
        with mock.patch.object(self.runtime, "status", return_value=True), \
             mock.patch.object(self.runtime, "current_authentication_mode", return_value="local"), \
             mock.patch.object(self.runtime, "_find_node_home", return_value=Path("/opt/node")), \
             mock.patch.object(self.runtime, "_run") as run:
            self.runtime.verify()

        run.assert_called_once_with(
            [
                "/opt/node/bin/node",
                str(REPOSITORY_ROOT / "scripts/dev/verify_iam002_local_password.cjs"),
            ]
        )

    def test_oidc_browser_verification_remains_explicitly_available(self):
        with mock.patch.object(self.runtime, "status", return_value=True), \
             mock.patch.object(self.runtime, "current_authentication_mode", return_value="oidc"), \
             mock.patch.object(self.runtime, "_find_node_home", return_value=Path("/opt/node")), \
             mock.patch.object(self.runtime, "_run") as run:
            self.runtime.verify()

        run.assert_called_once_with(
            [
                "/opt/node/bin/node",
                str(REPOSITORY_ROOT / "scripts/dev/verify_iam002_local.cjs"),
            ]
        )

    def test_postgres_password_rotation_escapes_sql_literals(self):
        statement = self.runtime.postgres_password_statement("private'password")
        self.assertEqual(
            statement,
            "SET statement_timeout='10s'; SET lock_timeout='3s'; "
            "ALTER ROLE payment_dev WITH PASSWORD 'private''password';\n",
        )

        with self.assertRaisesRegex(RuntimeError, "invalid PostgreSQL password"):
            self.runtime.postgres_password_statement("contains\nnewline")

    def test_local_merchant_v32_upgrade_is_exact_idempotent_and_preserves_menus(self):
        statement = self.runtime.local_merchant_v32_upgrade_statement()

        self.assertIn("pg_advisory_xact_lock", statement)
        self.assertIn("version = '32' AND success", statement)
        self.assertIn("tenant.tenant_code = 'local-merchant'", statement)
        self.assertIn("role_row.role_code = 'merchant-admin'", statement)
        self.assertIn("menu.parent_id IS NULL", statement)
        self.assertIn("menu.parent_id = 6200", statement)
        self.assertIn("menu.redirect_path = '/dashboard/workspace'", statement)
        self.assertIn("menu.redirect_path IS NULL", statement)
        self.assertIn("menu.sort_order = 10", statement)
        self.assertIn("menu.sort_order = 20", statement)
        self.assertIn("menu.auth_code IS NULL", statement)
        self.assertIn("menu.component_path IS NULL", statement)
        self.assertIn("linked_count NOT IN (0, 2)", statement)
        self.assertIn("menu.route_name LIKE 'Merchant%'", statement)
        self.assertIn("menu_id IN (6200, 6201)", statement)
        self.assertIn("DELETE FROM iam_role_menu", statement)
        self.assertNotIn("DELETE FROM iam_menu", statement)
        self.assertNotIn("DELETE FROM iam_role\n", statement)
        self.assertNotIn("DELETE FROM iam_tenant", statement)
        self.assertTrue(statement.rstrip().endswith("COMMIT;"))

    def test_local_merchant_v32_upgrade_wraps_database_errors(self):
        failure = __import__("subprocess").CalledProcessError(1, ["psql"])
        with mock.patch.object(self.runtime, "_psql", side_effect=failure):
            with self.assertRaisesRegex(
                RuntimeError, "local Merchant V32 upgrade preparation failed"
            ):
                self.runtime._prepare_local_merchant_v32_upgrade()


if __name__ == "__main__":
    unittest.main()
