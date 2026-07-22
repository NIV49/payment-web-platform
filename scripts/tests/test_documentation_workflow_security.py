from __future__ import annotations

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

    def test_security_gates_run_before_tests_and_again_after_integrity_check(
        self,
    ) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        test_position = content.index("Test documentation governance")
        scanner_positions = [
            index
            for index in range(len(content))
            if content.startswith("python3 scripts/check_sensitive_artifacts.py", index)
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
        self.assertGreater(scanner_positions[1], test_position)
        self.assertGreater(artifact_positions[1], test_position)
        integrity_position = content.index('git diff --exit-code "$checked_out_sha" --')
        archive_position = content.index(
            'git --no-replace-objects archive "$checked_out_sha"'
        )
        self.assertLess(test_position, integrity_position)
        self.assertLess(integrity_position, archive_position)
        self.assertLess(archive_position, scanner_positions[1])
        self.assertIn('checked_out_sha="$(git rev-parse --verify HEAD)"', content)
        self.assertIn('test "$checked_out_sha" = "$GITHUB_SHA"', content)
        self.assertIn('git diff --exit-code "$checked_out_sha" --', content)
        self.assertIn("git status --porcelain=v1 --untracked-files=all", content)
        self.assertIn("GIT_NO_REPLACE_OBJECTS=1", content)
        self.assertIn('git --no-replace-objects archive "$checked_out_sha"', content)
        self.assertEqual(2, content.count('--commit "$'))
        self.assertEqual(2, content.count('--repository-root "$GITHUB_WORKSPACE"'))
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
        self.assertIn("fetch-depth: 0", content)
        self.assertIn(
            "actions/setup-python@a309ff8b426b58ec0e2a45f0f869d46889d02405",
            content,
        )
        self.assertIn('python-version: "3.13.14"', content)

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
