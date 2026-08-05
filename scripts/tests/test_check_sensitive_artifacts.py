from __future__ import annotations

import base64
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import check_sensitive_artifacts as sensitive_artifacts  # noqa: E402
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

    def test_only_current_lockfile_findings_have_exact_approvals(self) -> None:
        approved_paths = {
            path
            for path, _line_number, _rule_id, _digest in (
                sensitive_artifacts.APPROVED_FINDING_HASHES
            )
        }
        context_paths = {
            path
            for path, _line_number, _rule_id in (
                sensitive_artifacts.APPROVED_FINDING_CONTEXTS
            )
        }

        self.assertEqual({"frontend/admin/pnpm-lock.yaml"}, approved_paths)
        self.assertEqual({"frontend/admin/pnpm-lock.yaml"}, context_paths)
        self.assertEqual(3, len(sensitive_artifacts.APPROVED_FINDING_HASHES))
        self.assertEqual(3, len(sensitive_artifacts.APPROVED_FINDING_CONTEXTS))

    def test_empty_secret_assignments_are_safe_but_nonempty_values_are_rejected(
        self,
    ) -> None:
        password_key = "pass" + "word"
        secret_key = "client_" + "secret"
        token_key = "auth_" + "token"
        unsafe_value = "synthetic-" + "credential-value"
        safe_text = "\n".join(
            (
                f'{password_key} = ""',
                f"{secret_key}: ''",
                f"{token_key} := ``",
            )
        )
        safe_json = json.dumps({password_key: "", secret_key: ""})
        safe_yaml = f'{password_key}: ""\n{secret_key}: \'\'\n'
        unsafe_text = "\n".join(
            (
                f'{password_key}: "{unsafe_value}"',
                f"{secret_key} = {unsafe_value}",
                f"{token_key} := `{unsafe_value}`",
            )
        )

        self.assertEqual([], scan_text_content("docs/config.txt", safe_text))
        self.assertEqual([], scan_text_content("docs/config.json", safe_json))
        self.assertEqual([], scan_text_content("docs/config.yml", safe_yaml))
        text_errors = scan_text_content("docs/config.txt", unsafe_text)
        json_errors = scan_text_content(
            "docs/config.json", json.dumps({password_key: unsafe_value})
        )
        yaml_errors = scan_text_content(
            "docs/config.yml", f"{password_key}: {unsafe_value}\n"
        )

        self.assertEqual(
            3,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in text_errors),
            text_errors,
        )
        self.assertTrue(
            any("JSON_SECRET_SCALAR" in error for error in json_errors), json_errors
        )
        self.assertTrue(
            any("YAML_SECRET_SCALAR" in error for error in yaml_errors), yaml_errors
        )

    def test_whitespace_only_secret_values_are_not_empty(self) -> None:
        password_key = "pass" + "word"
        whitespace = " " * 3
        text_errors = scan_text_content(
            "docs/config.txt", f'{password_key}:"{whitespace}"\n'
        )
        json_errors = scan_text_content(
            "docs/config.json", json.dumps({password_key: whitespace})
        )
        yaml_errors = scan_text_content(
            "docs/config.yml", f'{password_key}: "{whitespace}"\n'
        )

        for name, errors, rule_id in (
            ("text", text_errors, "GENERIC_SECRET_ASSIGNMENT"),
            ("json", json_errors, "JSON_SECRET_SCALAR"),
            ("yaml", yaml_errors, "YAML_SECRET_SCALAR"),
        ):
            with self.subTest(name=name):
                self.assertTrue(
                    any(rule_id in error for error in errors), errors
                )

    def test_quoted_keys_must_be_paired_before_assignment_matching(self) -> None:
        password_key = "pass" + "word"
        token_key = "auth_" + "token"
        unsafe_value = "synthetic-" + "credential-value"
        message = "Invalid username or " + password_key
        safe_content = json.dumps({message: "authentication failed"})
        unsafe_content = "\n".join(
            (
                json.dumps({password_key: unsafe_value}),
                f"'{token_key}': '{unsafe_value}'",
            )
        )

        self.assertEqual([], scan_text_content("docs/messages.txt", safe_content))
        errors = scan_text_content("docs/config.txt", unsafe_content)

        self.assertEqual(
            2,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
            errors,
        )

    def test_python_quoted_keys_support_only_valid_string_prefixes(self) -> None:
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        prefixed_assignments = "\n".join(
            f'payload = {{{prefix}"{password_key}": "{unsafe_value}"}}'
            for prefix in ("r", "u", "b", "f", "br", "rb", "fr", "rf")
        )
        phrase = json.dumps(
            {"Invalid username or " + password_key: "authentication failed"}
        )

        errors = scan_text_content("docs/config.py", prefixed_assignments)

        self.assertEqual(
            8,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
            errors,
        )
        self.assertEqual([], scan_text_content("docs/messages.py", phrase))

    def test_python_triple_quoted_keys_support_valid_string_prefixes(self) -> None:
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        prefixes = ("", "r", "u", "b", "f", "br", "rb", "fr", "rf")
        assignments = "\n".join(
            f"payload = {{{prefix}{quote}{password_key}{quote}: "
            f'"{unsafe_value}"}}'
            for prefix in prefixes
            for quote in ('"""', "'''")
        )
        phrase = (
            'payload = {"""Invalid username or '
            + password_key
            + '""": "authentication failed"}'
        )

        errors = scan_text_content("docs/config.py", assignments)

        self.assertEqual(
            18,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
            errors,
        )
        self.assertEqual([], scan_text_content("docs/messages.py", phrase))

    def test_only_real_assignment_separators_are_scanned(self) -> None:
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        comparisons = "\n".join(
            (
                f'{password_key} == "comparison"',
                f'{password_key} === "strict-comparison"',
                f'{password_key} => "callback"',
                f'{password_key} =&gt; "markup-callback"',
            )
        )
        assignments = "\n".join(
            (
                f'{password_key}: "{unsafe_value}"',
                f'{password_key} = "{unsafe_value}"',
                f'{password_key} := "{unsafe_value}"',
            )
        )

        self.assertEqual([], scan_text_content("docs/comparisons.txt", comparisons))
        errors = scan_text_content("docs/assignments.txt", assignments)

        self.assertEqual(
            3,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
            errors,
        )

    def test_maven_env_placeholder_requires_exact_uppercase_form(self) -> None:
        password_key = "pass" + "word"
        maven_variable = "DB_" + "PASSWORD"
        exact_placeholder = "${env." + maven_variable + "}"
        unsafe_placeholders = (
            "${env." + maven_variable + ":-fallback}",
            "${env.${" + maven_variable + "_NAME}}",
            "${env." + maven_variable.casefold() + "}",
            "${project." + maven_variable.casefold() + "}",
        )

        def maven_xml(value: str) -> str:
            return f"<settings><{password_key}>{value}</{password_key}></settings>"

        self.assertEqual(
            [], scan_text_content("pom.xml", maven_xml(exact_placeholder))
        )
        for placeholder in unsafe_placeholders:
            with self.subTest(placeholder=placeholder):
                errors = scan_text_content("pom.xml", maven_xml(placeholder))
                self.assertTrue(
                    any("XML_SECRET_SCALAR" in error for error in errors), errors
                )

    def test_env_safe_default_placeholder_is_exact_and_bounded(self) -> None:
        password_key = "pass" + "word"
        variable = "PAYMENT_DB_" + "PASSWORD"
        lowercase_variable = "payment_db_" + "password"
        safe_placeholders = (
            "${" + variable + ":disabled}",
            "${" + variable + ":null}",
        )
        unsafe_placeholders = (
            "${" + lowercase_variable + ":disabled}",
            "${${PASSWORD_NAME}:disabled}",
            "${PAYMENT_${NAME}:disabled}",
            "${" + variable + ":}",
            "${" + variable + ":synthetic-credential-value}",
            "${" + variable + ":disabled:extra}",
            "${env." + variable + ":disabled}",
        )

        for placeholder in safe_placeholders:
            with self.subTest(safe=placeholder):
                content = f'{password_key}: "{placeholder}"\n'
                self.assertEqual(
                    [], scan_text_content("docs/application.yml", content)
                )
        for placeholder in unsafe_placeholders:
            with self.subTest(unsafe=placeholder):
                content = f'{password_key}: "{placeholder}"\n'
                errors = scan_text_content("docs/application.yml", content)
                self.assertTrue(
                    any("YAML_SECRET_SCALAR" in error for error in errors), errors
                )

    def test_safe_placeholder_spans_do_not_hide_composed_or_adjacent_secrets(
        self,
    ) -> None:
        password_key = "pass" + "word"
        secret_key = "client_" + "secret"
        token_key = "auth_" + "token"
        variable = "PAYMENT_DB_" + "PASSWORD"
        plain_placeholder = "${" + variable + "}"
        default_placeholder = "${" + variable + ":disabled}"
        unsafe_value = "synthetic-" + "credential-value"
        composed_values = (
            plain_placeholder + "disabled",
            "disabled" + plain_placeholder,
            "null" + plain_placeholder,
            "false" + plain_placeholder,
            plain_placeholder + unsafe_value,
        )

        standalone = f'{password_key}="{default_placeholder}"\n'
        self.assertEqual(
            [], scan_text_content("docs/standalone.txt", standalone)
        )
        for value in composed_values:
            with self.subTest(composed=value):
                quoted = f'{password_key}="{value}"\n'
                unquoted = f"{password_key}={value}\n"
                quoted_errors = scan_text_content("docs/quoted.txt", quoted)
                unquoted_errors = scan_text_content("docs/unquoted.txt", unquoted)
                self.assertTrue(
                    any(
                        "GENERIC_SECRET_ASSIGNMENT" in error
                        for error in quoted_errors
                    ),
                    quoted_errors,
                )
                self.assertTrue(
                    any(
                        "GENERIC_SECRET_ASSIGNMENT" in error
                        for error in unquoted_errors
                    ),
                    unquoted_errors,
                )

        adjacent_content = "\n".join(
            (
                f'{password_key}="{default_placeholder}" '
                f'{secret_key}="{unsafe_value}"',
                f"{password_key}={default_placeholder} "
                f"{token_key}={unsafe_value}",
            )
        )
        adjacent_errors = scan_text_content("docs/adjacent.txt", adjacent_content)

        joined_content = default_placeholder + f"{secret_key}={unsafe_value}\n"
        joined_errors = scan_text_content("docs/joined.txt", joined_content)
        safe_literal_quote_content = (
            f'{password_key}=disabled"{unsafe_value}"\n'
        )
        safe_literal_quote_errors = scan_text_content(
            "docs/safe-literal-quote.txt", safe_literal_quote_content
        )

        self.assertEqual(
            2,
            sum(
                "GENERIC_SECRET_ASSIGNMENT" in error
                for error in adjacent_errors
            ),
            adjacent_errors,
        )
        self.assertTrue(
            any(
                "GENERIC_SECRET_ASSIGNMENT" in error for error in joined_errors
            ),
            joined_errors,
        )
        self.assertTrue(
            any(
                "GENERIC_SECRET_ASSIGNMENT" in error
                for error in safe_literal_quote_errors
            ),
            safe_literal_quote_errors,
        )

    def test_quoted_secret_assignments_reject_direct_token_suffixes(self) -> None:
        password_key = "pass" + "word"
        variable = "PAYMENT_DB_" + "PASSWORD"
        unsafe_suffix = "synthetic-" + "credential-value"
        safe_values = ("${" + variable + "}", "disabled", "")

        for value in safe_values:
            with self.subTest(value=value):
                standalone = f'{password_key}="{value}"'
                composed = standalone + unsafe_suffix

                self.assertEqual(
                    [], scan_text_content("docs/standalone.txt", standalone)
                )
                self.assertFalse(
                    sensitive_artifacts._contains_secret_assignment(standalone)
                )
                composed_errors = scan_text_content(
                    "docs/composed.txt", composed
                )
                self.assertTrue(
                    any(
                        "GENERIC_SECRET_ASSIGNMENT" in error
                        for error in composed_errors
                    ),
                    composed_errors,
                )
                self.assertTrue(
                    sensitive_artifacts._contains_secret_assignment(composed)
                )

    def test_shell_quoted_assignments_use_shell_token_boundaries(self) -> None:
        password_key = "pass" + "word"
        placeholder = "${PAYMENT_DB_" + "PASSWORD}"
        unsafe_value = "synthetic-" + "credential-value"
        shell_labels = (
            "mvnw",
            "gradlew",
            "scripts/setup.sh",
            "scripts/setup.bash",
            "scripts/setup.zsh",
            ".env",
            "config/.env.local",
        )
        unsafe_suffixes = (":", ",", "]", "}")
        safe_boundaries = ("&&next", "||next", "<input", ">output", ";next")

        for label in shell_labels:
            with self.subTest(label=label, case="standalone"):
                self.assertEqual(
                    [],
                    scan_text_content(
                        label, f'{password_key}="{placeholder}"'
                    ),
                )
            for suffix in unsafe_suffixes:
                with self.subTest(label=label, unsafe_suffix=suffix):
                    content = (
                        f'{password_key}="{placeholder}"'
                        f"{suffix}{unsafe_value}"
                    )
                    errors = scan_text_content(label, content)
                    self.assertTrue(
                        any(
                            "GENERIC_SECRET_ASSIGNMENT" in error
                            for error in errors
                        ),
                        errors,
                    )
            for boundary in safe_boundaries:
                with self.subTest(label=label, safe_boundary=boundary):
                    content = f'{password_key}="{placeholder}"{boundary}'
                    self.assertEqual([], scan_text_content(label, content))

        self.assertFalse(
            sensitive_artifacts._contains_secret_assignment(
                f'{password_key}="{placeholder}":{unsafe_value}'
            )
        )

    def test_mvnw_password_presence_marker_is_safe_only_as_a_complete_token(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        native_case_block = "\n".join(
            (
                f'case "{presence_marker}" in',
                "'') MVNW_USERNAME='' MVNW_PASSWORD='' ;;",
                "has-password) [ -n \"${MVNW_USERNAME-}\" ] || "
                "MVNW_USERNAME='' MVNW_PASSWORD='' ;;",
                "esac",
            )
        )

        self.assertEqual([], scan_text_content("mvnw", native_case_block))
        self.assertEqual(
            [],
            scan_text_content(
                "mvnw",
                "has-password) [ -n \"${MVNW_USERNAME-}\" ] || "
                "MVNW_USERNAME='' MVNW_PASSWORD='' ;;",
            ),
        )
        for rendered_marker in (
            presence_marker,
            f'"{presence_marker}"',
            f"'{presence_marker}'",
            presence_marker + " " + presence_marker,
        ):
            with self.subTest(marker=rendered_marker):
                self.assertEqual(
                    [],
                    scan_text_content(
                        "backend/mvnw", f"case {rendered_marker} in"
                    ),
                )

        self.assertTrue(
            sensitive_artifacts._contains_secret_assignment(presence_marker)
        )

    def test_mvnw_password_marker_uses_original_offsets_after_unicode_prefixes(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        nested_marker = "${${" + wrapper_variable + "_NAME}:+has-password}"

        for prefix in ("\u00df\u00df", "\u0130\u0130"):
            with self.subTest(prefix=prefix, marker="exact"):
                self.assertEqual(
                    [],
                    scan_text_content(
                        "mvnw", f"{prefix} {presence_marker}"
                    ),
                )
            with self.subTest(prefix=prefix, marker="nested"):
                errors = scan_text_content(
                    "mvnw", f"{prefix} {nested_marker}"
                )
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_marker_fails_closed_for_malformed_context(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        nested_marker = "${${" + wrapper_variable + "_NAME}:+has-password}"
        safe_lines = (
            presence_marker,
            "${OTHER} " + presence_marker,
            "${OTHER:-disabled} " + presence_marker,
        )
        blocked_lines = (
            "${UNFINISHED " + presence_marker,
            "${OTHER} ${UNFINISHED " + presence_marker,
            "${UNFINISHED " + presence_marker + " " + nested_marker,
            "} " + presence_marker,
            presence_marker + " }",
            "${" + wrapper_variable + "} ${OTHER:+has-password}",
        )

        for line in safe_lines:
            with self.subTest(context="safe", line=line):
                self.assertEqual([], scan_text_content("mvnw", line))
        for line in blocked_lines:
            with self.subTest(context="blocked", line=line):
                errors = scan_text_content("mvnw", line)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_marker_rejects_all_noncanonical_ordered_signals(
        self,
    ) -> None:
        unsafe_markers = (
            "${MVNW_PASSWORD[0]:+has-password}",
            "${MVNW_PASSWORD[@]:+has-password}",
            "${MVNW_PASSWORD+has-password}",
            "${MVNW_PASSWORDé:+has-password}",
            "${$(printf MVNW_PASSWORD ):+has-password}",
            "${$(printf MVNW_PASSWORD' '):+has-password}",
            "${$(printf MVNW_PASSWORD\\ ):+has-password}",
            "${MVNW_PASSWORD${SUFFIX}:+has-password}",
        )

        for marker in unsafe_markers:
            with self.subTest(marker=marker):
                errors = scan_text_content("mvnw", marker)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_marker_rejects_quoted_and_escaped_delimiters(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        marker_value = "has-" + "password"
        unsafe_markers = (
            "${$(printf '}" + wrapper_variable + "'):+" + marker_value + "}",
            '${$(printf "}' + wrapper_variable + '"):+' + marker_value + "}",
            "${$(printf \\}" + wrapper_variable + "):+" + marker_value + "}",
            "${$(printf '${' " + wrapper_variable + "):+" + marker_value + "}",
        )
        self.assertEqual(
            (
                "${$(printf '}MVNW_PASSWORD'):+has-password}",
                '${$(printf "}MVNW_PASSWORD"):+has-password}',
                "${$(printf \\}MVNW_PASSWORD):+has-password}",
                "${$(printf '${' MVNW_PASSWORD):+has-password}",
            ),
            unsafe_markers,
        )

        for marker in unsafe_markers:
            with self.subTest(marker=marker):
                errors = scan_text_content("mvnw", marker)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_marker_has_no_arbitrary_signal_window(self) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        marker_value = "has-" + "password"
        long_variant = (
            "${"
            + wrapper_variable
            + "_" * 2048
            + ":+"
            + marker_value
            + "}"
        )
        ordered_unrelated_signals = (
            "${" + wrapper_variable + "} ${OTHER:+" + marker_value + "}"
        )
        long_arbitrary_signals = (
            "${"
            + wrapper_variable
            + ("界 \t[@]${OTHER}" * 256)
            + ":+"
            + marker_value
            + "}"
        )
        multiple_signals = (
            marker_value
            + " ${OTHER} ${"
            + wrapper_variable
            + "} ${ANOTHER} "
            + marker_value
        )

        for marker in (
            long_variant,
            ordered_unrelated_signals,
            long_arbitrary_signals,
            multiple_signals,
        ):
            with self.subTest(marker=marker):
                errors = scan_text_content("mvnw", marker)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_presence_marker_is_not_safe_for_other_labels(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        content = f'case "{presence_marker}" in'
        other_labels = (
            "gradlew",
            "MVNW",
            "mvnw.sh",
            "\uff4d\uff56\uff4e\uff57",
            "mvnw\u0130",
            "scripts/setup.sh",
            "scripts/setup.bash",
            "scripts/setup.zsh",
            ".env",
            "config/.env.local",
            "docs/example.txt",
            "docs/example.yml",
            "docs/example.json",
        )

        for label in other_labels:
            with self.subTest(label=label):
                errors = scan_text_content(label, content)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_presence_marker_variants_remain_blocked(self) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        other_variable = "OTHER_" + "PASSWORD"
        variants = (
            "${" + other_variable + ":+has-password}",
            "${" + wrapper_variable + ":-has-password}",
            "${" + wrapper_variable + ":+password-present}",
            "${" + wrapper_variable + ":+HAS-PASSWORD}",
            "${${" + wrapper_variable + "_NAME}:+has-password}",
            "${${" + wrapper_variable.casefold() + "_name}:+has-password}",
            "${${MVNW_PA\u017f\u017fWORD_NAME}:+has-password}",
            "${MVNW_${PASSWORD_NAME}:+has-password}",
            "${" + wrapper_variable + ":+${MARKER}}",
            "${" + wrapper_variable + ":+$(printf has-password)}",
            "${$(printf " + wrapper_variable + "):+has-password}",
        )

        for marker in variants:
            with self.subTest(marker=marker):
                errors = scan_text_content("mvnw", f'case "{marker}" in')
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_mvnw_password_presence_marker_rejects_suffixes_and_real_secrets(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        secret_key = "client_" + "secret"
        unsafe_value = "synthetic-" + "credential-value"
        unsafe_lines = (
            "prefix" + presence_marker,
            f'prefix"{presence_marker}"',
            presence_marker + "suffix",
            f'"{presence_marker}"suffix',
            f"'{presence_marker}'suffix",
            presence_marker + ":suffix",
            presence_marker + ",suffix",
            presence_marker + "]suffix",
            presence_marker + "}suffix",
            f'{presence_marker} {secret_key}="{unsafe_value}"',
            f'{presence_marker}{secret_key}="{unsafe_value}"',
            f'"{presence_marker}" {secret_key}="{unsafe_value}"',
        )

        for line in unsafe_lines:
            with self.subTest(line=line):
                errors = scan_text_content("mvnw", line)
                self.assertTrue(
                    any("GENERIC_SECRET_ASSIGNMENT" in error for error in errors),
                    errors,
                )

    def test_many_mvnw_password_presence_markers_keep_span_matching_bounded(
        self,
    ) -> None:
        wrapper_variable = "MVNW_" + "PASSWORD"
        presence_marker = "${" + wrapper_variable + ":+has-password}"
        secret_key = "client_" + "secret"
        unsafe_value = "synthetic-" + "credential-value"
        content = " ".join(
            (*([presence_marker] * 512), f'{secret_key}="{unsafe_value}"')
        )

        errors = scan_text_content("mvnw", content)

        self.assertEqual(
            ["mvnw:1: GENERIC_SECRET_ASSIGNMENT"],
            errors,
        )

    def test_many_shell_quoted_assignments_keep_suffix_detection_bounded(
        self,
    ) -> None:
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        assignments = [
            f'{password_key}="${{PASSWORD_{index:03d}}}"'
            for index in range(512)
        ]
        assignments[-1] += ":" + unsafe_value

        errors = scan_text_content("mvnw", " ".join(assignments))

        self.assertEqual(
            ["mvnw:1: GENERIC_SECRET_ASSIGNMENT"],
            errors,
        )

    def test_many_safe_placeholder_spans_do_not_hide_a_real_secret(self) -> None:
        password_key = "pass" + "word"
        secret_key = "client_" + "secret"
        unsafe_value = "synthetic-" + "credential-value"
        safe_assignments = (
            f'{password_key}="${{PASSWORD_{index:03d}:disabled}}"'
            for index in range(256)
        )
        content = " ".join(
            (*safe_assignments, f'{secret_key}="{unsafe_value}"')
        )

        errors = scan_text_content("docs/many-placeholders.txt", content)

        self.assertEqual(
            ["docs/many-placeholders.txt:1: GENERIC_SECRET_ASSIGNMENT"],
            errors,
        )

    def test_yaml_sa_token_mapping_root_is_not_a_secret_container(self) -> None:
        framework_key = "sa-" + "token"
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        safe_content = (
            f"{framework_key}:\n"
            "  is-share: true\n"
            "  token-name: satoken\n"
        )
        scalar_content = f"{framework_key}: {unsafe_value}\n"
        nested_secret_content = (
            f"{framework_key}:\n  {password_key}: {unsafe_value}\n"
        )

        self.assertEqual([], scan_text_content("docs/config.yml", safe_content))
        scalar_errors = scan_text_content("docs/config.yml", scalar_content)
        nested_errors = scan_text_content(
            "docs/config.yml", nested_secret_content
        )

        self.assertTrue(
            any("YAML_SECRET_SCALAR" in error for error in scalar_errors),
            scalar_errors,
        )
        self.assertTrue(
            any("YAML_SECRET_SCALAR" in error for error in nested_errors),
            nested_errors,
        )

    def test_yaml_sa_token_flow_mapping_root_is_not_a_generic_assignment(
        self,
    ) -> None:
        framework_key = "sa-" + "token"
        password_key = "pass" + "word"
        unsafe_value = "synthetic-" + "credential-value"
        safe_contents = (
            f"{framework_key}: {{}}\n",
            f"{framework_key}: {{is-share: true, token-name: satoken}}\n",
            f'"{framework_key}": {{is-share: true}}\n',
            f'!!str "{framework_key}": {{}}\n',
            f"&key {framework_key}: {{is-share: true}}\n",
        )

        for content in safe_contents:
            with self.subTest(content=content):
                self.assertEqual(
                    [], scan_text_content("docs/config.yml", content)
                )

        scalar_errors = scan_text_content(
            "docs/config.yml", f"{framework_key}: {unsafe_value}\n"
        )
        nested_errors = scan_text_content(
            "docs/config.yml",
            f"{framework_key}: {{{password_key}: {unsafe_value}}}\n",
        )
        tag_anchor_errors = scan_text_content(
            "docs/config.yml",
            f"!!str &key {framework_key}: {{}} "
            f"# {password_key}={unsafe_value}\n",
        )

        self.assertTrue(
            any("YAML_SECRET_SCALAR" in error for error in scalar_errors),
            scalar_errors,
        )
        self.assertTrue(
            any("YAML_SECRET_SCALAR" in error for error in nested_errors),
            nested_errors,
        )
        self.assertFalse(
            any("INVALID_YAML" in error for error in tag_anchor_errors),
            tag_anchor_errors,
        )
        self.assertEqual(
            1,
            sum(
                "GENERIC_SECRET_ASSIGNMENT" in error
                for error in tag_anchor_errors
            ),
            tag_anchor_errors,
        )

    def test_typescript_type_members_are_safe_but_value_contexts_are_scanned(
        self,
    ) -> None:
        password_key = "pass" + "word"
        token_key = "auth_" + "token"
        secret_key = "client_" + "secret"
        unsafe_value = "synthetic-" + "credential-value"
        safe_content = "\n".join(
            (
                "interface Credentials {",
                f"  readonly {password_key}: string;",
                f"  {token_key}: string | undefined;",
                "}",
                "type SecretEnvelope = {",
                f'  "{secret_key}": Uint8Array;',
                "  nested: {",
                f"    {password_key}: PasswordValue;",
                "  };",
                "};",
            )
        )
        unsafe_content = "\n".join(
            (
                "const credentials = {",
                f"  {password_key}: string,",
                "};",
                "class CredentialHolder {",
                f"  {token_key}: string;",
                "}",
                f"{password_key} = string;",
                f'const explicit = {{ {secret_key}: "{unsafe_value}" }};',
            )
        )
        literal_type_content = "\n".join(
            (
                "interface UnsafeLiteralType {",
                f'  {password_key}: "{unsafe_value}";',
                "}",
            )
        )

        self.assertEqual([], scan_text_content("docs/types.ts", safe_content))
        unsafe_errors = scan_text_content("docs/values.ts", unsafe_content)
        literal_errors = scan_text_content(
            "docs/literal-type.ts", literal_type_content
        )

        self.assertEqual(
            4,
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in unsafe_errors),
            unsafe_errors,
        )
        self.assertTrue(
            any("GENERIC_SECRET_ASSIGNMENT" in error for error in literal_errors),
            literal_errors,
        )

    def test_typescript_type_scopes_must_start_as_declarations(self) -> None:
        password_key = "pass" + "word"
        runtime_content = "\n".join(
            (
                "const open = /interface Credentials {/;",
                f"  {password_key}: data.{password_key};",
                "const close = /}/;",
            )
        )
        declaration_headers = (
            "export default interface Credentials {",
            "export declare interface Credentials {",
            "declare interface Credentials {",
            "export type Credentials = {",
            "export declare type Credentials = {",
            "declare type Credentials = {",
        )

        runtime_errors = scan_text_content("docs/runtime.ts", runtime_content)

        self.assertTrue(
            any(
                "GENERIC_SECRET_ASSIGNMENT" in error
                for error in runtime_errors
            ),
            runtime_errors,
        )
        for header in declaration_headers:
            with self.subTest(header=header):
                declaration = f"{header}\n  {password_key}: string;\n}}\n"
                self.assertEqual(
                    [], scan_text_content("docs/declaration.ts", declaration)
                )

    def test_tsx_jsx_text_cannot_create_a_type_member_scope(self) -> None:
        password_key = "pass" + "word"
        jsx_content = "\n".join(
            (
                "const view = (",
                "  <div>",
                "    interface Credentials {(() => {",
                f"      {password_key}: data.{password_key};",
                "      return null;",
                "    })()}",
                "  </div>",
                ");",
            )
        )

        errors = scan_text_content("docs/view.tsx", jsx_content)

        self.assertTrue(
            any(
                "GENERIC_SECRET_ASSIGNMENT" in error for error in errors
            ),
            errors,
        )

    def test_typescript_type_member_exemption_does_not_cover_comments(
        self,
    ) -> None:
        password_key = "pass" + "word"
        secret_key = "client_" + "secret"
        unsafe_value = "synthetic-" + "credential-value"
        member = f"{password_key}: string;"
        comment_cases = (
            ("trailing-line", f'{member} // {secret_key}="{unsafe_value}"'),
            ("leading-block", f'/* {secret_key}="{unsafe_value}" */ {member}'),
            ("trailing-block", f'{member} /* {secret_key}="{unsafe_value}" */'),
            ("leading-line", f'// {secret_key}="{unsafe_value}" {member}'),
        )

        self.assertEqual(
            [],
            scan_text_content(
                "docs/member.ts", "interface Credentials {\n  " + member + "\n}\n"
            ),
        )
        for name, candidate in comment_cases:
            with self.subTest(name=name):
                content = "interface Credentials {\n  " + candidate + "\n}\n"
                errors = scan_text_content("docs/comment.ts", content)
                self.assertGreaterEqual(
                    sum(
                        "GENERIC_SECRET_ASSIGNMENT" in error
                        for error in errors
                    ),
                    1,
                    errors,
                )

    def test_allows_only_the_exact_approved_pnpm_lockfile_findings(self) -> None:
        relative_path = "frontend/admin/pnpm-lock.yaml"
        content = REPOSITORY.joinpath(relative_path).read_text(encoding="utf-8")

        self.assertEqual([], scan_text_content(relative_path, content))

    def test_pnpm_lockfile_approvals_reject_every_binding_change(self) -> None:
        relative_path = "frontend/admin/pnpm-lock.yaml"
        content = REPOSITORY.joinpath(relative_path).read_text(encoding="utf-8")
        lines = content.splitlines()
        email_line_number = 7474
        dependency_line_number = 17867
        email_line_index = email_line_number - 1
        dependency_line_index = dependency_line_number - 1

        email_candidates = list(
            sensitive_artifacts._iter_email_addresses(lines[email_line_index])
        )
        assignment_matches = [
            *sensitive_artifacts.GENERIC_SECRET_ASSIGNMENT_PATTERN.finditer(
                lines[dependency_line_index]
            ),
            *sensitive_artifacts.UNQUOTED_SECRET_ASSIGNMENT_PATTERN.finditer(
                lines[dependency_line_index]
            ),
        ]
        self.assertEqual(1, len(email_candidates))
        self.assertEqual(1, len(assignment_matches))

        def with_changed_line(line_number: int, line: str) -> str:
            changed = list(lines)
            changed[line_number - 1] = line
            return "\n".join(changed) + "\n"

        assignment = assignment_matches[0]
        assignment_line = lines[dependency_line_index]
        approved_dependency_value = assignment.group("value")

        changed_email = "lockfile-contact" + "@dependency.example.org"
        changed_email_content = with_changed_line(
            email_line_number,
            lines[email_line_index].replace(email_candidates[0], changed_email),
        )
        changed_version_content = with_changed_line(
            dependency_line_number,
            assignment_line[: assignment.start("value")]
            + "5.1.2"
            + assignment_line[assignment.end("value") :],
        )
        changed_email_context = with_changed_line(
            email_line_number,
            lines[email_line_index] + " # changed context",
        )
        changed_dependency_context = with_changed_line(
            dependency_line_number,
            assignment_line + " # changed context",
        )
        changed_token_key = with_changed_line(
            dependency_line_number,
            assignment_line.replace(
                "registry-auth-" + "token",
                "different-auth-" + "token",
            ),
        )
        adversarial_value = "production-" + "credential-value"
        adversarial_value_content = with_changed_line(
            dependency_line_number,
            assignment_line[: assignment.start("value")]
            + adversarial_value
            + assignment_line[assignment.end("value") :],
        )

        cases = (
            (
                "copied-path",
                "frontend/admin/copied-pnpm-lock.yaml",
                content,
                {email_line_number, dependency_line_number},
                ("EMAIL_ADDRESS", "GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
            (
                "moved-lines",
                relative_path,
                "\n" + content,
                {email_line_number + 1, dependency_line_number + 1},
                ("EMAIL_ADDRESS", "GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
            (
                "changed-email",
                relative_path,
                changed_email_content,
                {email_line_number, dependency_line_number},
                ("EMAIL_ADDRESS",),
            ),
            (
                "changed-version",
                relative_path,
                changed_version_content,
                {email_line_number, dependency_line_number},
                ("GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
            (
                "changed-email-context",
                relative_path,
                changed_email_context,
                {email_line_number, dependency_line_number},
                ("EMAIL_ADDRESS",),
            ),
            (
                "changed-dependency-context",
                relative_path,
                changed_dependency_context,
                {email_line_number, dependency_line_number},
                ("GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
            (
                "changed-token-key",
                relative_path,
                changed_token_key,
                {email_line_number, dependency_line_number},
                ("GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
            (
                "real-secret-on-approved-line",
                relative_path,
                adversarial_value_content,
                {email_line_number, dependency_line_number},
                ("GENERIC_SECRET_ASSIGNMENT", "YAML_SECRET_SCALAR"),
            ),
        )

        for name, label, candidate_content, selected_lines, expected_rules in cases:
            with self.subTest(name=name):
                errors = scan_text_content(
                    label,
                    candidate_content,
                    selected_lines=selected_lines,
                )
                rendered = "\n".join(errors)

                self.assertCountEqual(
                    expected_rules,
                    [error.rsplit(": ", 1)[-1] for error in errors],
                    errors,
                )
                self.assertNotIn(approved_dependency_value, rendered)
                self.assertNotIn(changed_email, rendered)
                self.assertNotIn(adversarial_value, rendered)

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

    def test_default_target_discovery_requires_the_exact_repository_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory).resolve()
            nested = repository / "nested"
            nested.mkdir()
            environment = {"PATH": "/trusted/bin"}
            isolated_environment = {
                "PATH": environment["PATH"],
                "HOME": os.devnull,
                "LANG": "C",
                "LC_ALL": "C",
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_NO_REPLACE_OBJECTS": "1",
                "GIT_LITERAL_PATHSPECS": "1",
                "GIT_ALLOW_PROTOCOL": "",
                "GIT_NO_LAZY_FETCH": "1",
                "GIT_TERMINAL_PROMPT": "0",
            }
            top_level = subprocess.CompletedProcess(
                args=(),
                returncode=0,
                stdout=os.fsencode(repository) + b"\n",
            )
            expected_command = (
                "git",
                "-C",
                str(nested),
                "-c",
                "safe.directory=",
                "-c",
                f"safe.directory={nested}",
                "-c",
                "core.fsmonitor=",
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "core.commitGraph=false",
                "-c",
                "core.useReplaceRefs=false",
                "-c",
                "submodule.recurse=false",
                "--no-replace-objects",
                "--literal-pathspecs",
                "rev-parse",
                "--show-toplevel",
            )

            with (
                mock.patch.dict(os.environ, environment, clear=True),
                mock.patch.object(
                    sensitive_artifacts.subprocess,
                    "run",
                    return_value=top_level,
                ) as run_git,
            ):
                targets, errors = (
                    sensitive_artifacts.discover_tracked_artifact_targets(nested)
                )

            self.assertEqual((), targets)
            self.assertEqual(
                [
                    "repository: GIT_REPOSITORY_MISMATCH: "
                    "default scan requires the exact repository root"
                ],
                errors,
            )
            self.assertEqual(
                [
                    mock.call(
                        expected_command,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL,
                        env=isolated_environment,
                        check=False,
                    )
                ],
                run_git.call_args_list,
            )

    def test_default_target_discovery_rejects_git_overrides_before_git_runs(
        self,
    ) -> None:
        forbidden_variables = (
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_ATTR_NOSYSTEM",
            "GIT_ATTR_SOURCE",
            "GIT_CEILING_DIRECTORIES",
            "GIT_COMMON_DIR",
            "GIT_CONFIG_COUNT",
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_NOSYSTEM",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_SYSTEM",
            "GIT_DIR",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM",
            "GIT_EXEC_PATH",
            "GIT_GRAFT_FILE",
            "GIT_INDEX_FILE",
            "GIT_INTERNAL_SUPER_PREFIX",
            "GIT_NAMESPACE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_PREFIX",
            "GIT_QUARANTINE_PATH",
            "GIT_REPLACE_REF_BASE",
            "GIT_SHALLOW_FILE",
            "GIT_WORK_TREE",
            "GIT_CONFIG_KEY_0",
            "GIT_CONFIG_VALUE_27",
        )
        repository = Path("/expected/repository")

        for variable in forbidden_variables:
            with self.subTest(variable=variable):
                environment = {"PATH": "/trusted/bin", variable: "hostile"}
                with (
                    mock.patch.dict(os.environ, environment, clear=True),
                    mock.patch.object(
                        sensitive_artifacts.subprocess,
                        "run",
                    ) as run_git,
                ):
                    targets, errors = (
                        sensitive_artifacts.discover_tracked_artifact_targets(
                            repository
                        )
                    )

                self.assertEqual((), targets)
                self.assertEqual(
                    [
                        "repository: GIT_ENVIRONMENT_OVERRIDE: "
                        "Git environment overrides are not allowed"
                    ],
                    errors,
                )
                run_git.assert_not_called()

    def test_default_target_discovery_uses_isolated_git_calls_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory).resolve()
            environment = {"PATH": "/trusted/bin"}
            isolated_environment = {
                "PATH": environment["PATH"],
                "HOME": os.devnull,
                "LANG": "C",
                "LC_ALL": "C",
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_NO_REPLACE_OBJECTS": "1",
                "GIT_LITERAL_PATHSPECS": "1",
                "GIT_ALLOW_PROTOCOL": "",
                "GIT_NO_LAZY_FETCH": "1",
                "GIT_TERMINAL_PROMPT": "0",
            }

            def git_command(*arguments: str) -> tuple[str, ...]:
                return (
                    "git",
                    "-C",
                    str(repository),
                    "-c",
                    "safe.directory=",
                    "-c",
                    f"safe.directory={repository}",
                    "-c",
                    "core.fsmonitor=",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "core.commitGraph=false",
                    "-c",
                    "core.useReplaceRefs=false",
                    "-c",
                    "submodule.recurse=false",
                    "--no-replace-objects",
                    "--literal-pathspecs",
                    *arguments,
                )

            top_level = subprocess.CompletedProcess(
                args=(),
                returncode=0,
                stdout=os.fsencode(repository) + b"\n",
            )
            tracked = subprocess.CompletedProcess(
                args=(),
                returncode=0,
                stdout=(
                    b"scripts/tests/fixtures/capture.json\0"
                    b"scripts/tests/test_source.py\0"
                    b"docs/manual.md\0"
                    b"src/test/resources/application.yaml\0"
                    b"tests/output.snap\0"
                ),
            )

            with (
                mock.patch.dict(os.environ, environment, clear=True),
                mock.patch.object(
                    sensitive_artifacts.subprocess,
                    "run",
                    side_effect=(top_level, tracked),
                ) as run_git,
            ):
                targets, errors = (
                    sensitive_artifacts.discover_tracked_artifact_targets(repository)
                )

            self.assertEqual(
                (
                    "scripts/tests/fixtures/capture.json",
                    "src/test/resources/application.yaml",
                    "tests/output.snap",
                ),
                targets,
            )
            self.assertEqual([], errors)
            self.assertEqual(
                [
                    mock.call(
                        git_command("rev-parse", "--show-toplevel"),
                        stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL,
                        env=isolated_environment,
                        check=False,
                    ),
                    mock.call(
                        git_command("ls-files", "-z", "--cached"),
                        stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL,
                        env=isolated_environment,
                        check=False,
                    ),
                ],
                run_git.call_args_list,
            )

    def test_immutable_git_preserves_isolation_and_resets_exact_trust(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory).resolve()
            environment = {"PATH": "/trusted/bin"}
            completed = subprocess.CompletedProcess(
                args=(),
                returncode=0,
                stdout=b"ok\n",
                stderr=b"",
            )

            with (
                mock.patch.dict(os.environ, environment, clear=True),
                mock.patch.object(
                    sensitive_artifacts.subprocess,
                    "run",
                    return_value=completed,
                ) as run_git,
            ):
                output = sensitive_artifacts._run_immutable_git(
                    repository,
                    ("status", "--short"),
                )

            self.assertEqual(b"ok\n", output)
            self.assertEqual(
                (
                    "git",
                    "-c",
                    "safe.directory=",
                    "-c",
                    f"safe.directory={repository}",
                    "-c",
                    "core.fsmonitor=",
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "core.commitGraph=false",
                    "-c",
                    "core.useReplaceRefs=false",
                    "-c",
                    "submodule.recurse=false",
                    "--no-replace-objects",
                    "--literal-pathspecs",
                    "status",
                    "--short",
                ),
                run_git.call_args.args[0],
            )
            self.assertEqual(
                sensitive_artifacts._isolated_git_environment(environment),
                run_git.call_args.kwargs["env"],
            )

    def test_git_scans_reject_literal_star_before_any_git_subprocess(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory, "*")
            repository.mkdir()
            with mock.patch.object(
                sensitive_artifacts.subprocess,
                "run",
            ) as run_git:
                targets, discovery_errors = (
                    sensitive_artifacts.discover_tracked_artifact_targets(
                        repository
                    )
                )
                diff_errors = scan_git_diff(
                    repository,
                    "0" * 40,
                    "1" * 40,
                )

            self.assertEqual((), targets)
            self.assertEqual(
                [
                    "repository: GIT_REPOSITORY_MISMATCH: "
                    "default scan requires the exact repository root"
                ],
                discovery_errors,
            )
            self.assertTrue(
                any("wildcard" in error for error in diff_errors),
                diff_errors,
            )
            run_git.assert_not_called()

    def test_git_scans_accept_only_the_exact_different_owner_repository_without_running_repository_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "source"
            repository.mkdir()
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            base = self.commit_all(repository, "base")
            self.write_artifact(repository, "still safe\n", "README.md")
            commit = self.commit_all(repository, "change")

            wrapper_directory = root / "git-wrapper"
            wrapper_directory.mkdir()
            git_wrapper = wrapper_directory / "git"
            git_path = shutil.which("git")
            self.assertIsNotNone(git_path)
            git_wrapper.write_text(
                "#!/bin/sh\n"
                "GIT_TEST_ASSUME_DIFFERENT_OWNER=1\n"
                "export GIT_TEST_ASSUME_DIFFERENT_OWNER\n"
                f"exec {shlex.quote(git_path)} \"$@\"\n",
                encoding="utf-8",
            )
            git_wrapper.chmod(0o755)
            empty_home = root / "home"
            empty_home.mkdir()
            environment = {
                "HOME": str(empty_home),
                "LANG": "C",
                "LC_ALL": "C",
                "PATH": f"{wrapper_directory}{os.pathsep}{os.environ['PATH']}",
            }
            probe_environment = os.environ.copy()
            probe_environment.update(environment)
            probe_environment.update(
                {
                    "GIT_CONFIG_GLOBAL": os.devnull,
                    "GIT_CONFIG_NOSYSTEM": "1",
                    "GIT_CONFIG_SYSTEM": os.devnull,
                }
            )
            control = subprocess.run(
                (str(git_path), "-C", str(repository), "status", "--short"),
                check=False,
                capture_output=True,
                env=probe_environment,
                text=True,
            )
            self.assertEqual(0, control.returncode, control.stderr)

            baseline_environment = probe_environment.copy()
            baseline_environment["GIT_TEST_ASSUME_DIFFERENT_OWNER"] = "1"
            baseline = subprocess.run(
                (str(git_path), "-C", str(repository), "status", "--short"),
                check=False,
                capture_output=True,
                env=baseline_environment,
                text=True,
            )
            if baseline.returncode == 0:
                self.skipTest(
                    "Git does not enforce the different-owner test capability"
                )

            exact_override = subprocess.run(
                (
                    str(git_path),
                    "-c",
                    f"safe.directory={repository.resolve()}",
                    "-C",
                    str(repository),
                    "status",
                    "--short",
                ),
                check=False,
                capture_output=True,
                env=baseline_environment,
                text=True,
            )
            if exact_override.returncode != 0:
                self.skipTest(
                    "Git does not accept a command-scoped safe.directory override"
                )

            fsmonitor_sentinel = root / "fsmonitor-ran"
            fsmonitor_hook = root / "hostile-fsmonitor"
            fsmonitor_hook.write_text(
                "#!/bin/sh\n"
                f": > {shlex.quote(str(fsmonitor_sentinel))}\n"
                "exit 0\n",
                encoding="utf-8",
            )
            fsmonitor_hook.chmod(0o755)
            subprocess.run(
                ("git", "config", "core.fsmonitor", str(fsmonitor_hook)),
                cwd=repository,
                check=True,
            )
            self.assertFalse(fsmonitor_sentinel.exists())

            with mock.patch.dict(os.environ, environment, clear=True):
                diff_errors = scan_git_diff(repository, base, commit)
                _targets, discovery_errors = (
                    sensitive_artifacts.discover_tracked_artifact_targets(repository)
                )

            self.assertEqual([], diff_errors)
            self.assertEqual([], discovery_errors)
            self.assertFalse(fsmonitor_sentinel.exists())

    def test_git_scans_disable_lazy_fetch_and_all_transports(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "source"
            repository.mkdir()
            self.initialize_git_repository(repository)
            self.write_artifact(repository, "safe\n", "README.md")
            base = self.commit_all(repository, "base")
            self.write_artifact(repository, "still safe\n", "README.md")
            commit = self.commit_all(repository, "change")

            wrapper_directory = root / "git-wrapper"
            wrapper_directory.mkdir()
            git_wrapper = wrapper_directory / "git"
            git_path = shutil.which("git")
            self.assertIsNotNone(git_path)
            transport_sentinel = root / "git-transport-enabled"
            git_wrapper.write_text(
                "#!/bin/sh\n"
                "if [ \"${GIT_NO_LAZY_FETCH:-}\" != 1 ] ||\n"
                "   [ \"${GIT_ALLOW_PROTOCOL+x}\" != x ] ||\n"
                "   [ -n \"${GIT_ALLOW_PROTOCOL:-}\" ] ||\n"
                "   [ \"${GIT_TERMINAL_PROMPT:-}\" != 0 ]; then\n"
                f"  : > {shlex.quote(str(transport_sentinel))}\n"
                "fi\n"
                f"exec {shlex.quote(git_path)} \"$@\"\n",
                encoding="utf-8",
            )
            git_wrapper.chmod(0o755)
            environment = {
                "HOME": str(root / "home"),
                "LANG": "C",
                "LC_ALL": "C",
                "PATH": f"{wrapper_directory}{os.pathsep}{os.environ['PATH']}",
            }

            with mock.patch.dict(os.environ, environment, clear=True):
                diff_errors = scan_git_diff(repository, base, commit)
                _targets, discovery_errors = (
                    sensitive_artifacts.discover_tracked_artifact_targets(repository)
                )

            self.assertEqual([], diff_errors)
            self.assertEqual([], discovery_errors)
            self.assertFalse(transport_sentinel.exists())

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

    def test_immutable_diff_disables_textconv_for_root_and_parent_edges(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory).resolve()
            grafts = repository / "missing-grafts"
            base = "0" * 40
            target = "1" * 40

            for edge_command, parents in (
                ("diff-tree", ()),
                ("diff", (base,)),
            ):
                with self.subTest(edge_command=edge_command):
                    calls: list[tuple[str, ...]] = []

                    def run_git(
                        _repository: Path,
                        arguments: tuple[str, ...],
                    ) -> bytes:
                        calls.append(arguments)
                        if arguments == (
                            "rev-parse",
                            "--is-shallow-repository",
                        ):
                            return b"false\n"
                        if arguments == (
                            "rev-parse",
                            "--git-path",
                            "info/grafts",
                        ):
                            return os.fsencode(grafts) + b"\n"
                        if arguments[:2] == (
                            "merge-base",
                            "--is-ancestor",
                        ):
                            return b""
                        if arguments[0] == "rev-list":
                            history = " ".join((target, *parents))
                            return os.fsencode(history) + b"\n"
                        if arguments[:2] == ("cat-file", "commit"):
                            parent_headers = b"".join(
                                b"parent " + os.fsencode(parent) + b"\n"
                                for parent in parents
                            )
                            return b"tree " + b"2" * 40 + b"\n" + parent_headers + b"\n"
                        if arguments[0] == edge_command:
                            return b""
                        raise AssertionError(f"unexpected Git call: {arguments}")

                    with mock.patch.object(
                        sensitive_artifacts,
                        "_run_immutable_git",
                        side_effect=run_git,
                    ):
                        errors = scan_git_diff(repository, base, target)

                    self.assertEqual([], errors)
                    edge_arguments = next(
                        arguments
                        for arguments in calls
                        if arguments[0] == edge_command
                    )
                    self.assertIn("--no-textconv", edge_arguments)

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

    def test_har_and_json_string_fields_detect_recursively_stringified_secrets(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        token_key = "sessionTo" + "ken"
        har_value = "synthetic-har-body-value"
        nested_value = "synthetic-nested-json-value"
        har_content = json.dumps(
            {
                "log": {
                    "entries": [
                        {
                            "request": {
                                "postData": {
                                    "mimeType": "Application/JSON; charset=utf-8",
                                    "text": json.dumps({password_key: har_value}),
                                }
                            }
                        }
                    ]
                }
            }
        )
        json_content = json.dumps(
            {
                "payload": json.dumps(
                    {"nested": json.dumps({token_key: nested_value})}
                )
            }
        )

        errors = [
            *scan_text_content("evidence/request.har", har_content),
            *scan_text_content("docs/envelope.json", json_content),
        ]
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("JSON_SECRET_SCALAR"), errors)
        self.assertNotIn(har_value, rendered)
        self.assertNotIn(nested_value, rendered)

    def test_declared_har_json_body_fails_closed_without_echoing_malformed_value(
        self,
    ) -> None:
        malformed_value = "synthetic-malformed-har-value"
        har_content = json.dumps(
            {
                "log": {
                    "entries": [
                        {
                            "request": {
                                "postData": {
                                    "mimeType": "application/problem+json",
                                    "text": '{"dbPass'
                                    + 'word":"'
                                    + malformed_value
                                    + '"',
                                }
                            }
                        }
                    ]
                }
            }
        )

        errors = scan_text_content("evidence/request.har", har_content)
        rendered = "\n".join(errors)

        self.assertIn("INVALID_JSON", rendered)
        self.assertNotIn(malformed_value, rendered)

    def test_har_json_response_content_honors_encoding_and_fails_closed(
        self,
    ) -> None:
        password_key = "dbPass" + "word"
        response_value = "synthetic-har-response-value"
        malformed_value = "synthetic-malformed-response-value"
        encoded_body = base64.b64encode(
            json.dumps({password_key: response_value}).encode("utf-8")
        ).decode("ascii")

        def har_response(content: dict[str, str]) -> str:
            return json.dumps(
                {"log": {"entries": [{"response": {"content": content}}]}}
            )

        encoded_har = har_response(
            {
                "mimeType": "application/json",
                "encoding": "base64",
                "text": encoded_body,
            }
        )
        malformed_har = har_response(
            {
                "mimeType": "application/json",
                "text": '{"dbPass' + 'word":"' + malformed_value + '"',
            }
        )
        malformed_base64_har = har_response(
            {
                "mimeType": "application/json",
                "encoding": "base64",
                "text": "not-valid-base64!",
            }
        )

        errors = [
            *scan_text_content("evidence/encoded.har", encoded_har),
            *scan_text_content("evidence/malformed.har", malformed_har),
            *scan_text_content(
                "evidence/malformed-base64.har", malformed_base64_har
            ),
        ]
        rendered = "\n".join(errors)

        self.assertEqual(1, rendered.count("JSON_SECRET_SCALAR"), errors)
        self.assertEqual(2, rendered.count("INVALID_JSON"), errors)
        self.assertNotIn(response_value, rendered)
        self.assertNotIn(malformed_value, rendered)

    def test_malformed_har_entries_carriers_fail_closed_with_shared_budget(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-malformed-har-entries-value"
        encoded_body = base64.b64encode(
            json.dumps({password_key: hidden_value}).encode("utf-8")
        ).decode("ascii")
        malformed_entries = {
            "mapping": {
                "unexpected": {
                    "request": {
                        "postData": {
                            "mimeType": "application/json",
                            "encoding": "base64",
                            "text": encoded_body,
                        }
                    }
                }
            },
            "scalar": encoded_body,
            "null": None,
        }

        for name, entries in malformed_entries.items():
            with self.subTest(name=name):
                content = json.dumps({"log": {"entries": entries}})
                errors = scan_text_content("evidence/request.har", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_JSON", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

        oversized_mapping = {
            "log": {
                "entries": {
                    str(index): {"value": index}
                    for index in range(10)
                }
            }
        }
        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 8
        ):
            errors = scan_text_content(
                "evidence/oversized.har",
                json.dumps(oversized_mapping),
            )

        self.assertTrue(
            any("exceeds structural limits" in error for error in errors),
            errors,
        )

    def test_malformed_har_body_carriers_fail_closed_before_context_is_lost(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-malformed-har-body-value"
        encoded_body = base64.b64encode(
            json.dumps({password_key: hidden_value}).encode("utf-8")
        ).decode("ascii")
        carriers = (
            {
                "request": {
                    "postData": [
                        {
                            "mimeType": "application/json",
                            "encoding": "base64",
                            "text": encoded_body,
                        }
                    ]
                }
            },
            {
                "response": {
                    "content": [
                        {
                            "mimeType": "application/json",
                            "encoding": "base64",
                            "text": encoded_body,
                        }
                    ]
                }
            },
        )

        for index, entry in enumerate(carriers):
            with self.subTest(index=index):
                content = json.dumps({"log": {"entries": [entry]}})
                errors = scan_text_content("evidence/body.har", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_JSON", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

        body = {
            "mimeType": "application/json",
            "encoding": "base64",
            "text": encoded_body,
        }
        intermediate_carriers = (
            {"log": {"entries": [[{"request": {"postData": body}}]]}},
            {
                "log": {
                    "entries": [{"request": [{"postData": body}]}]
                }
            },
            {
                "log": {
                    "entries": [{"response": [{"content": body}]}]
                }
            },
        )
        for index, document in enumerate(intermediate_carriers):
            with self.subTest(intermediate=index):
                errors = scan_text_content(
                    "evidence/intermediate.har",
                    json.dumps(document),
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_JSON", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

    def test_har_body_json_is_not_reinterpreted_as_har_metadata(self) -> None:
        business_payload = {
            "postData": {
                "mimeType": "application/json",
                "text": "ordinary prose",
            }
        }
        har_content = json.dumps(
            {
                "log": {
                    "entries": [
                        {
                            "request": {
                                "postData": {
                                    "mimeType": "application/json",
                                    "text": json.dumps(business_payload),
                                }
                            }
                        }
                    ]
                }
            }
        )

        self.assertEqual(
            [], scan_text_content("evidence/request.har", har_content)
        )

    def test_har_extension_fields_are_not_treated_as_standard_body_paths(
        self,
    ) -> None:
        har_content = json.dumps(
            {
                "log": {
                    "entries": [],
                    "_extension": {
                        "response": {
                            "content": {
                                "mimeType": "application/json",
                                "text": "ordinary prose",
                            }
                        },
                        "postData": {
                            "mimeType": "application/json",
                            "text": "ordinary prose",
                        },
                    },
                }
            }
        )

        self.assertEqual([], scan_text_content("evidence/request.har", har_content))

    def test_recursive_json_policy_errors_and_declared_formats_fail_closed(
        self,
    ) -> None:
        password_key = "dbPass" + "word"
        hidden_value = "synthetic-policy-hidden-value"
        invalid_number = (
            '{"' + password_key + '":"' + hidden_value + '","n":1e9999}'
        )
        recursive_content = json.dumps({"payload": invalid_number})
        invalid_constant = json.dumps(
            {
                "payload": (
                    '{"' + password_key + '":"' + hidden_value + '","n":NaN}'
                )
            }
        )

        def request_har(mime_type: str, text: str, encoding: str | None = None) -> str:
            body = {"mimeType": mime_type, "text": text}
            if encoding is not None:
                body["encoding"] = encoding
            return json.dumps(
                {
                    "log": {
                        "entries": [{"request": {"postData": body}}]
                    }
                }
            )

        cases = (
            recursive_content,
            invalid_constant,
            request_har("model/gltf+json", invalid_number),
            request_har("application/json", "\u00a0{}\u00a0"),
            request_har("application/json", "e3\u00a00=", "base64"),
        )
        labels = (
            "docs/recursive.json",
            "docs/constant.json",
            "evidence/model.har",
            "evidence/whitespace.har",
            "evidence/base64.har",
        )
        errors = [
            error
            for label, content in zip(labels, cases, strict=True)
            for error in scan_text_content(label, content)
        ]
        rendered = "\n".join(errors)

        self.assertEqual(5, rendered.count("INVALID_JSON"), errors)
        self.assertNotIn(hidden_value, rendered)

    def test_nonstandard_json_constant_prefix_in_prose_is_not_forced_to_json(
        self,
    ) -> None:
        contents = (
            json.dumps({"documentation": '{"status":NaNcy}'}),
            json.dumps({"documentation": 'example {"count":1١} trailing'}),
        )

        for index, content in enumerate(contents):
            with self.subTest(index=index):
                self.assertEqual(
                    [], scan_text_content("docs/metadata.json", content)
                )

    def test_json_string_scans_complete_objects_surrounded_by_prose(self) -> None:
        password_key = "dbPass" + "word"
        hidden_value = "synthetic-surrounded-json-value"
        content = json.dumps(
            {
                "payload": "prefix {\""
                + password_key
                + "\":\""
                + hidden_value
                + "\"} trailing"
            }
        )

        errors = scan_text_content("docs/envelope.json", content)
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_non_har_mime_metadata_does_not_force_plain_text_to_json(self) -> None:
        metadata = json.dumps(
            {
                "mimeType": "application/json",
                "text": "JSON is generated by the runtime",
            }
        )

        self.assertEqual([], scan_text_content("docs/metadata.json", metadata))

    def test_invalid_json_shaped_string_does_not_consume_recursive_limits(
        self,
    ) -> None:
        invalid_candidate = "{" * 129 + "plain text" + "}" * 129
        content = json.dumps({"documentation": invalid_candidate})

        self.assertEqual([], scan_text_content("docs/metadata.json", content))

    def test_json_schema_sensitive_field_names_are_not_credentials(self) -> None:
        password_key = "databasePass" + "word"
        user_key = "userPass" + "word"
        schemas = (
            {"name": password_key, "type": "string"},
            {"properties": {user_key: {"type": "string"}}},
            {"$defs": {password_key: {"type": "string"}}},
            {
                "components": {
                    "schemas": {user_key: {"type": "object"}}
                }
            },
            {
                "properties": {
                    password_key: {
                        "description": "credential metadata only",
                        "minLength": 12,
                        "readOnly": True,
                    }
                }
            },
            {"properties": {user_key: True}},
        )

        for index, schema in enumerate(schemas):
            with self.subTest(index=index):
                self.assertEqual(
                    [],
                    scan_text_content(
                        f"docs/schema-{index}.json", json.dumps(schema)
                    ),
                )

    def test_nested_non_secret_schema_defaults_are_not_credentials(self) -> None:
        password_key = "databasePass" + "word"
        schemas = (
            json.dumps(
                {
                    "properties": {
                        password_key: {
                            "type": "object",
                            "properties": {
                                "timeout": {"type": "integer", "default": 30}
                            },
                        }
                    }
                }
            ),
            (
                "properties:\n"
                f"  {password_key}:\n"
                "    type: object\n"
                "    properties:\n"
                "      timeout:\n"
                "        type: integer\n"
                "        default: 30\n"
            ),
        )

        self.assertEqual([], scan_text_content("docs/schema.json", schemas[0]))
        self.assertEqual([], scan_text_content("docs/schema.yml", schemas[1]))

    def test_nested_sensitive_json_schema_defaults_are_credentials(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-nested-schema-default"
        schema = {
            "properties": {
                password_key: {
                    "type": "object",
                    "properties": {
                        password_key: {
                            "type": "string",
                            "default": hidden_value,
                        }
                    },
                }
            }
        }

        errors = scan_text_content("docs/schema.json", json.dumps(schema))
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

    def test_malformed_stringified_json_cannot_hide_a_secret_assignment(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-malformed-embedded-value"
        malformed_values = (
            '{"' + password_key + '":"' + hidden_value + '", broken}',
            '{"db\\u0050assword":"' + hidden_value + '", broken}',
        )

        for index, malformed in enumerate(malformed_values):
            with self.subTest(index=index):
                content = json.dumps({"payload": malformed})
                errors = scan_text_content("docs/envelope.json", content)
                rendered = "\n".join(errors)

                self.assertIn("JSON_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_sensitive_json_containers_propagate_to_nested_scalars(self) -> None:
        keys = (
            "databasePass" + "word",
            "userPass" + "word",
            "jwtTo" + "ken",
        )
        values = tuple(f"synthetic-nested-sensitive-{index}" for index in range(3))
        content = json.dumps(
            {
                keys[0]: {"value": values[0]},
                keys[1]: [values[1]],
                keys[2]: {"current": values[2]},
            }
        )

        errors = scan_text_content("docs/config.json", content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("JSON_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_sensitive_json_schema_value_keywords_are_scanned(self) -> None:
        keys = (
            "databasePass" + "word",
            "jwtTo" + "ken",
            "userPass" + "word",
        )
        values = tuple(f"synthetic-schema-secret-{index}" for index in range(3))
        schema = {
            "properties": {
                keys[0]: {"type": "string", "default": values[0]},
                keys[1]: {"type": "string", "const": values[1]},
                keys[2]: {"type": "string", "examples": [values[2]]},
            }
        }

        errors = scan_text_content("docs/schema.json", json.dumps(schema))
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("JSON_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_sensitive_json_schema_local_pointer_refs_are_scanned(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-local-ref-default"
        schema = {
            "$defs": {
                "Credential/Schema": {
                    "type": "string",
                    "default": hidden_value,
                }
            },
            "properties": {
                password_key: {"$ref": "#/$defs/Credential~1Schema"}
            },
        }

        errors = scan_text_content("docs/schema.json", json.dumps(schema))
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_sensitive_json_schema_refs_fail_closed_when_not_safely_resolvable(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        cases = (
            {
                "properties": {
                    password_key: {
                        "$ref": "external.json#/$defs/Credential"
                    }
                }
            },
            {
                "properties": {
                    password_key: {"$ref": "#/$defs/Missing"}
                }
            },
            {
                "$defs": {
                    "A": {"$ref": "#/$defs/B"},
                    "B": {"$ref": "#/$defs/A"},
                },
                "properties": {password_key: {"$ref": "#/$defs/A"}},
            },
            {
                "$defs": {
                    "Credential": {
                        "type": "string",
                        "default": "masked",
                    }
                },
                "properties": {
                    password_key: {
                        "$id": "nested-schema.json",
                        "$defs": {
                            "Credential": {
                                "type": "string",
                                "default": "synthetic-nested-resource-value",
                            }
                        },
                        "$ref": "#/$defs/Credential",
                    }
                },
            },
        )

        for index, schema in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/ref-{index}.json", json.dumps(schema)
                )

                self.assertTrue(
                    any("INVALID_JSON" in error for error in errors),
                    errors,
                )

    def test_sensitive_json_schema_ref_beneath_ancestor_id_fails_closed(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-ancestor-resource-default"
        schema = {
            "$defs": {
                "Credential": {
                    "type": "string",
                    "default": "masked",
                },
                "NestedResource": {
                    "$id": "nested-resource.json",
                    "$defs": {
                        "Credential": {
                            "type": "string",
                            "default": hidden_value,
                        }
                    },
                    "properties": {
                        password_key: {
                            "$ref": "#/$defs/Credential",
                        }
                    },
                },
            }
        }

        errors = scan_text_content("docs/schema.json", json.dumps(schema))
        rendered = "\n".join(errors)

        self.assertIn("INVALID_JSON", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

    def test_sensitive_json_dynamic_schema_references_fail_closed(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-dynamic-schema-default"
        cases = (
            {
                "$defs": {
                    "Credential": {
                        "$dynamicAnchor": "credential",
                        "type": "string",
                        "default": hidden_value,
                    }
                },
                "properties": {
                    password_key: {
                        "$dynamicRef": "#credential",
                    }
                },
            },
            {
                "properties": {
                    password_key: {
                        "$dynamicAnchor": "credential",
                        "type": "string",
                        "default": hidden_value,
                    }
                }
            },
        )

        for index, schema in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/dynamic-ref-{index}.json",
                    json.dumps(schema),
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_JSON", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

    def test_sensitive_json_schema_ref_resolution_uses_structural_budget(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        schema = {
            "$defs": {"Credential": {"type": "string"}},
            "properties": {
                password_key: {"$ref": "#/$defs/Credential"}
            },
        }
        document = sensitive_artifacts._parse_json_source(json.dumps(schema))
        budget = sensitive_artifacts.StructuredScanBudget()

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 1
        ):
            errors = sensitive_artifacts._scan_loaded_json(
                "docs/schema.json",
                document,
                budget,
                ".json",
            )

        self.assertTrue(
            any("INVALID_JSON" in error for error in errors),
            errors,
        )

    def test_sensitive_json_schema_ref_fanout_is_memoized(self) -> None:
        class CountingJsonObject(sensitive_artifacts.JsonObject):
            visits = 0

            def __iter__(self):  # type: ignore[no-untyped-def]
                type(self).visits += 1
                return super().__iter__()

        fanout = 64
        target_depth = 64
        shared_target: sensitive_artifacts.JsonObject = CountingJsonObject(
            [("type", "string")]
        )
        for _ in range(target_depth - 1):
            shared_target = CountingJsonObject(
                [("allOf", [shared_target])]
            )
        document = sensitive_artifacts.JsonObject(
            [
                (
                    "$defs",
                    sensitive_artifacts.JsonObject(
                        [("Shared", shared_target)]
                    ),
                ),
                (
                    "properties",
                    sensitive_artifacts.JsonObject(
                        [
                            (
                                f"scope{index}.token",
                                sensitive_artifacts.JsonObject(
                                    [("$ref", "#/$defs/Shared")]
                                ),
                            )
                            for index in range(fanout)
                        ]
                    ),
                ),
            ]
        )

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_DEPTH",
            256,
        ):
            errors = sensitive_artifacts._scan_loaded_json(
                "docs/schema.json",
                document,
                sensitive_artifacts.StructuredScanBudget(),
                ".json",
            )

        self.assertEqual([], errors)
        self.assertLessEqual(
            CountingJsonObject.visits,
            8 * (fanout + target_depth),
        )

    def test_schema_applicator_shapes_cannot_exempt_sensitive_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        malformed_carriers = {
            "allOf": {"arbitrary": "synthetic-all-of-value"},
            "oneOf": "synthetic-one-of-value",
            "anyOf": {"arbitrary": "synthetic-any-of-value"},
            "items": "synthetic-items-value",
            "prefixItems": {"arbitrary": "synthetic-prefix-items-value"},
            "not": {"arbitrary": "synthetic-not-value"},
            "dependentSchemas": ["synthetic-dependent-schemas-value"],
            "patternProperties": ["synthetic-pattern-properties-value"],
        }

        for suffix, finding in (
            ("json", "JSON_SECRET_SCALAR"),
            ("yml", "YAML_SECRET_SCALAR"),
        ):
            for carrier, malformed in malformed_carriers.items():
                with self.subTest(suffix=suffix, carrier=carrier):
                    schema = {
                        "properties": {
                            password_key: {
                                carrier: malformed,
                            }
                        }
                    }
                    errors = scan_text_content(
                        f"docs/schema.{suffix}",
                        json.dumps(schema)
                        if suffix == "json"
                        else sensitive_artifacts.yaml.safe_dump(
                            schema,
                            sort_keys=False,
                        ),
                    )
                    rendered = "\n".join(errors)

                    self.assertIn(finding, rendered, errors)
                    self.assertNotIn("synthetic-", rendered)

        valid_schema = {
            "properties": {
                password_key: {
                    "allOf": [{"type": "string"}],
                    "oneOf": [True, {"type": "string"}],
                    "anyOf": [False, {"type": "string"}],
                    "items": {"type": "string"},
                    "prefixItems": [{"type": "string"}, True],
                    "not": {"type": "null"},
                    "dependentSchemas": {
                        "mode": {"type": "object"},
                    },
                    "patternProperties": {
                        ".*": {"type": "string"},
                    },
                }
            }
        }
        for suffix in ("json", "yml"):
            with self.subTest(suffix=suffix, carrier="valid"):
                self.assertEqual(
                    [],
                    scan_text_content(
                        f"docs/valid-schema.{suffix}",
                        json.dumps(valid_schema)
                        if suffix == "json"
                        else sensitive_artifacts.yaml.safe_dump(
                            valid_schema,
                            sort_keys=False,
                        ),
                    ),
                )

    def test_malformed_json_schema_collections_do_not_exempt_sensitive_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        collection_keys = ("enum", "examples")

        for collection_key in collection_keys:
            with self.subTest(collection_key=collection_key):
                hidden_value = f"synthetic-{collection_key}-mapping-value"
                schema = {
                    "properties": {
                        password_key: {
                            "type": "string",
                            collection_key: {"named": hidden_value},
                        }
                    }
                }
                errors = scan_text_content(
                    "docs/schema.json", json.dumps(schema)
                )
                rendered = "\n".join(errors)

                self.assertIn("JSON_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_malformed_json_required_carrier_does_not_exempt_sensitive_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-malformed-required-value"
        malformed_schema = {
            "properties": {
                password_key: {
                    "required": hidden_value,
                }
            }
        }

        errors = scan_text_content(
            "docs/schema.json",
            json.dumps(malformed_schema),
        )
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

        valid_schema = {
            "properties": {
                password_key: {
                    "type": "object",
                    "required": ["credentialAlias"],
                }
            }
        }
        self.assertEqual(
            [],
            scan_text_content("docs/valid-schema.json", json.dumps(valid_schema)),
        )

    def test_sensitive_json_schema_vendor_value_extensions_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-vendor-example",
            "synthetic-vendor-default",
            "synthetic-vendor-namespaced-value",
        )
        schema = {
            "properties": {
                password_key: {
                    "type": "string",
                    "x-example": values[0],
                    "x-default": values[1],
                    "x-provider-value": values[2],
                    "x-description": "credential metadata only",
                }
            }
        }

        errors = scan_text_content("docs/schema.json", json.dumps(schema))
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("JSON_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_sensitive_structured_mapping_keys_cannot_hide_values_behind_null(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_keys = (
            "synthetic-enum-object-key",
            "synthetic-examples-object-key",
            "synthetic-default-object-key",
            "synthetic-vendor-object-key",
        )
        schema = {
            "properties": {
                password_key: {
                    "type": "string",
                    "enum": [{hidden_keys[0]: None}],
                    "examples": [{hidden_keys[1]: None}],
                    "default": {hidden_keys[2]: None},
                    "x-provider-value": {hidden_keys[3]: None},
                }
            }
        }
        yaml_content = (
            "properties:\n"
            f"  {password_key}:\n"
            "    type: string\n"
            "    enum:\n"
            f"      - {hidden_keys[0]}: null\n"
            "    examples:\n"
            f"      - {hidden_keys[1]}: null\n"
            "    default:\n"
            f"      {hidden_keys[2]}: null\n"
            "    x-provider-value:\n"
            f"      {hidden_keys[3]}: null\n"
        )

        json_errors = scan_text_content("docs/schema.json", json.dumps(schema))
        yaml_errors = scan_text_content("docs/schema.yml", yaml_content)
        rendered = "\n".join((*json_errors, *yaml_errors))

        self.assertEqual(
            4,
            sum("JSON_SECRET_SCALAR" in error for error in json_errors),
            json_errors,
        )
        self.assertEqual(
            4,
            sum("YAML_SECRET_SCALAR" in error for error in yaml_errors),
            yaml_errors,
        )
        for hidden_key in hidden_keys:
            self.assertNotIn(hidden_key, rendered)

    def test_vendor_carrier_shapes_keep_descriptor_context_at_real_entries(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-vendor-mapping-value",
            "synthetic-vendor-list-value",
            "synthetic-vendor-scalar-value",
        )
        descriptor = {
            "name": password_key,
            "x-provider-examples": {
                "named": {
                    "summary": "Human readable example",
                    "value": values[0],
                }
            },
            "x-provider-value": [values[1]],
            "x-example": values[2],
        }
        json_payload = json.dumps({"parameters": [descriptor]})
        yaml_payload = (
            "parameters:\n"
            f"  - name: {password_key}\n"
            "    x-provider-examples:\n"
            "      named:\n"
            "        summary: Human readable example\n"
            f"        value: {values[0]}\n"
            "    x-provider-value:\n"
            f"      - {values[1]}\n"
            f"    x-example: {values[2]}\n"
        )
        har_payload = json.dumps(
            {
                "log": {
                    "entries": [
                        {
                            "request": {
                                "postData": {
                                    "mimeType": "application/json",
                                    "text": json_payload,
                                }
                            }
                        }
                    ]
                }
            }
        )
        xml_payload = f"<root><payload>{json_payload}</payload></root>"
        cases = (
            ("json", "docs/openapi.json", json_payload, "JSON_SECRET_SCALAR"),
            ("yaml", "docs/openapi.yml", yaml_payload, "YAML_SECRET_SCALAR"),
            ("har", "evidence/request.har", har_payload, "JSON_SECRET_SCALAR"),
            ("xml", "docs/embedded.xml", xml_payload, "XML_SECRET_SCALAR"),
        )

        for name, label, content, finding in cases:
            with self.subTest(name=name):
                errors = scan_text_content(label, content)
                rendered = "\n".join(errors)

                self.assertEqual(3, rendered.count(finding), errors)
                for value in values:
                    self.assertNotIn(value, rendered)

    def test_vendor_mapping_metadata_and_schema_property_names_remain_safe(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        content = {
            "properties": {
                password_key: {
                    "type": "object",
                    "properties": {
                        "credentialAlias": {"type": "string"},
                    },
                }
            },
            "parameters": [
                {
                    "name": password_key,
                    "x-provider-examples": {
                        "masked": {
                            "summary": "Human readable example",
                            "value": "masked",
                        }
                    },
                }
            ],
        }

        self.assertEqual(
            [],
            scan_text_content("docs/openapi.json", json.dumps(content)),
        )

    def test_schema_like_uppercase_keys_and_nested_descriptors_do_not_hide_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-uppercase-shape",
            "synthetic-parameter-default",
            "synthetic-business-type-value",
        )
        content = json.dumps(
            {
                "properties": {password_key: {"TYPE": values[0]}},
                "parameters": [
                    {
                        "name": password_key,
                        "schema": {"type": "string", "default": values[1]},
                    }
                ],
                "configuration": {
                    password_key: {"type": values[2]},
                },
            }
        )

        errors = scan_text_content("docs/schema.json", content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("JSON_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_descriptor_content_and_schema_maps_scan_only_actual_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-content-default"
        content = json.dumps(
            {
                "parameters": [
                    {
                        "name": password_key,
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "string",
                                    "default": hidden_value,
                                }
                            }
                        },
                    },
                    {
                        "name": password_key,
                        "examples": {
                            "masked": {
                                "summary": "Human readable example",
                                "value": "masked",
                            }
                        },
                    },
                ],
                "properties": {
                    password_key: {
                        "type": "object",
                        "dependencies": {"mode": ["timeout"]},
                        "description": "metadata only",
                    }
                },
            }
        )

        errors = scan_text_content("docs/openapi.json", content)
        rendered = "\n".join(errors)

        self.assertEqual(1, rendered.count("JSON_SECRET_SCALAR"), errors)
        self.assertNotIn(hidden_value, rendered)

    def test_sensitive_json_named_maps_preserve_context_to_nested_refs(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-named-map-ref-default"
        target = {"type": "string", "default": hidden_value}

        for carrier in ("properties", "schemas", "definitions", "$defs"):
            with self.subTest(carrier=carrier):
                document = {
                    "$defs": {"Target": target},
                    "parameters": [
                        {
                            "name": password_key,
                            "schema": {
                                "type": "object",
                                carrier: {
                                    "value": {"$ref": "#/$defs/Target"}
                                },
                            },
                        }
                    ],
                }
                errors = scan_text_content(
                    "docs/openapi.json",
                    json.dumps(document),
                )
                rendered = "\n".join(errors)

                self.assertIn("JSON_SECRET_SCALAR", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

        vendor_examples = {
            "$defs": {"Target": target},
            "parameters": [
                {
                    "name": password_key,
                    "x-provider-examples": {
                        "properties": {
                            "value": {"$ref": "#/$defs/Target"}
                        }
                    },
                }
            ],
        }
        errors = scan_text_content(
            "docs/openapi.json",
            json.dumps(vendor_examples),
        )
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

    def test_json_schema_map_member_names_are_not_value_keywords(self) -> None:
        password_key = "databasePass" + "word"

        for carrier in ("patternProperties", "dependentSchemas"):
            with self.subTest(carrier=carrier):
                schema = {
                    "properties": {
                        password_key: {
                            carrier: {
                                "default": {"type": "string"},
                            }
                        }
                    }
                }

                self.assertEqual(
                    [],
                    scan_text_content(
                        "docs/schema.json",
                        json.dumps(schema),
                    ),
                )

        for carrier in ("patternProperties", "dependentSchemas"):
            with self.subTest(carrier=carrier, member="$id"):
                schema = {
                    "$defs": {"Target": {"type": "string"}},
                    "properties": {
                        password_key: {
                            carrier: {
                                "$id": {"$ref": "#/$defs/Target"},
                            }
                        }
                    },
                }

                self.assertEqual(
                    [],
                    scan_text_content(
                        "docs/schema.json",
                        json.dumps(schema),
                    ),
                )

    def test_sensitive_business_properties_maps_are_not_schema_exempt(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-business-properties-value"
        content = json.dumps(
            {password_key: {"properties": {"value": hidden_value}}}
        )

        errors = scan_text_content("docs/config.json", content)
        rendered = "\n".join(errors)

        self.assertIn("JSON_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_business_properties_objects_do_not_disable_json_secret_scanning(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = ("synthetic-properties-scalar", "synthetic-properties-nested")
        content = json.dumps(
            {
                "properties": {
                    password_key: values[0],
                    "nested": {password_key: {"value": values[1]}},
                }
            },
            indent=2,
        )

        errors = scan_text_content("docs/business.json", content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("JSON_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_yaml_schema_sensitive_field_names_are_not_credentials(self) -> None:
        user_key = "userPass" + "word"
        schema = f"properties:\n  {user_key}:\n    type: string\n"

        self.assertEqual([], scan_text_content("docs/schema.yml", schema))
        self.assertEqual(
            [],
            scan_text_content(
                "docs/boolean-schema.yml",
                f"properties:\n  {user_key}: true\n",
            ),
        )

    def test_yaml_boolean_schema_requires_valid_standard_tag_direct_and_ref(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        invalid_boolean_values = {
            "invalid-standard": "!!bool synthetic-invalid-boolean",
            "custom-tag": (
                "!<tag:example.invalid,2026:bool> "
                "synthetic-custom-boolean"
            ),
        }

        for name, tagged_value in invalid_boolean_values.items():
            with self.subTest(name=name, carrier="direct"):
                content = (
                    "properties:\n"
                    f"  {password_key}:\n"
                    f"    {tagged_value}\n"
                )
                errors = scan_text_content("docs/schema.yml", content)
                rendered = "\n".join(errors)

                self.assertIn("YAML_SECRET_SCALAR", rendered, errors)
                self.assertNotIn("synthetic-", rendered)

            with self.subTest(name=name, carrier="local-ref"):
                content = (
                    "$defs:\n"
                    f"  Target: {tagged_value}\n"
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    $ref: '#/$defs/Target'\n"
                )
                errors = scan_text_content("docs/schema.yml", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_YAML", rendered, errors)
                self.assertNotIn("synthetic-", rendered)

        for valid_value in ("false", "!!bool TRUE"):
            with self.subTest(valid_value=valid_value):
                content = (
                    "properties:\n"
                    f"  {password_key}:\n"
                    f"    {valid_value}\n"
                )
                self.assertEqual(
                    [],
                    scan_text_content("docs/valid-schema.yml", content),
                )

    def test_sensitive_yaml_schema_local_pointer_refs_are_scanned(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-yaml-local-ref-default"
        schema = (
            "$defs:\n"
            "  Credential/Schema:\n"
            "    type: string\n"
            f"    default: {hidden_value}\n"
            "properties:\n"
            f"  {password_key}:\n"
            "    $ref: '#/$defs/Credential~1Schema'\n"
        )

        errors = scan_text_content("docs/schema.yml", schema)
        rendered = "\n".join(errors)

        self.assertIn("YAML_SECRET_SCALAR", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

    def test_sensitive_yaml_schema_refs_fail_closed_when_not_safely_resolvable(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-yaml-unresolved-ref-default"
        cases = (
            (
                "external",
                (
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    $ref: 'external.yml#/$defs/Credential'\n"
                    f"    default: {hidden_value}\n"
                ),
            ),
            (
                "missing",
                (
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    $ref: '#/$defs/Missing'\n"
                    f"    default: {hidden_value}\n"
                ),
            ),
            (
                "anchor",
                (
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    $ref: '#credential'\n"
                    f"    default: {hidden_value}\n"
                ),
            ),
            (
                "cycle",
                (
                    "$defs:\n"
                    "  A:\n"
                    "    $ref: '#/$defs/B'\n"
                    "  B:\n"
                    "    $ref: '#/$defs/A'\n"
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    $ref: '#/$defs/A'\n"
                ),
            ),
            (
                "nested-id",
                (
                    "$defs:\n"
                    "  Credential:\n"
                    "    type: string\n"
                    "    default: masked\n"
                    "  NestedResource:\n"
                    "    $id: nested-resource.yml\n"
                    "    $defs:\n"
                    "      Credential:\n"
                    "        type: string\n"
                    f"        default: {hidden_value}\n"
                    "    properties:\n"
                    f"      {password_key}:\n"
                    "        $ref: '#/$defs/Credential'\n"
                ),
            ),
        )

        for name, schema in cases:
            with self.subTest(name=name):
                errors = scan_text_content(
                    f"docs/yaml-ref-{name}.yml",
                    schema,
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_YAML", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

    def test_yaml_sensitive_containers_and_schema_values_are_scanned(self) -> None:
        password_key = "databasePass" + "word"
        token_key = "jwtTo" + "ken"
        values = tuple(f"synthetic-yaml-nested-{index}" for index in range(4))
        content = (
            f"{password_key}:\n  value: {values[0]}\n"
            f"{token_key}:\n  - {values[1]}\n"
            "properties:\n"
            f"  {password_key}:\n"
            "    type: string\n"
            f"    default: {values[2]}\n"
            f"  {token_key}:\n"
            "    type: string\n"
            "    examples:\n"
            f"      - {values[3]}\n"
        )

        errors = scan_text_content("docs/config.yml", content)
        rendered = "\n".join(errors)

        self.assertEqual(4, rendered.count("YAML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_yaml_schema_like_uppercase_keys_and_nested_descriptors_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-yaml-uppercase",
            "synthetic-yaml-default",
            "synthetic-yaml-business-type",
        )
        content = (
            "properties:\n"
            f"  {password_key}:\n"
            f"    TYPE: {values[0]}\n"
            "parameters:\n"
            f"  - name: {password_key}\n"
            "    schema:\n"
            "      type: string\n"
            f"      default: {values[1]}\n"
            "configuration:\n"
            f"  {password_key}:\n"
            f"    type: {values[2]}\n"
        )

        errors = scan_text_content("docs/schema.yml", content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("YAML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_yaml_merge_descriptors_and_schema_metadata_are_disambiguated(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_values = (
            "synthetic-yaml-merged-default",
            "synthetic-yaml-content-default",
        )
        content = (
            "parameters:\n"
            f"  - name: {password_key}\n"
            f"    <<: {{default: {hidden_values[0]}}}\n"
            f"  - name: {password_key}\n"
            "    content:\n"
            "      application/json:\n"
            "        schema:\n"
            "          type: string\n"
            f"          default: {hidden_values[1]}\n"
            f"  - name: {password_key}\n"
            "    examples:\n"
            "      masked:\n"
            "        summary: Human readable example\n"
            "        value: masked\n"
            "properties:\n"
            f"  {password_key}:\n"
            "    type: string\n"
            "    <<: {description: metadata only}\n"
        )

        errors = scan_text_content("docs/openapi.yml", content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("YAML_SECRET_SCALAR"), errors)
        for hidden_value in hidden_values:
            self.assertNotIn(hidden_value, rendered)

    def test_yaml_merged_sensitive_descriptors_propagate_to_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-inherited-descriptor",
            "synthetic-sequence-descriptor",
        )
        content = (
            "sensitive: &sensitive\n"
            f"  name: {password_key}\n"
            "safe: &safe\n"
            "  name: timeout\n"
            "parameters:\n"
            "  - <<: *sensitive\n"
            f"    value: {values[0]}\n"
            "  - <<: [*sensitive, *safe]\n"
            f"    value: {values[1]}\n"
        )

        errors = scan_text_content("docs/openapi.yml", content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("YAML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

        overridden = (
            "sensitive: &sensitive\n"
            f"  name: {password_key}\n"
            "parameter:\n"
            "  <<: *sensitive\n"
            "  name: timeout\n"
            "  value: 30\n"
        )
        self.assertEqual(
            [],
            scan_text_content("docs/overridden.yml", overridden),
        )

        literal_merge_key = (
            "sensitive: &sensitive\n"
            f"  name: {password_key}\n"
            "parameter:\n"
            '  "<<": *sensitive\n'
            "  value: 30\n"
        )
        self.assertEqual(
            [],
            scan_text_content("docs/literal-merge-key.yml", literal_merge_key),
        )

    def test_yaml_merged_business_maps_are_not_schema_exempt(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-merged-business-value"
        content = (
            "business: &business\n"
            f"  arbitrary: {hidden_value}\n"
            f"{password_key}:\n"
            "  <<: *business\n"
        )

        errors = scan_text_content("docs/config.yml", content)
        rendered = "\n".join(errors)

        self.assertIn("YAML_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_malformed_yaml_schema_collections_do_not_exempt_sensitive_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"

        for collection_key in ("enum", "examples"):
            with self.subTest(collection_key=collection_key):
                hidden_value = f"synthetic-yaml-{collection_key}-mapping"
                content = (
                    "properties:\n"
                    f"  {password_key}:\n"
                    "    type: string\n"
                    f"    {collection_key}:\n"
                    f"      named: {hidden_value}\n"
                )
                errors = scan_text_content("docs/schema.yml", content)
                rendered = "\n".join(errors)

                self.assertIn("YAML_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_sensitive_yaml_schema_vendor_value_extensions_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = (
            "synthetic-yaml-vendor-example",
            "synthetic-yaml-vendor-default",
            "synthetic-yaml-vendor-namespaced-value",
        )
        content = (
            "properties:\n"
            f"  {password_key}:\n"
            "    type: string\n"
            f"    x-example: {values[0]}\n"
            f"    x-default: {values[1]}\n"
            f"    x-provider-value: {values[2]}\n"
            "    x-description: credential metadata only\n"
        )

        errors = scan_text_content("docs/schema.yml", content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("YAML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_yaml_sensitive_business_properties_maps_are_not_schema_exempt(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-yaml-business-properties"
        content = (
            f"{password_key}:\n"
            "  properties:\n"
            f"    value: {hidden_value}\n"
        )

        errors = scan_text_content("docs/config.yml", content)
        rendered = "\n".join(errors)

        self.assertIn("YAML_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_yaml_recursive_alias_fails_closed_without_crashing(self) -> None:
        errors = scan_text_content("docs/recursive.yml", "loop: &loop [*loop]\n")

        self.assertTrue(any("INVALID_YAML" in error for error in errors), errors)

    def test_yaml_schema_alias_dag_classification_is_memoized_and_budgeted(
        self,
    ) -> None:
        content_lines = ("leaf: &level0", "  type: string")
        content = "\n".join(content_lines) + "\n"
        for level in range(1, 7):
            content += (
                f"level{level}: &level{level}\n"
                "  definitions:\n"
                f"    left: *level{level - 1}\n"
                f"    right: *level{level - 1}\n"
            )
        document = sensitive_artifacts.yaml.compose(content)
        self.assertIsInstance(document, sensitive_artifacts.MappingNode)
        candidate = next(
            value_node
            for key_node, value_node in document.value
            if isinstance(key_node, sensitive_artifacts.ScalarNode)
            and key_node.value == "level6"
        )

        budget = sensitive_artifacts.StructuredScanBudget()
        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 40
        ):
            self.assertTrue(
                sensitive_artifacts._is_yaml_schema_definition(
                    candidate,
                    budget=budget,
                )
            )
        self.assertLessEqual(budget.nodes, 40)

        limited_budget = sensitive_artifacts.StructuredScanBudget()
        with (
            mock.patch(
                "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 16
            ),
            self.assertRaises(sensitive_artifacts.StructuredScanLimit),
        ):
            sensitive_artifacts._is_yaml_schema_definition(
                candidate,
                budget=limited_budget,
            )
        self.assertEqual(17, limited_budget.nodes)

    def test_yaml_node_limit_is_checked_before_composing_the_ast(self) -> None:
        content = "values:\n" + "".join(f"  - {index}\n" for index in range(20))

        with (
            mock.patch("check_sensitive_artifacts.MAX_STRUCTURED_NODES", 8),
            mock.patch.object(
                sensitive_artifacts.yaml,
                "compose_all",
                wraps=sensitive_artifacts.yaml.compose_all,
            ) as composer,
        ):
            errors = scan_text_content("docs/large.yml", content)

        self.assertTrue(any("INVALID_YAML" in error for error in errors), errors)
        composer.assert_not_called()

    def test_recursive_json_scanning_shares_depth_node_and_byte_limits(self) -> None:
        password_key = "databasePass" + "word"
        nested_value = "synthetic-budget-value"

        deeply_encoded = json.dumps({password_key: nested_value})
        for _ in range(3):
            deeply_encoded = json.dumps({"payload": deeply_encoded})
        depth_content = json.dumps({"payload": deeply_encoded})

        node_payload = json.dumps({"values": [0, 1, 2, 3, 4]})
        node_content = json.dumps({"payload": node_payload})

        byte_payload = json.dumps({password_key: nested_value})
        byte_content = json.dumps({"payload": byte_payload})
        cumulative_byte_limit = max(
            len(byte_content.encode("utf-8")),
            len(byte_payload.encode("utf-8")),
        ) + 1

        cases = (
            ("depth", "MAX_STRUCTURED_DEPTH", 3, depth_content),
            ("nodes", "MAX_STRUCTURED_NODES", 8, node_content),
            (
                "bytes",
                "MAX_STRUCTURED_BYTES",
                cumulative_byte_limit,
                byte_content,
            ),
        )
        for name, limit_name, limit, content in cases:
            with self.subTest(name=name), mock.patch(
                f"check_sensitive_artifacts.{limit_name}", limit, create=True
            ):
                errors = scan_text_content("docs/envelope.json", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_JSON", rendered)
                self.assertNotIn(nested_value, rendered)

    def test_recursive_json_node_preflight_does_not_parse_over_limit(self) -> None:
        nested = json.dumps(list(range(20)))
        content = json.dumps({"payload": nested})

        with (
            mock.patch("check_sensitive_artifacts.MAX_STRUCTURED_NODES", 8),
            mock.patch.object(
                sensitive_artifacts,
                "_parse_json_source",
                wraps=sensitive_artifacts._parse_json_source,
            ) as parser,
        ):
            errors = scan_text_content("docs/envelope.json", content)

        self.assertTrue(any("INVALID_JSON" in error for error in errors), errors)
        self.assertEqual(1, parser.call_count)

    def test_camel_case_secret_keys_avoid_non_secret_suffix_false_positives(
        self,
    ) -> None:
        sensitive_keys = (
            "databasePass" + "word",
            "dbPass" + "word",
            "userPass" + "word",
            "secretAccess" + "Key",
            "sessionTo" + "ken",
            "jwtTo" + "ken",
        )
        safe_keys = (
            "databasePasswordHash",
            "passwordPolicy",
            "tokenEndpoint",
            "tokenCount",
            "secretName",
            "secretKeyRef",
            "accessKeyId",
            "cancellationToken",
            "jwtTokenizer",
        )
        sensitive_content = "\n".join(
            f'{key}="synthetic-camel-value-{index}"'
            for index, key in enumerate(sensitive_keys)
        )
        safe_content = "\n".join(
            f'{key}="synthetic-metadata-value-{index}"'
            for index, key in enumerate(safe_keys)
        )
        yaml_content = "\n".join(
            f'{key}: "synthetic-yaml-value-{index}"'
            for index, key in enumerate(sensitive_keys)
        )

        text_errors = scan_text_content("docs/config.txt", sensitive_content)
        yaml_errors = scan_text_content("docs/config.yml", yaml_content)

        self.assertEqual(
            len(sensitive_keys),
            sum("GENERIC_SECRET_ASSIGNMENT" in error for error in text_errors),
            text_errors,
        )
        self.assertEqual(
            len(sensitive_keys),
            sum("YAML_SECRET_SCALAR" in error for error in yaml_errors),
            yaml_errors,
        )
        self.assertEqual([], scan_text_content("docs/metadata.txt", safe_content))

    def test_xml_param_and_property_descriptors_reject_every_unsafe_value(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        token_key = "sessionTo" + "ken"
        values = tuple(f"synthetic-xml-descriptor-{index}" for index in range(5))
        xml_content = (
            "<config>"
            f"<context-param><param-name>{password_key}</param-name>"
            f"<param-value>{values[0]}</param-value></context-param>"
            f"<property><property-name>{token_key}</property-name>"
            f"<property-value>{values[1]}</property-value></property>"
            f'<entry param-name="dbPassword" param-value="{values[2]}"/>'
            f'<entry property-name="client_secret" property-value="{values[3]}"/>'
            f"<context-param><param-name>{password_key}</param-name>"
            "<param-value>${PASSWORD}</param-value>"
            f"<param-value>{values[4]}</param-value></context-param>"
            "</config>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(5, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_default_namespace_descriptor_pairs_attributes_and_children(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = tuple(f"synthetic-default-namespace-{index}" for index in range(3))
        cases = (
            (
                f'<entry xmlns="urn:x" param-name="{password_key}">'
                f"<param-value>{values[0]}</param-value></entry>"
            ),
            (
                f'<entry xmlns="urn:x" param-value="{values[1]}">'
                f"<param-name>{password_key}</param-name></entry>"
            ),
            (
                f'<entry xmlns="urn:x" name="{password_key}">'
                f"<value>{values[2]}</value></entry>"
            ),
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/default-namespace-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(values[index], rendered)

    def test_xml_sensitive_element_value_attributes_are_checked_independently(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        attribute_names = ("value", "data", "default", "text", "content")
        values = tuple(
            f"synthetic-sensitive-element-{attribute_name}"
            for attribute_name in attribute_names
        )
        xml_content = "<root>" + "".join(
            f'<{password_key} {attribute_name}="{value}">'
            f"redacted</{password_key}>"
            for attribute_name, value in zip(
                attribute_names,
                values,
                strict=True,
            )
        ) + "</root>"

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(5, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

        safe_content = (
            f'<{password_key} value="${{PASSWORD}}">'
            f"redacted</{password_key}>"
        )
        self.assertEqual(
            [],
            scan_text_content("docs/safe-config.xml", safe_content),
        )

    def test_xml_comments_cdata_and_processing_instructions_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        token_key = "sessionTo" + "ken"
        jwt_key = "jwtTo" + "ken"
        values = (
            "synthetic-comment-value",
            "synthetic-cdata-value",
            "synthetic-pi-value",
        )
        xml_content = (
            f'<?legacy {jwt_key}="{values[2]}"?>'
            "<config>"
            f"<!-- <{password_key}>{values[0]}</{password_key}> -->"
            f"<payload><![CDATA[<{token_key}>{values[1]}</{token_key}>]]></payload>"
            "</config>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_hidden_descriptors_with_prose_and_pi_targets_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = ("synthetic-hidden-pi-value", "synthetic-hidden-comment-value")
        xml_content = (
            f'<?cfg param-name="{password_key}" param-value="{values[0]}"?>'
            "<config>"
            f"<!-- note <param-name>{password_key}</param-name>"
            f"<param-value>{values[1]}</param-value> -->"
            "</config>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_hidden_descriptors_pair_expanded_namespace_names(self) -> None:
        password_key = "databasePass" + "word"
        values = tuple(f"synthetic-hidden-namespace-{index}" for index in range(4))
        cases = (
            (
                "comment-elements",
                "<root><!-- "
                f'<a:param-name xmlns:a="urn:x">{password_key}</a:param-name>'
                f'<b:param-value xmlns:b="urn:x">{values[0]}</b:param-value>'
                " --></root>",
            ),
            (
                "cdata-ancestor",
                '<root><![CDATA[<wrapper xmlns:a="urn:x" xmlns:b="urn:x">'
                f"<a:property-name>{password_key}</a:property-name>"
                f"<b:property-value>{values[1]}</b:property-value>"
                "</wrapper>]]></root>",
            ),
            (
                "pi-default",
                '<?cfg <param-name xmlns="urn:x">'
                f"{password_key}</param-name>"
                f'<b:param-value xmlns:b="urn:x">{values[2]}</b:param-value>?>'
                "<root/>",
            ),
            (
                "comment-attributes",
                '<root><!-- <entry xmlns:a="urn:x" xmlns:b="urn:x" '
                f'a:param-name="{password_key}" '
                f'b:param-value="{values[3]}"/> --></root>',
            ),
        )

        for (name, content), value in zip(cases, values, strict=True):
            with self.subTest(name=name):
                errors = scan_text_content(f"docs/{name}.xml", content)
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(value, rendered)

        unrelated = (
            "<root><![CDATA["
            f'<a:param-name xmlns:a="urn:name">{password_key}</a:param-name>'
            '<a:param-value xmlns:a="urn:value">${PASSWORD}</a:param-value>'
            "]]></root>"
        )
        self.assertEqual([], scan_text_content("docs/unrelated.xml", unrelated))

    def test_xml_hidden_xml_prefix_rebinding_fails_closed_without_echoing_values(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-illegal-xml-prefix-value"
        fragment = (
            '<xml:param-name xmlns:xml="urn:invalid">'
            f"{password_key}</xml:param-name>"
            f"<xml:param-value>{hidden_value}</xml:param-value>"
        )
        cases = (
            f"<root><!-- {fragment} --></root>",
            f"<root><![CDATA[{fragment}]]></root>",
            f"<?cfg {fragment}?><root/>",
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/illegal-xml-prefix-{index}.xml",
                    content,
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_reserved_namespace_bindings_match_expat_rules(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-reserved-namespace-value"
        xml_namespace = "http://www.w3.org/XML/1998/namespace"
        xmlns_namespace = "http://www.w3.org/2000/xmlns/"
        invalid_declarations = (
            'xmlns:xml="urn:invalid"',
            'xmlns:xmlns="urn:invalid"',
            f'xmlns:p="{xml_namespace}"',
            f'xmlns:p="{xmlns_namespace}"',
            f'xmlns="{xml_namespace}"',
            'xmlns:p=""',
        )

        for index, declaration in enumerate(invalid_declarations):
            with self.subTest(index=index):
                content = (
                    f"<root><!-- <entry {declaration} "
                    f'param-name="{password_key}" '
                    f'param-value="{hidden_value}"/> --></root>'
                )
                errors = scan_text_content(
                    f"docs/reserved-namespace-{index}.xml",
                    content,
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered)
                self.assertNotIn(hidden_value, rendered)

        legal_content = (
            "<root><!-- "
            f'<entry xmlns:xml="{xml_namespace}" '
            f'xml:param-name="{password_key}" '
            f'xml:param-value="{hidden_value}"/>'
            " --></root>"
        )
        legal_errors = scan_text_content(
            "docs/legal-xml-prefix.xml",
            legal_content,
        )
        legal_rendered = "\n".join(legal_errors)
        self.assertIn("XML_SECRET_SCALAR", legal_rendered)
        self.assertNotIn("INVALID_XML", legal_rendered)
        self.assertNotIn(hidden_value, legal_rendered)

    def test_xml_hidden_encoded_nested_descriptor_text_is_aggregated(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-encoded-nested-descriptor-value"
        fragment = (
            "&lt;param-name&gt;&lt;span&gt;"
            f"{password_key}"
            "&lt;/span&gt;&lt;/param-name&gt;"
            "&lt;param-value&gt;&lt;span&gt;"
            f"{hidden_value}"
            "&lt;/span&gt;&lt;/param-value&gt;"
        )
        cases = (
            f"<root><!-- {fragment} --></root>",
            f"<root><![CDATA[{fragment}]]></root>",
            f"<?cfg {fragment}?><root/>",
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/encoded-nested-{index}.xml",
                    content,
                )
                rendered = "\n".join(errors)

                self.assertEqual(1, rendered.count("XML_SECRET_SCALAR"), errors)
                self.assertNotIn(hidden_value, rendered)

        unrelated_namespaces = (
            "<root><!-- "
            '&lt;a:param-name xmlns:a="urn:descriptor"&gt;'
            f"&lt;span&gt;{password_key}&lt;/span&gt;"
            "&lt;/a:param-name&gt;"
            '&lt;b:param-value xmlns:b="urn:value"&gt;'
            f"&lt;span&gt;{hidden_value}&lt;/span&gt;"
            "&lt;/b:param-value&gt;"
            " --></root>"
        )
        self.assertEqual(
            [],
            scan_text_content(
                "docs/encoded-nested-unrelated-namespaces.xml",
                unrelated_namespaces,
            ),
        )

    def test_xml_encoded_hidden_attribute_quotes_do_not_terminate_tags(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-encoded-attribute-value"
        content = (
            "<root><!-- "
            "&lt;entry note=&quot;left&gt;right&quot; "
            f"param-name=&quot;{password_key}&quot; "
            f"param-value=&quot;{hidden_value}&quot;/&gt;"
            " --></root>"
        )

        errors = scan_text_content("docs/encoded-attributes.xml", content)
        rendered = "\n".join(errors)

        self.assertIn("XML_SECRET_SCALAR", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_fragments_inherit_only_live_ancestor_namespaces(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = tuple(
            f"synthetic-inherited-namespace-{index}" for index in range(4)
        )
        cases = (
            (
                "comment",
                '<root xmlns:a="urn:shared">'
                '<scope xmlns:b="urn:shared"><!-- '
                f"<a:param-name>{password_key}</a:param-name>"
                f"<b:param-value>{values[0]}</b:param-value>"
                " --></scope>"
                '<scope xmlns:b="urn:different"><!-- '
                f"<a:param-name>{password_key}</a:param-name>"
                f"<b:param-value>{values[1]}</b:param-value>"
                " --></scope>"
                "</root>",
            ),
            (
                "cdata",
                '<root xmlns:a="urn:shared">'
                '<scope xmlns:b="urn:shared"><![CDATA['
                f"<a:property-name>{password_key}</a:property-name>"
                f"<b:property-value>{values[2]}</b:property-value>"
                "]]></scope>"
                '<scope xmlns:b="urn:different"><![CDATA['
                f"<a:property-name>{password_key}</a:property-name>"
                f"<b:property-value>{values[3]}</b:property-value>"
                "]]></scope>"
                "</root>",
            ),
        )

        for name, content in cases:
            with self.subTest(name=name):
                errors = scan_text_content(
                    f"docs/inherited-{name}.xml",
                    content,
                )
                rendered = "\n".join(errors)

                self.assertEqual(1, rendered.count("XML_SECRET_SCALAR"), errors)
                for value in values:
                    self.assertNotIn(value, rendered)

    def test_xml_no_namespace_sibling_elements_are_not_namespace_wildcards(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = tuple(
            f"synthetic-no-namespace-sibling-{index}" for index in range(4)
        )
        cases = (
            (
                "live-unqualified-value",
                '<root xmlns:a="urn:descriptor">'
                f"<a:param-name>{password_key}</a:param-name>"
                f"<param-value>{values[0]}</param-value>"
                "</root>",
            ),
            (
                "live-unqualified-descriptor",
                '<root xmlns:a="urn:value">'
                f"<param-name>{password_key}</param-name>"
                f"<a:param-value>{values[1]}</a:param-value>"
                "</root>",
            ),
            (
                "comment",
                '<root xmlns:a="urn:descriptor"><!-- '
                f"<a:param-name>{password_key}</a:param-name>"
                f"<param-value>{values[2]}</param-value>"
                " --></root>",
            ),
            (
                "cdata",
                '<root xmlns:a="urn:value"><![CDATA['
                f"<param-name>{password_key}</param-name>"
                f"<a:param-value>{values[3]}</a:param-value>"
                "]]></root>",
            ),
        )

        for name, content in cases:
            with self.subTest(name=name):
                self.assertEqual(
                    [],
                    scan_text_content(
                        f"docs/no-namespace-sibling-{name}.xml",
                        content,
                    ),
                )

    def test_xml_hidden_default_namespace_pairs_unqualified_attributes(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        values = tuple(
            f"synthetic-hidden-default-namespace-{index}"
            for index in range(3)
        )
        cases = (
            (
                '<root><!-- <entry xmlns="urn:x" '
                f'param-name="{password_key}">'
                f"<param-value>{values[0]}</param-value></entry> --></root>"
            ),
            (
                '<root><!-- <entry xmlns="urn:x" '
                f'param-value="{values[1]}">'
                f"<param-name>{password_key}</param-name></entry> --></root>"
            ),
            (
                '<root><!-- <entry xmlns="urn:x" '
                f'name="{password_key}">'
                f"<value>{values[2]}</value></entry> --></root>"
            ),
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/hidden-default-{index}.xml",
                    content,
                )
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(values[index], rendered)

    def test_xml_hidden_unicode_namespace_prefixes_are_tokenized(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-unicode-namespace-value"
        fragment = (
            f'<α:param-name xmlns:α="urn:x">{password_key}</α:param-name>'
            f'<β:param-value xmlns:β="urn:x">{hidden_value}</β:param-value>'
        )
        cases = (
            f"<root><!-- {fragment} --></root>",
            f"<root><![CDATA[{fragment}]]></root>",
            f"<?cfg {fragment}?><root/>",
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/unicode-namespace-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_json_honors_escaped_keys_in_every_channel(self) -> None:
        values = (
            "synthetic-hidden-json-comment",
            "synthetic-hidden-json-cdata",
            "synthetic-hidden-json-pi",
        )
        json_bodies = tuple(
            '{"db\\u0050assword":"' + value + '"}' for value in values
        )
        xml_content = (
            f"<?cfg note: {json_bodies[2]} trailing?>"
            "<config>"
            f"<!-- note: {json_bodies[0]} trailing -->"
            f"<payload><![CDATA[note: {json_bodies[1]} trailing]]></payload>"
            "</config>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(3, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_hidden_json_policy_errors_fail_closed_in_every_channel(
        self,
    ) -> None:
        payload = '{"n":1e9999}'
        cases = (
            f"<root><!-- {payload} --></root>",
            f"<root><![CDATA[{payload}]]></root>",
            f"<?cfg {payload}?><root/>",
        )

        for index, xml_content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/policy-{index}.xml", xml_content
                )

                self.assertTrue(
                    any("INVALID_XML" in error for error in errors), errors
                )

    def test_xml_malformed_hidden_json_cannot_hide_an_escaped_secret_key(
        self,
    ) -> None:
        hidden_value = "synthetic-malformed-hidden-json"
        payload = '{"db\\u0050assword":"' + hidden_value + '", broken}'
        cases = (
            f"<root><!-- {payload} --></root>",
            f"<root><![CDATA[{payload}]]></root>",
            f"<?cfg {payload}?><root/>",
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/malformed-hidden-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_descriptor_fallbacks_cover_non_xml_channel_syntax(
        self,
    ) -> None:
        password_key = "dbPass" + "word"
        values = tuple(f"synthetic-hidden-fallback-{index}" for index in range(8))
        cases = (
            (
                "comment-prose",
                "<root><!-- R&D "
                f"<param-name>{password_key}</param-name>"
                f"<param-value>{values[0]}</param-value> --></root>",
            ),
            (
                "pi-duplicate",
                f'<?cfg param-name="{password_key}" '
                f'param-value="${{PASSWORD}}" param-value="{values[1]}"?><root/>',
            ),
            (
                "pi-prefixed",
                f'<?cfg x:param-name="{password_key}" '
                f'x:param-value="{values[2]}"?><root/>',
            ),
            (
                "pi-unquoted",
                f"<?cfg param-name={password_key} "
                f"param-value={values[3]}?><root/>",
            ),
            (
                "cdata-prefixed",
                '<root xmlns:x="urn:x"><![CDATA['
                f"<x:param-name>{password_key}</x:param-name>"
                f"<x:param-value>{values[4]}</x:param-value>"
                "]]></root>",
            ),
            (
                "comment-prefixed-sensitive",
                '<root xmlns:x="urn:x"><!-- '
                f"<x:{password_key}>{values[5]}</x:{password_key}>"
                " --></root>",
            ),
            (
                "cdata-unclosed-sensitive",
                f"<root><![CDATA[<{password_key}>{values[6]}]]></root>",
            ),
            (
                "comment-unclosed-value",
                f"<root><!-- <param-name>{password_key}</param-name>"
                f"<param-value>{values[7]} --></root>",
            ),
        )

        for (name, xml_content), value in zip(cases, values, strict=True):
            with self.subTest(name=name):
                errors = scan_text_content(f"docs/{name}.xml", xml_content)
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(value, rendered)

    def test_xml_element_text_scans_embedded_json_after_entity_decoding(self) -> None:
        values = ("synthetic-xml-text-value", "synthetic-xml-entity-value")
        xml_content = (
            "<root>"
            '<payload>{"db\\u0050assword":"' + values[0] + '"}</payload>'
            '<payload>{"db&#x50;assword":"' + values[1] + '"}</payload>'
            "</root>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_pi_target_and_multiline_hidden_assignment_are_scanned(self) -> None:
        password_key = "dbPass" + "word"
        token_key = "jwtTo" + "ken"
        values = ("synthetic-pi-target-value", "synthetic-multiline-value")
        xml_content = (
            f"<?{password_key} {values[0]}?>"
            "<config><!-- "
            f"{token_key} =\n\"{values[1]}\""
            " --></config>"
        )

        errors = scan_text_content("docs/config.xml", xml_content)
        rendered = "\n".join(errors)

        self.assertEqual(2, rendered.count("XML_SECRET_SCALAR"), errors)
        for value in values:
            self.assertNotIn(value, rendered)

    def test_xml_hidden_quoted_keys_across_lines_are_scanned(self) -> None:
        keys = (
            "databasePass" + "word",
            "sessionTo" + "ken",
            "jwtTo" + "ken",
        )
        hidden_value = "synthetic-quoted-hidden-value"
        cases = (
            f'<root><!--\n"{keys[0]}"\n:\n"{hidden_value}"\n--></root>',
            f'<root><![CDATA[\n"{keys[1]}"\n=\n"{hidden_value}"\n]]></root>',
            f'<?cfg\n"{keys[2]}"\n:\n"{hidden_value}"\n?><root/>',
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(f"docs/quoted-{index}.xml", content)
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_unclosed_assignment_is_scanned_without_echoing_value(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-unclosed-hidden-value"
        content = f'<root><!-- {password_key}="{hidden_value} --></root>'

        errors = scan_text_content("docs/config.xml", content)
        rendered = "\n".join(errors)

        self.assertIn("INVALID_XML", rendered)
        self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_unclosed_quoted_descriptor_fails_closed(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-unclosed-tail-value"
        cases = (
            (
                "comment",
                f'<root><!-- param-name={password_key} '
                f'param-value="${{PASSWORD}} {hidden_value} --></root>',
            ),
            (
                "cdata",
                f'<root><![CDATA[param-name={password_key} '
                f'param-value="${{PASSWORD}} {hidden_value}]]></root>',
            ),
            (
                "pi",
                f'<?cfg param-name={password_key} '
                f'param-value="${{PASSWORD}} {hidden_value}?><root/>',
            ),
        )

        for name, content in cases:
            with self.subTest(name=name):
                errors = scan_text_content(f"docs/{name}.xml", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_same_name_nesting_cannot_discard_outer_tail(self) -> None:
        password_key = "pass" + "word"
        hidden_value = "synthetic-outer-tail-value"
        content = (
            '<root xmlns:x="urn:x"><!-- <x:wrapper>'
            f"<{password_key}>${{PASSWORD}}"
            f"<{password_key}>${{PASSWORD}}</{password_key}>"
            f"{hidden_value}</{password_key}>"
            "</x:wrapper> --></root>"
        )

        errors = scan_text_content("docs/nested.xml", content)
        rendered = "\n".join(errors)

        self.assertTrue(
            "XML_SECRET_SCALAR" in rendered or "INVALID_XML" in rendered,
            errors,
        )
        self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_sensitive_self_closing_element_attributes_are_scanned(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        token_key = "jwtTo" + "ken"
        hidden_value = "synthetic-self-closing-attribute"
        cases = (
            f'<root><!-- <{password_key} value="{hidden_value}"/> --></root>',
            f'<root><![CDATA[<{token_key} data="{hidden_value}"/>]]></root>',
            f'<?cfg <{password_key} value="{hidden_value}"/>?><root/>',
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/self-closing-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("XML_SECRET_SCALAR", rendered)
                self.assertNotIn(hidden_value, rendered)

        safe = f'<root><!-- <{password_key} value="${{PASSWORD}}"/> --></root>'
        self.assertEqual([], scan_text_content("docs/safe.xml", safe))

    def test_xml_hidden_sensitive_ancestor_scans_descendant_value_attributes(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-descendant-attribute"
        content = (
            f"<root><!-- <{password_key}>${{PASSWORD}}"
            f'<entry value="{hidden_value}"/>'
            f"</{password_key}> --></root>"
        )

        errors = scan_text_content("docs/nested-attribute.xml", content)
        rendered = "\n".join(errors)

        self.assertIn("XML_SECRET_SCALAR", rendered, errors)
        self.assertNotIn(hidden_value, rendered)

        safe = (
            f"<root><!-- <{password_key}>${{PASSWORD}}"
            '<entry value="${PASSWORD}"/>'
            f"</{password_key}> --></root>"
        )
        self.assertEqual([], scan_text_content("docs/safe.xml", safe))

        mismatched_close = (
            f"<root><!-- <{password_key}>${{PASSWORD}}"
            f"</{password_key.upper()}>"
            f'<entry value="{hidden_value}"/>'
            f"</{password_key}> --></root>"
        )
        errors = scan_text_content("docs/mismatched-close.xml", mismatched_close)
        rendered = "\n".join(errors)
        self.assertTrue(
            "XML_SECRET_SCALAR" in rendered or "INVALID_XML" in rendered,
            errors,
        )
        self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_malformed_closers_cannot_clear_sensitive_scope(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-malformed-close-value"
        cases = (
            (
                f"<root><!-- <{password_key}>${{PASSWORD}}<ordinary>"
                f"</{password_key}>"
                f'<entry value="{hidden_value}"/></ordinary> --></root>'
            ),
            (
                f"<root><!-- <{password_key}>${{PASSWORD}}"
                f"</{password_key} bogus>"
                f'<entry value="{hidden_value}"/>'
                f"</{password_key}> --></root>"
            ),
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/malformed-close-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered, errors)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_hidden_unclosed_descriptor_name_fails_closed(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-unclosed-descriptor-tail"
        cases = (
            (
                "comment",
                f"<root><!-- <param-name>{password_key}"
                f"<param-value>{hidden_value}</param-value> --></root>",
            ),
            (
                "cdata",
                f"<root><![CDATA[<property-name>{password_key}"
                f"<property-value>{hidden_value}</property-value>]]></root>",
            ),
        )

        for name, content in cases:
            with self.subTest(name=name):
                errors = scan_text_content(f"docs/{name}.xml", content)
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered)
                self.assertNotIn(hidden_value, rendered)

    def test_xml_oversized_character_references_fail_closed_without_crashing(
        self,
    ) -> None:
        oversized_decimal = "&#" + "1" * 5_000 + ";"
        oversized_hexadecimal = "&#x" + "f" * 5_000 + ";"
        cases = (
            f"<root><!-- param-name={oversized_decimal} --></root>",
            (
                "<root><![CDATA[<param-name>"
                + oversized_hexadecimal
                + "</param-name><param-value>synthetic-value</param-value>]]></root>"
            ),
        )

        for index, content in enumerate(cases):
            with self.subTest(index=index):
                errors = scan_text_content(
                    f"docs/oversized-reference-{index}.xml", content
                )
                rendered = "\n".join(errors)

                self.assertIn("INVALID_XML", rendered)
                self.assertNotIn("1111111111", rendered)
                self.assertNotIn("ffffffffff", rendered)

    def test_xml_hidden_fragments_share_the_document_node_budget(self) -> None:
        xml_content = "<root><payload><![CDATA[<nested/>]]></payload></root>"

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 2
        ):
            errors = scan_text_content("docs/config.xml", xml_content)

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)

    def test_xml_hidden_carriers_share_the_explicit_xml_node_budget(self) -> None:
        xml_content = "<?cfg?><root><!----><![CDATA[]]></root>"

        with (
            mock.patch("check_sensitive_artifacts.MAX_STRUCTURED_NODES", 3),
            mock.patch.object(
                sensitive_artifacts.ElementTree,
                "fromstring",
                wraps=sensitive_artifacts.ElementTree.fromstring,
            ) as parser,
        ):
            errors = scan_text_content("docs/hidden-carriers.xml", xml_content)

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        parser.assert_not_called()

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 4
        ):
            self.assertEqual(
                [],
                scan_text_content("docs/hidden-carriers.xml", xml_content),
            )

    def test_xml_encoded_hidden_elements_share_the_node_budget(self) -> None:
        xml_content = (
            "<root><![CDATA[&lt;span&gt;safe&lt;/span&gt;]]></root>"
        )

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 2
        ):
            errors = scan_text_content(
                "docs/encoded-node-budget.xml",
                xml_content,
            )
        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)

        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_NODES", 3
        ):
            self.assertEqual(
                [],
                scan_text_content(
                    "docs/encoded-node-budget.xml",
                    xml_content,
                ),
            )

    def test_xml_node_limit_is_checked_before_elementtree_builds_the_ast(
        self,
    ) -> None:
        content = "<root>" + "<node/>" * 20 + "</root>"

        with (
            mock.patch("check_sensitive_artifacts.MAX_STRUCTURED_NODES", 2),
            mock.patch.object(
                sensitive_artifacts.ElementTree,
                "fromstring",
                wraps=sensitive_artifacts.ElementTree.fromstring,
            ) as parser,
        ):
            errors = scan_text_content("docs/large.xml", content)

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        parser.assert_not_called()

    def test_xml_inert_declarations_and_unrelated_descriptor_metadata_are_safe(
        self,
    ) -> None:
        password_key = "databasePass" + "word"
        cases = (
            (
                "inert-declarations",
                "<root><!-- <!DOCTYPE inert> -->"
                "<![CDATA[<!ENTITY inert>]]></root>",
            ),
            (
                "namespace-pairing",
                '<root xmlns:a="urn:a" xmlns:b="urn:b" '
                f'a:param-name="{password_key}" a:param-value="${{PASSWORD}}" '
                'b:param-name="timeout" b:param-value="30"/>'
            ),
            (
                "descriptor-metadata",
                f"<root><param-name>{password_key}</param-name>"
                "<param-value>${PASSWORD}</param-value>"
                "<description>credential setting</description></root>",
            ),
            (
                "schema-field-name",
                f'<schema><element name="{password_key}" type="string"/></schema>',
            ),
            (
                "descriptor-prose",
                f"<property><name>{password_key}</name>"
                "<value>${PASSWORD}</value>"
                "<description>说明 credential setting</description></property>",
            ),
        )

        for name, xml_content in cases:
            with self.subTest(name=name):
                self.assertEqual(
                    [], scan_text_content(f"docs/{name}.xml", xml_content)
                )

    def test_xml_hidden_tokenization_is_linear_for_unclosed_openers(self) -> None:
        xml_content = (
            "<root><![CDATA["
            + "<!--" * 16_000
            + "<param-name>" * 16_000
            + "]]></root>"
        )

        started = time.monotonic()
        errors = scan_text_content("docs/large.xml", xml_content)
        elapsed = time.monotonic() - started

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        self.assertLess(elapsed, 2.0)

    def test_xml_encoded_hidden_tag_normalization_is_linear(self) -> None:
        xml_content = (
            "<root><![CDATA["
            + "&lt;span " * 16_000
            + "&lt;param-name"
            + "]]></root>"
        )

        started = time.monotonic()
        errors = scan_text_content("docs/encoded-openers.xml", xml_content)
        elapsed = time.monotonic() - started

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        self.assertLess(elapsed, 2.0)

    def test_xml_hidden_scope_stack_honors_depth_and_cpu_limits(self) -> None:
        xml_content = (
            "<root><![CDATA["
            + "<a>" * 10_000
            + "</b>" * 10_000
            + "]]></root>"
        )

        started = time.monotonic()
        errors = scan_text_content("docs/mismatched.xml", xml_content)
        elapsed = time.monotonic() - started

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        self.assertLess(elapsed, 2.0)

    def test_secret_assignment_prefix_scanning_has_a_cpu_bound(self) -> None:
        content = "a." * 10_000

        started = time.monotonic()
        found = sensitive_artifacts._contains_secret_assignment(content)
        errors = scan_text_content("docs/linear.txt", content)
        elapsed = time.monotonic() - started

        self.assertFalse(found)
        self.assertEqual([], errors)
        self.assertLess(elapsed, 2.0)

    def test_email_candidate_scanning_scales_linearly(self) -> None:
        def scan(repetitions: int) -> float:
            started = time.monotonic()
            errors = scan_text_content("docs/linear.txt", "a." * repetitions)
            elapsed = time.monotonic() - started
            self.assertEqual([], errors)
            return elapsed

        small_elapsed = scan(20_000)
        large_elapsed = scan(40_000)

        self.assertLess(large_elapsed, 2.0)
        self.assertLess(large_elapsed, small_elapsed * 3 + 0.1)

    def test_xml_nested_unique_hidden_tags_share_extracted_byte_budget(
        self,
    ) -> None:
        tag_count = 2_000
        hidden = (
            "".join(f"<p{index}:password>" for index in range(tag_count))
            + "synthetic-value"
            + "".join(
                f"</p{index}:password>" for index in range(tag_count - 1, -1, -1)
            )
        )
        content = f"<root><![CDATA[{hidden}]]></root>"
        byte_limit = len(content.encode("utf-8")) + 20_000

        started = time.monotonic()
        with mock.patch(
            "check_sensitive_artifacts.MAX_STRUCTURED_BYTES", byte_limit
        ):
            errors = scan_text_content("docs/nested-hidden.xml", content)
        elapsed = time.monotonic() - started

        self.assertTrue(any("INVALID_XML" in error for error in errors), errors)
        self.assertLess(elapsed, 2.0)

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

    def test_xml_safe_child_descriptor_value_cannot_hide_mixed_tail(self) -> None:
        password_key = "databasePass" + "word"
        hidden_value = "synthetic-child-descriptor-tail"
        cases = (
            (
                "param",
                f"<context-param><param-name>{password_key}</param-name>"
                f"<param-value>${{PASSWORD}}</param-value>{hidden_value}"
                "</context-param>",
            ),
            (
                "property",
                f"<property><property-name>{password_key}</property-name>"
                f"<property-value>${{PASSWORD}}</property-value>{hidden_value}"
                "</property>",
            ),
        )

        for name, content in cases:
            with self.subTest(name=name):
                errors = scan_text_content(f"docs/{name}.xml", content)
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
