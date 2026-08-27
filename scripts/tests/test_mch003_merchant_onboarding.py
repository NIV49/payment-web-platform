from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_mch003_merchant_onboarding.py"
SPEC = importlib.util.spec_from_file_location("mch003_merchant_onboarding", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Mch003MerchantOnboardingTest(unittest.TestCase):
    def snapshot(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repository = Path(directory.name)
        for relative_path in MODULE.CONTRACT_PATHS:
            source = REPOSITORY / relative_path
            if not source.exists():
                continue
            destination = repository / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        for relative_path in MODULE.IMMUTABLE_MIGRATION_SHA256:
            source = REPOSITORY / relative_path
            destination = repository / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return repository

    def test_repository_contract_contains_all_mch003_rules(self) -> None:
        self.assertEqual([], MODULE.validate_contract(REPOSITORY))

    def test_executed_migration_assets_are_immutable(self) -> None:
        for relative_path in MODULE.IMMUTABLE_MIGRATION_SHA256:
            with self.subTest(path=relative_path):
                repository = self.snapshot()
                path = repository / relative_path
                path.write_text(
                    path.read_text(encoding="utf-8") + "\n-- forbidden rewrite\n",
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(
                    any(str(relative_path) in error for error in errors), errors
                )

    def test_v40_lock_callback_and_forward_migrations_are_pinned(self) -> None:
        required = {
            Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
                 "beforeEachMigrate__lock_v40_merchant_button_i18n.sql"),
            Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
                 "V41__verify_canonical_merchant_button_i18n_titles.sql"),
            Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
                 "V42__add_merchant_amendment_document_references.sql"),
            Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
                 "V43__guard_merchant_amendment_document_references.sql"),
        }
        self.assertTrue(required.issubset(MODULE.IMMUTABLE_MIGRATION_SHA256))

    def test_v40_release_and_rollback_gate_is_mandatory(self) -> None:
        literals = (
            "deploy the dual-key frontend before V40",
            "stop every `iam_menu` writer",
            "V40 callback and V41 postcondition",
            "must not roll back to a frontend that lacks `merchant.permission.*`",
        )
        for literal in literals:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTEXT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED", 1),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(literal in error for error in errors), errors)

    def test_v42_writer_quiescence_and_rollback_gate_is_mandatory(self) -> None:
        for literal in MODULE.V42_RELEASE_LITERALS:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTEXT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED", 1),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(literal in error for error in errors), errors)

    def test_backend_clean_verify_status_cannot_be_both_passed_and_pending(self) -> None:
        self.assertEqual([], MODULE.validate_clean_verify_status(REPOSITORY))

        repository = self.snapshot()
        path = repository / MODULE.CONTEXT_PATH
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                MODULE.CLEAN_VERIFY_PASS_LITERAL,
                "Unified backend `clean verify` remains unproven.",
                1,
            ),
            encoding="utf-8",
        )

        errors = MODULE.validate_clean_verify_status(repository)
        self.assertTrue(any("clean verify status" in error for error in errors), errors)

    def test_backend_clean_verify_status_rejects_additional_conflicting_statements(self) -> None:
        conflicts = (
            "\nUnified backend `clean verify`: FAIL.\n",
            "\nUnified backend `clean verify`\nremains pending.\n",
        )
        for conflict in conflicts:
            with self.subTest(conflict=conflict):
                repository = self.snapshot()
                path = repository / MODULE.CONTEXT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8") + conflict,
                    encoding="utf-8",
                )

                errors = MODULE.validate_clean_verify_status(repository)
                self.assertTrue(
                    any("contradicts PASS" in error for error in errors),
                    errors,
                )

    def test_each_adr_decision_marker_is_mandatory(self) -> None:
        for marker in MODULE.ADR_MARKERS:
            with self.subTest(marker=marker):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(marker, "REMOVED", 1),
                    encoding="utf-8",
                )
                self.assertTrue(
                    any(marker in error for error in MODULE.validate_contract(repository))
                )

    def test_exact_endpoints_and_permissions_are_mandatory(self) -> None:
        for literal in (*MODULE.ENDPOINTS, *MODULE.PERMISSIONS):
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED"),
                    encoding="utf-8",
                )
                self.assertTrue(MODULE.validate_contract(repository))

    def test_all_23_profile_inputs_are_mandatory(self) -> None:
        for field in MODULE.PROFILE_FIELDS:
            with self.subTest(field=field):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(f"`{field}`", "`REMOVED`"),
                    encoding="utf-8",
                )
                self.assertTrue(MODULE.validate_contract(repository))

    def test_document_security_contract_is_mandatory(self) -> None:
        for literal in MODULE.DOCUMENT_SECURITY_LITERALS:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED"),
                    encoding="utf-8",
                )
                self.assertTrue(MODULE.validate_contract(repository))

    def test_current_effective_detail_fields_are_mandatory(self) -> None:
        for field in MODULE.CURRENT_DETAIL_FIELDS:
            with self.subTest(field=field):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                content = path.read_text(encoding="utf-8")
                start_marker = "<!-- MCH-003-CURRENT-EFFECTIVE-DETAIL -->"
                end_marker = "<!-- /MCH-003-CURRENT-EFFECTIVE-DETAIL -->"
                prefix, remainder = content.split(start_marker, 1)
                detail, suffix = remainder.split(end_marker, 1)
                detail = detail.replace(f'"{field}"', '"REMOVED"', 1)
                path.write_text(
                    prefix + start_marker + detail + end_marker + suffix,
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(field in error for error in errors), errors)

    def test_pending_read_no_result_semantics_are_mandatory(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.CONTRACT_PATH
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "HTTP `200` with `data:null`", "HTTP `404`", 1
            ),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("data:null" in error for error in errors), errors)

    def test_pending_amendment_document_context_is_explicit(self) -> None:
        for literal in MODULE.AMENDMENT_DOCUMENT_LITERALS:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED"),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(literal in error for error in errors), errors)

    def test_amendment_document_retain_and_replace_semantics_are_mandatory(self) -> None:
        for literal in MODULE.AMENDMENT_DOCUMENT_LITERALS[:4]:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED", 1),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(literal in error for error in errors), errors)

    def test_rule_card_must_bind_exact_judges(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.RULE_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["judgeTests"] = payload["judgeTests"][:-1]
        path.write_text(json.dumps(payload), encoding="utf-8")
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("exact three Judge checks" in error for error in errors), errors)

    def test_policy_and_registry_bind_mch003(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "MCH-003-FRONTEND-VERIFY", "REMOVED", 1
            ),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("MCH-003-FRONTEND-VERIFY" in error for error in errors), errors)

    def test_direct_is_replay_only_and_platform_is_canonical(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.CONTRACT_PATH
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "DIRECT is replay-only", "DIRECT remains writable", 1
            ),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("DIRECT is replay-only" in error for error in errors), errors)

    def test_detail_and_review_full_page_boundary_is_mandatory(self) -> None:
        for literal in (
            "create and edit to one full-page form component",
            "separate hidden full-page routes",
            "one read-only 23-field/five-document presentation",
            "one `Review` action",
        ):
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(
                    path.read_text(encoding="utf-8").replace(literal, "REMOVED", 1),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(literal in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
