from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from check_sensitive_artifacts import scan_repository  # noqa: E402


class SensitiveArtifactValidationTest(unittest.TestCase):
    def write_artifact(
        self,
        repository: Path,
        content: str,
        relative_path: str = "docs/capability.md",
    ) -> Path:
        artifact = repository / relative_path
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(content, encoding="utf-8")
        return artifact

    def test_accepts_explicit_placeholders(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_artifact(
                repository,
                "api_key: \"${PAYMENT_API_KEY}\"\naccessToken: 'cookie-session'\n",
            )

            self.assertEqual([], scan_repository(repository, ("docs",)))

    def test_allows_the_approved_loopback_fixture_only_at_its_bound_location(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            lines = [""] * 71
            lines[59] = "Local fixture: `admin / Admin@123456`."
            lines[70] = "Password: payment_dev"
            readme = "\n".join(lines) + "\n"
            self.write_artifact(repository, readme, "README.md")
            self.write_artifact(repository, "Password: payment_dev\n")

            self.assertEqual([], scan_repository(repository, ("README.md",)))
            copied_errors = scan_repository(repository, ("docs",))
            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in copied_errors),
                copied_errors,
            )

            self.write_artifact(
                repository,
                "Local fixture: `admin / Admin@123456`.\n",
            )
            copied_pair_errors = scan_repository(repository, ("docs",))
            self.assertTrue(
                any("INLINE_CREDENTIAL_PAIR" in error for error in copied_pair_errors),
                copied_pair_errors,
            )

    def test_detects_a_secret_without_echoing_its_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            secret = "do-not-print-this-secret"
            self.write_artifact(repository, f'client_secret: "{secret}"\n')

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )
            self.assertNotIn(secret, "\n".join(errors))

    def test_detects_unquoted_secrets_without_echoing_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = ("supersecret-password", "abc123-production-key")
            self.write_artifact(
                repository,
                f"password: {values[0]}\napi_key={values[1]}\n",
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("GENERIC_SECRET_ASSIGNMENT"), errors)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_detects_a_private_key_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_artifact(
                repository,
                "-----BEGIN PRIVATE KEY-----\n",
            )

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(any("PRIVATE_KEY" in error for error in errors), errors)

    def test_detects_personal_data_without_echoing_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = (
                "alice@merchant-payments.com",
                "13812345678",
                "4111 1111 1111 1111",
            )
            self.write_artifact(
                repository,
                "\n".join(
                    (
                        f'owner_email: "{values[0]}"',
                        f'customer_phone: "{values[1]}"',
                        f'pan: "{values[2]}"',
                    )
                ),
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertIn("EMAIL_ADDRESS", rendered)
            self.assertIn("CN_MOBILE_NUMBER", rendered)
            self.assertIn("PAYMENT_CARD_NUMBER", rendered)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_detects_documented_credential_forms_without_echoing_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = ("merchant_password", "Prod@123456")
            self.write_artifact(
                repository,
                "\n".join(
                    (
                        f"用户/密码 `merchant_user / {values[0]}`。",
                        f"默认开发密码为 `{values[1]}`。",
                    )
                ),
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertIn("USER_PASSWORD_PAIR", rendered)
            self.assertIn("DOCUMENTED_PASSWORD", rendered)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_rejects_a_target_outside_the_repository(self) -> None:
        with (
            tempfile.TemporaryDirectory() as repository_directory,
            tempfile.TemporaryDirectory() as external_directory,
        ):
            repository = Path(repository_directory)
            external = Path(external_directory)
            (external / "evidence.md").write_text("# Evidence\n", encoding="utf-8")

            errors = scan_repository(repository, (str(external),))

            self.assertTrue(
                any("OUTSIDE_REPOSITORY" in error for error in errors), errors
            )

    def test_scans_an_explicit_artifact_outside_the_default_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_artifact(
                repository,
                "password: explicit-owned-secret\n",
                "backend/fixtures/legacy.txt",
            )

            errors = scan_repository(repository, ("backend/fixtures/legacy.txt",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_rejects_a_symlink_without_reading_its_target(self) -> None:
        with (
            tempfile.TemporaryDirectory() as repository_directory,
            tempfile.TemporaryDirectory() as external_directory,
        ):
            repository = Path(repository_directory)
            docs = repository / "docs"
            docs.mkdir()
            secret = "SECRET_SENTINEL=do-not-print"
            external = Path(external_directory) / "secret.md"
            external.write_text(secret, encoding="utf-8")
            (docs / "linked.md").symlink_to(external)

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(any("SYMLINK" in error for error in errors), errors)
            self.assertNotIn("SECRET_SENTINEL", "\n".join(errors))


if __name__ == "__main__":
    unittest.main()
