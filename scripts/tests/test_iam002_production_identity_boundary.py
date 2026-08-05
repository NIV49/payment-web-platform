from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_iam002_production_identity_boundary.py"
SPEC = importlib.util.spec_from_file_location("iam002_identity_boundary", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Iam002ProductionIdentityBoundaryTest(unittest.TestCase):
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

    def test_repository_contract_contains_all_six_invariants(self) -> None:
        self.assertEqual([], MODULE.validate_contract(REPOSITORY))
        self.assertEqual(6, len(MODULE.REQUIRED_INVARIANTS))
        self.assertEqual(6, len(MODULE.REQUIRED_COUNTEREXAMPLES))

    def test_each_adr_invariant_is_mandatory(self) -> None:
        for invariant_id, statement in MODULE.REQUIRED_INVARIANTS.items():
            with self.subTest(invariant_id=invariant_id):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                content = path.read_text(encoding="utf-8")
                path.write_text(content.replace(statement, "weaker statement", 1), encoding="utf-8")

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any(invariant_id in error for error in errors),
                    errors,
                )

    def test_each_rule_invariant_is_mandatory(self) -> None:
        for invariant_id, statement in MODULE.REQUIRED_INVARIANTS.items():
            with self.subTest(invariant_id=invariant_id):
                repository = self.snapshot()
                path = repository / MODULE.RULE_PATH
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload["then"].remove(statement)
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any(invariant_id in error for error in errors),
                    errors,
                )

    def test_each_counterexample_is_mandatory(self) -> None:
        for invariant_id, counterexample in MODULE.REQUIRED_COUNTEREXAMPLES.items():
            with self.subTest(invariant_id=invariant_id):
                repository = self.snapshot()
                path = repository / MODULE.RULE_PATH
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload["counterexamples"].remove(counterexample)
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

                errors = MODULE.validate_contract(repository)

                self.assertTrue(
                    any(invariant_id in error for error in errors),
                    errors,
                )

    def test_rule_rejects_an_additional_contradictory_result(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.RULE_PATH
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["then"].append("SameSite is sufficient without a CSRF token.")
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("exact ordered invariant set" in error for error in errors), errors)

    def test_adr_rejects_a_second_normative_statement(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.ADR_PATH
        content = path.read_text(encoding="utf-8")
        marker = f"Normative statement: {MODULE.REQUIRED_INVARIANTS['IAM-002-R4']}"
        path.write_text(
            content.replace(
                marker,
                marker + "\n\nNormative statement: SameSite is sufficient without CSRF.",
                1,
            ),
            encoding="utf-8",
        )

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("IAM-002-R4" in error for error in errors), errors)

    def test_duplicate_rule_keys_fail_closed(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.RULE_PATH
        content = path.read_text(encoding="utf-8")
        path.write_text(content.replace("{\n", '{\n  "ruleId": "IAM-002",\n', 1), encoding="utf-8")

        errors = MODULE.validate_contract(repository)

        self.assertTrue(any("duplicate JSON key" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
