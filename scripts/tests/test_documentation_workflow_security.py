from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY / ".github/workflows/documentation.yml"


class DocumentationWorkflowSecurityTest(unittest.TestCase):
    def test_workflow_is_valid_yaml(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))

        self.assertIsInstance(parsed, dict)

    def test_every_repository_change_dispatches_the_documentation_workflow(
        self,
    ) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")

        self.assertNotIn("    paths:\n", content)
        self.assertIn("  pull_request: {}", content)
        self.assertIn("  push:\n    branches:\n      - main", content)

    def test_security_gates_finish_before_pr_controlled_tests_and_drift_is_checked(
        self,
    ) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        test_position = content.index("Test documentation governance")
        scanner_positions = [
            match.start()
            for match in re.finditer(
                r'python3 -I [^\n]*check_sensitive_artifacts\.py', content
            )
        ]
        artifact_positions = [
            index
            for index in range(len(content))
            if content.startswith("scripts/check_modernization_artifacts.py", index)
        ]

        self.assertEqual(2, len(scanner_positions))
        self.assertEqual(2, len(artifact_positions))
        self.assertLess(scanner_positions[0], test_position)
        self.assertLess(artifact_positions[0], test_position)
        self.assertLess(scanner_positions[1], test_position)
        self.assertLess(artifact_positions[1], test_position)
        integrity_positions = [
            match.start()
            for match in re.finditer(
                re.escape('git diff --exit-code "$checked_out_sha" --'), content
            )
        ]
        self.assertEqual(2, len(integrity_positions))
        archive_position = content.index(
            'git --no-replace-objects archive "$checked_out_sha"'
        )
        self.assertLess(archive_position, scanner_positions[1])
        self.assertLess(scanner_positions[1], test_position)
        self.assertLess(test_position, integrity_positions[-1])
        self.assertIn('checked_out_sha="$(git rev-parse --verify HEAD)"', content)
        self.assertIn('test "$checked_out_sha" = "$GITHUB_SHA"', content)
        self.assertIn('git diff --exit-code "$checked_out_sha" --', content)
        self.assertIn("git status --porcelain=v1 --untracked-files=all", content)
        self.assertIn("GIT_NO_REPLACE_OBJECTS=1", content)
        self.assertIn('git --no-replace-objects archive "$checked_out_sha"', content)
        self.assertEqual(4, content.count('--commit "$'))
        self.assertEqual(4, content.count('--repository-root "$GITHUB_WORKSPACE"'))
        self.assertEqual(2, content.count('--base-commit "$TRUSTED_POLICY_COMMIT"'))
        self.assertEqual(
            2,
            content.count('--trusted-policy-commit "$TRUSTED_POLICY_COMMIT"'),
        )
        self.assertIn(
            "TRUSTED_POLICY_COMMIT: ${{ github.event.pull_request.base.sha || github.event.before }}",
            content,
        )

    def test_checkout_history_and_python_runtime_are_immutable_inputs(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("timeout-minutes: 10", content)
        self.assertIn("runs-on: ubuntu-24.04", content)
        self.assertNotIn("ubuntu-latest", content)
        self.assertRegex(
            content,
            r"container:\n\s+image: python:3\.13\.14-bookworm@sha256:[0-9a-f]{64}",
        )
        self.assertIn("options: --platform linux/amd64", content)
        self.assertIn("fetch-depth: 0", content)
        self.assertNotIn("actions/setup-python@", content)
        self.assertIn("--require-hashes --no-deps", content)
        for command in (
            "check-doc-decisions.py",
            "check_project_skills.py",
            "check_sensitive_artifacts.py",
            "check_modernization_artifacts.py",
            "-m unittest",
        ):
            self.assertRegex(content, rf"python3 -I [^\n]*{command}")

    def test_isolated_python_ignores_a_sibling_stdlib_shadow_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            docs = root / "docs"
            scripts.mkdir()
            docs.mkdir()
            scripts.joinpath("pathlib.py").write_text(
                "raise SystemExit(0)\n", encoding="utf-8"
            )
            scripts.joinpath("check_sensitive_artifacts.py").write_bytes(
                (REPOSITORY / "scripts/check_sensitive_artifacts.py").read_bytes()
            )
            docs.joinpath("evidence.md").write_text(
                "client_" + "secret=must-be-detected\n", encoding="utf-8"
            )

            result = subprocess.run(
                (
                    "python3",
                    "-I",
                    "scripts/check_sensitive_artifacts.py",
                    "docs",
                ),
                cwd=root,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("GENERIC_SECRET_ASSIGNMENT", result.stderr)

    def test_git_plumbing_guard_rejects_a_test_that_rewrites_a_tracked_file(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            artifact = repository / "docs/evidence.md"
            artifact.parent.mkdir(parents=True)
            artifact.write_text("safe\n", encoding="utf-8")
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(
                (
                    "git",
                    "-c",
                    "user.name=CI",
                    "-c",
                    "user.email=ci@example.invalid",
                    "commit",
                    "--quiet",
                    "-m",
                    "fixture",
                ),
                cwd=repository,
                check=True,
            )
            checked_out_sha = subprocess.run(
                ("git", "rev-parse", "--verify", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            artifact.write_text("password=mutated-by-test\n", encoding="utf-8")

            guard = subprocess.run(
                (
                    "git",
                    "diff",
                    "--exit-code",
                    checked_out_sha,
                    "--",
                ),
                cwd=repository,
                capture_output=True,
            )

            self.assertNotEqual(0, guard.returncode)


if __name__ == "__main__":
    unittest.main()
