from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from check_sensitive_artifacts import (  # noqa: E402
    scan_git_diff,
    scan_repository,
    scan_text_content,
)


class SensitiveArtifactValidationTest(unittest.TestCase):
    def initialize_git_repository(self, repository: Path) -> None:
        subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
        subprocess.run(
            ("git", "config", "user.name", "CI"), cwd=repository, check=True
        )
        subprocess.run(
            ("git", "config", "user.email", "ci@example.invalid"),
            cwd=repository,
            check=True,
        )

    def commit_all(self, repository: Path, message: str) -> str:
        subprocess.run(("git", "add", "-A"), cwd=repository, check=True)
        subprocess.run(
            ("git", "commit", "--quiet", "-m", message),
            cwd=repository,
            check=True,
        )
        return subprocess.run(
            ("git", "rev-parse", "HEAD"),
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

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
                "Local fixture: `ad" + "min " + "/ Admin@123456`.\n",
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
                repository, "client_sec" + f'ret: "{sentinel_value}"\n'
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

    def test_immutable_diff_scans_secrets_removed_before_the_target_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            self.write_artifact(repository, "safe=true\n", "config/runtime.conf")
            base = self.commit_all(repository, "base")

            transient_value = "transient-history-value"
            self.write_artifact(
                repository,
                "DATABASE_PASS" + f"WORD={transient_value}\n",
                "docs/transient.md",
            )
            self.commit_all(repository, "add transient credential")
            repository.joinpath("docs/transient.md").unlink()
            self.commit_all(repository, "delete transient credential")

            restored_value = "restored-history-value"
            self.write_artifact(
                repository,
                "CLIENT_SEC" + f"RET={restored_value}\n",
                "config/runtime.conf",
            )
            self.commit_all(repository, "modify tracked config with credential")
            self.write_artifact(repository, "safe=true\n", "config/runtime.conf")
            target = self.commit_all(repository, "restore baseline content")

            errors = scan_git_diff(repository, base, target)
            rendered = "\n".join(errors)

            self.assertGreaterEqual(rendered.count("GENERIC_SECRET_ASSIGNMENT"), 2, errors)
            self.assertNotIn(transient_value, rendered)
            self.assertNotIn(restored_value, rendered)

    def test_immutable_diff_scans_hidden_commit_on_a_merged_branch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            base = self.commit_all(repository, "base")

            subprocess.run(
                ("git", "checkout", "--quiet", "-b", "credential-side"),
                cwd=repository,
                check=True,
            )
            hidden_value = "side-branch-history-value"
            self.write_artifact(
                repository,
                "AUTH_TO" + f"KEN={hidden_value}\n",
                "docs/side-only.md",
            )
            self.commit_all(repository, "add credential on side branch")

            subprocess.run(
                ("git", "checkout", "--quiet", "-b", "mainline", base),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                (
                    "git",
                    "merge",
                    "--quiet",
                    "--no-ff",
                    "-s",
                    "ours",
                    "credential-side",
                    "-m",
                    "merge side history without its tree",
                ),
                cwd=repository,
                check=True,
            )
            target = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            errors = scan_git_diff(repository, base, target)
            rendered = "\n".join(errors)

            self.assertIn("GENERIC_SECRET_ASSIGNMENT", rendered)
            self.assertNotIn(hidden_value, rendered)

    def test_immutable_diff_rejects_shallow_history_that_hides_a_parent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            shallow = root / "shallow"
            source.mkdir()
            self.initialize_git_repository(source)
            self.write_artifact(source, "safe\n", "README.md")
            root_commit = self.commit_all(source, "root")

            subprocess.run(
                ("git", "checkout", "--quiet", "-b", "mainline"),
                cwd=source,
                check=True,
            )
            self.write_artifact(source, "mainline\n", "main.txt")
            base = self.commit_all(source, "trusted base")

            subprocess.run(
                ("git", "checkout", "--quiet", "-b", "secret-side", root_commit),
                cwd=source,
                check=True,
            )
            hidden_value = "shallow-hidden-value"
            self.write_artifact(
                source,
                "CLIENT_SEC" + f"RET={hidden_value}\n",
                "docs/hidden.md",
            )
            self.commit_all(source, "add hidden credential")
            source.joinpath("docs/hidden.md").unlink()
            self.commit_all(source, "delete hidden credential")

            subprocess.run(
                ("git", "checkout", "--quiet", "mainline"),
                cwd=source,
                check=True,
            )
            subprocess.run(
                (
                    "git",
                    "merge",
                    "--quiet",
                    "--no-ff",
                    "-s",
                    "ours",
                    "secret-side",
                    "-m",
                    "merge hidden history",
                ),
                cwd=source,
                check=True,
            )
            target = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=source,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            subprocess.run(
                (
                    "git",
                    "clone",
                    "--quiet",
                    "--depth",
                    "2",
                    source.resolve().as_uri(),
                    str(shallow),
                ),
                check=True,
            )
            self.assertEqual(
                "true",
                subprocess.run(
                    ("git", "rev-parse", "--is-shallow-repository"),
                    cwd=shallow,
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip(),
            )

            errors = scan_git_diff(shallow, base, target)
            rendered = "\n".join(errors)

            self.assertIn("IMMUTABLE_DIFF", rendered)
            self.assertNotIn(hidden_value, rendered)

    def test_immutable_diff_rejects_graft_parent_rewrites(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            base = self.commit_all(repository, "base")
            hidden_value = "graft-hidden-value"
            self.write_artifact(
                repository,
                "AUTH_TO" + f"KEN={hidden_value}\n",
                "docs/transient.md",
            )
            self.commit_all(repository, "add credential")
            repository.joinpath("docs/transient.md").unlink()
            target = self.commit_all(repository, "delete credential")
            grafts_path = subprocess.run(
                ("git", "rev-parse", "--git-path", "info/grafts"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            grafts = Path(grafts_path)
            if not grafts.is_absolute():
                grafts = repository / grafts
            grafts.parent.mkdir(parents=True, exist_ok=True)
            grafts.write_text(f"{target} {base}\n", encoding="utf-8")

            errors = scan_git_diff(repository, base, target)
            rendered = "\n".join(errors)

            self.assertIn("IMMUTABLE_DIFF", rendered)
            self.assertNotIn(hidden_value, rendered)

    def test_immutable_diff_rejects_alternate_graft_environment_with_newline_path(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            base = self.commit_all(repository, "base")
            self.write_artifact(repository, "still-safe\n", "README.md")
            target = self.commit_all(repository, "target")
            alternate_grafts = repository / "alternate-grafts\n"
            alternate_grafts.write_text(f"{target} {base}\n", encoding="ascii")

            with mock.patch.dict(
                "os.environ",
                {"GIT_GRAFT_FILE": str(alternate_grafts)},
                clear=False,
            ):
                errors = scan_git_diff(repository, base, target)

            self.assertTrue(
                any("IMMUTABLE_DIFF" in error for error in errors), errors
            )

    def test_immutable_diff_rejects_symlink_and_gitlink_type_changes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "docs/policy.md")
            self.write_artifact(repository, "safe\n", "modules/provider")
            base = self.commit_all(repository, "base regular blobs")

            repository.joinpath("docs/policy.md").unlink()
            repository.joinpath("docs/policy.md").symlink_to("outside-policy.md")
            self.commit_all(repository, "replace policy with symlink")
            subprocess.run(
                (
                    "git",
                    "update-index",
                    "--add",
                    "--cacheinfo",
                    f"160000,{base},modules/provider",
                ),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ("git", "commit", "--quiet", "-m", "replace provider with gitlink"),
                cwd=repository,
                check=True,
            )
            target = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            errors = scan_git_diff(repository, base, target)
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("UNSAFE_GIT_MODE"), errors)
            self.assertNotIn("outside-policy.md", rendered)

    def test_immutable_diff_rejects_gitlink_oid_changes_ignored_by_configuration(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "anchor\n", "README.md")
            anchor = self.commit_all(repository, "anchor commit")
            self.write_artifact(
                repository,
                (
                    '[submodule "provider"]\n'
                    "  path = modules/provider\n"
                    "  url = https://example.invalid/provider.git\n"
                    "  ignore = all\n"
                ),
                ".gitmodules",
            )
            subprocess.run(
                ("git", "add", ".gitmodules"),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                (
                    "git",
                    "update-index",
                    "--add",
                    "--cacheinfo",
                    f"160000,{anchor},modules/provider",
                ),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ("git", "commit", "--quiet", "-m", "base gitlink"),
                cwd=repository,
                check=True,
            )
            base = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            subprocess.run(
                ("git", "commit", "--quiet", "--allow-empty", "-m", "replacement"),
                cwd=repository,
                check=True,
            )
            replacement = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            subprocess.run(
                (
                    "git",
                    "update-index",
                    "--cacheinfo",
                    f"160000,{replacement},modules/provider",
                ),
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ("git", "commit", "--quiet", "-m", "change ignored gitlink oid"),
                cwd=repository,
                check=True,
            )
            target = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            errors = scan_git_diff(repository, base, target)

            self.assertTrue(any("UNSAFE_GIT_MODE" in error for error in errors), errors)

    def test_json_and_xml_structured_secrets_are_rejected_without_echoing_values(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            json_value = "json-structured-value"
            xml_value = "xml-structured-value"
            unicode_value = "unicode-structured-value"
            json_content = (
                "{\n"
                '  "client_sec' + 'ret":\n'
                f'    "{json_value}",\n'
                '  "pa\\u0073sword":\n'
                f'    "{unicode_value}"\n'
                "}\n"
            )
            xml_content = (
                "<config>\n"
                "  <client_sec" + "ret>\n"
                f"    {xml_value}\n"
                "  </client_sec" + "ret>\n"
                "  <entry key=\"pass&#119;ord\">\n"
                "    another-xml-value\n"
                "  </entry>\n"
                "</config>\n"
            )
            self.write_artifact(repository, json_content, "docs/config.json")
            self.write_artifact(repository, xml_content, "docs/config.xml")

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertEqual(2, rendered.count("JSON_SECRET_SCALAR"), errors)
            self.assertEqual(2, rendered.count("XML_SECRET_SCALAR"), errors)
            self.assertNotIn(json_value, rendered)
            self.assertNotIn(xml_value, rendered)
            self.assertNotIn(unicode_value, rendered)

    def test_json_and_xml_descriptor_objects_are_rejected_without_echoing_values(
        self,
    ) -> None:
        json_value = "json-descriptor-value"
        xml_value = "xml-descriptor-value"
        json_content = (
            '[{"name": "pass' + 'word", "value": "' + json_value + '"},'
            '{"key": "client_sec' + 'ret", "value": "another-value"}]'
        )
        xml_content = (
            "<config><property><name>pass"
            + "word</name><value>"
            + xml_value
            + "</value></property><property><key>client_sec"
            + "ret</key><value>another-value</value></property></config>"
        )

        errors = [
            *scan_text_content("docs/config.json", json_content),
            *scan_text_content("docs/config.xml", xml_content),
        ]
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("JSON_SECRET_SCALAR"), errors)
        self.assertEqual(2, rendered.count("XML_SECRET_SCALAR"), errors)
        self.assertNotIn(json_value, rendered)
        self.assertNotIn(xml_value, rendered)

    def test_xml_namespaced_sensitive_attributes_cannot_overwrite_each_other(
        self,
    ) -> None:
        secret_value = "namespace-collision-value"
        xml_content = (
            '<config xmlns:a="urn:unsafe" xmlns:b="urn:safe"\n'
            "  a:pass" + "word =\n"
            f'    "{secret_value}"\n'
            "  b:pass" + "word =\n"
            '    "${PASSWORD}"/>\n'
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertIn("XML_SECRET_SCALAR", rendered)
        self.assertNotIn(secret_value, rendered)

    def test_xml_safe_value_attribute_cannot_hide_sensitive_element_text(
        self,
    ) -> None:
        hidden_value = "descriptor-shadow-value"
        xml_content = (
            '<entry key="'
            + "pass"
            + 'word" value="${PASSWORD}">'
            + hidden_value
            + "</entry>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertIn("XML_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_malformed_json_xml_and_xml_doctype_fail_closed_without_echoing_content(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            malformed_value = "malformed-structured-value"
            self.write_artifact(
                repository,
                '{"safe": "' + malformed_value + '"',
                "docs/malformed.json",
            )
            self.write_artifact(
                repository,
                "<config><safe>" + malformed_value + "</config>",
                "docs/malformed.xml",
            )
            self.write_artifact(
                repository,
                '<!DOCTYPE config [<!ENTITY x "' + malformed_value + '">]><config>&x;</config>',
                "docs/doctype.xml",
            )

            errors = scan_repository(repository, ("docs",))
            rendered = "\n".join(errors)

            self.assertIn("INVALID_JSON", rendered)
            self.assertGreaterEqual(rendered.count("INVALID_XML"), 2, errors)
            self.assertNotIn(malformed_value, rendered)

    def test_structured_secret_placeholders_null_and_false_are_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            json_content = (
                '{"pass' + 'word": null, "client_sec' + 'ret": "${CLIENT_SECRET}"}\n'
            )
            xml_content = (
                "<config><pass" + "word>${PASSWORD}</pass" + "word>"
                '<entry key="client_secret" value="${CLIENT_SECRET}"/></config>\n'
            )
            self.write_artifact(repository, json_content, "docs/config.json")
            self.write_artifact(repository, xml_content, "docs/config.xml")

            self.assertEqual([], scan_repository(repository, ("docs",)))

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
