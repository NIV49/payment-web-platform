from __future__ import annotations

import copy
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

import yaml


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from check_project_skills import UniqueKeySafeLoader  # noqa: E402


REPOSITORY = SCRIPTS_DIR.parent
WORKFLOW_DIRECTORY = REPOSITORY / ".github/workflows"
ROOT_WORKFLOWS = tuple(
    sorted(
        {
            *WORKFLOW_DIRECTORY.glob("*.yml"),
            *WORKFLOW_DIRECTORY.glob("*.yaml"),
        }
    )
)
WORKFLOW = REPOSITORY / ".github/workflows/documentation.yml"
REPOSITORY_GUARD = REPOSITORY / "scripts/ci_repository_guard.sh"
CHECKOUT_ACTION_SLUG = "actions/checkout"
CHECKOUT_ACTION_REVISION = "df4cb1c069e1874edd31b4311f1884172cec0e10"
CHECKOUT_ACTION = f"{CHECKOUT_ACTION_SLUG}@{CHECKOUT_ACTION_REVISION}"
WORKSPACE_PREFLIGHT_STEP_NAME = "Verify checkout workspace access"
CHECKOUT_STEP_NAME = "Check out repository"
PREPARATION_STEP_NAME = (
    "Capture Python runtime and prepare pinned documentation dependencies"
)
CONTROLLED_STEP_NAME = (
    "Verify and test documentation governance without snapshot drift"
)
STATIC_RUN_SHELL = "/bin/sh -eu {0}"
RUN_SCRIPT_HEREDOC = "DOCUMENTATION_CI_SCRIPT"
RUN_STEP_BOUNDARIES = {
    WORKSPACE_PREFLIGHT_STEP_NAME: (
        {"GITHUB_WORKSPACE": "${{ github.workspace }}"},
        (
            "PATH=/usr/bin:/bin",
            "HOME=/tmp/documentation-ci-home",
            'GITHUB_WORKSPACE="$GITHUB_WORKSPACE"',
        ),
    ),
    PREPARATION_STEP_NAME: (
        {"GITHUB_SHA": "${{ github.sha }}"},
        (
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "HOME=/tmp/documentation-ci-home",
            "LANG=C",
            "LC_ALL=C",
            "CI_PYTHON_DEPENDENCY_ROOT=/tmp/documentation-python-dependencies",
            "CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME=1",
            'GITHUB_SHA="$GITHUB_SHA"',
        ),
    ),
    CONTROLLED_STEP_NAME: (
        {
            "GITHUB_SHA": "${{ github.sha }}",
            "TRUSTED_POLICY_COMMIT": (
                "${{ github.event.pull_request.base.sha || github.event.before }}"
            ),
        },
        (
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "HOME=/tmp/documentation-ci-home",
            "LANG=C",
            "LC_ALL=C",
            "CI_PYTHON_DEPENDENCY_ROOT=/tmp/documentation-python-dependencies",
            "CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME=1",
            'GITHUB_SHA="$GITHUB_SHA"',
            'TRUSTED_POLICY_COMMIT="$TRUSTED_POLICY_COMMIT"',
        ),
    ),
}


class DocumentationPythonRuntimeSecurityTest(unittest.TestCase):
    LOCAL_EXECUTABLES = ("bash", "env", "python3")

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

    @classmethod
    def local_shell_environment(cls, home: Path) -> dict[str, str]:
        return {
            "PATH": cls.local_safe_path,
            "HOME": str(home),
            "LANG": "C",
            "LC_ALL": "C",
        }

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

    def load_workflow_text(self, content: str) -> dict[str, Any]:
        parsed = yaml.load(
            content,
            Loader=UniqueKeySafeLoader,
        )
        self.assertIsInstance(parsed, dict)
        return parsed

    def load_workflow(self) -> dict[str, Any]:
        return self.load_workflow_text(WORKFLOW.read_text(encoding="utf-8"))

    def checkout_action_revision(self, uses: object) -> str | None:
        if not isinstance(uses, str):
            return None
        slug, separator, revision = uses.partition("@")
        if separator != "@" or slug.casefold() != CHECKOUT_ACTION_SLUG.casefold():
            return None
        return revision

    def assert_checkout_steps_secure(
        self,
        workflow: dict[str, Any],
        *,
        require_full_history: bool = False,
    ) -> None:
        checkout_steps = [
            step
            for job in workflow["jobs"].values()
            for step in job.get("steps", ())
            if isinstance(step, dict)
            and self.checkout_action_revision(step.get("uses")) is not None
        ]
        self.assertTrue(checkout_steps)
        for checkout in checkout_steps:
            self.assertEqual(
                CHECKOUT_ACTION_REVISION,
                self.checkout_action_revision(checkout["uses"]),
            )
            options = checkout.get("with")
            self.assertIsInstance(options, dict)
            self.assertIs(options.get("persist-credentials"), False)
            if require_full_history:
                self.assertEqual(0, options.get("fetch-depth"))

    def isolated_run_bodies(
        self,
        workflow: dict[str, Any],
    ) -> dict[str, str]:
        steps = workflow["jobs"]["verify"]["steps"]
        run_steps = {step["name"]: step for step in steps if "run" in step}
        self.assertEqual(set(RUN_STEP_BOUNDARIES), set(run_steps))

        bodies: dict[str, str] = {}
        for name, (expected_env, forwarded_environment) in RUN_STEP_BOUNDARIES.items():
            step = run_steps[name]
            self.assertEqual(
                {"name", "shell", "env", "run"},
                set(step),
                name,
            )
            self.assertEqual(STATIC_RUN_SHELL, step["shell"], name)
            self.assertNotIn("${{", step["shell"], name)
            self.assertEqual(expected_env, step["env"], name)
            self.assertTrue(
                all(isinstance(key, str) for key in step["env"]),
                name,
            )
            self.assertTrue(
                all(isinstance(value, str) for value in step["env"].values()),
                name,
            )

            run = step["run"]
            self.assertNotIn("${{", run, name)
            prologue = ["exec /usr/bin/env -i \\"]
            prologue.extend(
                f"  {assignment} \\" for assignment in forwarded_environment
            )
            prologue.append(
                "  /bin/bash --noprofile --norc -e -u -o pipefail "
                f"<<'{RUN_SCRIPT_HEREDOC}'"
            )
            lines = run.splitlines()
            self.assertGreater(len(lines), len(prologue) + 1, name)
            self.assertEqual(prologue, lines[: len(prologue)], name)
            self.assertEqual(RUN_SCRIPT_HEREDOC, lines[-1], name)
            self.assertEqual(1, run.count("exec /usr/bin/env -i"), name)
            self.assertEqual(2, run.count(RUN_SCRIPT_HEREDOC), name)
            bodies[name] = "\n".join(lines[len(prologue) : -1]) + "\n"

        return bodies

    def assert_documentation_container_options(
        self,
        workflow: dict[str, Any],
    ) -> None:
        container_jobs = {
            name: job["container"]
            for name, job in workflow["jobs"].items()
            if "container" in job
        }

        self.assertEqual({"verify"}, set(container_jobs))
        options = shlex.split(container_jobs["verify"]["options"])
        self.assertEqual(
            [
                "--platform",
                "linux/amd64",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--cap-add",
                "DAC_OVERRIDE",
                "--tmpfs",
                "/tmp:rw,exec,nosuid,nodev,mode=1777",
            ],
            options,
        )

    def assert_python_runtime_immutability_preflight(
        self,
        workflow: dict[str, Any],
    ) -> None:
        jobs = workflow["jobs"]
        self.assertEqual({"verify"}, set(jobs))
        verify_job = jobs["verify"]
        self.assertEqual(
            {"name", "runs-on", "container", "timeout-minutes", "steps"},
            set(verify_job),
        )
        self.assertEqual("documentation-verify", verify_job["name"])
        self.assertEqual("ubuntu-24.04", verify_job["runs-on"])
        self.assertEqual(20, verify_job["timeout-minutes"])
        self.assertEqual({"image", "options"}, set(verify_job["container"]))
        self.assertEqual(
            "python:3.13.14-bookworm@sha256:"
            "b4d62b6602fd5b284f82cb9b733cc550f237f847c065b84cbb54dfe28ce837a2",
            verify_job["container"]["image"],
        )
        self.assert_documentation_container_options(workflow)

        steps = verify_job["steps"]
        names = [step.get("name") for step in steps]
        self.assertEqual(
            [
                WORKSPACE_PREFLIGHT_STEP_NAME,
                CHECKOUT_STEP_NAME,
                PREPARATION_STEP_NAME,
                CONTROLLED_STEP_NAME,
            ],
            names,
        )
        workspace_preflight, checkout, preparation, controlled = steps
        allowed_step_keys = {"name", "shell", "env", "run"}
        self.assertEqual(allowed_step_keys, set(workspace_preflight))
        self.assertEqual({"name", "uses", "with"}, set(checkout))
        self.assertEqual(allowed_step_keys, set(preparation))
        self.assertEqual(allowed_step_keys, set(controlled))
        self.assertEqual(
            CHECKOUT_ACTION,
            checkout["uses"],
        )
        self.assertEqual(
            {"fetch-depth": 0, "persist-credentials": False},
            checkout["with"],
        )

        bodies = self.isolated_run_bodies(workflow)
        self.assertEqual(
            'set -u\n'
            'source scripts/ci_repository_guard.sh\n'
            'ci_capture_repository_state "$GITHUB_SHA"\n'
            'ci_verify_python_runtime_immutability\n'
            'ci_prepare_python_dependencies --require-hashes \\\n'
            '  "$CI_EXPECTED_WORKSPACE/scripts/requirements-documentation.txt"\n'
            'ci_verify_python_execution_environment\n'
            'ci_verify_repository_state\n',
            bodies[PREPARATION_STEP_NAME],
        )
        controlled_prefix = (
            'set -u\n'
            'source scripts/ci_repository_guard.sh\n'
            'ci_capture_repository_state "$GITHUB_SHA"\n'
            'ci_verify_python_runtime_immutability\n'
            'ci_capture_python_dependency_state\n'
            'ci_verify_python_execution_environment\n'
            'set +e\n'
            '(\n'
            '  set -e\n'
        )
        self.assertEqual(
            controlled_prefix,
            bodies[CONTROLLED_STEP_NAME][: len(controlled_prefix)],
        )

    def test_run_step_boundaries_reject_host_environment_and_command_injection(
        self,
    ) -> None:
        workflow = self.load_workflow()
        self.isolated_run_bodies(workflow)

        def mutate_step(
            step_name: str,
            mutation: object,
        ) -> dict[str, Any]:
            candidate = copy.deepcopy(workflow)
            step = next(
                item
                for item in candidate["jobs"]["verify"]["steps"]
                if item.get("name") == step_name
            )
            mutation(step)
            return candidate

        mutations = {
            "dynamic shell": mutate_step(
                PREPARATION_STEP_NAME,
                lambda step: step.__setitem__(
                    "shell",
                    "/bin/sh -eu ${{ github.workspace }}/{0}",
                ),
            ),
            "missing context env": mutate_step(
                PREPARATION_STEP_NAME,
                lambda step: step["env"].pop("GITHUB_SHA"),
            ),
            "extra context env": mutate_step(
                PREPARATION_STEP_NAME,
                lambda step: step["env"].__setitem__(
                    "ATTACKER_INPUT",
                    "${{ github.event.pull_request.title }}",
                ),
            ),
            "pre-boundary command": mutate_step(
                CONTROLLED_STEP_NAME,
                lambda step: step.__setitem__("run", "/bin/true\n" + step["run"]),
            ),
            "pre-boundary assignment": mutate_step(
                CONTROLLED_STEP_NAME,
                lambda step: step.__setitem__("run", "attack=1\n" + step["run"]),
            ),
            "pre-boundary source": mutate_step(
                CONTROLLED_STEP_NAME,
                lambda step: step.__setitem__(
                    "run",
                    "source /tmp/attacker-profile\n" + step["run"],
                ),
            ),
            "env-i argument injection": mutate_step(
                CONTROLLED_STEP_NAME,
                lambda step: step.__setitem__(
                    "run",
                    step["run"].replace(
                        "exec /usr/bin/env -i \\\n",
                        "exec /usr/bin/env -i \\\n  ATTACKER_INPUT=enabled \\\n",
                        1,
                    ),
                ),
            ),
        }
        for dangerous_name in ("BASH_ENV", "ENV", "SHELLOPTS"):
            mutations[f"dangerous {dangerous_name}"] = mutate_step(
                CONTROLLED_STEP_NAME,
                lambda step, name=dangerous_name: step["env"].__setitem__(
                    name,
                    "/tmp/attacker-profile",
                ),
            )

        for label, mutation in mutations.items():
            with self.subTest(attack=label):
                with self.assertRaises(AssertionError):
                    self.isolated_run_bodies(mutation)

    def create_runtime_probe_launcher(
        self,
        fixture_root: Path,
        *,
        deny_stdlib_write: bool,
        writable_directory: str | None,
    ) -> tuple[Path, Path]:
        base_prefix = fixture_root / "python-runtime"
        version = f"python{sys.version_info.major}.{sys.version_info.minor}"
        library_root = base_prefix / "lib"
        runtime_root = library_root / version
        dynamic_root = runtime_root / "lib-dynload"
        site_root = runtime_root / "site-packages"
        dynamic_root.mkdir(parents=True)
        site_root.mkdir()
        stdlib_probe = runtime_root / "tarfile.py"
        stdlib_probe.write_text("# runtime probe fixture\n", encoding="utf-8")
        marker = fixture_root / "python-launcher-invoked"
        launcher = fixture_root / "python-runtime-launcher"
        launcher.write_text(
            f"""#!{self.local_tools['python3']}
import errno
import os
import sys

expected = ["-B", "-I", "-S", "-c"]
if len(sys.argv) != 6 or sys.argv[1:5] != expected:
    raise SystemExit(f"unexpected Python probe arguments: {{sys.argv[1:]!r}}")

with open({str(marker)!r}, "w", encoding="utf-8") as marker_file:
    marker_file.write("invoked\\n")

stdlib_probe = {str(stdlib_probe)!r}
deny_stdlib_write = {deny_stdlib_write!r}
writable_directory = {writable_directory!r}
directory_labels = {{
    (metadata.st_dev, metadata.st_ino): label
    for path, label in (
        ({str(library_root)!r}, "library"),
        ({str(runtime_root)!r}, "runtime"),
        ({str(dynamic_root)!r}, "lib-dynload"),
        ({str(site_root)!r}, "site-packages"),
    )
    for metadata in (os.stat(path),)
}}
original_open = os.open
original_mkdir = os.mkdir


def guarded_open(path, flags, mode=0o777, *, dir_fd=None):
    write_requested = flags & (os.O_WRONLY | os.O_RDWR)
    if deny_stdlib_write and path == stdlib_probe and write_requested:
        raise OSError(errno.EROFS, os.strerror(errno.EROFS), path)
    return original_open(path, flags, mode, dir_fd=dir_fd)


def guarded_mkdir(path, mode=0o777, *, dir_fd=None):
    identity = os.fstat(dir_fd)
    label = directory_labels.get((identity.st_dev, identity.st_ino))
    if label != writable_directory:
        raise OSError(errno.EROFS, os.strerror(errno.EROFS), path)
    return original_mkdir(path, mode, dir_fd=dir_fd)


os.open = guarded_open
os.mkdir = guarded_mkdir
sys.base_prefix = {str(base_prefix)!r}
sys.path = [
    {str(library_root / f"python{sys.version_info.major}{sys.version_info.minor}.zip")!r},
    {str(runtime_root)!r},
    {str(dynamic_root)!r},
]
exec(compile(sys.argv[5], "<runtime-immutability-probe>", "exec"))
""",
            encoding="utf-8",
        )
        launcher.chmod(0o755)
        return launcher, marker

    def run_runtime_immutability_guard(
        self,
        fixture_root: Path,
        launcher: Path,
        requirement: str | None,
    ) -> subprocess.CompletedProcess[str]:
        home = fixture_root / "home"
        home.mkdir()
        environment = self.local_shell_environment(home)
        environment.update(
            {
                "CI_SAFE_HOME": str(home),
                "CI_SAFE_PATH": self.local_safe_path,
                "CI_TOOL_ENV": self.local_tools["env"],
                "CI_TOOL_PYTHON3": str(launcher),
            }
        )
        if requirement is not None:
            environment["CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME"] = requirement
        return subprocess.run(
            (
                self.local_tools["bash"],
                "--noprofile",
                "--norc",
                "-u",
                "-o",
                "pipefail",
                "-c",
                "source scripts/ci_repository_guard.sh\n"
                "ci_verify_python_runtime_immutability\n",
            ),
            cwd=REPOSITORY,
            capture_output=True,
            text=True,
            env=environment,
        )

    def test_documentation_container_keeps_python_runtime_read_only(self) -> None:
        workflow = self.load_workflow()

        self.assert_documentation_container_options(workflow)

    def test_container_options_ignore_block_scalar_decoys(self) -> None:
        expected_options = (
            "--platform linux/amd64 --read-only --cap-drop ALL "
            "--cap-add DAC_OVERRIDE "
            "--tmpfs /tmp:rw,exec,nosuid,nodev,mode=1777"
        )
        content = WORKFLOW.read_text(encoding="utf-8")
        content = content.replace(
            "    runs-on: ubuntu-24.04\n",
            "    runs-on: ubuntu-24.04\n"
            "    env:\n"
            "      OPTIONS_DECOY: |\n"
            f"        options: {expected_options}\n",
            1,
        )
        content = content.replace(
            f"\n      options: {expected_options}\n",
            "\n      options: --platform linux/amd64 --privileged\n",
            1,
        )

        legacy_match = re.search(
            r"^\s+options:\s*(?P<options>.+)$",
            content,
            re.MULTILINE,
        )
        self.assertIsNotNone(legacy_match)
        self.assertEqual(expected_options, legacy_match.group("options"))
        workflow = self.load_workflow_text(content)
        self.assertEqual(
            "--platform linux/amd64 --privileged",
            workflow["jobs"]["verify"]["container"]["options"],
        )
        with self.assertRaises(AssertionError):
            self.assert_documentation_container_options(workflow)

    def test_workflow_loader_rejects_duplicate_mapping_keys(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        options_line = (
            "      options: --platform linux/amd64 --read-only --cap-drop ALL "
            "--cap-add DAC_OVERRIDE "
            "--tmpfs /tmp:rw,exec,nosuid,nodev,mode=1777\n"
        )
        preflight_run = (
            "        env:\n"
            "          GITHUB_WORKSPACE: ${{ github.workspace }}\n"
            "        run: |\n"
        )
        self.assertEqual(1, content.count(options_line))
        self.assertEqual(1, content.count(preflight_run))
        mutations = {
            "top-level jobs": content + "\njobs: {}\n",
            "container options": content.replace(
                options_line,
                options_line + "      options: --privileged\n",
                1,
            ),
            "step run": content.replace(
                preflight_run,
                preflight_run.replace(
                    "        run: |\n",
                    "        run: echo bypass\n        run: |\n",
                ),
                1,
            ),
        }

        for label, mutation in mutations.items():
            with self.subTest(mapping=label):
                with self.assertRaises(yaml.constructor.ConstructorError):
                    self.load_workflow_text(mutation)

    def test_workflow_preflights_workspace_access_before_checkout(self) -> None:
        workflow = self.load_workflow()
        bodies = self.isolated_run_bodies(workflow)
        steps = workflow["jobs"]["verify"]["steps"]
        names = [step.get("name") for step in steps]
        preflight_index = names.index(WORKSPACE_PREFLIGHT_STEP_NAME)
        checkout_index = names.index(CHECKOUT_STEP_NAME)

        self.assertLess(preflight_index, checkout_index)
        self.assertEqual(1, names.count(WORKSPACE_PREFLIGHT_STEP_NAME))
        preflight = steps[preflight_index]
        self.assertEqual(STATIC_RUN_SHELL, preflight["shell"])
        self.assertEqual(
            {"GITHUB_WORKSPACE": "${{ github.workspace }}"},
            preflight["env"],
        )
        self.assertEqual(
            'probe="$GITHUB_WORKSPACE/.ci-checkout-write-probe-$$"\n'
            '/usr/bin/mkdir -- "$probe"\n'
            '/usr/bin/rmdir -- "$probe"\n',
            bodies[WORKSPACE_PREFLIGHT_STEP_NAME],
        )

    def test_root_workflow_checkouts_do_not_persist_credentials(self) -> None:
        self.assertEqual(
            {"backend.yml", "documentation.yml", "frontend.yml"},
            {path.name for path in ROOT_WORKFLOWS},
        )

        for path in ROOT_WORKFLOWS:
            workflow = self.load_workflow_text(path.read_text(encoding="utf-8"))
            with self.subTest(workflow=path.name):
                self.assert_checkout_steps_secure(
                    workflow,
                    require_full_history=path == WORKFLOW,
                )

    def test_mixed_case_checkout_cannot_bypass_credential_policy(self) -> None:
        workflow = self.load_workflow()
        mixed_case_checkout = {
            "name": "Case-insensitive checkout credential bypass",
            "uses": f"Actions/Checkout@{CHECKOUT_ACTION_REVISION}",
        }
        workflow["jobs"]["verify"]["steps"].append(mixed_case_checkout)

        self.assertEqual(
            CHECKOUT_ACTION_REVISION,
            self.checkout_action_revision(mixed_case_checkout["uses"]),
        )
        with self.assertRaises(AssertionError):
            self.assert_checkout_steps_secure(
                workflow,
                require_full_history=True,
            )

    def test_workflow_preflights_python_runtime_immutability(self) -> None:
        workflow = self.load_workflow()

        self.assert_python_runtime_immutability_preflight(workflow)

    def test_runtime_preflight_rejects_non_executable_guard_decoys(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        invocation = "\n          ci_verify_python_runtime_immutability\n"
        self.assertEqual(2, content.count(invocation))
        replacements = {
            "comment": "\n          # ci_verify_python_runtime_immutability\n",
            "heredoc": (
                "\n          cat <<'RUNTIME_GUARD_DECOY'\n"
                "          ci_verify_python_runtime_immutability\n"
                "          RUNTIME_GUARD_DECOY\n"
            ),
        }

        for label, replacement in replacements.items():
            with self.subTest(decoy=label):
                mutation = content.replace(invocation, replacement, 2)
                self.assertEqual(
                    2,
                    mutation.count("ci_verify_python_runtime_immutability"),
                )
                workflow = self.load_workflow_text(mutation)
                with self.assertRaises(AssertionError):
                    self.assert_python_runtime_immutability_preflight(workflow)

    def test_runtime_preflight_rejects_requirement_text_outside_boundary(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        requirement = "  CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME=1 \\\n"
        invocation = "\n          ci_verify_python_runtime_immutability\n"
        self.assertEqual(2, content.count(requirement))
        mutation = content.replace(requirement, "", 2).replace(
            invocation,
            "\n          # CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME=1\n"
            "          ci_verify_python_runtime_immutability\n",
            2,
        )

        self.assertEqual(2, mutation.count("CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME=1"))
        workflow = self.load_workflow_text(mutation)
        with self.assertRaises(AssertionError):
            self.assert_python_runtime_immutability_preflight(workflow)

    def test_runtime_preflight_rejects_execution_control_fields(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        job_anchor = "    runs-on: ubuntu-24.04\n"
        self.assertEqual(1, content.count(job_anchor))

        def add_to_runtime_steps(field: str) -> str:
            mutation = content
            for name in (PREPARATION_STEP_NAME, CONTROLLED_STEP_NAME):
                anchor = f"      - name: {name}\n"
                self.assertEqual(1, mutation.count(anchor))
                mutation = mutation.replace(anchor, anchor + field, 1)
            return mutation

        mutations = {
            "extra job": content.replace(
                "jobs:\n",
                "jobs:\n"
                "  decoy:\n"
                "    runs-on: ubuntu-24.04\n"
                "    steps:\n"
                "      - run: /bin/true\n",
                1,
            ),
            "job if": content.replace(
                job_anchor,
                "    if: ${{ false }}\n" + job_anchor,
                1,
            ),
            "job needs": content.replace(
                job_anchor,
                "    needs: []\n" + job_anchor,
                1,
            ),
            "job continue-on-error": content.replace(
                job_anchor,
                "    continue-on-error: true\n" + job_anchor,
                1,
            ),
            "job defaults": content.replace(
                job_anchor,
                "    defaults:\n"
                "      run:\n"
                "        working-directory: /tmp\n"
                + job_anchor,
                1,
            ),
            "step if": add_to_runtime_steps("        if: ${{ false }}\n"),
            "step continue-on-error": add_to_runtime_steps(
                "        continue-on-error: true\n"
            ),
            "step working-directory": add_to_runtime_steps(
                "        working-directory: /tmp\n"
            ),
            "step timeout": add_to_runtime_steps("        timeout-minutes: 1\n"),
            "dangerous step env": content.replace(
                "          GITHUB_SHA: ${{ github.sha }}\n",
                "          GITHUB_SHA: ${{ github.sha }}\n"
                "          BASH_ENV: /tmp/attacker-controlled-startup\n",
                2,
            ),
        }

        for label, mutation in mutations.items():
            with self.subTest(control=label):
                self.assertNotEqual(content, mutation)
                workflow = self.load_workflow_text(mutation)
                with self.assertRaises(AssertionError):
                    self.assert_python_runtime_immutability_preflight(workflow)

    def test_runtime_preflight_rejects_interstitial_dependency_mutation_step(
        self,
    ) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        controlled_anchor = f"      - name: {CONTROLLED_STEP_NAME}\n"
        self.assertEqual(1, content.count(controlled_anchor))
        mutation = content.replace(
            controlled_anchor,
            "      - name: Mutate prepared documentation dependencies\n"
            "        shell: /bin/bash --noprofile --norc -e -o pipefail {0}\n"
            "        run: |\n"
            "          printf 'bypass = true\\n' > "
            "/tmp/documentation-python-dependencies/yaml.py\n\n"
            + controlled_anchor,
            1,
        )

        workflow = self.load_workflow_text(mutation)
        with self.assertRaises(AssertionError):
            self.assert_python_runtime_immutability_preflight(workflow)

    def test_non_pip_python_entries_disable_site_initialization(self) -> None:
        content = REPOSITORY_GUARD.read_text(encoding="utf-8")
        invocations = [
            line.strip()
            for line in content.splitlines()
            if line.strip().startswith('"$CI_TOOL_PYTHON3"')
        ]

        self.assertGreaterEqual(len(invocations), 10)
        for invocation in invocations:
            with self.subTest(invocation=invocation):
                if "-m pip install" in invocation:
                    self.assertIn(
                        '"$CI_TOOL_PYTHON3" -B -I -m pip install',
                        invocation,
                    )
                else:
                    self.assertIn('"$CI_TOOL_PYTHON3" -B -I -S', invocation)

    def test_python_runtime_immutability_check_defaults_to_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=False,
                writable_directory="runtime",
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                None,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(marker.exists())

    def test_python_runtime_immutability_check_rejects_invalid_requirement(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=True,
                writable_directory=None,
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "required",
            )

            self.assert_guard_diagnostic(
                result,
                "immutable Python runtime requirement is invalid",
            )
            self.assertFalse(marker.exists())

    def test_python_runtime_immutability_check_rejects_writable_stdlib(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=False,
                writable_directory="runtime",
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "1",
            )

            self.assert_guard_diagnostic(
                result,
                "Python runtime import roots are writable",
            )
            self.assertIn("pinned Python stdlib is writable", result.stderr)
            self.assertTrue(marker.is_file())

    def test_python_runtime_immutability_check_rejects_writable_system_site(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=True,
                writable_directory="site-packages",
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "1",
            )

            self.assert_guard_diagnostic(
                result,
                "Python runtime import roots are writable",
            )
            self.assertIn("pinned Python system site is writable", result.stderr)
            self.assertTrue(marker.is_file())

    def test_python_runtime_immutability_check_rejects_writable_import_root(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=True,
                writable_directory="lib-dynload",
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "1",
            )

            self.assert_guard_diagnostic(
                result,
                "Python runtime import roots are writable",
            )
            self.assertIn("pinned Python import root is writable", result.stderr)
            self.assertTrue(marker.is_file())

    def test_python_runtime_immutability_check_rejects_writable_zip_parent(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=True,
                writable_directory="library",
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "1",
            )

            self.assert_guard_diagnostic(
                result,
                "Python runtime import roots are writable",
            )
            self.assertIn("pinned Python import root is writable", result.stderr)
            self.assertTrue(marker.is_file())

    def test_python_runtime_immutability_check_accepts_read_only_roots(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory)
            launcher, marker = self.create_runtime_probe_launcher(
                fixture_root,
                deny_stdlib_write=True,
                writable_directory=None,
            )

            result = self.run_runtime_immutability_guard(
                fixture_root,
                launcher,
                "1",
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue(marker.is_file())


if __name__ == "__main__":
    unittest.main()
