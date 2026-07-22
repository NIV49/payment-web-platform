from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from check_sensitive_artifacts import scan_git_diff, scan_repository  # noqa: E402


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
                "api_" + "key: \"${PAYMENT_API_KEY}\"\naccessToken: 'cookie-session'\n",
            )

            self.assertEqual([], scan_repository(repository, ("docs",)))

    def test_allows_the_approved_loopback_fixture_only_at_its_bound_location(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            lines = (
                REPOSITORY.joinpath("README.md")
                .read_text(encoding="utf-8")
                .splitlines()[:71]
            )
            readme = "\n".join(lines) + "\n"
            self.write_artifact(repository, readme, "README.md")
            self.write_artifact(repository, "Pass" + "word: payment_dev\n")

            self.assertEqual([], scan_repository(repository, ("README.md",)))
            copied_errors = scan_repository(repository, ("docs",))
            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in copied_errors),
                copied_errors,
            )

            self.write_artifact(
                repository,
                "Local fixture: `ad" + "min / Admin@123456`.\n",
            )
            copied_pair_errors = scan_repository(repository, ("docs",))
            self.assertTrue(
                any("INLINE_CREDENTIAL_PAIR" in error for error in copied_pair_errors),
                copied_pair_errors,
            )

    def test_rejects_an_allowlisted_password_when_its_host_is_not_loopback(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            lines = (
                REPOSITORY.joinpath("README.md")
                .read_text(encoding="utf-8")
                .splitlines()[:71]
            )
            lines[66] = "Host: database.production.example.com"
            self.write_artifact(repository, "\n".join(lines) + "\n", "README.md")

            errors = scan_repository(repository, ("README.md",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_rejects_a_production_host_despite_an_unrelated_loopback_comment(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            lines = (
                REPOSITORY.joinpath("README.md")
                .read_text(encoding="utf-8")
                .splitlines()[:71]
            )
            lines[66] = "Host: database.production.example.com"
            lines[70] = f"{lines[70]} # unrelated localhost note"
            self.write_artifact(repository, "\n".join(lines) + "\n", "README.md")

            errors = scan_repository(repository, ("README.md",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_rejects_a_changed_production_endpoint_on_an_allowlisted_line(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            approved_line = (
                REPOSITORY.joinpath("README.md")
                .read_text(encoding="utf-8")
                .splitlines()[59]
            )
            changed_line = approved_line.replace(
                "http://127.0.0.1:8080/api",
                "https://api.production.example.com",
            )
            lines = [""] * 60
            lines[59] = changed_line
            self.write_artifact(repository, "\n".join(lines) + "\n", "README.md")

            errors = scan_repository(repository, ("README.md",))

            self.assertTrue(
                any("INLINE_CREDENTIAL_PAIR" in error for error in errors),
                errors,
            )

    def test_detects_a_secret_without_echoing_its_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            sentinel_value = "do-not-print-this-secret"
            self.write_artifact(
                repository, "client_" + f'secret: "{sentinel_value}"\n'
            )

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )
            self.assertNotIn(sentinel_value, "\n".join(errors))

    def test_detects_unquoted_secrets_without_echoing_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = ("supersecret-password", "abc123-production-key")
            self.write_artifact(
                repository,
                "pass" + f"word: {values[0]}\napi_" + f"key={values[1]}\n",
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("GENERIC_SECRET_ASSIGNMENT"), errors)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_detects_json_namespaced_backtick_aws_and_safe_prefix_secrets(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = (
                "lowercase-only",
                "weakpassword",
                "backtick-secret",
                "abcdefghijklmnopqrstuvwxyz0123456789ABCD",
                "null#secret",
            )
            self.write_artifact(
                repository,
                "\n".join(
                    (
                        '{"pass' + f'word": "{values[0]}"}}',
                        "DATABASE_PASS" + f"WORD={values[1]}",
                        "service.pass" + f"word=`{values[2]}`",
                        "AWS_SECRET_ACCESS_" + f"KEY={values[3]}",
                        "pass" + f"word={values[4]}",
                    )
                ),
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(5, rendered.count("GENERIC_SECRET_ASSIGNMENT"), errors)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_detects_a_weak_inline_account_password(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = ("lowerpassword", "123456")
            self.write_artifact(
                repository,
                "ad" + f"min / {values[0]}\nus" + f"er / {values[1]}\n",
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("INLINE_CREDENTIAL_PAIR"), errors)
            for value in values:
                self.assertNotIn(value, rendered)

    def test_rejects_utf16le_without_a_bom_instead_of_silently_skipping_it(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            artifact = repository / "docs/evidence.txt"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(("pass" + "word=utf16-secret\n").encode("utf-16le"))

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(any("NON_UTF8" in error for error in errors), errors)

    def test_detects_a_private_key_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_artifact(
                repository,
                "-----BEGIN " + "PRIVATE KEY-----\n",
            )

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(any("PRIVATE_KEY" in error for error in errors), errors)

    def test_detects_personal_data_without_echoing_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            values = (
                "alice@merchant-" + "payments.com",
                "13812" + "345678",
                "4111 1111 " + "1111 1111",
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
                        "用户/" + f"密码 `merchant_" + f"user / {values[0]}`。",
                        "默认开发密" + f"码为 `{values[1]}`。",
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
                "pass" + "word: explicit-owned-secret\n",
                "backend/fixtures/legacy.txt",
            )

            errors = scan_repository(repository, ("backend/fixtures/legacy.txt",))

            self.assertTrue(
                any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_default_scan_discovers_git_tracked_test_fixture_resources(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            resources = (
                "scripts/tests/fixtures/legacy-payload.txt",
                "backend/src/test/resources/request.txt",
                "review/evidence/trace.log",
            )
            for index, relative_path in enumerate(resources):
                self.write_artifact(
                    repository,
                    "pass" + f"word=tracked-fixture-secret-{index}\n",
                    relative_path,
                )
            subprocess.run(
                ("git", "init", "--quiet"),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ("git", "add", *resources),
                cwd=repository,
                check=True,
            )

            errors = scan_repository(repository)

            self.assertEqual(
                3,
                sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_default_scan_discovers_fixture_named_files_under_test_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            resources = (
                "tests/payment.fixture.json",
                "frontend/portal/tests/data/payment.json",
            )
            for index, relative_path in enumerate(resources):
                self.write_artifact(
                    repository,
                    '{"pass' + f'word":"test-asset-secret-{index}"}}\n',
                    relative_path,
                )
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(("git", "add", *resources), cwd=repository, check=True)

            errors = scan_repository(repository)

            self.assertEqual(
                2,
                sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                errors,
            )

    def test_default_scan_fails_closed_for_a_tracked_binary_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            artifact = repository / "scripts/tests/fixtures/capture.bin"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"\x00pass" + b"word=binary-secret\xff")
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(
                ("git", "add", "scripts/tests/fixtures/capture.bin"),
                cwd=repository,
                check=True,
            )

            errors = scan_repository(repository)

            self.assertTrue(any("NON_UTF8" in error for error in errors), errors)

    def test_default_scan_does_not_treat_test_source_as_fixture_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_artifact(
                repository,
                'EXAMPLE = "pass' + 'word=synthetic-test-value"\n',
                "scripts/tests/test_example.py",
            )
            subprocess.run(
                ("git", "init", "--quiet"),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ("git", "add", "scripts/tests/test_example.py"),
                cwd=repository,
                check=True,
            )

            errors = scan_repository(repository)

            self.assertFalse(
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
            sentinel = "SECRET_" + "SENTINEL=do-not-print"
            external = Path(external_directory) / "secret.md"
            external.write_text(sentinel, encoding="utf-8")
            (docs / "linked.md").symlink_to(external)

            errors = scan_repository(repository, ("docs",))

            self.assertTrue(any("SYMLINK" in error for error in errors), errors)
            self.assertNotIn("SECRET_SENTINEL", "\n".join(errors))

    def test_immutable_diff_scans_every_changed_text_path_without_name_heuristics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(("git", "config", "user.name", "CI"), cwd=repository, check=True)
            subprocess.run(("git", "config", "user.email", "ci@example.invalid"), cwd=repository, check=True)
            self.write_artifact(repository, "safe\n", "README.md")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(("git", "commit", "--quiet", "-m", "base"), cwd=repository, check=True)
            base = subprocess.run(("git", "rev-parse", "HEAD"), cwd=repository, check=True, capture_output=True, text=True).stdout.strip()
            secret_assignment = "DATABASE_PASS" + "WORD=changed-secret\n"
            self.write_artifact(repository, secret_assignment, "backend/src/main/resources/application-prod.conf")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(("git", "commit", "--quiet", "-m", "changed config"), cwd=repository, check=True)
            commit = subprocess.run(("git", "rev-parse", "HEAD"), cwd=repository, check=True, capture_output=True, text=True).stdout.strip()

            errors = scan_git_diff(repository, base, commit)

            self.assertTrue(any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors), errors)
            self.assertNotIn("changed-secret", "\n".join(errors))

    def test_immutable_diff_scans_full_blob_when_gitattributes_disables_diff(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(("git", "config", "user.name", "CI"), cwd=repository, check=True)
            subprocess.run(("git", "config", "user.email", "ci@example.invalid"), cwd=repository, check=True)
            self.write_artifact(repository, "safe=true\n", "config/application-prod.conf")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(("git", "commit", "--quiet", "-m", "base"), cwd=repository, check=True)
            base = subprocess.run(("git", "rev-parse", "HEAD"), cwd=repository, check=True, capture_output=True, text=True).stdout.strip()

            self.write_artifact(repository, "*.conf -diff\n", ".gitattributes")
            hidden_assignment = "DATABASE_PASS" + "WORD=hidden-by-gitattributes\n"
            self.write_artifact(repository, hidden_assignment, "config/application-prod.conf")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(("git", "commit", "--quiet", "-m", "disable config diff"), cwd=repository, check=True)
            commit = subprocess.run(("git", "rev-parse", "HEAD"), cwd=repository, check=True, capture_output=True, text=True).stdout.strip()

            errors = scan_git_diff(repository, base, commit)

            self.assertTrue(any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors), errors)
            self.assertNotIn("hidden-by-gitattributes", "\n".join(errors))

    def test_yaml_multiline_secret_and_uncommon_account_weak_password_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            yaml_content = "pass" + "word:\n  weak-multiline-value\n"
            account_content = "settlement-bot / " + "123456\nmerchant.ops@example.invalid / qwerty123\n"
            self.write_artifact(repository, yaml_content, "docs/config.yml")
            self.write_artifact(repository, account_content, "docs/accounts.md")

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertIn("YAML_SECRET_SCALAR", rendered)
            self.assertEqual(2, rendered.count("INLINE_CREDENTIAL_PAIR"), errors)
            self.assertNotIn("weak-multiline-value", rendered)
            self.assertNotIn("qwerty123", rendered)

    def test_yaml_bare_secret_and_lowercase_weak_password_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            yaml_content = (
                "sec" + "ret:\n  weak-multiline-value\n"
                "auth_to" + "ken:\n  weak-token-value\n"
            )
            account_content = "settlement-bot / " + "weakpassword\n"
            self.write_artifact(repository, yaml_content, "docs/config.yml")
            self.write_artifact(repository, account_content, "docs/accounts.md")

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("YAML_SECRET_SCALAR"), errors)
            self.assertEqual(1, rendered.count("INLINE_CREDENTIAL_PAIR"), errors)
            self.assertNotIn("weak-multiline-value", rendered)
            self.assertNotIn("weakpassword", rendered)


if __name__ == "__main__":
    unittest.main()
