from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/check_iam002_keycloak_realms.py"
SPEC = importlib.util.spec_from_file_location("iam002_keycloak_realms", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Iam002KeycloakRealmTest(unittest.TestCase):
    def snapshot(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repository = Path(directory.name)
        paths = [MODULE.README_PATH]
        paths.extend(
            MODULE.REALM_DIRECTORY / f"{domain}-realm.json"
            for domain in MODULE.DOMAINS
        )
        for relative_path in paths:
            source = REPOSITORY / relative_path
            destination = repository / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return repository

    def mutate_realm(self, repository: Path, domain: str, mutation) -> None:
        path = repository / MODULE.REALM_DIRECTORY / f"{domain}-realm.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        mutation(payload)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def test_repository_configuration_is_valid(self) -> None:
        self.assertEqual([], MODULE.validate_configuration(REPOSITORY))

    def test_each_realm_is_mandatory(self) -> None:
        for domain in MODULE.DOMAINS:
            with self.subTest(domain=domain):
                repository = self.snapshot()
                (repository / MODULE.REALM_DIRECTORY / f"{domain}-realm.json").unlink()
                self.assertTrue(MODULE.validate_configuration(repository))

    def test_direct_access_grant_fails_closed(self) -> None:
        repository = self.snapshot()
        self.mutate_realm(repository, "MERCHANT", lambda payload:
            payload["clients"][0].update({"directAccessGrantsEnabled": True}))
        errors = MODULE.validate_configuration(repository)
        self.assertTrue(any("Direct Grant" in error for error in errors), errors)

    def test_cross_realm_audience_fails_closed(self) -> None:
        repository = self.snapshot()
        self.mutate_realm(repository, "AGENT", lambda payload:
            payload["clients"][0]["protocolMappers"][0]["config"].update(
                {"included.client.audience": "merchant-admin-api"}))
        errors = MODULE.validate_configuration(repository)
        self.assertTrue(any("audience" in error for error in errors), errors)

    def test_missing_recovery_code_factor_fails_closed(self) -> None:
        repository = self.snapshot()

        def remove_recovery(payload) -> None:
            flow = next(item for item in payload["authenticationFlows"]
                        if item["alias"] == "iam-loa2-flow")
            flow["authenticationExecutions"] = [
                item for item in flow["authenticationExecutions"]
                if item.get("authenticator") != "auth-recovery-authn-code-form"
            ]

        self.mutate_realm(repository, "PLATFORM", remove_recovery)
        errors = MODULE.validate_configuration(repository)
        self.assertTrue(any("recovery-authn-code-form" in error for error in errors), errors)

    def test_identity_provider_fails_closed(self) -> None:
        repository = self.snapshot()
        self.mutate_realm(repository, "PLATFORM", lambda payload:
            payload["identityProviders"].append({"alias": "shared"}))
        errors = MODULE.validate_configuration(repository)
        self.assertTrue(any("identity brokering" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
