from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_mch002_merchant_profile.py"
SPEC = importlib.util.spec_from_file_location("mch002_merchant_profile", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Mch002MerchantProfileTest(unittest.TestCase):
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
        return repository

    def test_repository_contract_contains_all_mch002_rules(self) -> None:
        self.assertEqual([], MODULE.validate_contract(REPOSITORY))

    def test_each_adr_decision_marker_is_mandatory(self) -> None:
        for marker in MODULE.ADR_MARKERS:
            with self.subTest(marker=marker):
                repository = self.snapshot()
                path = repository / MODULE.ADR_PATH
                path.write_text(path.read_text(encoding="utf-8").replace(marker, "REMOVED", 1), encoding="utf-8")
                self.assertTrue(any(marker in error for error in MODULE.validate_contract(repository)))

    def test_endpoint_permission_and_market_contract_are_mandatory(self) -> None:
        for literal in (
            MODULE.ENDPOINT,
            MODULE.PERMISSION,
            "ISO 3166-1 alpha-2",
            "ISO 3166-1 alpha-3",
            "BRA",
            "PHL",
            "merchantTypeCode",
            "legalPersonName",
            "authenticationType",
            "V35",
            "recent step-up",
        ):
            with self.subTest(literal=literal):
                repository = self.snapshot()
                path = repository / MODULE.CONTRACT_PATH
                path.write_text(path.read_text(encoding="utf-8").replace(literal, "REMOVED"), encoding="utf-8")
                self.assertTrue(MODULE.validate_contract(repository))

    def test_policy_and_registry_bind_mch002(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.REGISTRY_PATH
        path.write_text(path.read_text(encoding="utf-8").replace("MCH-002-FRONTEND-VERIFY", "REMOVED", 1), encoding="utf-8")
        self.assertTrue(any("MCH-002-FRONTEND-VERIFY" in error for error in MODULE.validate_contract(repository)))

    def test_v34_bridge_callback_is_present_and_frozen(self) -> None:
        repository = self.snapshot()
        path = repository / MODULE.V34_BRIDGE_CALLBACK_PATH
        path.unlink()
        self.assertTrue(any(str(MODULE.V34_BRIDGE_CALLBACK_PATH) in error
                            for error in MODULE.validate_contract(repository)))

        repository = self.snapshot()
        path = repository / MODULE.V34_BRIDGE_CALLBACK_PATH
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "current_version IS DISTINCT FROM '33'",
                "current_version IS DISTINCT FROM '32'",
                1,
            ),
            encoding="utf-8",
        )
        errors = MODULE.validate_contract(repository)
        self.assertTrue(any("reviewed SHA-256" in error for error in errors), errors)
        self.assertTrue(
            any("current_version IS DISTINCT FROM '33'" in error for error in errors),
            errors,
        )

    def test_audit_evidence_migration_resources_are_present_and_frozen(self) -> None:
        cases = (
            (
                MODULE.V34_AUDIT_PREFLIGHT_CALLBACK_PATH,
                "current_version IS DISTINCT FROM '33'",
                "current_version IS DISTINCT FROM '32'",
                "V34 audit preflight callback",
            ),
            (
                MODULE.V36_AUDIT_UNIQUENESS_PATH,
                "UNIQUE (merchant_id, merchant_version)",
                "UNIQUE (merchant_id, actor_membership_id)",
                "V36 Merchant audit uniqueness migration",
            ),
        )
        for path_value, original, replacement, expected_error in cases:
            with self.subTest(path=path_value, mutation="deleted"):
                repository = self.snapshot()
                (repository / path_value).unlink()
                self.assertTrue(any(str(path_value) in error
                                    for error in MODULE.validate_contract(repository)))

            with self.subTest(path=path_value, mutation="changed"):
                repository = self.snapshot()
                path = repository / path_value
                path.write_text(
                    path.read_text(encoding="utf-8").replace(original, replacement, 1),
                    encoding="utf-8",
                )
                errors = MODULE.validate_contract(repository)
                self.assertTrue(any(expected_error in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
