#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


CHECK_ID = "IAM-002-KEYCLOAK-CONFIG"
README_PATH = Path("infra/keycloak/README.md")
REALM_DIRECTORY = Path("infra/keycloak/realms")
IMAGE_REFERENCE = (
    "quay.io/keycloak/keycloak:26.7.0@"
    "sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13"
)
DOMAINS = {
    "PLATFORM": "platform",
    "MERCHANT": "merchant",
    "AGENT": "agent",
}


class ConfigurationError(RuntimeError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ConfigurationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read_regular_file(repository: Path, relative_path: Path) -> str:
    repository = repository.resolve()
    current = repository
    for component in relative_path.parts:
        current = current / component
        if current.is_symlink():
            raise ConfigurationError(
                f"configuration path must not contain a symbolic link: {relative_path}"
            )
    if not current.is_file():
        raise ConfigurationError(f"configuration file is missing: {relative_path}")
    return current.read_text(encoding="utf-8")


def _load_realm(repository: Path, domain: str) -> Mapping[str, Any]:
    relative_path = REALM_DIRECTORY / f"{domain}-realm.json"
    try:
        payload = json.loads(
            _read_regular_file(repository, relative_path),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (json.JSONDecodeError, UnicodeError, OSError, ConfigurationError) as error:
        raise ConfigurationError(f"cannot parse {relative_path}: {error}") from error
    if not isinstance(payload, Mapping):
        raise ConfigurationError(f"{relative_path} must contain a JSON object")
    return payload


def _one(items: Any, key: str, value: str, label: str) -> Mapping[str, Any]:
    if not isinstance(items, list):
        raise ConfigurationError(f"{label} must be a list")
    matches = [item for item in items if isinstance(item, Mapping) and item.get(key) == value]
    if len(matches) != 1:
        raise ConfigurationError(f"{label} must contain exactly one {key}={value}")
    return matches[0]


def _expect(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def _validate_realm(domain: str, prefix: str, realm: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    label = f"{domain} realm"
    _expect(errors, realm.get("realm") == domain, f"{label} name must be exact")
    _expect(errors, realm.get("enabled") is True, f"{label} must be enabled")
    _expect(errors, realm.get("registrationAllowed") is False,
            f"{label} registration must be disabled")
    _expect(errors, realm.get("verifyEmail") is True, f"{label} must verify email")
    _expect(errors, realm.get("duplicateEmailsAllowed") is False,
            f"{label} duplicate email profiles must be disabled")
    _expect(errors, realm.get("browserFlow") == "iam-browser-loa2",
            f"{label} must bind the IAM LoA 2 browser flow")
    _expect(errors, realm.get("identityProviders") == [],
            f"{label} must not configure identity brokering")

    actions = realm.get("requiredActions")
    for alias in ("CONFIGURE_TOTP", "VERIFY_EMAIL", "RECOVERY_AUTHN_CODES"):
        try:
            action = _one(actions, "alias", alias, f"{label} requiredActions")
            _expect(errors, action.get("enabled") is True and action.get("defaultAction") is True,
                    f"{label} {alias} must be enabled and default")
        except ConfigurationError as error:
            errors.append(str(error))

    try:
        flow = _one(realm.get("authenticationFlows"), "alias", "iam-loa2-flow",
                    f"{label} authenticationFlows")
        executions = flow.get("authenticationExecutions")
        condition = _one(executions, "authenticator", "conditional-level-of-authentication",
                         f"{label} LoA 2 executions")
        otp = _one(executions, "authenticator", "auth-otp-form",
                   f"{label} LoA 2 executions")
        recovery = _one(executions, "authenticator", "auth-recovery-authn-code-form",
                        f"{label} LoA 2 executions")
        _expect(errors, condition.get("requirement") == "REQUIRED",
                f"{label} LoA 2 condition must be required")
        _expect(errors, otp.get("requirement") == "ALTERNATIVE"
                and recovery.get("requirement") == "ALTERNATIVE",
                f"{label} OTP and recovery code must be alternative LoA 2 factors")
    except ConfigurationError as error:
        errors.append(str(error))

    client_id = f"{prefix}-admin-api"
    lifecycle_id = f"{prefix}-identity-lifecycle"
    clients = realm.get("clients")
    _expect(errors, isinstance(clients, list) and len(clients) == 2,
            f"{label} must declare exactly two clients")
    try:
        login = _one(clients, "clientId", client_id, f"{label} clients")
        _expect(errors, login.get("publicClient") is False,
                f"{label} login client must be confidential")
        _expect(errors, login.get("standardFlowEnabled") is True,
                f"{label} login client must enable Authorization Code")
        _expect(errors, login.get("implicitFlowEnabled") is False
                and login.get("directAccessGrantsEnabled") is False,
                f"{label} login client must disable implicit and Direct Grant")
        _expect(errors, login.get("serviceAccountsEnabled") is False,
                f"{label} login client must not be a service account")
        _expect(errors, login.get("secret") == f"${{PAYMENT_{domain}_OIDC_CLIENT_SECRET}}",
                f"{label} login secret must use its domain-specific variable")
        _expect(errors, login.get("redirectUris") ==
                [f"${{PAYMENT_{domain}_OIDC_REDIRECT_URI}}"],
                f"{label} callback must use its domain-specific variable")
        _expect(errors, login.get("webOrigins") ==
                [f"${{PAYMENT_{domain}_WEB_ORIGIN}}"],
                f"{label} web origin must use its domain-specific variable")
        attributes = login.get("attributes")
        _expect(errors, isinstance(attributes, Mapping)
                and attributes.get("pkce.code.challenge.method") == "S256",
                f"{label} login client must require PKCE S256")
        _expect(errors, isinstance(attributes, Mapping)
                and attributes.get("backchannel.logout.url") ==
                f"${{PAYMENT_{domain}_OIDC_BACKCHANNEL_LOGOUT_URI}}"
                and attributes.get("backchannel.logout.session.required") == "true",
                f"{label} login client must configure session-bound back-channel logout")
        mapper = _one(login.get("protocolMappers"), "protocolMapper",
                      "oidc-audience-mapper", f"{label} protocolMappers")
        _expect(errors, isinstance(mapper.get("config"), Mapping)
                and mapper["config"].get("included.client.audience") == client_id,
                f"{label} audience mapper must name only its login client")
    except ConfigurationError as error:
        errors.append(str(error))

    try:
        lifecycle = _one(clients, "clientId", lifecycle_id, f"{label} clients")
        _expect(errors, lifecycle.get("standardFlowEnabled") is False
                and lifecycle.get("directAccessGrantsEnabled") is False,
                f"{label} lifecycle client must not accept user grants")
        _expect(errors, lifecycle.get("serviceAccountsEnabled") is True,
                f"{label} lifecycle client must enable its service account")
        _expect(errors, lifecycle.get("secret") ==
                f"${{PAYMENT_{domain}_KEYCLOAK_ADMIN_CLIENT_SECRET}}",
                f"{label} lifecycle secret must use its domain-specific variable")
        service_user = _one(realm.get("users"), "serviceAccountClientId", lifecycle_id,
                            f"{label} service users")
        roles = service_user.get("clientRoles")
        _expect(errors, isinstance(roles, Mapping)
                and roles.get("realm-management") == ["manage-users", "view-users"],
                f"{label} lifecycle service account must have only required user roles")
    except ConfigurationError as error:
        errors.append(str(error))
    return errors


def validate_configuration(repository: Path) -> list[str]:
    repository = repository.resolve()
    errors: list[str] = []
    try:
        readme = _read_regular_file(repository, README_PATH)
        if readme.count(IMAGE_REFERENCE) != 1:
            errors.append("Keycloak README must pin the exact 26.7.0 multi-arch image digest once")
    except (OSError, UnicodeError, ConfigurationError) as error:
        errors.append(str(error))

    realm_dir = repository / REALM_DIRECTORY
    if realm_dir.is_dir():
        actual = sorted(path.name for path in realm_dir.iterdir())
        expected = sorted(f"{domain}-realm.json" for domain in DOMAINS)
        if actual != expected:
            errors.append("Keycloak realm directory must contain exactly three regular JSON files")
    else:
        errors.append(f"configuration directory is missing: {REALM_DIRECTORY}")

    for domain, prefix in DOMAINS.items():
        try:
            errors.extend(_validate_realm(domain, prefix, _load_realm(repository, domain)))
        except ConfigurationError as error:
            errors.append(str(error))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate IAM-002 Keycloak realm configuration.")
    parser.add_argument("--repository-root", type=Path,
                        default=Path(__file__).resolve().parent.parent)
    arguments = parser.parse_args()
    errors = validate_configuration(arguments.repository_root)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"{CHECK_ID} failed with {len(errors)} problem(s).", file=sys.stderr)
        return 1
    print(f"{CHECK_ID} passed: three isolated realm baselines are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
