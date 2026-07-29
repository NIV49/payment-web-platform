from __future__ import annotations

import ast
import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import yaml


REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY / ".github/workflows/documentation.yml"
REPOSITORY_GUARD = REPOSITORY / "scripts/ci_repository_guard.sh"
CONTROLLED_STEP_NAME = (
    "Verify and test documentation governance without snapshot drift"
)


class DocumentationWorkflowSecurityTest(unittest.TestCase):
    MINIMUM_FIXTURE_GIT_VERSION = (2, 31, 0)
    CRITICAL_EXECUTABLES = (
        "env",
        "git",
        "python3",
        "find",
        "grep",
        "tar",
        "mktemp",
        "mkdir",
        "rm",
    )
    LOCAL_EXECUTABLES = (
        "bash",
        "chmod",
        "env",
        "find",
        "git",
        "grep",
        "mktemp",
        "mkdir",
        "mv",
        "python3",
        "rm",
        "tar",
    )
    ARCHIVE_POLICY_PATH = ".agents/payment-modernization-policy.json"
    ARCHIVE_GATE_PATHS = (
        ".github/CODEOWNERS",
        ".github/workflows/documentation.yml",
        "scripts/check-doc-decisions.py",
        "scripts/check_modernization_artifacts.py",
        "scripts/check_project_skills.py",
        "scripts/check_sensitive_artifacts.py",
        "scripts/ci_repository_guard.sh",
        "scripts/requirements-documentation.txt",
    )
    ARCHIVE_TEST_PATH = "scripts/tests/test_fixture_gate.py"

    @classmethod
    def setUpClass(cls) -> None:
        super().setUpClass()
        resolved = {name: shutil.which(name) for name in cls.LOCAL_EXECUTABLES}
        missing = sorted(name for name, path in resolved.items() if path is None)
        if missing:
            raise AssertionError(
                f"local fixture is missing required executables: {', '.join(missing)}"
            )
        cls.local_tools = {
            name: str(Path(path).absolute())
            for name, path in resolved.items()
            if path is not None
        }
        required_directories = {
            str(Path(path).parent) for path in cls.local_tools.values()
        }
        path_directories = [
            str(Path(entry or os.curdir).absolute())
            for entry in os.environ.get("PATH", "").split(os.pathsep)
        ]
        ordered_directories = list(
            dict.fromkeys(
                directory
                for directory in path_directories
                if directory in required_directories
            )
        )
        ordered_directories.extend(
            sorted(required_directories.difference(ordered_directories))
        )
        cls.local_safe_path = os.pathsep.join(ordered_directories)
        for name, expected in cls.local_tools.items():
            actual = shutil.which(name, path=cls.local_safe_path)
            if actual is None or str(Path(actual).absolute()) != expected:
                raise AssertionError(
                    f"minimal local PATH does not preserve {name} identity: "
                    f"expected {expected}, found {actual}"
                )
        version_result = subprocess.run(
            (cls.local_tools["git"], "--version"),
            check=True,
            capture_output=True,
            text=True,
            env=cls.local_shell_environment(Path(tempfile.gettempdir())),
        )
        match = re.fullmatch(
            r"git version ([0-9]+)\.([0-9]+)\.([0-9]+)(?: .*)?\n?",
            version_result.stdout,
        )
        if match is None:
            raise AssertionError(
                f"cannot parse local fixture Git version: {version_result.stdout!r}"
            )
        cls.local_git_version = tuple(int(part) for part in match.groups())
        if cls.local_git_version < cls.MINIMUM_FIXTURE_GIT_VERSION:
            required = ".".join(
                str(part) for part in cls.MINIMUM_FIXTURE_GIT_VERSION
            )
            actual = ".".join(str(part) for part in cls.local_git_version)
            raise AssertionError(
                f"local fixture requires Git >= {required}; found {actual}"
            )

    @classmethod
    def local_shell_environment(
        cls,
        home: Path,
        *,
        safe_path: str | None = None,
    ) -> dict[str, str]:
        return {
            "PATH": safe_path or cls.local_safe_path,
            "HOME": str(home),
            "LANG": "C",
            "LC_ALL": "C",
        }

    @classmethod
    def fixture_git_environment(cls, repository: Path) -> dict[str, str]:
        environment = cls.local_shell_environment(repository.parent)
        environment.update(
            {
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_SYSTEM": os.devnull,
                "GIT_CONFIG_GLOBAL": os.devnull,
            }
        )
        return environment

    def run_fixture_git(
        self,
        repository: Path,
        *arguments: str,
        **options: object,
    ) -> subprocess.CompletedProcess:
        options.setdefault("check", True)
        options.setdefault("env", self.fixture_git_environment(repository))
        return subprocess.run(
            (
                self.local_tools["git"],
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "commit.gpgSign=false",
                "-c",
                "tag.gpgSign=false",
                *arguments,
            ),
            cwd=repository,
            **options,
        )

    def assert_guard_diagnostic(
        self,
        result: subprocess.CompletedProcess[str],
        expected_diagnostic: str,
    ) -> None:
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("CI repository guard failed:", result.stderr)
        self.assertIn(expected_diagnostic, result.stderr)
        for unrelated_failure in (
            "No such file or directory",
            "command not found",
            "unbound variable",
        ):
            self.assertNotIn(unrelated_failure, result.stderr)

    def archive_contract_files(self) -> dict[str, str]:
        judge_paths = [*self.ARCHIVE_GATE_PATHS, self.ARCHIVE_TEST_PATH]
        files = {
            self.ARCHIVE_POLICY_PATH: json.dumps(
                {"judgePaths": judge_paths},
                sort_keys=True,
            )
            + "\n",
            ".github/CODEOWNERS": "/scripts/ @fixture\n",
            ".github/workflows/documentation.yml": "name: fixture\n",
            "scripts/requirements-documentation.txt": "\n",
            self.ARCHIVE_TEST_PATH: (
                "import unittest\n\n"
                "class FixtureGateTest(unittest.TestCase):\n"
                "    def test_fixture(self) -> None:\n"
                "        self.assertTrue(True)\n"
            ),
        }
        for path in self.ARCHIVE_GATE_PATHS:
            if path.startswith("scripts/check"):
                files[path] = "raise SystemExit(0)\n"
        return files

    def create_guarded_repository(
        self,
        fixture_root: Path,
        tracked_files: dict[str, str] | None = None,
    ) -> tuple[Path, str]:
        repository = fixture_root / "repository"
        scripts = repository / "scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(REPOSITORY_GUARD, scripts / REPOSITORY_GUARD.name)
        files = (
            tracked_files
            if tracked_files is not None
            else {
                **self.archive_contract_files(),
                "tracked.txt": "original tree\n",
            }
        )
        for relative_path, content in files.items():
            path = repository / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.run_fixture_git(
            repository,
            "init",
            "--quiet",
            "--initial-branch=fixture",
        )
        self.run_fixture_git(repository, "add", ".")
        self.run_fixture_git(
            repository,
            "-c",
            "user.name=CI",
            "-c",
            "user.email=ci@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "original",
        )
        commit = self.run_fixture_git(
            repository,
            "rev-parse",
            "HEAD",
            capture_output=True,
            text=True,
        ).stdout.strip()
        return repository, commit

    def create_linked_guarded_repository(
        self,
        fixture_root: Path,
        tracked_files: dict[str, str],
    ) -> tuple[Path, Path, str]:
        repository, commit = self.create_guarded_repository(
            fixture_root,
            tracked_files,
        )
        linked = fixture_root / "linked"
        self.run_fixture_git(
            repository,
            "worktree",
            "add",
            "--quiet",
            "--detach",
            str(linked),
            commit,
        )
        return repository, linked, commit

    def resolve_git_path(self, repository: Path, name: str) -> Path:
        return Path(
            self.run_fixture_git(
                repository,
                "rev-parse",
                "--path-format=absolute",
                "--git-path",
                name,
                capture_output=True,
                text=True,
            ).stdout.strip()
        )

    def run_workflow_shell(
        self,
        fixture_root: Path,
        repository: Path,
        script: str,
        commit: str,
        extra_environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        script_path = fixture_root / "workflow-step.sh"
        lines = script.splitlines(keepends=True)
        if lines and lines[0] == "exec /usr/bin/env -i \\\n":
            opening = next(
                (
                    index
                    for index, line in enumerate(lines)
                    if line.endswith("<<'DOCUMENTATION_CI_SCRIPT'\n")
                ),
                None,
            )
            if opening is None or lines[-1] != "DOCUMENTATION_CI_SCRIPT\n":
                raise AssertionError("workflow run boundary is malformed")
            script = "".join(lines[opening + 1 : -1])
        script_path.write_text(script, encoding="utf-8")
        fixture_root.joinpath("home").mkdir(exist_ok=True)
        environment_overrides = dict(extra_environment or {})
        environment_overrides.setdefault("TRUSTED_POLICY_COMMIT", commit)
        dependency_root = Path(
            environment_overrides.setdefault(
                "CI_PYTHON_DEPENDENCY_ROOT",
                str(fixture_root / "python-dependencies"),
            )
        )
        dependency_root.mkdir(parents=True, exist_ok=True)
        command = [
            self.local_tools["env"],
            "-i",
            f"PATH={self.local_safe_path}",
            f"HOME={fixture_root / 'home'}",
            "LANG=C",
            "LC_ALL=C",
            f"GITHUB_SHA={commit}",
        ]
        for name, value in sorted(environment_overrides.items()):
            command.append(f"{name}={value}")
        command.extend(
            (
                self.local_tools["bash"],
                "--noprofile",
                "--norc",
                "-e",
                "-o",
                "pipefail",
                str(script_path),
            )
        )
        return subprocess.run(
            command,
            cwd=repository,
            capture_output=True,
            text=True,
        )

    def test_controlled_scripts_run_inside_a_minimal_guarded_shell(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        guarded_steps = [
            step for step in steps if step.get("name") == CONTROLLED_STEP_NAME
        ]

        self.assertEqual(1, len(guarded_steps))
        for step in guarded_steps:
            with self.subTest(step=step["name"]):
                shell = step.get("shell", "")
                script = step["run"]
                self.assertEqual("/bin/sh -eu {0}", shell)
                self.assertNotIn("${{", shell)
                self.assertEqual(
                    {
                        "GITHUB_SHA": "${{ github.sha }}",
                        "TRUSTED_POLICY_COMMIT": (
                            "${{ github.event.pull_request.base.sha || "
                            "github.event.before }}"
                        ),
                    },
                    step["env"],
                )
                self.assertTrue(script.startswith("exec /usr/bin/env -i \\\n"))
                self.assertIn(
                    "/bin/bash --noprofile --norc -e -u -o pipefail "
                    "<<'DOCUMENTATION_CI_SCRIPT'\n",
                    script,
                )
                self.assertTrue(script.endswith("DOCUMENTATION_CI_SCRIPT\n"))
                self.assertIn("source scripts/ci_repository_guard.sh", script)
                capture = script.index("ci_capture_repository_state")
                first_controlled_python = script.index(
                    "ci_python ",
                    capture,
                )
                verify = script.rindex("ci_verify_repository_state")
                self.assertLess(capture, first_controlled_python)
                self.assertLess(first_controlled_python, verify)

    def test_governance_tests_isolate_bytecode_side_effects(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        original_test_command = (
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        self.assertIn(original_test_command, test_step["run"])
        bytecode_command = (
            "mkdir -p bytecode-side-effect\n"
            "printf '%s\\n' 'fixture = True' > "
            "bytecode-side-effect/module.py\n"
            "python3 -I -m py_compile bytecode-side-effect/module.py"
        )
        attacked_script = test_step["run"].replace(
            original_test_command,
            bytecode_command,
        )

        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                attacked_script,
                commit,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual([], list(repository.rglob("*.pyc")))
            self.assertFalse(repository.joinpath("bytecode-side-effect").exists())

    def test_repository_guard_binds_identity_and_snapshots_local_config(self) -> None:
        self.assertTrue(REPOSITORY_GUARD.is_file())
        content = REPOSITORY_GUARD.read_text(encoding="utf-8")

        for required in (
            '-C "$CI_EXPECTED_WORKSPACE"',
            '--git-dir="$CI_EXPECTED_GIT_DIR"',
            '--work-tree="$CI_EXPECTED_WORK_TREE"',
            "core.commitGraph=false",
            "core.useReplaceRefs=false",
            "GIT_CONFIG_NOSYSTEM=1",
            "GIT_CONFIG_GLOBAL=/dev/null",
            "CI_EXPECTED_LOCAL_CONFIG_FINGERPRINT",
            "CI_EXPECTED_CONFIG_FILES_FINGERPRINT",
            "CI_EXPECTED_TOOLCHAIN_FINGERPRINT",
            "ci_capture_toolchain",
            "ci_verify_toolchain",
            '"$CI_TOOL_ENV" -i',
            '"$CI_TOOL_GIT"',
            '"$CI_TOOL_PYTHON3" -B -I -S',
            '"$CI_TOOL_FIND"',
            '"$CI_TOOL_GREP"',
            '"$CI_TOOL_MKDIR"',
            'test "$(pwd -P)" = "$CI_EXPECTED_WORKSPACE"',
        ):
            self.assertIn(required, content)

    def test_repository_guard_accepts_checkout_with_different_owner(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)
            wrapper_directory = fixture_root / "git-wrapper"
            wrapper_directory.mkdir()
            git_wrapper = wrapper_directory / "git"
            git_wrapper.write_text(
                "#!/bin/sh\n"
                "GIT_TEST_ASSUME_DIFFERENT_OWNER=1\n"
                "export GIT_TEST_ASSUME_DIFFERENT_OWNER\n"
                f"exec {shlex.quote(self.local_tools['git'])} \"$@\"\n",
                encoding="utf-8",
            )
            git_wrapper.chmod(0o755)
            script = (
                "set -euo pipefail\n"
                f"CI_SAFE_PATH={shlex.quote(str(wrapper_directory))}:"
                f"{shlex.quote(self.local_safe_path)}\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(fixture_root.joinpath("home", ".gitconfig").exists())
            guard = REPOSITORY_GUARD.read_text(encoding="utf-8")
            discovery = re.search(
                r"^ci_discovery_git\(\) \{\n(?P<body>.*?)^\}\n",
                guard,
                re.MULTILINE | re.DOTALL,
            )
            bound = re.search(
                r"^ci_bound_git\(\) \{\n(?P<body>.*?)^\}\n",
                guard,
                re.MULTILINE | re.DOTALL,
            )
            self.assertIsNotNone(discovery)
            self.assertIsNotNone(bound)
            self.assertIn(
                '-c safe.directory="$workspace"',
                discovery.group("body"),
            )
            self.assertIn(
                '-c safe.directory="$CI_EXPECTED_WORKSPACE"',
                bound.group("body"),
            )
            self.assertNotIn("safe.directory=*", guard)

    def test_python_toolchain_capture_uses_native_identity_before_python_output(
        self,
    ) -> None:
        content = REPOSITORY_GUARD.read_text(encoding="utf-8")

        self.assertIn("ci_native_python_identity() {", content)
        native_identity = re.search(
            r"^ci_native_python_identity\(\) \{\n(?P<body>.*?)^\}\n",
            content,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(native_identity)
        native_body = native_identity.group("body")
        self.assertNotIn("CI_TOOL_PYTHON3", native_body)
        for trusted_primitive in (
            "CI_TOOL_STAT",
            "CI_TOOL_READLINK",
            "CI_TOOL_SHA256",
        ):
            self.assertIn(trusted_primitive, native_body)

        capture = re.search(
            r"^ci_capture_toolchain\(\) \{\n(?P<body>.*?)^\}\n",
            content,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(capture)
        capture_body = capture.group("body")
        native_capture = capture_body.index(
            'CI_EXPECTED_PYTHON_NATIVE_IDENTITY="$native_identity"'
        )
        python_fingerprint = capture_body.index(
            'fingerprint="$(ci_toolchain_fingerprint)"'
        )
        runtime_boundary = capture_body.index(
            'CI_EXPECTED_PYTHON_RUNTIME_BOUNDARY="$runtime_boundary"'
        )
        self.assertLess(native_capture, python_fingerprint)
        self.assertLess(native_capture, runtime_boundary)

    def test_python_toolchain_runtime_boundary_is_isolated_and_explicit(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            dependency_root = fixture_root / "dependencies"
            dependency_root.mkdir()
            dependency_root.joinpath("ordinary.txt").write_text(
                "fixed dependency fixture\n",
                encoding="utf-8",
            )
            repository, commit = self.create_guarded_repository(fixture_root)
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "ci_capture_python_dependency_state\n"
                "ci_python_runtime_boundary\n"
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
                {"CI_PYTHON_DEPENDENCY_ROOT": str(dependency_root)},
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            boundary = json.loads(result.stdout.splitlines()[-1])
            self.assertEqual(
                {
                    "dont_write_bytecode": 1,
                    "ignore_environment": 1,
                    "isolated": 1,
                    "no_site": 1,
                    "no_user_site": 1,
                    "safe_path": True,
                },
                boundary["flags"],
            )
            self.assertEqual(
                str(dependency_root.resolve()),
                boundary["dependencyRoot"],
            )
            self.assertEqual(
                str(dependency_root.resolve()),
                boundary["sysPath"][0],
            )
            self.assertNotIn("", boundary["sysPath"])
            self.assertNotIn(str(repository), boundary["sysPath"])
            self.assertFalse(boundary["siteImported"])
            self.assertFalse(boundary["sitecustomizeImported"])

    def test_python_toolchain_controlled_runner_executes_without_site_imports(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            dependency_root = fixture_root / "dependencies"
            dependency_root.mkdir()
            repository, commit = self.create_guarded_repository(fixture_root)
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "ci_capture_python_dependency_state\n"
                "ci_python -m unittest --help\n"
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
                {"CI_PYTHON_DEPENDENCY_ROOT": str(dependency_root)},
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_python_toolchain_rejects_ordinary_dependency_fixture_drift(
        self,
    ) -> None:
        for drift, mutation in (
            (
                "path",
                '"$FIXTURE_MV" "$CI_PYTHON_DEPENDENCY_ROOT/ordinary.txt" '
                '"$CI_PYTHON_DEPENDENCY_ROOT/moved.txt"\n',
            ),
            (
                "content",
                "printf '%s\\n' changed > "
                '"$CI_PYTHON_DEPENDENCY_ROOT/ordinary.txt"\n',
            ),
            (
                "metadata",
                '"$FIXTURE_CHMOD" 0600 '
                '"$CI_PYTHON_DEPENDENCY_ROOT/ordinary.txt"\n',
            ),
        ):
            with self.subTest(drift=drift), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                dependency_root = fixture_root / "dependencies"
                dependency_root.mkdir()
                dependency_file = dependency_root / "ordinary.txt"
                dependency_file.write_text(
                    "fixed dependency fixture\n",
                    encoding="utf-8",
                )
                dependency_file.chmod(0o644)
                repository, commit = self.create_guarded_repository(fixture_root)
                script = (
                    "set -euo pipefail\n"
                    "source scripts/ci_repository_guard.sh\n"
                    'ci_capture_repository_state "$GITHUB_SHA"\n'
                    "ci_capture_python_dependency_state\n"
                    f"{mutation}"
                    "ci_verify_python_dependency_state\n"
                )

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    script,
                    commit,
                    {
                        "CI_PYTHON_DEPENDENCY_ROOT": str(dependency_root),
                        "FIXTURE_CHMOD": self.local_tools["chmod"],
                        "FIXTURE_MV": self.local_tools["mv"],
                    },
                )

                self.assert_guard_diagnostic(
                    result,
                    "Python dependency root content or metadata changed",
                )
                self.assertFalse(os.access(dependency_file, os.X_OK))

    def test_python_toolchain_workflow_orders_and_verifies_every_phase(
        self,
    ) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        parsed = yaml.safe_load(content)
        steps = parsed["jobs"]["verify"]["steps"]
        names = [step.get("name") for step in steps]

        preparation_name = (
            "Capture Python runtime and prepare pinned documentation dependencies"
        )
        controlled_name = CONTROLLED_STEP_NAME
        self.assertIn(preparation_name, names)
        self.assertIn(controlled_name, names)
        preparation = next(
            step for step in steps if step.get("name") == preparation_name
        )
        controlled = next(
            step for step in steps if step.get("name") == controlled_name
        )
        self.assertLess(names.index(preparation_name), names.index(controlled_name))

        preparation_script = preparation["run"]
        capture = preparation_script.index("ci_capture_repository_state")
        prepare = preparation_script.index("ci_prepare_python_dependencies")
        post_prepare_verify = preparation_script.index(
            "ci_verify_python_execution_environment",
            prepare,
        )
        self.assertLess(capture, prepare)
        self.assertLess(prepare, post_prepare_verify)
        self.assertIn("--require-hashes", preparation_script)
        self.assertIn("scripts/requirements-documentation.txt", preparation_script)

        for step in (preparation, controlled):
            with self.subTest(step=step["name"]):
                self.assertIn(
                    "CI_PYTHON_DEPENDENCY_ROOT=/tmp/documentation-python-dependencies",
                    step["run"],
                )
                self.assertIn("source scripts/ci_repository_guard.sh", step["run"])
                self.assertIn("ci_capture_repository_state", step["run"])
                self.assertIn("ci_verify_repository_state", step["run"])

        controlled_script = controlled["run"]
        self.assertIn("ci_capture_python_dependency_state", controlled_script)
        self.assertIn("ci_verify_python_execution_environment", controlled_script)
        self.assertNotIn('"$CI_TOOL_PYTHON3"', controlled_script)
        self.assertIn("ci_python ", controlled_script)

        guard = REPOSITORY_GUARD.read_text(encoding="utf-8")
        for function_name in (
            "ci_archive_captured_tree",
            "ci_python",
            "ci_verify_repository_state",
        ):
            function = re.search(
                rf"^{function_name}\(\) \{{\n(?P<body>.*?)^\}}\n",
                guard,
                re.MULTILINE | re.DOTALL,
            )
            self.assertIsNotNone(function, function_name)
            self.assertIn(
                "ci_verify_python_execution_environment",
                function.group("body"),
            )

    def test_all_pr_controlled_python_runs_in_one_dependency_seal(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        controlled_steps = [
            step for step in steps if "ci_python " in step.get("run", "")
        ]

        self.assertEqual(1, len(controlled_steps), controlled_steps)
        script = controlled_steps[0]["run"]
        self.assertEqual(1, script.count("ci_capture_python_dependency_state"))
        self.assertIn("ci_python scripts/check-doc-decisions.py", script)
        self.assertIn(
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"',
            script,
        )
        final_repository_verify = script.rindex("ci_verify_repository_state")
        self.assertGreater(
            final_repository_verify,
            script.index('ci_python -m unittest discover'),
        )

    def test_archive_helper_never_executes_a_late_path_wrapper(self) -> None:
        resolved_commands = {
            name: shutil.which(name) for name in ("bash", "chmod", "env", "mkdir")
        }
        self.assertTrue(all(resolved_commands.values()), resolved_commands)

        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)
            wrapper_directory = fixture_root / "late-wrapper"
            wrapper_directory.mkdir()
            wrapper = wrapper_directory / "mkdir"
            invocation_marker = fixture_root / "wrapper-invoked"
            real_mkdir = resolved_commands["mkdir"]
            self.assertIsNotNone(real_mkdir)
            wrapper.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' invoked >> "
                f"{shlex.quote(str(invocation_marker))}\n"
                f"exec {shlex.quote(real_mkdir)} \"$@\"\n",
                encoding="utf-8",
            )
            wrapper.chmod(0o644)
            safe_path = os.pathsep.join(
                (str(wrapper_directory), self.local_safe_path)
            )
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                f"{shlex.quote(resolved_commands['chmod'])} 0755 "
                f"{shlex.quote(str(wrapper))}\n"
                'ci_git archive "$CI_EXPECTED_COMMIT" >/dev/null\n'
            )

            result = subprocess.run(
                (
                    resolved_commands["env"],
                    "-i",
                    f"PATH={safe_path}",
                    f"HOME={fixture_root / 'home'}",
                    "LANG=C",
                    "LC_ALL=C",
                    f"GITHUB_SHA={commit}",
                    resolved_commands["bash"],
                    "--noprofile",
                    "--norc",
                    "-e",
                    "-o",
                    "pipefail",
                    "-c",
                    script,
                ),
                cwd=repository,
                capture_output=True,
                text=True,
            )

            self.assert_guard_diagnostic(
                result,
                "critical executable identity changed: mkdir",
            )
            self.assertFalse(
                invocation_marker.exists(),
                "archive helper executed a late PATH forwarding wrapper",
            )

    def test_repository_guard_fails_closed_when_critical_executable_resolution_drifts(
        self,
    ) -> None:
        resolved_commands = {
            name: shutil.which(name)
            for name in (*self.CRITICAL_EXECUTABLES, "bash", "chmod")
        }
        self.assertTrue(all(resolved_commands.values()), resolved_commands)

        for executable in self.CRITICAL_EXECUTABLES:
            with self.subTest(executable=executable):
                with tempfile.TemporaryDirectory() as directory:
                    fixture_root = Path(directory)
                    repository, commit = self.create_guarded_repository(fixture_root)
                    wrapper_directory = fixture_root / "forwarding-wrappers"
                    wrapper_directory.mkdir()
                    wrapper = wrapper_directory / executable
                    invocation_marker = fixture_root / "wrapper-invoked"
                    real_executable = resolved_commands[executable]
                    self.assertIsNotNone(real_executable)
                    wrapper.write_text(
                        "#!/bin/sh\n"
                        f"printf '%s\\n' invoked >> "
                        f"{shlex.quote(str(invocation_marker))}\n"
                        f"exec {shlex.quote(real_executable)} \"$@\"\n",
                        encoding="utf-8",
                    )
                    wrapper.chmod(0o644)
                    command_directories = {
                        str(Path(command).absolute().parent)
                        for command in resolved_commands.values()
                        if command is not None
                    }
                    safe_path = os.pathsep.join(
                        (str(wrapper_directory), *sorted(command_directories))
                    )
                    script = (
                        "set -euo pipefail\n"
                        "source scripts/ci_repository_guard.sh\n"
                        'ci_capture_repository_state "$GITHUB_SHA"\n'
                        f"{shlex.quote(resolved_commands['chmod'])} 0755 "
                        f"{shlex.quote(str(wrapper))}\n"
                        "ci_verify_repository_state\n"
                    )

                    result = subprocess.run(
                        (
                            resolved_commands["env"],
                            "-i",
                            f"PATH={safe_path}",
                            f"HOME={fixture_root / 'home'}",
                            "LANG=C",
                            "LC_ALL=C",
                            f"GITHUB_SHA={commit}",
                            resolved_commands["bash"],
                            "--noprofile",
                            "--norc",
                            "-e",
                            "-o",
                            "pipefail",
                            "-c",
                            script,
                        ),
                        cwd=repository,
                        capture_output=True,
                        text=True,
                    )

                    self.assert_guard_diagnostic(
                        result,
                        f"critical executable identity changed: {executable}",
                    )
                    self.assertFalse(
                        invocation_marker.exists(),
                        "verifier executed a drifted forwarding wrapper",
                    )

    def test_repository_guard_fails_closed_when_bound_tool_content_drifts(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)
            wrapper_directory = fixture_root / "bound-forwarding-wrappers"
            wrapper_directory.mkdir()
            for executable in self.CRITICAL_EXECUTABLES:
                wrapper = wrapper_directory / executable
                wrapper.write_text(
                    "#!/bin/sh\n"
                    f"exec {shlex.quote(self.local_tools[executable])} \"$@\"\n",
                    encoding="utf-8",
                )
                wrapper.chmod(0o755)
            safe_path = os.pathsep.join(
                (str(wrapper_directory), self.local_safe_path)
            )
            git_wrapper = wrapper_directory / "git"
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                f"printf '%s\\n' '# harmless drift' >> "
                f"{shlex.quote(str(git_wrapper))}\n"
                "ci_verify_repository_state\n"
            )

            result = subprocess.run(
                (
                    self.local_tools["env"],
                    "-i",
                    f"PATH={safe_path}",
                    f"HOME={fixture_root / 'home'}",
                    "LANG=C",
                    "LC_ALL=C",
                    f"GITHUB_SHA={commit}",
                    self.local_tools["bash"],
                    "--noprofile",
                    "--norc",
                    "-e",
                    "-o",
                    "pipefail",
                    "-c",
                    script,
                ),
                cwd=repository,
                capture_output=True,
                text=True,
            )

            self.assert_guard_diagnostic(
                result,
                "critical executable content or metadata changed",
            )

    def test_fixture_repository_setup_ignores_host_git_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            hooks = fixture_root / "host-hooks"
            hooks.mkdir()
            hook_marker = fixture_root / "host-hook-ran"
            pre_commit = hooks / "pre-commit"
            pre_commit.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' inherited > {shlex.quote(str(hook_marker))}\n",
                encoding="utf-8",
            )
            pre_commit.chmod(0o755)
            host_config = fixture_root / "host.gitconfig"
            host_config.write_text(
                "[init]\n"
                "\tdefaultBranch = inherited-host-branch\n"
                "[core]\n"
                f"\thooksPath = {hooks}\n",
                encoding="utf-8",
            )

            with mock.patch.dict(
                os.environ,
                {"GIT_CONFIG_GLOBAL": str(host_config)},
                clear=False,
            ):
                repository, _ = self.create_guarded_repository(fixture_root)

            branch = self.run_fixture_git(
                repository,
                "symbolic-ref",
                "--short",
                "HEAD",
                capture_output=True,
                text=True,
            ).stdout.strip()

            self.assertFalse(
                hook_marker.exists(),
                "fixture Git setup executed an inherited core.hooksPath",
            )
            self.assertEqual("fixture", branch)

    def test_fixture_git_runner_uses_a_minimal_unsigned_hookless_environment(
        self,
    ) -> None:
        repository = Path(tempfile.gettempdir()) / "synthetic-fixture-repository"
        with mock.patch("subprocess.run") as run:
            self.run_fixture_git(repository, "status", check=False)

        command = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertEqual(self.local_tools["git"], command[0])
        self.assertIn("core.hooksPath=/dev/null", command)
        self.assertIn("commit.gpgSign=false", command)
        self.assertIn("tag.gpgSign=false", command)
        self.assertEqual(
            {
                "PATH",
                "HOME",
                "LANG",
                "LC_ALL",
                "GIT_CONFIG_NOSYSTEM",
                "GIT_CONFIG_SYSTEM",
                "GIT_CONFIG_GLOBAL",
            },
            set(environment),
        )
        self.assertEqual("1", environment["GIT_CONFIG_NOSYSTEM"])
        self.assertEqual(os.devnull, environment["GIT_CONFIG_SYSTEM"])
        self.assertEqual(os.devnull, environment["GIT_CONFIG_GLOBAL"])

    def test_local_fixture_and_fixed_runner_have_separate_toolchain_contracts(
        self,
    ) -> None:
        required = ".".join(
            str(part) for part in self.MINIMUM_FIXTURE_GIT_VERSION
        )
        self.assertGreaterEqual(
            self.local_git_version,
            self.MINIMUM_FIXTURE_GIT_VERSION,
            f"local fixtures require Git >= {required}",
        )
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository = fixture_root / "repository"
            repository.mkdir()
            with mock.patch("subprocess.run") as run:
                self.run_workflow_shell(
                    fixture_root,
                    repository,
                    "exit 0\n",
                    "0" * 40,
                )

        command = run.call_args.args[0]
        self.assertEqual(self.local_tools["env"], command[0])
        self.assertIn(f"PATH={self.local_safe_path}", command)
        self.assertIn(self.local_tools["bash"], command)
        workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        run_steps = [
            step
            for step in workflow["jobs"]["verify"]["steps"]
            if "run" in step
        ]
        self.assertEqual(3, len(run_steps))
        self.assertTrue(
            all(step["shell"] == "/bin/sh -eu {0}" for step in run_steps)
        )
        self.assertTrue(
            all(step["run"].startswith("exec /usr/bin/env -i \\\n") for step in run_steps)
        )

    def test_repository_guard_rejects_workspace_or_local_config_drift(self) -> None:
        self.assertTrue(REPOSITORY_GUARD.is_file())

        scenarios = {
            "workspace": (
                'mkdir "$fixture_root/other"\n'
                'cd "$fixture_root/other"\n',
                "workspace",
            ),
            "local-config": (
                'git -C "$repository" config guard.changed true\n',
                "configuration",
            ),
            "git-environment": (
                'export GIT_DIR="$repository/.git"\n',
                "environment",
            ),
        }
        for name, (mutation, expected_error) in scenarios.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository = fixture_root / "repository"
                repository.mkdir()
                repository.joinpath("tracked.txt").write_text(
                    "immutable\n", encoding="utf-8"
                )
                self.run_fixture_git(
                    repository,
                    "init",
                    "--quiet",
                    "--initial-branch=fixture",
                )
                self.run_fixture_git(repository, "add", ".")
                self.run_fixture_git(
                    repository,
                    "-c",
                    "user.name=CI",
                    "-c",
                    "user.email=ci@example.invalid",
                    "commit",
                    "--quiet",
                    "-m",
                    "fixture",
                )
                commit = self.run_fixture_git(
                    repository,
                    "rev-parse",
                    "HEAD",
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                dependency_root = fixture_root / "python-dependencies"
                dependency_root.mkdir()
                script = (
                    "set -euo pipefail\n"
                    f"CI_SAFE_PATH={shlex.quote(self.local_safe_path)}\n"
                    f"CI_SAFE_HOME={shlex.quote(str(fixture_root / 'home'))}\n"
                    "CI_PYTHON_DEPENDENCY_ROOT="
                    f"{shlex.quote(str(dependency_root))}\n"
                    f"source {shlex.quote(str(REPOSITORY_GUARD))}\n"
                    f"fixture_root={shlex.quote(str(fixture_root))}\n"
                    f"repository={shlex.quote(str(repository))}\n"
                    'cd "$repository"\n'
                    f"ci_capture_repository_state {shlex.quote(commit)}\n"
                    f"{mutation}"
                    "ci_verify_repository_state\n"
                )

                result = subprocess.run(
                    (
                        self.local_tools["bash"],
                        "--noprofile",
                        "--norc",
                        "-c",
                        script,
                    ),
                    cwd=repository,
                    capture_output=True,
                    text=True,
                    env=self.local_shell_environment(fixture_root),
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected_error, result.stderr.lower())

    def test_repository_guard_fingerprints_worktree_scope_includes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)
            included_config = fixture_root / "worktree-include.conf"
            self.run_fixture_git(
                repository,
                "config",
                "extensions.worktreeConfig",
                "true",
            )
            self.run_fixture_git(
                repository,
                "config",
                "--worktree",
                "include.path",
                str(included_config),
            )
            included_config.write_text(
                "[guard]\nincluded = before\n", encoding="utf-8"
            )
            script = (
                "set -u\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                f"printf '%s\\n' '[guard]' 'included = after' > "
                f"{shlex.quote(str(included_config))}\n"
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
            )

            self.assert_guard_diagnostic(
                result,
                "effective local Git configuration changed after controlled scripts",
            )

    def test_repository_guard_accepts_a_clean_linked_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            _, linked, commit = self.create_linked_guarded_repository(
                fixture_root,
                {"tracked.txt": "immutable\n"},
            )
            script = (
                "set -u\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                linked,
                script,
                commit,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_archive_members_are_bound_to_the_captured_tree_attributes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            _, linked, commit = self.create_linked_guarded_repository(
                fixture_root,
                {
                    ".gitattributes": "tree-only.txt export-ignore\n",
                    "kept.txt": "kept\n",
                    "tree-only.txt": "tree\n",
                    "user-only.txt": "user\n",
                    "working-only.txt": "working\n",
                },
            )
            info_attributes = self.resolve_git_path(linked, "info/attributes")
            info_attributes.write_text(
                "kept.txt export-ignore\n", encoding="utf-8"
            )
            user_attributes = fixture_root / "user-attributes"
            user_attributes.write_text(
                "user-only.txt export-ignore\n", encoding="utf-8"
            )
            self.run_fixture_git(
                linked,
                "config",
                "core.attributesFile",
                str(user_attributes),
            )
            linked.joinpath(".gitattributes").write_text(
                "working-only.txt export-ignore\n", encoding="utf-8"
            )
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                'ci_git archive "$CI_EXPECTED_COMMIT" | tar -tf -\n'
            )

            result = self.run_workflow_shell(
                fixture_root,
                linked,
                script,
                commit,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(
                {
                    ".gitattributes",
                    "kept.txt",
                    "scripts/",
                    "scripts/ci_repository_guard.sh",
                    "user-only.txt",
                    "working-only.txt",
                },
                set(result.stdout.splitlines()),
            )

    def test_captured_archive_manifest_preserves_legitimate_export_ignore(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            tracked_files = {
                **self.archive_contract_files(),
                ".gitattributes": "docs/root-ignored.txt export-ignore\n",
                "docs/.gitattributes": "nested-ignored.txt export-ignore\n",
                "docs/kept.txt": "kept\n",
                "docs/nested-ignored.txt": "nested ignored\n",
                "docs/root-ignored.txt": "root ignored\n",
            }
            repository, commit = self.create_guarded_repository(
                fixture_root,
                tracked_files,
            )
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                'snapshot="$("$CI_TOOL_MKTEMP" -d)"\n'
                "trap '\"$CI_TOOL_RM\" -rf -- \"$snapshot\"' EXIT\n"
                'ci_git archive "$CI_EXPECTED_COMMIT" |\n'
                '  "$CI_TOOL_TAR" -x -C "$snapshot"\n'
                'ci_verify_archive_manifest "$snapshot"\n'
                '[[ ! -e "$snapshot/docs/root-ignored.txt" ]]\n'
                '[[ ! -e "$snapshot/docs/nested-ignored.txt" ]]\n'
                "printf '%s\\n' manifest-ok\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("manifest-ok", result.stdout)

    def test_captured_archive_manifest_rejects_export_ignored_gates_and_tests(
        self,
    ) -> None:
        cases = {
            "root-required-gate": (
                {
                    ".gitattributes": (
                        "scripts/check-doc-decisions.py export-ignore\n"
                    )
                },
                "archive is missing required governance paths",
            ),
            "nested-test": (
                {
                    "scripts/.gitattributes": (
                        "tests/test_fixture_gate.py export-ignore\n"
                    )
                },
                "archive test manifest differs from captured tree",
            ),
        }
        for name, (attribute_files, expected_diagnostic) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, commit = self.create_guarded_repository(
                    fixture_root,
                    {
                        **self.archive_contract_files(),
                        **attribute_files,
                    },
                )
                script = (
                    "set -euo pipefail\n"
                    "source scripts/ci_repository_guard.sh\n"
                    'ci_capture_repository_state "$GITHUB_SHA"\n'
                    'snapshot="$("$CI_TOOL_MKTEMP" -d)"\n'
                    "trap '\"$CI_TOOL_RM\" -rf -- \"$snapshot\"' EXIT\n"
                    'ci_git archive "$CI_EXPECTED_COMMIT" |\n'
                    '  "$CI_TOOL_TAR" -x -C "$snapshot"\n'
                    'ci_verify_archive_manifest "$snapshot"\n'
                )

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    script,
                    commit,
                )

                self.assert_guard_diagnostic(result, expected_diagnostic)

    def test_captured_archive_manifest_rejects_export_substituted_runtime_bytes(
        self,
    ) -> None:
        cases = {
            "required-gate": "scripts/check-doc-decisions.py",
            "governance-test": "scripts/tests/test_fixture_gate.py",
        }
        for name, target in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, commit = self.create_guarded_repository(
                    fixture_root,
                    {
                        **self.archive_contract_files(),
                        ".gitattributes": f"{target} export-subst\n",
                        target: 'REVISION = "$Format:%H$"\n',
                    },
                )
                script = (
                    "set -euo pipefail\n"
                    "source scripts/ci_repository_guard.sh\n"
                    'ci_capture_repository_state "$GITHUB_SHA"\n'
                    'snapshot="$("$CI_TOOL_MKTEMP" -d)"\n'
                    "trap '\"$CI_TOOL_RM\" -rf -- \"$snapshot\"' EXIT\n"
                    'ci_git archive "$CI_EXPECTED_COMMIT" |\n'
                    '  "$CI_TOOL_TAR" -x -C "$snapshot"\n'
                    'ci_verify_archive_manifest "$snapshot"\n'
                )

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    script,
                    commit,
                )

                self.assert_guard_diagnostic(
                    result,
                    "archive regular-file content differs from captured Git blob",
                )

    def test_captured_archive_manifest_rejects_symlinked_governance_tests(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, _commit = self.create_guarded_repository(fixture_root)
            test_path = repository / self.ARCHIVE_TEST_PATH
            test_path.unlink()
            test_path.symlink_to("/tmp/outside-governance-test.py")
            self.run_fixture_git(repository, "add", ".")
            self.run_fixture_git(
                repository,
                "-c",
                "user.name=CI",
                "-c",
                "user.email=ci@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "symlinked governance test",
            )
            commit = self.run_fixture_git(
                repository,
                "rev-parse",
                "HEAD",
                capture_output=True,
                text=True,
            ).stdout.strip()
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                'snapshot="$("$CI_TOOL_MKTEMP" -d)"\n'
                "trap '\"$CI_TOOL_RM\" -rf -- \"$snapshot\"' EXIT\n"
                'ci_git archive "$CI_EXPECTED_COMMIT" |\n'
                '  "$CI_TOOL_TAR" -x -C "$snapshot"\n'
                'ci_verify_archive_manifest "$snapshot"\n'
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
            )

            self.assert_guard_diagnostic(
                result,
                "archive governance and test paths must be regular files",
            )

    def test_captured_archive_manifest_binds_regular_symlink_target_blobs(
        self,
    ) -> None:
        cases = (
            ("unchanged", False, None),
            (
                "mutated-archive-linkname",
                True,
                "archive symlink target differs from captured Git blob",
            ),
        )
        for name, mutate_linkname, expected_diagnostic in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, _commit = self.create_guarded_repository(
                    fixture_root,
                    {
                        **self.archive_contract_files(),
                        "docs/target.txt": "runtime target\n",
                    },
                )
                if mutate_linkname:
                    guard_path = repository / "scripts/ci_repository_guard.sh"
                    guard = guard_path.read_text(encoding="utf-8")
                    comparison = (
                        "                tree_mode, tree_type, tree_oid = "
                        "tree_entries[name]\n"
                        '                if tree_mode != "120000" or '
                        'tree_type != "blob":\n'
                    )
                    self.assertEqual(1, guard.count(comparison))
                    guard_path.write_text(
                        guard.replace(
                            comparison,
                            (
                                '                if name == "docs/runtime-link":\n'
                                "                    member.linkname += "
                                '"-fixture-mismatch"\n'
                                + comparison
                            ),
                        ),
                        encoding="utf-8",
                    )
                link = repository / "docs/runtime-link"
                link.symlink_to("target.txt")
                self.run_fixture_git(repository, "add", ".")
                self.run_fixture_git(
                    repository,
                    "-c",
                    "user.name=CI",
                    "-c",
                    "user.email=ci@example.invalid",
                    "commit",
                    "--quiet",
                    "-m",
                    "add runtime symlink",
                )
                commit = self.run_fixture_git(
                    repository,
                    "rev-parse",
                    "HEAD",
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                script = (
                    "set -euo pipefail\n"
                    "source scripts/ci_repository_guard.sh\n"
                    'ci_capture_repository_state "$GITHUB_SHA"\n'
                    'snapshot="$("$CI_TOOL_MKTEMP" -d)"\n'
                    "trap '\"$CI_TOOL_RM\" -rf -- \"$snapshot\"' EXIT\n"
                    'ci_git archive "$CI_EXPECTED_COMMIT" |\n'
                    '  "$CI_TOOL_TAR" -x -C "$snapshot"\n'
                    'ci_verify_archive_manifest "$snapshot"\n'
                )

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    script,
                    commit,
                )

                if expected_diagnostic is None:
                    self.assertEqual(0, result.returncode, result.stderr)
                else:
                    self.assert_guard_diagnostic(result, expected_diagnostic)

    def test_guard_rejects_ignored_untracked_files_from_every_exclude_source(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            _, linked, commit = self.create_linked_guarded_repository(
                fixture_root,
                {
                    ".gitignore": "tracked-ignore.side-effect\n",
                    "tracked.txt": "tracked\n",
                },
            )
            info_exclude = self.resolve_git_path(linked, "info/exclude")
            info_exclude.write_text(
                "info-ignore.side-effect\n", encoding="utf-8"
            )
            user_excludes = fixture_root / "user-excludes"
            user_excludes.write_text(
                "user-ignore.side-effect\n", encoding="utf-8"
            )
            self.run_fixture_git(
                linked,
                "config",
                "core.excludesFile",
                str(user_excludes),
            )
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "printf '%s\\n' side-effect > tracked-ignore.side-effect\n"
                "printf '%s\\n' side-effect > info-ignore.side-effect\n"
                "printf '%s\\n' side-effect > user-ignore.side-effect\n"
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                linked,
                script,
                commit,
            )

            self.assert_guard_diagnostic(
                result,
                "worktree has untracked or modified files after controlled scripts",
            )
            for sensitive_path in (
                "tracked-ignore.side-effect",
                "info-ignore.side-effect",
                "user-ignore.side-effect",
            ):
                self.assertNotIn(sensitive_path, result.stderr)

    def test_guard_rejects_exclude_control_file_drift(self) -> None:
        for control_name in ("info-exclude", "core-excludes-file"):
            with (
                self.subTest(control=control_name),
                tempfile.TemporaryDirectory() as directory,
            ):
                fixture_root = Path(directory)
                _, linked, commit = self.create_linked_guarded_repository(
                    fixture_root,
                    {
                        ".gitignore": "*.ignored\n",
                        "tracked.txt": "tracked\n",
                    },
                )
                if control_name == "info-exclude":
                    control_path = self.resolve_git_path(linked, "info/exclude")
                else:
                    control_path = fixture_root / "user-excludes"
                    self.run_fixture_git(
                        linked,
                        "config",
                        "core.excludesFile",
                        str(control_path),
                    )
                control_path.write_text("before.ignored\n", encoding="utf-8")
                script = (
                    "set -euo pipefail\n"
                    "source scripts/ci_repository_guard.sh\n"
                    'ci_capture_repository_state "$GITHUB_SHA"\n'
                    f"printf '%s\\n' after.ignored > {shlex.quote(str(control_path))}\n"
                    "ci_verify_repository_state\n"
                )

                result = self.run_workflow_shell(
                    fixture_root,
                    linked,
                    script,
                    commit,
                )

                self.assert_guard_diagnostic(
                    result,
                    "exclude controls changed after controlled scripts",
                )
                self.assertNotIn(str(control_path), result.stderr)

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
        parsed = yaml.safe_load(content)
        steps = parsed["jobs"]["verify"]["steps"]
        controlled_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        controlled_script = controlled_step["run"]
        test_position = controlled_script.index(
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        scanner_positions = [
            match.start()
            for match in re.finditer(
                r"check_sensitive_artifacts\.py",
                controlled_script,
            )
        ]
        artifact_positions = [
            index
            for index in range(len(controlled_script))
            if controlled_script.startswith(
                "scripts/check_modernization_artifacts.py", index
            )
        ]

        self.assertEqual(2, len(scanner_positions))
        self.assertEqual(2, len(artifact_positions))
        self.assertLess(scanner_positions[0], test_position)
        self.assertLess(artifact_positions[0], test_position)
        self.assertLess(scanner_positions[1], test_position)
        self.assertLess(artifact_positions[1], test_position)
        self.assertEqual(
            1,
            controlled_script.count(
                'ci_capture_repository_state "$GITHUB_SHA"'
            ),
        )
        self.assertEqual(2, controlled_script.count("ci_verify_repository_state"))
        self.assertEqual(
            1,
            controlled_script.count(
                'archive_pipeline_status=("${PIPESTATUS[@]}")'
            ),
        )
        self.assertEqual(
            1,
            controlled_script.count('ci_verify_archive_manifest "$snapshot"'),
        )
        self.assertLess(
            controlled_script.index(
                'archive_status="${archive_pipeline_status[0]}"'
            ),
            controlled_script.index(
                'tar_status="${archive_pipeline_status[1]}"'
            ),
        )
        self.assertRegex(
            controlled_script,
            r'ci_git archive "\$CI_EXPECTED_COMMIT" \|\n'
            r'\s+"\$CI_TOOL_TAR" -x -C "\$snapshot"',
        )
        self.assertNotRegex(controlled_script, r"(^|\s)python3\s")
        self.assertNotRegex(controlled_script, r"(^|\s)git\s")
        self.assertEqual(4, content.count('--commit "$CI_EXPECTED_COMMIT"'))
        self.assertEqual(
            4, content.count('--repository-root "$CI_EXPECTED_WORKSPACE"')
        )
        self.assertEqual(2, content.count('--base-commit "$TRUSTED_POLICY_COMMIT"'))
        self.assertEqual(
            2,
            content.count('--trusted-policy-commit "$TRUSTED_POLICY_COMMIT"'),
        )
        self.assertEqual(
            "${{ github.event.pull_request.base.sha || github.event.before }}",
            controlled_step.get("env", {}).get("TRUSTED_POLICY_COMMIT"),
        )

    def test_checkout_history_and_python_runtime_are_immutable_inputs(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("timeout-minutes: 20", content)
        self.assertIn("runs-on: ubuntu-24.04", content)
        self.assertNotIn("ubuntu-latest", content)
        self.assertRegex(
            content,
            r"container:\n\s+image: python:3\.13\.14-bookworm@sha256:[0-9a-f]{64}",
        )
        self.assertIn("options: --platform linux/amd64", content)
        self.assertIn("fetch-depth: 0", content)
        self.assertNotIn("actions/setup-python@", content)
        self.assertIn("ci_prepare_python_dependencies --require-hashes", content)
        guard = REPOSITORY_GUARD.read_text(encoding="utf-8")
        for locked_install_option in (
            "--require-hashes",
            "--no-deps",
            "--only-binary=:all:",
            "--no-compile",
            '--target "$CI_PYTHON_DEPENDENCY_ROOT"',
        ):
            self.assertIn(locked_install_option, guard)
        for command in (
            "check-doc-decisions.py",
            "check_project_skills.py",
            "check_sensitive_artifacts.py",
            "check_modernization_artifacts.py",
            "-m unittest",
        ):
            self.assertIn(command, content)
        self.assertIn("ci_python ", content)
        self.assertNotIn('"$CI_TOOL_PYTHON3"', content)

    def test_isolated_python_ignores_a_sibling_stdlib_shadow_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            dependency_root = fixture_root / "python-dependencies"
            yaml_package = Path(yaml.__file__).resolve().parent
            shutil.copytree(yaml_package, dependency_root / "yaml")
            repository, commit = self.create_guarded_repository(
                fixture_root,
                {
                    **self.archive_contract_files(),
                    "scripts/pathlib.py": "raise SystemExit(0)\n",
                    "scripts/check_sensitive_artifacts.py": (
                        REPOSITORY / "scripts/check_sensitive_artifacts.py"
                    ).read_text(encoding="utf-8"),
                    "docs/evidence.md": (
                        "client_sec" + "ret=must-be-detected\n"
                    ),
                },
            )
            script = (
                "set -euo pipefail\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "ci_capture_python_dependency_state\n"
                "ci_python scripts/check_sensitive_artifacts.py docs\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
                {"CI_PYTHON_DEPENDENCY_ROOT": str(dependency_root)},
            )

            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("GENERIC_SECRET_ASSIGNMENT", result.stderr)

    def test_isolation_regression_uses_the_guarded_python_runner(self) -> None:
        content = Path(__file__).read_text(encoding="utf-8")
        match = re.search(
            (
                r"    def "
                r"test_isolated_python_ignores_a_sibling_stdlib_shadow_module"
                r"\(.*?(?=\n    def )"
            ),
            content,
            re.DOTALL,
        )

        self.assertIsNotNone(match)
        regression = match.group(0) if match is not None else ""
        self.assertIn("run_workflow_shell", regression)
        self.assertIn("ci_python", regression)
        self.assertNotIn("subprocess.run", regression)

    def test_tests_do_not_spawn_an_uncontrolled_python_interpreter(self) -> None:
        violations: list[str] = []
        for test_path in sorted((REPOSITORY / "scripts/tests").glob("test_*.py")):
            tree = ast.parse(test_path.read_text(encoding="utf-8"))
            for call in (
                node for node in ast.walk(tree) if isinstance(node, ast.Call)
            ):
                function = call.func
                if not (
                    isinstance(function, ast.Attribute)
                    and function.attr in {"run", "Popen"}
                    and isinstance(function.value, ast.Name)
                    and function.value.id == "subprocess"
                ):
                    continue
                if any(
                    isinstance(node, ast.Attribute)
                    and node.attr == "executable"
                    and isinstance(node.value, ast.Name)
                    and node.value.id == "sys"
                    for argument in call.args
                    for node in ast.walk(argument)
                ):
                    violations.append(f"{test_path.name}:{call.lineno}")

        self.assertEqual(
            [],
            violations,
            "tests must not bypass ci_python via sys.executable",
        )

    def test_git_plumbing_guard_rejects_a_test_that_rewrites_a_tracked_file(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(
                fixture_root,
                {"docs/evidence.md": "safe\n"},
            )
            script = (
                "set -u\n"
                "source scripts/ci_repository_guard.sh\n"
                'ci_capture_repository_state "$GITHUB_SHA"\n'
                "printf '%s\\n' mutated-by-test > "
                '"$CI_EXPECTED_WORKSPACE/docs/evidence.md"\n'
                "ci_verify_repository_state\n"
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                script,
                commit,
            )

            self.assert_guard_diagnostic(
                result,
                "worktree content could not refresh against the captured tree",
            )

    def test_tracked_rewrite_regression_reaches_the_real_guard_entrypoint(
        self,
    ) -> None:
        content = Path(__file__).read_text(encoding="utf-8")
        match = re.search(
            (
                r"    def "
                r"test_git_plumbing_guard_rejects_a_test_that_rewrites_a_tracked_file"
                r"\(.*?(?=\n    def )"
            ),
            content,
            re.DOTALL,
        )

        self.assertIsNotNone(match)
        regression = match.group(0) if match is not None else ""
        for required_entrypoint in (
            "run_workflow_shell",
            "source scripts/ci_repository_guard.sh",
            "ci_capture_repository_state",
            "ci_verify_repository_state",
            "assert_guard_diagnostic",
        ):
            self.assertIn(required_entrypoint, regression)
        self.assertNotIn('"diff"', regression)

    def test_real_post_test_guard_rejects_a_replace_ref_attack(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        script = test_step["run"]
        original_test_command = (
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        self.assertIn(original_test_command, script)
        attack = (
            'git -C "$CI_EXPECTED_WORKSPACE" replace '
            '"$CI_EXPECTED_COMMIT" "$REPLACEMENT_SHA"\n'
            'git -C "$CI_EXPECTED_WORKSPACE" read-tree '
            '--reset -u "${REPLACEMENT_SHA}^{tree}"'
        )
        attacked_script = script.replace(original_test_command, attack)

        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, original = self.create_guarded_repository(fixture_root)
            tracked = repository / "tracked.txt"
            tracked.write_text("replacement tree\n", encoding="utf-8")
            self.run_fixture_git(repository, "add", ".")
            self.run_fixture_git(
                repository,
                "-c",
                "user.name=CI",
                "-c",
                "user.email=ci@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "replacement",
            )
            replacement = self.run_fixture_git(
                repository,
                "rev-parse",
                "HEAD",
                capture_output=True,
                text=True,
            ).stdout.strip()
            self.run_fixture_git(
                repository,
                "checkout",
                "--quiet",
                "--detach",
                original,
            )

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                attacked_script,
                original,
                {"REPLACEMENT_SHA": replacement},
            )

            self.assert_guard_diagnostic(
                result,
                "replace-ref directory is not empty",
            )

    def test_guarded_steps_verify_after_a_controlled_command_failure(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        controlled_step = next(
            step for step in steps if step.get("name") == CONTROLLED_STEP_NAME
        )
        cases = (
            ("early-gate", "ci_python scripts/check-doc-decisions.py"),
            (
                "governance-tests",
                'ci_python -m unittest discover -s scripts/tests -p "test_*.py"',
            ),
        )

        for phase, controlled_command in cases:
            with self.subTest(phase=phase):
                with tempfile.TemporaryDirectory() as directory:
                    fixture_root = Path(directory)
                    repository, commit = self.create_guarded_repository(fixture_root)
                    failing_command = (
                        "printf '%s\\n' changed > "
                        '"$CI_EXPECTED_WORKSPACE/controlled-command-artifact"\n'
                        "(exit 23)"
                    )
                    script = controlled_step["run"].replace(
                        controlled_command,
                        failing_command,
                        1,
                    )

                    result = self.run_workflow_shell(
                        fixture_root,
                        repository,
                        script,
                        commit,
                    )

                    self.assertEqual(
                        23,
                        result.returncode,
                        result.stdout + result.stderr,
                    )
                    self.assertIn(
                        "CI repository guard failed: worktree has untracked "
                        "or modified files after controlled scripts",
                        result.stderr,
                    )

    def test_guarded_workflow_rejects_final_dependency_root_mutation(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        original_test_command = (
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        self.assertIn(original_test_command, test_step["run"])
        attacked_script = test_step["run"].replace(
            original_test_command,
            "printf '%s\\n' forged > "
            '"$CI_PYTHON_DEPENDENCY_ROOT/unittest.py"',
            1,
        )

        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            repository, commit = self.create_guarded_repository(fixture_root)

            result = self.run_workflow_shell(
                fixture_root,
                repository,
                attacked_script,
                commit,
            )

        self.assert_guard_diagnostic(
            result,
            "Python dependency root content or metadata changed",
        )

    def test_archive_pipeline_preserves_first_stage_failure_and_runs_verifier(
        self,
    ) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        original_test_command = (
            'ci_python -m unittest discover '
            '-s scripts/tests -p "test_*.py"'
        )
        archive_command = 'ci_git archive "$CI_EXPECTED_COMMIT"'
        tar_command = '"$CI_TOOL_TAR" -x -C "$snapshot"'
        self.assertIn(original_test_command, test_step["run"])
        self.assertIn(archive_command, test_step["run"])
        self.assertIn(tar_command, test_step["run"])
        cases = {
            "archive-failure": (23, 0, 23),
            "tar-failure": (0, 24, 24),
            "double-failure": (23, 24, 23),
        }

        for name, (archive_status, tar_status, expected_status) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, commit = self.create_guarded_repository(fixture_root)
                archive_stage = (
                    "(printf '%s\\n' changed > "
                    '"$CI_EXPECTED_WORKSPACE/pipeline-status-artifact"; '
                    f"exit {archive_status})"
                )
                tar_stage = f"(exit {tar_status})"
                script = test_step["run"].replace(
                    original_test_command,
                    ":",
                    1,
                )
                script = script.replace(archive_command, archive_stage, 1)
                script = script.replace(tar_command, tar_stage, 1)

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    script,
                    commit,
                )

                self.assertEqual(
                    expected_status,
                    result.returncode,
                    result.stdout + result.stderr,
                )
                self.assertIn(
                    (
                        "Archive pipeline failed: "
                        f"archive_status={archive_status} tar_status={tar_status}"
                    ),
                    result.stderr,
                )
                self.assertIn(
                    "CI repository guard failed: worktree has untracked "
                    "or modified files after controlled scripts",
                    result.stderr,
                )

    def test_real_post_test_guard_rejects_hidden_index_flag_mutations(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        script = test_step["run"]
        original_test_command = (
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        attacks = {
            "assume-unchanged": (
                "printf '%s\\n' mutated-by-test > "
                '"$CI_EXPECTED_WORKSPACE/tracked.txt"\n'
                'git -C "$CI_EXPECTED_WORKSPACE" update-index '
                "--assume-unchanged tracked.txt"
            ),
            "skip-worktree": (
                'git -C "$CI_EXPECTED_WORKSPACE" update-index '
                "--skip-worktree tracked.txt\n"
                "printf '%s\\n' mutated-by-test > "
                '"$CI_EXPECTED_WORKSPACE/tracked.txt"'
            ),
        }

        for name, attack in attacks.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, original = self.create_guarded_repository(fixture_root)
                attacked_script = script.replace(original_test_command, attack)

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    attacked_script,
                    original,
                )

                self.assert_guard_diagnostic(
                    result,
                    "index contains hidden tracked-file flags",
                )

    def test_real_post_test_guard_rejects_broken_graph_override_files(self) -> None:
        parsed = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = parsed["jobs"]["verify"]["steps"]
        test_step = next(
            step
            for step in steps
            if step.get("name") == CONTROLLED_STEP_NAME
        )
        script = test_step["run"]
        original_test_command = (
            'ci_python -m unittest discover -s scripts/tests -p "test_*.py"'
        )
        attacks = {
            "broken-replace-ref": (
                'replace_dir="$(git -C "$CI_EXPECTED_WORKSPACE" '
                "--no-replace-objects rev-parse "
                '--path-format=absolute --git-path refs/replace)"\n'
                'mkdir -p "$replace_dir"\n'
                "printf '%s\\n' not-an-object > \"$replace_dir/broken\"",
                "replace-ref directory is not empty",
            ),
            "graft-parent-rewrite": (
                'grafts="$(git -C "$CI_EXPECTED_WORKSPACE" '
                "--no-replace-objects rev-parse "
                '--path-format=absolute --git-path info/grafts)"\n'
                'mkdir -p "$(dirname "$grafts")"\n'
                "printf '%s\\n' \"$CI_EXPECTED_COMMIT\" > \"$grafts\"",
                "Git graft metadata is forbidden",
            ),
            "alternate-graft-newline-path": (
                "alternate_grafts="
                "\"${CI_EXPECTED_WORKSPACE}/alternate-grafts\"$'\\n'\n"
                "printf '%s\\n' \"$CI_EXPECTED_COMMIT\" > \"$alternate_grafts\"\n"
                'export GIT_GRAFT_FILE="$alternate_grafts"',
                "worktree has untracked or modified files after controlled scripts",
            ),
        }

        for name, (attack, expected_diagnostic) in attacks.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture_root = Path(directory)
                repository, original = self.create_guarded_repository(fixture_root)
                attacked_script = script.replace(original_test_command, attack)

                result = self.run_workflow_shell(
                    fixture_root,
                    repository,
                    attacked_script,
                    original,
                )

                self.assert_guard_diagnostic(
                    result,
                    expected_diagnostic,
                )


if __name__ == "__main__":
    unittest.main()
