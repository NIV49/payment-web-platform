#!/usr/bin/env python3
"""Prepare private, repeatable IAM-002 local Keycloak artifacts."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import subprocess
import tempfile
from pathlib import Path
from typing import Callable


KEYCLOAK_VOLUME = "payment-web-platform-iam002-keycloak-data"
DOMAINS = ("PLATFORM", "MERCHANT", "AGENT")
LOCAL_IDENTITIES = {
    "PLATFORM": {"subject": "10000000-0000-4000-8000-000000000100", "host": "platform.localhost"},
    "MERCHANT": {"subject": "20000000-0000-4000-8000-000000000200", "host": "merchant.localhost"},
    "AGENT": {"subject": "30000000-0000-4000-8000-000000000300", "host": "agent.localhost"},
}


def _required_keys() -> set[str]:
    keys = {
        "PAYMENT_IAM002_POSTGRES_PASSWORD",
        "PAYMENT_IAM002_REDIS_PASSWORD",
        "PAYMENT_IAM002_REALM_DIR",
        "KC_BOOTSTRAP_ADMIN_USERNAME",
        "KC_BOOTSTRAP_ADMIN_PASSWORD",
        "PAYMENT_KEYCLOAK_SMTP_FROM",
    }
    for domain in DOMAINS:
        keys.update(
            {
                f"PAYMENT_{domain}_OIDC_CLIENT_SECRET",
                f"PAYMENT_{domain}_KEYCLOAK_ADMIN_CLIENT_SECRET",
                f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD",
                f"PAYMENT_{domain}_LOCAL_ADMIN_TOTP_SECRET",
            }
        )
    return keys


def _strict_json(path: Path):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise RuntimeError(f"duplicate JSON key in {path}: {key}")
            result[key] = value
        return result

    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read strict Realm JSON: {path}") from error


def _parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise RuntimeError("cannot read private runtime environment") from error
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError("private runtime environment is malformed")
        key, value = line.split("=", 1)
        if key in values or re.fullmatch(r"[A-Z][A-Z0-9_]*", key) is None or not value:
            raise RuntimeError("private runtime environment is malformed")
        values[key] = value
    missing = sorted(_required_keys() - values.keys())
    if missing:
        raise RuntimeError("private runtime environment is missing required keys")
    _validate_local_passwords(values)
    return values


def _atomic_private_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def _default_secret(key: str) -> str:
    if key.endswith("_TOTP_SECRET"):
        return base64.b32encode(secrets.token_bytes(20)).decode("ascii").rstrip("=")
    if key.endswith("_LOCAL_ADMIN_PASSWORD"):
        return "Aa1!" + secrets.token_urlsafe(32)
    return secrets.token_urlsafe(36)


def _validate_local_passwords(values: dict[str, str]) -> None:
    for domain in DOMAINS:
        password = values[f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD"]
        if (
            len(password) < 14
            or re.search(r"[A-Z]", password) is None
            or re.search(r"[a-z]", password) is None
            or re.search(r"[0-9]", password) is None
            or re.search(r"[^A-Za-z0-9]", password) is None
        ):
            raise RuntimeError(f"local {domain} administrator password violates the Realm policy")


def _new_environment(output_root: Path, secret_factory: Callable[[str], str]) -> dict[str, str]:
    values = {
        "PAYMENT_IAM002_POSTGRES_PASSWORD": secret_factory("PAYMENT_IAM002_POSTGRES_PASSWORD"),
        "PAYMENT_IAM002_REDIS_PASSWORD": secret_factory("PAYMENT_IAM002_REDIS_PASSWORD"),
        "PAYMENT_IAM002_REALM_DIR": str((output_root / "realms").resolve()),
        "KC_BOOTSTRAP_ADMIN_USERNAME": "iam002-local-admin",
        "KC_BOOTSTRAP_ADMIN_PASSWORD": secret_factory("KC_BOOTSTRAP_ADMIN_PASSWORD"),
        "PAYMENT_KEYCLOAK_SMTP_FROM": "no-reply@local.invalid",
    }
    for domain in DOMAINS:
        for suffix in (
            "OIDC_CLIENT_SECRET",
            "KEYCLOAK_ADMIN_CLIENT_SECRET",
            "LOCAL_ADMIN_PASSWORD",
            "LOCAL_ADMIN_TOTP_SECRET",
        ):
            key = f"PAYMENT_{domain}_{suffix}"
            values[key] = secret_factory(key)
    invalid = [key for key, value in values.items() if "\n" in value or "\r" in value or not value]
    if invalid:
        raise RuntimeError("generated private runtime environment is invalid")
    _validate_local_passwords(values)
    return values


def _render_environment(values: dict[str, str]) -> str:
    return "".join(f"{key}={values[key]}\n" for key in sorted(values))


def _local_user(domain: str) -> dict:
    lower = domain.lower()
    login_email = f"admin@{lower}.localhost"
    credential_data = json.dumps(
        {
            "subType": "totp",
            "digits": 6,
            "counter": 0,
            "period": 30,
            "algorithm": "HmacSHA256",
            "secretEncoding": "BASE32",
        },
        separators=(",", ":"),
    )
    secret_data = json.dumps(
        {"value": f"${{PAYMENT_{domain}_LOCAL_ADMIN_TOTP_SECRET}}"},
        separators=(",", ":"),
    )
    return {
        "id": LOCAL_IDENTITIES[domain]["subject"],
        "username": login_email,
        "enabled": True,
        "email": login_email,
        "emailVerified": True,
        "firstName": domain.title(),
        "lastName": "Local Administrator",
        "credentials": [
            {
                "type": "password",
                "value": f"${{PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD}}",
                "temporary": False,
            },
            {
                "type": "otp",
                "userLabel": "iam002-local-totp",
                "secretData": secret_data,
                "credentialData": credential_data,
            },
        ],
        "requiredActions": ["CONFIGURE_RECOVERY_AUTHN_CODES"],
    }


def _render_realms(repository_root: Path, output_root: Path) -> dict[str, dict[str, str]]:
    source_root = repository_root / "infra/keycloak/realms"
    target_root = output_root / "realms"
    target_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(target_root, 0o700)
    manifest: dict[str, dict[str, str]] = {}
    for domain in DOMAINS:
        source = source_root / f"{domain}-realm.json"
        realm = _strict_json(source)
        if realm.get("realm") != domain or not isinstance(realm.get("users"), list):
            raise RuntimeError(f"Realm baseline is invalid for {domain}")
        username = f"admin@{domain.lower()}.localhost"
        if any(user.get("username") == username for user in realm["users"]):
            raise RuntimeError(f"Realm baseline already contains the local user for {domain}")
        realm["users"].append(_local_user(domain))
        rendered = json.dumps(realm, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
        target = target_root / source.name
        _atomic_private_write(target, rendered)
        manifest[domain] = {
            "baselineSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "generatedSha256": hashlib.sha256(rendered.encode("utf-8")).hexdigest(),
            "subject": LOCAL_IDENTITIES[domain]["subject"],
        }
    _atomic_private_write(
        output_root / "manifest.json",
        json.dumps({"schemaVersion": 1, "realms": manifest}, ensure_ascii=True, indent=2, sort_keys=True)
        + "\n",
    )
    return manifest


def docker_volume_exists(name: str) -> bool:
    completed = subprocess.run(
        ["docker", "volume", "inspect", name],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return completed.returncode == 0


def prepare_local_artifacts(
    repository_root: Path,
    output_root: Path,
    volume_exists: Callable[[str], bool] = docker_volume_exists,
    secret_factory: Callable[[str], str] = _default_secret,
) -> dict[str, str]:
    repository_root = repository_root.resolve(strict=True)
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(output_root, 0o700)
    env_path = output_root / "runtime.env"
    if env_path.exists():
        values = _parse_env(env_path)
        expected_realm_dir = str((output_root / "realms").resolve())
        if values["PAYMENT_IAM002_REALM_DIR"] != expected_realm_dir:
            raise RuntimeError("private runtime environment belongs to another artifact directory")
    else:
        if volume_exists(KEYCLOAK_VOLUME):
            raise RuntimeError(
                "private runtime environment is missing while the persistent Keycloak volume exists"
            )
        values = _new_environment(output_root, secret_factory)
        _atomic_private_write(env_path, _render_environment(values))
    _render_realms(repository_root, output_root)
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    arguments = parser.parse_args()
    prepare_local_artifacts(arguments.repository_root, arguments.output_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
