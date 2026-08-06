import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "check_iam001_three_backoffice_boundary.py"
SPEC = importlib.util.spec_from_file_location("iam001_boundary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Iam001BoundaryCheckTest(unittest.TestCase):
    def test_mandatory_permission_context_requires_server_trusted_workspace_resolution(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        context_path = repository.joinpath(
            "docs/ai-context/permission/06-database-design.md"
        )
        context = context_path.read_text(encoding="utf-8")

        MODULE.validate_mandatory_permission_context(repository)
        self.assertIn(
            "登录 API 不接受 `tenantId` 或等价工作区选择字段",
            context,
        )
        self.assertIn("授权工作区只能由服务端可信入口或上下文解析", context)
        self.assertIn(
            "无法唯一解析一个 ACTIVE Membership 时，认证以不泄露 Membership 是否存在的通用 401 失败",
            context,
        )
        self.assertNotIn("多个活动 Membership 必须显式选择 tenantId", context)

        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory)
            destination = snapshot / MODULE.MANDATORY_PERMISSION_CONTEXT
            destination.parent.mkdir(parents=True)
            destination.write_text(
                "多个活动 Membership 必须显式选择 tenantId。\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "server-trusted workspace"):
                MODULE.validate_mandatory_permission_context(snapshot)

            destination.write_text(
                f"{context}\n服务器会拒绝冲突登录，但浏览器可以通过 workspaceId 指定工作区。\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "server-trusted workspace"):
                MODULE.validate_mandatory_permission_context(snapshot)

            destination.write_text(
                f"{context}\n不存在冲突时，浏览器可以通过 workspaceId 指定工作区。\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "server-trusted workspace"):
                MODULE.validate_mandatory_permission_context(snapshot)

    def test_mutations_clear_stale_build_outputs_without_following_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory, "snapshot")
            stale_target = snapshot / "backend/applications/platform-admin-api/target"
            stale_target.mkdir(parents=True)
            stale_target.joinpath("failsafe-summary.xml").write_text(
                "stale", encoding="utf-8"
            )

            MODULE._clear_mutation_build_outputs(snapshot)
            self.assertFalse(stale_target.exists())

            outside = Path(directory, "outside")
            outside.mkdir()
            stale_target.parent.mkdir(parents=True, exist_ok=True)
            stale_target.symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "symbolic link"):
                MODULE._clear_mutation_build_outputs(snapshot)
            self.assertTrue(outside.is_dir())

            stale_target.unlink()
            stale_target.write_text("not a directory", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "must be a directory"):
                MODULE._clear_mutation_build_outputs(snapshot)

    def test_queue_output_must_remain_inside_the_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory, "repository")
            repository.mkdir()
            expected = repository.resolve() / "target/judge/iam001/process.json"
            self.assertEqual(
                expected,
                MODULE._resolve_queue_output(
                    repository, Path("target/judge/iam001/process.json")
                ),
            )
            for redirected in (Path(directory, "outside.json"), Path("../outside.json")):
                with self.assertRaisesRegex(RuntimeError, "repository-relative"):
                    MODULE._resolve_queue_output(repository, redirected)

    def test_git_archive_ignores_replace_refs_and_rejects_redirects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory, "repository")
            repository.mkdir()

            def git(*arguments: str) -> str:
                return subprocess.run(
                    ["git", *arguments], cwd=repository, check=True,
                    capture_output=True, text=True,
                ).stdout.strip()

            git("init")
            git("config", "user.name", "IAM Judge Test")
            git("config", "user.email", "iam-judge@example.invalid")
            marker = repository / "marker.txt"
            marker.write_text("original\n", encoding="utf-8")
            git("add", "marker.txt")
            git("commit", "-m", "original")
            original = git("rev-parse", "HEAD")
            marker.write_text("replacement\n", encoding="utf-8")
            git("commit", "-am", "replacement")
            replacement = git("rev-parse", "HEAD")
            git("replace", original, replacement)

            self.assertEqual(
                "original\n",
                MODULE._git(repository, "show", f"{original}:marker.txt"),
            )
            snapshot = Path(directory, "snapshot")
            snapshot.mkdir()
            MODULE._archive(repository, original, snapshot)
            self.assertEqual("original\n", snapshot.joinpath("marker.txt").read_text())

            with mock.patch.dict(MODULE.os.environ, {"GIT_DIR": str(repository / ".git")}):
                with self.assertRaisesRegex(RuntimeError, "Git environment overrides"):
                    MODULE._git(repository, "status", "--porcelain")

    def test_runtime_environment_replaces_present_java17_with_java25(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java17 = root / "jdk17"
            java25_prefix = root / "openjdk25"
            java25 = java25_prefix / "libexec/openjdk.jdk/Contents/Home"
            for home in (java17, java25):
                (home / "bin").mkdir(parents=True)
                (home / "bin/java").touch()

            with (
                mock.patch.dict(os.environ, {"JAVA_HOME": str(java17), "PATH": "/usr/bin"}, clear=True),
                mock.patch.object(MODULE.shutil, "which", return_value="/opt/homebrew/bin/brew"),
                mock.patch.object(
                    MODULE.subprocess,
                    "run",
                    return_value=SimpleNamespace(returncode=0, stdout=str(java25_prefix) + "\n"),
                ),
                mock.patch.object(
                    MODULE,
                    "_java_major_version",
                    side_effect=lambda java: 17 if java == java17 / "bin/java" else 25,
                ),
            ):
                environment = MODULE._runtime_environment()

            self.assertEqual(str(java25), environment["JAVA_HOME"])
            self.assertEqual(str(java25 / "bin"), environment["PATH"].split(os.pathsep)[0])

    def test_every_mutation_has_one_exact_production_preimage(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        self.assertEqual(12, len(MODULE.MUTATIONS))
        for mutation in MODULE.MUTATIONS:
            source = repository.joinpath(mutation.path).read_text(encoding="utf-8")
            self.assertEqual(1, source.count(mutation.before), mutation.mutation_id)
            self.assertNotIn("/src/test/", mutation.path)
            selectors = [
                argument.split("=", 1)[1]
                for argument in mutation.command.argv
                if argument.startswith(("-Dtest=", "-Dit.test="))
            ]
            self.assertEqual(1, len(selectors), mutation.mutation_id)
            selected_class, selected_method = selectors[0].split("#", 1)
            self.assertTrue(
                mutation.test_class.endswith(f".{selected_class}"),
                mutation.mutation_id,
            )
            self.assertEqual(selected_method, mutation.test_name, mutation.mutation_id)

        tenant_mutation = next(
            mutation for mutation in MODULE.MUTATIONS
            if mutation.mutation_id == "CLIENT_TENANT_SELECTION"
        )
        self.assertTrue(any(
            "tests/iam001-blackbox" in argument
            for argument in tenant_mutation.command.argv
        ))
        self.assertIn("tests/iam001-blackbox", tenant_mutation.report_glob)
        origin_mutations = [
            mutation for mutation in MODULE.MUTATIONS
            if mutation.mutation_id.endswith("MUTATION_ORIGIN_GUARD")
        ]
        self.assertEqual(2, len(origin_mutations))
        self.assertEqual(
            {
                "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/SecurityConfiguration.java",
                "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeSecurityConfiguration.java",
            },
            {mutation.path for mutation in origin_mutations},
        )
        for mutation in origin_mutations:
            self.assertIn(
                "-Dit.test=ThreeBackofficeBoundaryIntegrationTest#untrustedOriginsCannotCreateOrTerminateSessions",
                mutation.command.argv,
            )
            self.assertIn("tests/iam001-blackbox", mutation.report_glob)
        self.assertEqual(
            ("pnpm", "install", "--offline", "--frozen-lockfile"),
            MODULE.PROCESS_COMMANDS[1].argv,
        )

    def test_mutation_oracle_requires_the_mapped_assertion_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory)
            reports = snapshot / "reports"
            reports.mkdir()
            report = reports / "TEST-mapped.xml"
            pattern = "reports/TEST-*.xml"
            test_class = "com.example.BoundaryTest"
            test_name = "rejectsCrossOrigin"

            def write_testcase(body: str, *, name: str = test_name) -> None:
                report.write_text(
                    "<testsuite>"
                    f'<testcase classname="{test_class}" name="{name}">{body}</testcase>'
                    "</testsuite>",
                    encoding="utf-8",
                )

            write_testcase('<error message="Docker unavailable"/>')
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            write_testcase('<failure message="wrong method"/>', name="anotherTest")
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            write_testcase("<skipped/>")
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            write_testcase('<failure message="expected assertion"/>')
            self.assertTrue(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            report.write_text(
                "<testsuite>"
                f'<testcase classname="{test_class}" name="{test_name}">'
                '<failure message="expected assertion"/></testcase>'
                '<testcase classname="com.example.SetupTest" name="starts">'
                '<error message="application failed to start"/></testcase>'
                "</testsuite>",
                encoding="utf-8",
            )
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            report.write_text(
                "<testsuite errors=\"1\">"
                '<error message="setup failed"/>'
                f'<testcase classname="{test_class}" name="{test_name}">'
                '<failure message="expected assertion"/></testcase>'
                "</testsuite>",
                encoding="utf-8",
            )
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            write_testcase(
                '<failure message="wrong class"/>',
                name=test_name,
            )
            report.write_text(
                report.read_text(encoding="utf-8").replace(
                    test_class, "com.example.AnotherBoundaryTest"
                ),
                encoding="utf-8",
            )
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            report.write_text(
                f'<report><testcase classname="{test_class}" name="{test_name}">'
                '<failure message="not junit"/></testcase></report>',
                encoding="utf-8",
            )
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

            for invalid_summary in ("0", "2", "not-an-integer"):
                report.write_text(
                    f'<testsuite failures="{invalid_summary}">'
                    f'<testcase classname="{test_class}" name="{test_name}">'
                    '<failure message="expected assertion"/></testcase>'
                    "</testsuite>",
                    encoding="utf-8",
                )
                self.assertFalse(
                    MODULE._failed_junit_report(
                        snapshot, pattern, test_class, test_name
                    ),
                    invalid_summary,
                )

            report.write_text("<testsuite>", encoding="utf-8")
            self.assertFalse(
                MODULE._failed_junit_report(
                    snapshot, pattern, test_class, test_name
                )
            )

    def test_mutation_runner_rejects_an_infrastructure_error_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory)
            production = snapshot / "production/Guard.java"
            production.parent.mkdir()
            production.write_text("guard\n", encoding="utf-8")
            mutation = MODULE.Mutation(
                "SYNTHETIC_GUARD",
                "production/Guard.java",
                "guard\n",
                "",
                MODULE.Command(".", ("synthetic-test",)),
                "reports/TEST-*.xml",
                "com.example.BoundaryTest",
                "rejectsCrossOrigin",
            )

            def infrastructure_error(_command, root, _environment, _log) -> int:
                report = root / "reports/TEST-boundary.xml"
                report.parent.mkdir()
                report.write_text(
                    "<testsuite>"
                    '<testcase classname="com.example.BoundaryTest" '
                    'name="rejectsCrossOrigin">'
                    '<error message="Docker unavailable"/></testcase>'
                    "</testsuite>",
                    encoding="utf-8",
                )
                return 1

            with (
                mock.patch.object(MODULE, "MUTATIONS", (mutation,)),
                mock.patch.object(MODULE, "_run", side_effect=infrastructure_error),
            ):
                failures = MODULE.run_mutations(snapshot, {}, io.BytesIO())

            self.assertEqual(
                [
                    "SYNTHETIC_GUARD: failed without the expected test failure report"
                ],
                failures,
            )
            self.assertEqual("guard\n", production.read_text(encoding="utf-8"))

    def test_task_identity_owned_paths_cover_a_real_git_diff(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.email", "judge@example.invalid"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "IAM-001 Judge"],
                cwd=repository,
                check=True,
            )
            shared_paths = (
                "frontend/admin/packages/@core/base/typings/src/vue-router.d.ts",
                "frontend/admin/packages/effects/access/src/accessible.ts",
            )
            for shared_path in shared_paths:
                path = repository / shared_path
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("baseline\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=repository, check=True)
            subprocess.run(
                ["git", "commit", "-qm", "baseline"], cwd=repository, check=True
            )
            base = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            for shared_path in shared_paths:
                (repository / shared_path).write_text("candidate\n", encoding="utf-8")
            subprocess.run(
                ["git", "commit", "-qam", "candidate"],
                cwd=repository,
                check=True,
            )
            target = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()

            self.assertEqual(
                [],
                MODULE._unowned_changed_paths(
                    repository, base, target, MODULE.OWNED_PATHS
                ),
            )
            unowned = repository / "frontend/portal/app.vue"
            unowned.parent.mkdir(parents=True)
            unowned.write_text("unowned\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=repository, check=True)
            subprocess.run(
                ["git", "commit", "-qm", "unowned"],
                cwd=repository,
                check=True,
            )
            unowned_target = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            self.assertEqual(
                ["frontend/portal/app.vue"],
                MODULE._unowned_changed_paths(
                    repository, base, unowned_target, MODULE.OWNED_PATHS
                ),
            )
        for shared_path in (
            "frontend/admin/packages/@core/base/typings/src/vue-router.d.ts",
            "frontend/admin/packages/effects/access/src/accessible.ts",
        ):
            self.assertTrue(
                MODULE._path_is_owned(shared_path, MODULE.OWNED_PATHS),
                shared_path,
            )

    def test_conflicting_duplicate_queue_items_fail_closed(self) -> None:
        first = {"fingerprint": "same", "impact": "one"}
        second = {"fingerprint": "same", "impact": "two"}
        with self.assertRaisesRegex(RuntimeError, "conflicting queue items"):
            MODULE.deduplicate_queue_items([first, second])

    def test_candidate_queue_item_uses_exact_v2_contract(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        artifacts = MODULE._load_artifact_contracts(repository)
        evaluated_key = "sha256:" + "1" * 64
        item = MODULE._queue_item(
            MODULE.PROCESS_CHECK_ID, evaluated_key, ["synthetic failure"], "target/judge/log"
        )
        self.assertEqual([], artifacts.validate_queue_item(item))
        self.assertEqual(
            set(artifacts.QUEUE_ITEM_FIELDS),
            set(json.loads(json.dumps(item))),
        )


if __name__ == "__main__":
    unittest.main()
