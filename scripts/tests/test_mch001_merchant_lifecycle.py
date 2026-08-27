from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_mch001_merchant_lifecycle.py"
SPEC = importlib.util.spec_from_file_location("mch001_merchant_lifecycle", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Mch001MerchantLifecycleTest(unittest.TestCase):
    def snapshot(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repository = Path(directory.name)
        for relative_path in MODULE.CONTRACT_PATHS:
            source = REPOSITORY / relative_path
            destination = repository / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return repository

    def write_json(self, repository: Path, relative_path: Path, payload: object) -> None:
        path = repository / relative_path
        path.write_text(
            json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
            encoding="utf-8",
        )

    def test_repository_contract_contains_all_mch001_rules(self) -> None:
        self.assertEqual([], MODULE.validate_contract(REPOSITORY))
        self.assertEqual(12, len(MODULE.ADR_MARKER_REQUIREMENTS))
        self.assertEqual(5, len(MODULE.MERCHANT_STATES))
        self.assertEqual(8, len(MODULE.PERMISSION_CODES))
        self.assertEqual(8, len(MODULE.API_ENDPOINTS))
        self.assertEqual(12, len(MODULE.REASON_CODES))
        self.assertEqual(7, len(MODULE.REASON_CODES_BY_COMMAND))
        self.assertEqual(
            ("APPLICATION_SUBMITTED",),
            MODULE.REASON_CODES_BY_COMMAND["submit"],
        )
        self.assertEqual(
            ("APPLICATION_RESUBMITTED",),
            MODULE.REASON_CODES_BY_COMMAND["resubmit"],
        )

    def test_database_principal_bootstrap_is_a_mandatory_fail_closed_artifact(self) -> None:
        self.assertIn(MODULE.ROLE_BOOTSTRAP_PATH, MODULE.CONTRACT_PATHS)
        self.assertIn(MODULE.MIGRATION_PREFLIGHT_PATH, MODULE.CONTRACT_PATHS)

        for path, literal in (
            (
                MODULE.ROLE_BOOTSTRAP_PATH,
                "MCH-001 cluster bootstrap requires the rotation role to have zero memberships",
            ),
            (
                MODULE.MIGRATION_PREFLIGHT_PATH,
                "MCH-001 migration principal must be NOSUPERUSER and NOCREATEROLE",
            ),
        ):
            repository = self.snapshot()
            artifact = repository / path
            artifact.write_text(
                artifact.read_text(encoding="utf-8").replace(literal, "removed-guard"),
                encoding="utf-8",
            )
            errors = MODULE.validate_contract(repository)
            self.assertTrue(any(str(path) in error for error in errors), errors)

    def test_each_adr_marker_and_its_literals_are_mandatory(self) -> None:
        for marker, literals in MODULE.ADR_MARKER_REQUIREMENTS.items():
            with self.subTest(marker=marker):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                content = path.read_text(encoding="utf-8")
                content = content.replace(marker, "<!-- MCH-001-REMOVED -->", 1)
                path.write_text(content, encoding="utf-8")
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(marker in error for error in errors), errors)

                for literal in literals:
                    repository = self.snapshot()
                    path = repository / MODULE.ADR_PATH
                    content = path.read_text(encoding="utf-8")
                    marker_start = content.index(marker)
                    next_marker = content.find("<!-- MCH-001-D", marker_start + len(marker))
                    section_end = len(content) if next_marker < 0 else next_marker
                    section = content[marker_start:section_end]
                    path.write_text(
                        content[:marker_start]
                        + section.replace(literal, "removed-literal")
                        + content[section_end:],
                        encoding="utf-8",
                    )
                    errors = MODULE.validate_contract(repository)
                    self.assertTrue(any(marker in error for error in errors), errors)

    def test_contract_requires_exact_states_permissions_endpoints_and_errors(self) -> None:
        for values, label, expected_error in (
            (MODULE.MERCHANT_STATES, "state", "contract state literals"),
            (MODULE.PERMISSION_CODES, "permission", "contract permission literals"),
            (MODULE.API_ENDPOINTS, "endpoint", "contract endpoint literals"),
            (MODULE.ERROR_CODES, "error", "contract error literal"),
        ):
            for value in values:
                with self.subTest(label=label, value=value):
                    repository = self.snapshot()
                    path = repository / MODULE.API_CONTRACT_PATH
                    content = path.read_text(encoding="utf-8")
                    path.write_text(
                        content.replace(value, f"REMOVED_{label.upper()}"),
                        encoding="utf-8",
                    )
                    errors = MODULE.validate_contract(repository)
                    self.assertTrue(
                        any(expected_error in error.lower() for error in errors),
                        errors,
                    )

    def test_reason_code_table_rejects_extra_and_cross_action_values(self) -> None:
        for old, new in (
            (
                "`PROFILE_VERIFIED` |",
                "`PROFILE_VERIFIED`, `KYC_PENDING` |",
            ),
            (
                "`BUSINESS_CLOSED`, `COMPLIANCE_TERMINATION` |",
                "`BUSINESS_CLOSED`, `COMPLIANCE_TERMINATION`, `PROFILE_VERIFIED` |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                "| approve | `KYC_PENDING` |\n| approve | `PROFILE_VERIFIED` |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                "| manual-review | `KYC_PENDING` |\n| approve | `PROFILE_VERIFIED` |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                "| MANUAL | `KYC_PENDING` |\n| approve | `PROFILE_VERIFIED` |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                "| approve | `PROFILE_VERIFIED` plus manual notes |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                " | manual-review | `KYC_PENDING` |\n| approve | `PROFILE_VERIFIED` |",
            ),
            (
                "| approve | `PROFILE_VERIFIED` |",
                "   | approve | `PROFILE_VERIFIED` |\n| approve | `PROFILE_VERIFIED` |",
            ),
        ):
            with self.subTest(new=new):
                repository = self.snapshot()
                path = repository / MODULE.API_CONTRACT_PATH
                content = path.read_text(encoding="utf-8")
                path.write_text(content.replace(old, new, 1), encoding="utf-8")

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any("action-specific mapping" in error for error in errors),
                    errors,
                )

    def test_contract_rejects_extra_permission_or_endpoint(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.API_CONTRACT_PATH
        content = path.read_text(encoding="utf-8")
        path.write_text(
            content.replace(
                "MCH-002 adds exactly one protected permission",
                "`merchant:delete`\n\nMCH-002 adds exactly one protected permission",
                1,
            ).replace(
                "## 10. Compatibility and implementation status",
                "- `DELETE /api/platform/merchants/{merchantId}`\n\n"
                "## 10. Compatibility and implementation status",
                1,
            ),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("permission" in error.lower() for error in errors), errors)
        self.assertTrue(any("endpoint" in error.lower() for error in errors), errors)

    def test_contract_requires_stable_first_submit_replay_order(self) -> None:
        for literal in (
            "explicit JSON `expectedVersion: null` selects `submit`",
            "Only a deduplication miss may read the Merchant",
            "first-submit response-loss retry keeps command type `submit`",
        ):
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.API_CONTRACT_PATH
                content = path.read_text(encoding="utf-8")
                path.write_text(
                    content.replace(literal, "REMOVED_STABLE_REPLAY_ORDER", 1),
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any("security requirement" in error for error in errors),
                    errors,
                )

    def test_contract_requires_request_body_tenant_selector_prohibition(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.API_CONTRACT_PATH
        content = path.read_text(encoding="utf-8")
        path.write_text(
            content.replace(MODULE.TENANT_ID_PROHIBITION, "weaker statement", 1),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("tenantId" in error for error in errors), errors)

    def test_contract_requires_registration_idempotency_and_aead_security_rules(self) -> None:
        for literal in MODULE.API_SECURITY_REQUIREMENTS:
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.API_CONTRACT_PATH
                content = path.read_text(encoding="utf-8")
                path.write_text(
                    content.replace(literal, "removed-security-requirement", 1),
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any("security requirement" in error for error in errors),
                    errors,
                )

    def test_merchant_documentation_ownership_rule_is_mandatory(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.DOC_SYNC_CHECKER_PATH
        content = path.read_text(encoding="utf-8")
        path.write_text(
            content.replace('"DOC-SYNC-MERCHANT-LIFECYCLE"', '"REMOVED"'),
            encoding="utf-8",
        )

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("DOC-SYNC-MERCHANT-LIFECYCLE" in error for error in errors), errors)

    def test_rule_requires_exact_ordered_arrays(self) -> None:
        for field in ("scope", "given", "when", "then", "counterexamples", "judgeTests"):
            with self.subTest(field=field):
                repository = self.snapshot()
                path = repository / MODULE.RULE_PATH
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload[field] = list(reversed(payload[field]))
                self.write_json(repository, MODULE.RULE_PATH, payload)
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(field in error for error in errors), errors)

    def test_all_json_inputs_reject_duplicate_keys(self) -> None:
        for relative_path, duplicate_key, duplicate_value in (
            (MODULE.RULE_PATH, "ruleId", '"MCH-001"'),
            (MODULE.POLICY_PATH, "schemaVersion", "99"),
            (MODULE.REGISTRY_PATH, "schemaVersion", "99"),
        ):
            with self.subTest(relative_path=relative_path):
                repository = self.snapshot()
                path = repository / relative_path
                content = path.read_text(encoding="utf-8")
                path.write_text(
                    content.replace(
                        "{\n",
                        f'{{\n  "{duplicate_key}": {duplicate_value},\n',
                        1,
                    ),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertIn(
                    f"duplicate JSON key: {duplicate_key}",
                    "\n".join(errors),
                )

    def test_policy_paths_are_sorted_and_preserve_history(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.POLICY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["rulebookPaths"] = list(reversed(payload["rulebookPaths"]))
        self.write_json(repository, MODULE.POLICY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("canonical" in error for error in errors), errors)

        repository = self.snapshot()
        path = repository / MODULE.POLICY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["ruleCardPaths"].remove(
            ".agents/payment-modernization/rules/IAM-003.json"
        )
        payload["ruleCardPaths"].sort()
        self.write_json(repository, MODULE.POLICY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("historical" in error for error in errors), errors)

        for field, path_to_remove in (
            ("rulebookPaths", "docs/adr/0008-isolate-three-backoffice-account-domains-and-sessions.md"),
            ("judgePaths", "scripts/check_iam002_production_identity_boundary.py"),
            ("judgePaths", "backend/pom.xml"),
            ("judgePaths", "frontend/admin/package.json"),
        ):
            repository = self.snapshot()
            path = repository / MODULE.POLICY_PATH
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload[field].remove(path_to_remove)
            self.write_json(repository, MODULE.POLICY_PATH, payload)
            errors = MODULE.validate_contract(repository)
            self.assertTrue(
                any(
                    "historical" in error or "missing MCH-001 path" in error
                    for error in errors
                ),
                errors,
            )

    def test_registry_requires_the_exact_three_mch001_checks_and_history(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["checks"] = [
            check
            for check in payload["checks"]
            if check["checkId"] != "MCH-001-FRONTEND-VERIFY"
        ]
        self.write_json(repository, MODULE.REGISTRY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("MCH-001-FRONTEND-VERIFY" in error for error in errors), errors)

        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        frontend_check = next(
            check
            for check in payload["checks"]
            if check["checkId"] == "MCH-001-FRONTEND-VERIFY"
        )
        frontend_check["command"] = (
            "pnpm --dir frontend/admin test:unit && "
            "pnpm --dir frontend/admin build:all"
        )
        self.write_json(repository, MODULE.REGISTRY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("MCH-001-FRONTEND-VERIFY" in error for error in errors), errors)

        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["checks"] = [
            check for check in payload["checks"] if check["checkId"] != "IAM-003-BACKEND-VERIFY"
        ]
        self.write_json(repository, MODULE.REGISTRY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("historical" in error for error in errors), errors)

        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        historical_check = next(
            check
            for check in payload["checks"]
            if check["checkId"] == "IAM-003-BACKEND-VERIFY"
        )
        historical_check["command"] = "true"
        self.write_json(repository, MODULE.REGISTRY_PATH, payload)
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("historical check changed" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
