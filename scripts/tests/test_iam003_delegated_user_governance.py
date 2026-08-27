from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_iam003_delegated_user_governance.py"
SPEC = importlib.util.spec_from_file_location("iam003_delegated_user_governance", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Iam003DelegatedUserGovernanceTest(unittest.TestCase):
    def snapshot(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repository = Path(directory.name)
        for relative_path in (MODULE.ADR_PATH, MODULE.RULE_PATH):
            source = REPOSITORY / relative_path
            destination = repository / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return repository

    def test_repository_contract_contains_all_governance_rules(self) -> None:
        self.assertEqual([], MODULE.validate_contract(REPOSITORY))
        self.assertEqual(6, len(MODULE.REQUIRED_RESULTS))
        self.assertEqual(6, len(MODULE.REQUIRED_COUNTEREXAMPLES))

    def test_each_adr_decision_is_mandatory(self) -> None:
        for decision_id, statement in MODULE.REQUIRED_ADR_DECISIONS.items():
            with self.subTest(decision_id=decision_id):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                content = path.read_text(encoding="utf-8")
                path.write_text(
                    content.replace(statement, "weaker statement", 1),
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any(decision_id in error for error in errors),
                    errors,
                )

    def test_each_rule_result_is_mandatory(self) -> None:
        for result_id, statement in MODULE.REQUIRED_RESULTS.items():
            with self.subTest(result_id=result_id):
                repository = self.snapshot()
                path = repository / MODULE.RULE_PATH
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload["then"].remove(statement)
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(any(result_id in error for error in errors), errors)

    def test_each_counterexample_is_mandatory(self) -> None:
        for result_id, counterexample in MODULE.REQUIRED_COUNTEREXAMPLES.items():
            with self.subTest(result_id=result_id):
                repository = self.snapshot()
                path = repository / MODULE.RULE_PATH
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload["counterexamples"].remove(counterexample)
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(any(result_id in error for error in errors), errors)

    def test_rule_rejects_additional_cross_tenant_authority(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.RULE_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["then"].append(
            "PLATFORM may edit every ordinary tenant user and role."
        )
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("exact ordered result set" in error for error in errors), errors)

    def test_duplicate_rule_keys_fail_closed(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.RULE_PATH
        content = path.read_text(encoding="utf-8")
        path.write_text(
            content.replace("{\n", '{\n  "ruleId": "IAM-003",\n', 1),
            encoding="utf-8",
        )

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("duplicate JSON key" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
