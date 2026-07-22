from __future__ import annotations

import os
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
                re.escape(
                    "git --no-replace-objects diff --no-ext-diff "
                    '--exit-code "$checked_out_sha" --'
                ),
                content,
            )
        ]
        self.assertEqual(2, len(integrity_positions))
        archive_position = content.index(
            'git --no-replace-objects archive "$checked_out_sha"'
        )
        self.assertLess(archive_position, scanner_positions[1])
        self.assertLess(scanner_positions[1], test_position)
        self.assertLess(test_position, integrity_positions[-1])
        self.assertIn(
            'checked_out_sha="$(git --no-replace-objects rev-parse '
            "--verify 'HEAD^{commit}')\"",
            content,
        )
        self.assertIn('test "$checked_out_sha" = "$GITHUB_SHA"', content)
        self.assertIn(
            "git --no-replace-objects diff --no-ext-diff "
            '--exit-code "$checked_out_sha" --',
            content,
        )
        self.assertIn(
            "git --no-replace-objects status "
            "--porcelain=v1 --untracked-files=all",
            content,
        )
        self.assertIn("GIT_NO_REPLACE_OBJECTS=1", content)
        self.assertIn('git --no-replace-objects archive "$checked_out_sha"', content)
        self.assertIn('GIT_NO_REPLACE_OBJECTS: "1"', content)
        self.assertGreaterEqual(content.count("git --no-replace-objects rev-parse"), 6)
        self.assertEqual(4, content.count("for-each-ref"))
        self.assertEqual(
            4,
            content.count(
                'replace_ref_dir="$(git --no-replace-objects rev-parse '
                '--git-path refs/replace)"'
            ),
        )
        self.assertEqual(
            4,
            content.count(
                'grafts_path="$(git --no-replace-objects rev-parse '
                '--git-path info/grafts)"'
            ),
        )
        self.assertEqual(4, content.count("--is-shallow-repository"))
        self.assertEqual(4, content.count('-mindepth 1 -print -quit'))
        self.assertEqual(4, content.count('test ! -L "$replace_ref_dir"'))
        self.assertEqual(4, content.count('test -z "${GIT_GRAFT_FILE+x}"'))
        self.assertEqual(4, content.count('test -z "${GIT_SHALLOW_FILE+x}"'))
        self.assertEqual(
            4,
            content.count('verify_index_and_worktree "$checked_out_tree"'),
        )
        self.assertEqual(2, content.count("git --no-replace-objects ls-files -v"))
        self.assertEqual(2, content.count("git --no-replace-objects write-tree"))
        self.assertEqual(2, content.count("git --no-replace-objects diff-files"))
        self.assertEqual(
            2,
            content.count(
                'git --no-replace-objects diff --no-ext-diff --exit-code "$checked_out_sha" --'
            ),
        )
        self.assertEqual(
            2,
            content.count(
                "git --no-replace-objects status --porcelain=v1 --untracked-files=all"
            ),
        )
        self.assertGreaterEqual(content.count("checked_out_tree"), 6)
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
                "client_sec" + "ret=must-be-detected\n", encoding="utf-8"
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
            artifact.write_text("pass" + "word=mutated-by-test\n", encoding="utf-8")

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

    def test_real_post_test_guard_rejects_a_replace_ref_attack(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name")
            == "Test documentation governance without snapshot drift"
        )
        script = test_step["run"]
        original_test_command = (
            'python3 -I -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        self.assertIn(original_test_command, script)
        attack = (
            'git replace "$checked_out_sha" "$REPLACEMENT_SHA"\n'
            'git read-tree --reset -u "${REPLACEMENT_SHA}^{tree}"'
        )
        attacked_script = script.replace(original_test_command, attack)

        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
            subprocess.run(
                ("git", "config", "user.name", "CI"), cwd=repository, check=True
            )
            subprocess.run(
                ("git", "config", "user.email", "ci@example.invalid"),
                cwd=repository,
                check=True,
            )
            tracked = repository / "tracked.txt"
            tracked.write_text("original tree\n", encoding="utf-8")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(
                ("git", "commit", "--quiet", "-m", "original"),
                cwd=repository,
                check=True,
            )
            original = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            tracked.write_text("replacement tree\n", encoding="utf-8")
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
            subprocess.run(
                ("git", "commit", "--quiet", "-m", "replacement"),
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
                ("git", "checkout", "--quiet", "--detach", original),
                cwd=repository,
                check=True,
            )
            environment = os.environ.copy()
            environment.update(
                {
                    "GITHUB_SHA": original,
                    "REPLACEMENT_SHA": replacement,
                }
            )

            result = subprocess.run(
                ("bash", "-c", attacked_script),
                cwd=repository,
                env=environment,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_real_post_test_guard_rejects_hidden_index_flag_mutations(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name")
            == "Test documentation governance without snapshot drift"
        )
        script = test_step["run"]
        original_test_command = (
            'python3 -I -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        attacks = {
            "assume-unchanged": (
                "printf '%s\\n' mutated-by-test > tracked.txt\n"
                "git update-index --assume-unchanged tracked.txt"
            ),
            "skip-worktree": (
                "git update-index --skip-worktree tracked.txt\n"
                "printf '%s\\n' mutated-by-test > tracked.txt"
            ),
        }

        for name, attack in attacks.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
                repository.joinpath("tracked.txt").write_text(
                    "original tree\n", encoding="utf-8"
                )
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
                        "original",
                    ),
                    cwd=repository,
                    check=True,
                )
                original = subprocess.run(
                    ("git", "rev-parse", "HEAD"),
                    cwd=repository,
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                environment = os.environ.copy()
                environment["GITHUB_SHA"] = original
                attacked_script = script.replace(original_test_command, attack)

                result = subprocess.run(
                    ("bash", "-c", attacked_script),
                    cwd=repository,
                    env=environment,
                    capture_output=True,
                    text=True,
                )

                self.assertNotEqual(
                    0,
                    result.returncode,
                    result.stdout + result.stderr,
                )

    def test_real_post_test_guard_rejects_broken_graph_override_files(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name")
            == "Test documentation governance without snapshot drift"
        )
        script = test_step["run"]
        original_test_command = (
            'python3 -I -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        attacks = {
            "broken-replace-ref": (
                'replace_dir="$(git --no-replace-objects rev-parse '
                '--git-path refs/replace)"\n'
                'mkdir -p "$replace_dir"\n'
                "printf '%s\\n' not-an-object > \"$replace_dir/broken\""
            ),
            "graft-parent-rewrite": (
                'grafts="$(git --no-replace-objects rev-parse '
                '--git-path info/grafts)"\n'
                'mkdir -p "$(dirname "$grafts")"\n'
                "printf '%s\\n' \"$checked_out_sha\" > \"$grafts\""
            ),
            "alternate-graft-newline-path": (
                "alternate_grafts=\"${PWD}/alternate-grafts\"$'\\n'\n"
                "printf '%s\\n' \"$checked_out_sha\" > \"$alternate_grafts\"\n"
                'export GIT_GRAFT_FILE="$alternate_grafts"'
            ),
        }

        for name, attack in attacks.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
                tracked = repository / "tracked.txt"
                tracked.write_text("original tree\n", encoding="utf-8")
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
                        "original",
                    ),
                    cwd=repository,
                    check=True,
                )
                original = subprocess.run(
                    ("git", "rev-parse", "HEAD"),
                    cwd=repository,
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                environment = os.environ.copy()
                environment["GITHUB_SHA"] = original
                attacked_script = script.replace(original_test_command, attack)

                result = subprocess.run(
                    ("bash", "-c", attacked_script),
                    cwd=repository,
                    env=environment,
                    capture_output=True,
                    text=True,
                )

                self.assertNotEqual(
                    0,
                    result.returncode,
                    result.stdout + result.stderr,
                )


if __name__ == "__main__":
    unittest.main()
