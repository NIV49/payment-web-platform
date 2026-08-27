from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_doc_code_sync.py"
SPEC = importlib.util.spec_from_file_location("check_doc_code_sync", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class DocumentationCodeSyncTest(unittest.TestCase):
    def repository(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repository = Path(directory.name)
        subprocess.run(("git", "init", "--quiet"), cwd=repository, check=True)
        subprocess.run(
            ("git", "config", "user.name", "Documentation Sync Test"),
            cwd=repository,
            check=True,
        )
        subprocess.run(
            ("git", "config", "user.email", "docs-sync@example.invalid"),
            cwd=repository,
            check=True,
        )
        self.write(repository, "README.md", "baseline\n")
        self.commit(repository, "baseline")
        return repository

    @staticmethod
    def write(repository: Path, relative_path: str, content: str = "changed\n") -> None:
        path = repository / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    @staticmethod
    def commit(repository: Path, message: str, *, stage_all: bool = True) -> str:
        if stage_all:
            subprocess.run(("git", "add", "."), cwd=repository, check=True)
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

    @staticmethod
    def chmod(repository: Path, relative_path: str, executable: bool) -> None:
        subprocess.run(
            (
                "git",
                "update-index",
                f"--chmod={'+x' if executable else '-x'}",
                relative_path,
            ),
            cwd=repository,
            check=True,
        )

    @staticmethod
    def head(repository: Path) -> str:
        return subprocess.run(
            ("git", "rev-parse", "HEAD"),
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    @staticmethod
    def run_checker(repository: Path, base: str, commit: str) -> list[str]:
        return MODULE.validate(repository, base, commit)

    def test_backend_change_requires_backend_context_and_current_status(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "backend/modules/example/src/main/java/Example.java")
        commit = self.commit(repository, "backend change")

        result = self.run_checker(repository, base, commit)

        self.assertTrue(result)
        self.assertIn("docs/ai-context/backend/README.md", "\n".join(result))
        self.assertIn("docs/ai-context/current-status.md", "\n".join(result))

    def test_frontend_change_passes_when_required_context_is_updated(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "frontend/admin/apps/platform-admin/src/views/demo.vue")
        self.write(repository, "docs/ai-context/frontend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        commit = self.commit(repository, "frontend and docs")

        result = self.run_checker(repository, base, commit)

        self.assertEqual([], result)

    def test_identity_http_change_requires_identity_contract(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(
            repository,
            "backend/applications/platform-admin-api/src/main/java/example/web/UserController.java",
        )
        self.write(repository, "docs/ai-context/backend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        commit = self.commit(repository, "identity API without contract")

        result = self.run_checker(repository, base, commit)

        self.assertTrue(result)
        self.assertIn("docs/ai-contract/identity-admin-api-contract.md", "\n".join(result))

    def test_dictionary_change_requires_dictionary_contract(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "backend/modules/system-dictionary/src/main/java/Dictionary.java")
        self.write(repository, "docs/ai-context/backend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        commit = self.commit(repository, "dictionary without contract")

        result = self.run_checker(repository, base, commit)

        self.assertTrue(result)
        self.assertIn("docs/ai-contract/system-dictionary-api-contract.md", "\n".join(result))

    def test_governance_change_requires_workflow_status_and_charter(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "scripts/check_example.py")
        commit = self.commit(repository, "governance without docs")

        result = self.run_checker(repository, base, commit)

        self.assertTrue(result)
        output = "\n".join(result)
        self.assertIn("docs/ai-context/current-status.md", output)
        self.assertIn("docs/ai-context/development-workflow.md", output)
        self.assertIn("docs/judge-charter.md", output)

    def test_documentation_only_change_does_not_require_unrelated_docs(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "docs/notes/example.md")
        commit = self.commit(repository, "documentation only")

        result = self.run_checker(repository, base, commit)

        self.assertEqual([], result)

    def test_mode_only_documentation_change_cannot_satisfy_sync(self) -> None:
        repository = self.repository()
        self.write(repository, "docs/ai-context/backend/README.md", "context\n")
        self.write(repository, "docs/ai-context/current-status.md", "status\n")
        self.commit(repository, "seed docs")
        base = self.head(repository)
        self.write(repository, "backend/modules/example/src/main/java/Example.java")
        subprocess.run(("git", "add", "."), cwd=repository, check=True)
        self.chmod(repository, "docs/ai-context/backend/README.md", True)
        self.chmod(repository, "docs/ai-context/current-status.md", True)
        commit = self.commit(
            repository,
            "code with mode-only docs",
            stage_all=False,
        )

        result = self.run_checker(repository, base, commit)

        output = "\n".join(result)
        self.assertIn("docs/ai-context/backend/README.md", output)
        self.assertIn("docs/ai-context/current-status.md", output)

    def test_dictionary_production_surfaces_require_dictionary_contract(self) -> None:
        surfaces = (
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/SystemDictionaryController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeSystemDictionaryReadController.java",
            "frontend/admin/apps/platform-admin/src/views/system/dict/list.vue",
            "frontend/admin/packages/effects/backoffice-runtime/src/components/common-status-dictionary-alert.vue",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/dictionary-data/list.vue",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/backend/README.md")
                self.write(repository, "docs/ai-context/frontend/README.md")
                self.write(repository, "docs/ai-context/current-status.md")
                self.write(
                    repository,
                    "docs/ai-contract/identity-admin-api-contract.md",
                )
                commit = self.commit(
                    repository,
                    "dictionary surface without dictionary contract",
                )

                result = self.run_checker(repository, base, commit)

                self.assertIn(
                    "docs/ai-contract/system-dictionary-api-contract.md",
                    "\n".join(result),
                )

    def test_system_management_surfaces_require_product_documentation(self) -> None:
        surfaces = (
            "frontend/admin/apps/platform-admin/src/views/system/menu/list.vue",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/user/list.vue",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/SystemAdministrationController.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/AdminApiPermissionPolicy.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeUserRoleAdministrationController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeWebConfiguration.java",
            "backend/modules/system-dictionary/src/main/java/com/niv/payment/dictionary/core/DictionaryModels.java",
            "backend/modules/system-dictionary/src/main/resources/db/migration/V32__change_dictionary_menu.sql",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/backend/README.md")
                self.write(repository, "docs/ai-context/frontend/README.md")
                self.write(repository, "docs/ai-context/current-status.md")
                self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
                self.write(repository, "docs/ai-contract/system-dictionary-api-contract.md")
                commit = self.commit(
                    repository,
                    "system management without product documentation",
                )

                result = self.run_checker(repository, base, commit)

                self.assertIn(
                    "docs/product/system-management.md",
                    "\n".join(result),
                )

    def test_system_management_change_passes_with_product_documentation(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(
            repository,
            "frontend/admin/apps/platform-admin/src/views/system/dept/list.vue",
        )
        self.write(repository, "docs/ai-context/frontend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
        self.write(repository, "docs/product/system-management.md")
        commit = self.commit(repository, "system management with product docs")

        result = self.run_checker(repository, base, commit)

        self.assertEqual([], result)

    def test_system_management_test_and_internal_helpers_do_not_force_product_docs(self) -> None:
        surfaces = (
            "backend/modules/system-dictionary/src/test/java/DictionaryTest.java",
            "frontend/admin/apps/platform-admin/src/views/system/menu/data.test.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/user/list.test.ts",
            "frontend/admin/apps/platform-admin/src/views/system/platform-directory.test.ts",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/backend/README.md")
                self.write(repository, "docs/ai-context/frontend/README.md")
                self.write(repository, "docs/ai-context/current-status.md")
                self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
                self.write(repository, "docs/ai-contract/system-dictionary-api-contract.md")
                commit = self.commit(repository, "system management tests only")

                result = self.run_checker(repository, base, commit)

                self.assertNotIn(
                    "docs/product/system-management.md",
                    "\n".join(result),
                )

    def test_merchant_lifecycle_surfaces_require_owned_documentation(self) -> None:
        surfaces = (
            "backend/modules/merchant/core/src/main/java/com/niv/payment/merchant/core/Merchant.java",
            "backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/V99__merchant.sql",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/MerchantLifecycleController.java",
            "backend/applications/merchant-admin-api/src/main/java/com/niv/payment/merchantadminapi/web/MerchantApplicationController.java",
            "frontend/admin/apps/platform-admin/src/api/merchant/lifecycle.ts",
            "frontend/admin/apps/platform-admin/src/views/merchant/list.vue",
            "frontend/admin/apps/merchant-admin/src/api/merchant/application.ts",
            "frontend/admin/apps/merchant-admin/src/views/merchant/application.vue",
            "frontend/admin/apps/platform-admin/src/deployment.ts",
            "frontend/admin/apps/merchant-admin/src/deployment.ts",
            "frontend/admin/apps/agent-admin/src/deployment.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/permission-codes.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/common-pages.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/merchant/lifecycle.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/router/merchant-access.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/router/routes/modules/merchant.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/merchant/application.vue",
            "frontend/admin/packages/effects/backoffice-runtime/src/locales/langs/en-US/merchant.json",
            "frontend/admin/packages/effects/backoffice-runtime/src/locales/langs/zh-CN/merchant.json",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/AdminApiPermissionPolicy.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeAdministrationPermissionPolicy.java",
            "backend/modules/identity/persistence-postgres/src/main/java/com/niv/payment/identity/lifecycle/JooqIdentityInvitationRepository.java",
            "backend/applications/platform-admin-api/src/main/resources/db/local/iam-local-bootstrap.sql",
        )
        required_docs = (
            "docs/ai-context/current-status.md",
            "docs/ai-context/merchant/README.md",
            "docs/ai-contract/merchant-lifecycle-api-contract.md",
            "docs/product/merchant-management.md",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/backend/README.md")
                self.write(repository, "docs/ai-context/frontend/README.md")
                self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
                commit = self.commit(
                    repository,
                    "merchant lifecycle without owned documentation",
                )

                result = self.run_checker(repository, base, commit)
                output = "\n".join(result)
                for required_doc in required_docs:
                    self.assertIn(required_doc, output)

    def test_merchant_permission_migration_requires_owned_documentation(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(
            repository,
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V99__permissions.sql",
            "INSERT INTO iam_permission(code) VALUES ('merchant:view');\n",
        )
        self.write(repository, "docs/ai-context/backend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
        self.write(repository, "docs/product/system-management.md")
        commit = self.commit(repository, "merchant permission migration")

        result = self.run_checker(repository, base, commit)
        output = "\n".join(result)

        self.assertIn("docs/ai-context/merchant/README.md", output)
        self.assertIn("docs/ai-contract/merchant-lifecycle-api-contract.md", output)
        self.assertIn("docs/product/merchant-management.md", output)

    def test_unrelated_identity_migration_does_not_require_merchant_docs(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(
            repository,
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V99__unrelated.sql",
            "ALTER TABLE iam_user ADD COLUMN fixture_flag BOOLEAN;\n",
        )
        self.write(repository, "docs/ai-context/backend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
        self.write(repository, "docs/product/system-management.md")
        commit = self.commit(repository, "unrelated identity migration")

        result = self.run_checker(repository, base, commit)

        self.assertNotIn(
            "docs/ai-context/merchant/README.md",
            "\n".join(result),
        )

    def test_merchant_lifecycle_change_passes_with_owned_documentation(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(
            repository,
            "backend/modules/merchant/core/src/main/java/com/niv/payment/merchant/core/Merchant.java",
        )
        self.write(repository, "docs/ai-context/backend/README.md")
        self.write(repository, "docs/ai-context/current-status.md")
        self.write(repository, "docs/ai-context/merchant/README.md")
        self.write(repository, "docs/ai-contract/merchant-lifecycle-api-contract.md")
        self.write(repository, "docs/product/merchant-management.md")
        commit = self.commit(repository, "merchant lifecycle with owned docs")

        result = self.run_checker(repository, base, commit)

        self.assertEqual([], result)

    def test_merchant_lifecycle_tests_do_not_force_product_documentation(self) -> None:
        surfaces = (
            "backend/modules/merchant/core/src/test/java/com/niv/payment/merchant/core/MerchantTest.java",
            "frontend/admin/apps/platform-admin/src/views/merchant/list.test.ts",
            "frontend/admin/apps/merchant-admin/src/views/merchant/application.spec.ts",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/backend/README.md")
                self.write(repository, "docs/ai-context/frontend/README.md")
                self.write(repository, "docs/ai-context/current-status.md")
                self.write(repository, "docs/ai-contract/identity-admin-api-contract.md")
                commit = self.commit(repository, "merchant lifecycle tests only")

                result = self.run_checker(repository, base, commit)

                self.assertNotIn(
                    "docs/product/merchant-management.md",
                    "\n".join(result),
                )

    def test_identity_infra_surfaces_require_contract_and_backend_context(self) -> None:
        surfaces = (
            "infra/keycloak/realms/PLATFORM-realm.json",
            "infra/docker-compose.iam002-local.yml",
            "infra/local/iam002/converge-delegated-user-governance.sql",
        )
        for relative_path in surfaces:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/current-status.md")
                commit = self.commit(repository, "infra without owned docs")

                result = self.run_checker(repository, base, commit)

                output = "\n".join(result)
                self.assertIn("docs/ai-context/backend/README.md", output)
                self.assertIn("docs/ai-contract/identity-admin-api-contract.md", output)

    def test_test_only_and_development_scripts_do_not_force_governance_docs(self) -> None:
        for relative_path in (
            "scripts/tests/test_example.py",
            "scripts/dev/local_helper.py",
        ):
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                commit = self.commit(repository, "non-governance script")

                result = self.run_checker(repository, base, commit)

                self.assertEqual([], result)

    def test_application_workflows_do_not_force_judge_charter_updates(self) -> None:
        cases = (
            (
                ".github/workflows/backend.yml",
                "docs/ai-context/backend/README.md",
            ),
            (
                ".github/workflows/frontend.yml",
                "docs/ai-context/frontend/README.md",
            ),
        )
        for relative_path, owned_context in cases:
            with self.subTest(relative_path=relative_path):
                repository = self.repository()
                base = self.head(repository)
                self.write(repository, relative_path)
                self.write(repository, "docs/ai-context/current-status.md")
                self.write(repository, "docs/ai-context/development-workflow.md")
                self.write(repository, owned_context)
                commit = self.commit(repository, "application workflow and owned docs")

                result = self.run_checker(repository, base, commit)

                self.assertEqual([], result)

    def test_bound_git_reader_disables_replace_transport_and_lazy_fetch(self) -> None:
        repository = self.repository().resolve()
        context = MODULE._discover_context(repository)

        command = MODULE._git_command(context, "status", "--short")
        environment = MODULE._git_environment(context)

        self.assertIn(f"--git-dir={context.git_dir}", command)
        self.assertIn(f"--work-tree={context.work_tree}", command)
        self.assertIn(f"safe.directory={repository}", command)
        self.assertIn("core.commitGraph=false", command)
        self.assertIn("core.useReplaceRefs=false", command)
        self.assertIn("--no-replace-objects", command)
        self.assertEqual("", environment["GIT_ALLOW_PROTOCOL"])
        self.assertEqual("0", environment["GIT_PROTOCOL_FROM_USER"])
        self.assertEqual("1", environment["GIT_NO_LAZY_FETCH"])
        self.assertEqual("0", environment["GIT_TERMINAL_PROMPT"])
        self.assertEqual(str(context.git_dir), environment["GIT_DIR"])
        self.assertEqual(str(context.work_tree), environment["GIT_WORK_TREE"])
        self.assertEqual(str(context.common_dir), environment["GIT_COMMON_DIR"])
        self.assertEqual(
            str(context.object_directory),
            environment["GIT_OBJECT_DIRECTORY"],
        )

    def test_replace_ref_is_rejected_before_diff_evaluation(self) -> None:
        repository = self.repository()
        base = self.head(repository)
        self.write(repository, "ordinary.txt", "target\n")
        commit = self.commit(repository, "target")
        subprocess.run(
            ("git", "replace", base, commit),
            cwd=repository,
            check=True,
        )

        result = self.run_checker(repository, base, commit)

        self.assertIn("replace refs are forbidden", "\n".join(result))


if __name__ == "__main__":
    unittest.main()
