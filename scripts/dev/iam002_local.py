#!/usr/bin/env python3
"""Operate the isolated IAM-002 local runtime."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import shutil
import signal
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Mapping, Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
STATE_ROOT = REPOSITORY_ROOT / ".local/iam002"
RUN_ROOT = STATE_ROOT / "run"
ENV_PATH = STATE_ROOT / "runtime.env"
PROCESS_PATH = RUN_ROOT / "processes.json"
COMPOSE_PATH = REPOSITORY_ROOT / "infra/docker-compose.iam002-local.yml"
CONVERSION_PATH = REPOSITORY_ROOT / "infra/local/iam002/convert-local-fixture-to-oidc.sql"
DELEGATED_GOVERNANCE_CONVERGENCE_PATH = (
    REPOSITORY_ROOT / "infra/local/iam002/converge-delegated-user-governance.sql"
)
DICTIONARY_SAMPLE_PATH = (
    REPOSITORY_ROOT
    / "backend/applications/platform-admin-api/src/main/resources/db/local/system-dictionary-sample.sql"
)
VERIFICATION_PATH = REPOSITORY_ROOT / "scripts/dev/verify_iam002_local.cjs"
LOCAL_VERIFICATION_PATH = REPOSITORY_ROOT / "scripts/dev/verify_iam002_local_password.cjs"
VERIFICATION_TEST_PATH = REPOSITORY_ROOT / "scripts/tests/verify_iam002_local.test.cjs"
VERIFICATION_STATE_PATH = STATE_ROOT / "e2e-state.json"
BACKEND_ROOT = REPOSITORY_ROOT / "backend"
FRONTEND_ROOT = REPOSITORY_ROOT / "frontend/admin"
MAILPIT_VERSION = "1.30.0"
MAILPIT_ARCHIVES = {
    "arm64": (
        "mailpit-darwin-arm64.tar.gz",
        "dbebbf3e95e82e111dd08fbec106cc09c026b207a9f105e45f212db4c63824a5",
    ),
}

BACKENDS = (
    {
        "name": "platform-backend",
        "artifact": "platform-admin-api",
        "port": 18081,
        "host": "platform.localhost",
    },
    {
        "name": "merchant-backend",
        "artifact": "merchant-admin-api",
        "port": 18082,
        "host": "merchant-e2e.localhost",
    },
    {
        "name": "agent-backend",
        "artifact": "agent-admin-api",
        "port": 18083,
        "host": "agent.localhost",
    },
)

FRONTENDS = (
    {
        "name": "platform-frontend",
        "package": "@payment/platform-admin",
        "directory": "platform-admin",
        "port": 15999,
        "host": "platform.localhost",
        "target": "http://127.0.0.1:18081/api",
        "target_variable": "PAYMENT_PLATFORM_ADMIN_DEV_API_TARGET",
        "account_domain": "PLATFORM",
        "local_username": "admin@platform.localhost",
    },
    {
        "name": "merchant-frontend",
        "package": "@payment/merchant-admin",
        "directory": "merchant-admin",
        "port": 16002,
        "host": "merchant-e2e.localhost",
        "target": "http://127.0.0.1:18082/api",
        "target_variable": "PAYMENT_MERCHANT_ADMIN_DEV_API_TARGET",
        "account_domain": "MERCHANT",
        "local_username": "admin@merchant.localhost",
    },
    {
        "name": "agent-frontend",
        "package": "@payment/agent-admin",
        "directory": "agent-admin",
        "port": 16001,
        "host": "agent.localhost",
        "target": "http://127.0.0.1:18083/api",
        "target_variable": "PAYMENT_AGENT_ADMIN_DEV_API_TARGET",
        "account_domain": "AGENT",
        "local_username": "admin@agent.localhost",
    },
)

PRIVATE_BACKEND_KEYS = {
    "PAYMENT_IAM002_POSTGRES_PASSWORD",
    "PAYMENT_IAM002_REDIS_PASSWORD",
    "PAYMENT_PLATFORM_OIDC_CLIENT_SECRET",
    "PAYMENT_PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET",
    "PAYMENT_MERCHANT_OIDC_CLIENT_SECRET",
    "PAYMENT_MERCHANT_KEYCLOAK_ADMIN_CLIENT_SECRET",
    "PAYMENT_AGENT_OIDC_CLIENT_SECRET",
    "PAYMENT_AGENT_KEYCLOAK_ADMIN_CLIENT_SECRET",
}
MERCHANT_LOCAL_KEY_ENVIRONMENTS = {
    "search": ("MCH_SEARCH_HMAC_KEYS", "mch-registration-search-v1"),
    "idempotency": ("MCH_IDEMPOTENCY_HMAC_KEYS", "mch-idempotency-v1"),
    "aead": ("MCH_REGISTRATION_AEAD_KEYS", "mch-registration-aead-v1"),
    "legal-id-aead": ("MCH_LEGAL_ID_AEAD_KEYS", "mch-legal-id-aead-v1"),
    "document-aead": ("MCH_DOCUMENT_AEAD_KEYS", "mch-document-aead-v1"),
}

LOCAL_LOGIN_ACCOUNTS = (
    {
        "domain": "PLATFORM",
        "url": "http://platform.localhost:15999",
        "username": "admin@platform.localhost",
    },
    {
        "domain": "AGENT",
        "url": "http://agent.localhost:16001",
        "username": "admin@agent.localhost",
    },
)
LOCAL_ADMIN_IDENTITIES = (
    (100, "PLATFORM", "admin@platform.localhost", "local:platform",
     "http://127.0.0.1:18080/realms/PLATFORM",
     "10000000-0000-4000-8000-000000000100"),
    (200, "MERCHANT", "admin@merchant.localhost", "local:merchant",
     "http://127.0.0.1:18080/realms/MERCHANT",
     "20000000-0000-4000-8000-000000000200"),
    (300, "AGENT", "admin@agent.localhost", "local:agent",
     "http://127.0.0.1:18080/realms/AGENT",
     "30000000-0000-4000-8000-000000000300"),
)
MERCHANT_VERIFICATION_USERNAME = "invite-c0f7e9d5-e862-42b6-8c68-acff7fb74482"


def _atomic_private_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=True, indent=2, sort_keys=True)
            stream.write("\n")
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


def _read_private_environment() -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = ENV_PATH.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise RuntimeError("IAM-002 private environment is unavailable; run prepare") from error
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError("IAM-002 private environment is malformed")
        key, value = line.split("=", 1)
        if key in values or re.fullmatch(r"[A-Z][A-Z0-9_]*", key) is None or not value:
            raise RuntimeError("IAM-002 private environment is malformed")
        values[key] = value
    missing = sorted(PRIVATE_BACKEND_KEYS - values.keys())
    if missing:
        raise RuntimeError("IAM-002 private environment is incomplete")
    return values


def _require_private_permissions(path: Path) -> None:
    try:
        mode = stat.S_IMODE(path.stat().st_mode)
    except OSError as error:
        raise RuntimeError(f"IAM-002 private file is unavailable: {path.name}") from error
    if mode & 0o077:
        raise RuntimeError(f"IAM-002 private file permissions are too broad: {path.name}")


def _totp_at(secret: str, timestamp: float | None = None, digits: int = 6) -> str:
    normalized = secret.rstrip("=").upper()
    if re.fullmatch(r"[A-Z2-7]{20,}", normalized) is None or digits not in (6, 8):
        raise RuntimeError("IAM-002 local TOTP secret is malformed")
    try:
        key = base64.b32decode(normalized + "=" * (-len(normalized) % 8), casefold=False)
    except (ValueError, TypeError) as error:
        raise RuntimeError("IAM-002 local TOTP secret is malformed") from error
    instant = time.time() if timestamp is None else timestamp
    if instant < 0:
        raise RuntimeError("IAM-002 local TOTP timestamp is invalid")
    digest = hmac.new(
        key,
        struct.pack(">Q", int(instant // 30)),
        hashlib.sha256,
    ).digest()
    offset = digest[-1] & 0x0F
    binary = (
        ((digest[offset] & 0x7F) << 24)
        | ((digest[offset + 1] & 0xFF) << 16)
        | ((digest[offset + 2] & 0xFF) << 8)
        | (digest[offset + 3] & 0xFF)
    )
    return str(binary % (10 ** digits)).zfill(digits)


def local_login_records(
    private: Mapping[str, str], merchant_identity: Mapping[str, object] | None,
    timestamp: float,
) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for account in LOCAL_LOGIN_ACCOUNTS:
        domain = str(account["domain"])
        password = private.get(f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD")
        totp_secret = private.get(f"PAYMENT_{domain}_LOCAL_ADMIN_TOTP_SECRET")
        if not password or not totp_secret:
            raise RuntimeError("IAM-002 local login environment is incomplete")
        records.append(
            {
                **account,
                "available": True,
                "password": password,
                "totp": _totp_at(totp_secret, timestamp),
            }
        )

    merchant_record: dict[str, object] = {
        "domain": "MERCHANT",
        "url": "http://merchant-e2e.localhost:16002",
        "username": MERCHANT_VERIFICATION_USERNAME,
        "available": False,
    }
    if merchant_identity is not None:
        password = merchant_identity.get("password")
        totp_secret = merchant_identity.get("totpSecret")
        if not isinstance(password, str) or not password or not isinstance(totp_secret, str):
            raise RuntimeError("IAM-002 merchant verification identity is malformed")
        merchant_record.update(
            {
                "available": True,
                "password": password,
                "totp": _totp_at(totp_secret, timestamp),
            }
        )
    records.insert(1, merchant_record)
    return records


def local_password_login_records(private: Mapping[str, str]) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for application in FRONTENDS:
        domain = str(application["account_domain"])
        password = private.get(f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD")
        if not password:
            raise RuntimeError("IAM-002 local login environment is incomplete")
        records.append(
            {
                "domain": domain,
                "url": f"http://{application['host']}:{application['port']}",
                "username": str(application["local_username"]),
                "password": password,
                "available": True,
            }
        )
        if domain == "PLATFORM":
            records.append(
                {
                    "domain": "PLATFORM REVIEWER",
                    "url": f"http://{application['host']}:{application['port']}",
                    "username": "reviewer@platform.localhost",
                    "password": password,
                    "available": True,
                }
            )
    return records


def _read_merchant_verification_identity() -> Mapping[str, object] | None:
    if not VERIFICATION_STATE_PATH.exists():
        return None
    _require_private_permissions(VERIFICATION_STATE_PATH)
    try:
        state = json.loads(VERIFICATION_STATE_PATH.read_text(encoding="utf-8"))
        identity = state.get("identities", {}).get("merchantAdministrator")
    except (AttributeError, json.JSONDecodeError, OSError, UnicodeError) as error:
        raise RuntimeError("IAM-002 browser verification state is malformed") from error
    return identity if isinstance(identity, dict) else None


def login_info(timestamp: float | None = None) -> None:
    _require_private_permissions(ENV_PATH)
    private = _read_private_environment()
    instant = time.time() if timestamp is None else timestamp
    authentication_mode = current_authentication_mode() or "local"
    if authentication_mode == "local":
        records = local_password_login_records(private)
    else:
        records = local_login_records(
            private, _read_merchant_verification_identity(), instant
        )
    print("IAM-002 LOCAL-ONLY LOGIN INFORMATION")
    print(f"Authentication mode: {authentication_mode}")
    if authentication_mode == "oidc":
        valid_for = 30 - (int(instant) % 30)
        print(f"TOTP codes refresh every 30 seconds; current codes are valid for about {valid_for}s.")
    for record in records:
        print()
        print(str(record["domain"]))
        print(f"URL: {record['url']}")
        if not record["available"]:
            print("Login: unavailable until `python3 scripts/dev/iam002_local.py verify` completes")
            continue
        print(f"Username: {record['username']}")
        print(f"Password: {record['password']}")
        if "totp" in record:
            print(f"One-time code: {record['totp']}")


def backend_environment(private: Mapping[str, str]) -> dict[str, str]:
    missing = sorted(
        (PRIVATE_BACKEND_KEYS | {"PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD"}) - private.keys()
    )
    if missing:
        raise RuntimeError("IAM-002 private environment is incomplete")
    environment = {key: private[key] for key in sorted(PRIVATE_BACKEND_KEYS)}
    environment["PAYMENT_BOOTSTRAP_PASSWORD"] = private[
        "PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD"
    ]
    salt = hashlib.sha256(
        private["PAYMENT_IAM002_REDIS_PASSWORD"].encode("utf-8")
    ).digest()
    pseudo_random_key = hmac.new(
        salt,
        private["PAYMENT_IAM002_POSTGRES_PASSWORD"].encode("utf-8"),
        hashlib.sha256,
    ).digest()
    for purpose, (environment_key, key_id) in MERCHANT_LOCAL_KEY_ENVIRONMENTS.items():
        material = hmac.new(
            pseudo_random_key,
            f"payment-iam002-local/merchant/{purpose}/v1".encode("ascii"),
            hashlib.sha256,
        ).digest()
        environment[environment_key] = (
            f"{key_id}={base64.b64encode(material).decode('ascii')}"
        )
    return environment


def backend_profile(authentication_mode: str) -> str:
    if authentication_mode == "local":
        return "iam002-local"
    if authentication_mode == "oidc":
        return "oidc-local"
    raise RuntimeError("IAM-002 authentication mode is invalid")


def frontend_environment(
    base: Mapping[str, str], application: Mapping[str, object],
    private: Mapping[str, str], authentication_mode: str,
) -> dict[str, str]:
    backend_profile(authentication_mode)
    environment = dict(base)
    environment["VITE_AUTH_MODE"] = authentication_mode
    environment[str(application["target_variable"])] = str(application["target"])
    if authentication_mode == "local":
        domain = str(application["account_domain"])
        password = private.get(f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD")
        if not password:
            raise RuntimeError("IAM-002 local administrator password is unavailable")
        environment["VITE_LOCAL_ADMIN_USERNAME"] = str(application["local_username"])
        environment["VITE_LOCAL_ADMIN_PASSWORD"] = password
    else:
        environment.pop("VITE_LOCAL_ADMIN_USERNAME", None)
        environment.pop("VITE_LOCAL_ADMIN_PASSWORD", None)
    return environment


def _local_password_copy_data(private: Mapping[str, str]) -> str:
    rows = []
    for user_id, domain, username, *_ in LOCAL_ADMIN_IDENTITIES:
        password = private.get(f"PAYMENT_{domain}_LOCAL_ADMIN_PASSWORD")
        if password is None or not password or any(
            character in password for character in ("\x00", "\n", "\r")
        ):
            raise RuntimeError("IAM-002 local administrator password is invalid")
        encoded = base64.b64encode(password.encode("utf-8")).decode("ascii")
        rows.append(f"{user_id}\t{domain}\t{username}\t{encoded}")
    return "\n".join(rows) + "\n"


def local_password_login_statement(
    *, enabled: bool, password_copy_data: str | None = None,
) -> str:
    identity_rows = ",\n        ".join(
        f"({user_id}, '{domain}', '{username}', '{local_issuer}', "
        f"'{oidc_issuer}', '{oidc_subject}')"
        for user_id, domain, username, local_issuer, oidc_issuer, oidc_subject
        in LOCAL_ADMIN_IDENTITIES
    )
    basic_identity_rows = ",\n        ".join(
        f"({user_id}, '{domain}', '{username}')"
        for user_id, domain, username, *_ in LOCAL_ADMIN_IDENTITIES
    )
    if enabled:
        copy_lines = [] if password_copy_data is None else password_copy_data.splitlines()
        expected_prefixes = [
            f"{user_id}\t{domain}\t{username}\t"
            for user_id, domain, username, *_ in LOCAL_ADMIN_IDENTITIES
        ]
        if (
            len(copy_lines) != 3
            or any(not line.startswith(prefix) for line, prefix in zip(
                copy_lines, expected_prefixes, strict=True
            ))
            or any(
                re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", line.rsplit("\t", 1)[-1]) is None
                for line in copy_lines
            )
        ):
            raise RuntimeError("IAM-002 password COPY data is invalid")
        password_import = f"""CREATE TEMP TABLE iam002_local_passwords (
    user_id BIGINT NOT NULL,
    account_domain TEXT NOT NULL,
    username TEXT NOT NULL,
    password_base64 TEXT NOT NULL
) ON COMMIT DROP;
\\copy iam002_local_passwords (user_id, account_domain, username, password_base64) FROM STDIN WITH (FORMAT text)
{password_copy_data}\\.
"""
        credential_update = """
    UPDATE iam_authentication_credential credential
       SET username = expected.username,
           password_hash = crypt(
               convert_from(decode(expected.password_base64, 'base64'), 'UTF8'),
               gen_salt('bf', 12)),
           updated_at = now(),
           row_version = credential.row_version + 1
      FROM iam002_local_passwords expected
     WHERE credential.user_id = expected.user_id
       AND credential.account_domain = expected.account_domain
       AND credential.status = 'ACTIVE';
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local credential enablement was not exact';
    END IF;
"""
        verification = """
    IF (SELECT count(*)
          FROM iam_authentication_credential credential
          JOIN iam002_local_passwords expected
            ON credential.user_id = expected.user_id
           AND credential.account_domain = expected.account_domain
           AND credential.username = expected.username
         WHERE credential.status = 'ACTIVE'
           AND crypt(
               convert_from(decode(expected.password_base64, 'base64'), 'UTF8'),
               credential.password_hash) = credential.password_hash) <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local credentials failed verification';
    END IF;
"""
        extension = "CREATE EXTENSION IF NOT EXISTS pgcrypto;\n"
        identity_assignment = """idp_issuer = expected.local_issuer,
           idp_subject = expected.username,
           idp_provisioning_status = 'LOCAL_ONLY',"""
        identity_verification = """user_account.idp_issuer = expected.local_issuer
           AND user_account.idp_subject = expected.username
           AND user_account.idp_provisioning_status = 'LOCAL_ONLY'"""
    else:
        if password_copy_data is not None:
            raise RuntimeError("IAM-002 password COPY data is not allowed in OIDC mode")
        password_import = ""
        credential_update = f"""
    UPDATE iam_authentication_credential credential
       SET username = expected.username,
           password_hash = NULL,
           updated_at = now(),
           row_version = credential.row_version + 1
      FROM (VALUES
        {basic_identity_rows}
      ) AS expected(user_id, account_domain, username)
     WHERE credential.user_id = expected.user_id
       AND credential.account_domain = expected.account_domain
       AND credential.status = 'ACTIVE';
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local credential disablement was not exact';
    END IF;
"""
        verification = f"""
    IF (SELECT count(*)
          FROM iam_authentication_credential credential
          JOIN (VALUES
            {basic_identity_rows}
          ) AS expected(user_id, account_domain, username)
            ON credential.user_id = expected.user_id
           AND credential.account_domain = expected.account_domain
           AND credential.username = expected.username
         WHERE credential.status = 'ACTIVE' AND credential.password_hash IS NULL) <> 3 THEN
        RAISE EXCEPTION 'IAM-002 disabled local credentials failed verification';
    END IF;
"""
        extension = ""
        identity_assignment = """idp_issuer = expected.oidc_issuer,
           idp_subject = expected.oidc_subject,
           idp_provisioning_status = 'PROVISIONED',"""
        identity_verification = """user_account.idp_issuer = expected.oidc_issuer
           AND user_account.idp_subject = expected.oidc_subject
           AND user_account.idp_provisioning_status = 'PROVISIONED'"""
    return f"""SET statement_timeout='30s';
SET lock_timeout='3s';
BEGIN;
{extension}{password_import}DO $iam002_local$
DECLARE
    changed INTEGER;
BEGIN
    IF (SELECT count(*)
          FROM iam_user user_account
          JOIN (VALUES
        {identity_rows}
          ) AS expected(user_id, account_domain, username, local_issuer,
                        oidc_issuer, oidc_subject)
            ON user_account.id = expected.user_id
           AND user_account.account_domain = expected.account_domain
         WHERE user_account.idp_issuer = expected.local_issuer
           AND user_account.idp_subject = expected.username
           AND user_account.idp_provisioning_status = 'LOCAL_ONLY'
           AND user_account.status = 'ACTIVE') <> 3
       AND (SELECT count(*)
              FROM iam_user user_account
              JOIN (VALUES
        {identity_rows}
              ) AS expected(user_id, account_domain, username, local_issuer,
                            oidc_issuer, oidc_subject)
                ON user_account.id = expected.user_id
               AND user_account.account_domain = expected.account_domain
             WHERE user_account.idp_issuer = expected.oidc_issuer
               AND user_account.idp_subject = expected.oidc_subject
               AND user_account.idp_provisioning_status = 'PROVISIONED'
               AND user_account.status = 'ACTIVE') <> 3 THEN
        RAISE EXCEPTION 'IAM-002 identity synchronization source was not exact';
    END IF;
{credential_update}
    UPDATE iam_user user_account
       SET {identity_assignment}
           identity_version = identity_version + 1,
           updated_at = now(),
           row_version = row_version + 1
      FROM (VALUES
        {identity_rows}
      ) AS expected(user_id, account_domain, username, local_issuer,
                    oidc_issuer, oidc_subject)
     WHERE user_account.id = expected.user_id
       AND user_account.account_domain = expected.account_domain
       AND user_account.status = 'ACTIVE';
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 identity mapping update was not exact';
    END IF;

    UPDATE iam_membership membership
       SET session_version = session_version + 1,
           updated_at = now(),
           row_version = row_version + 1
      FROM (VALUES
        {basic_identity_rows}
      ) AS expected(user_id, account_domain, username)
     WHERE membership.user_id = expected.user_id
       AND membership.account_domain = expected.account_domain
       AND membership.status = 'ACTIVE';
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 3 THEN
        RAISE EXCEPTION 'IAM-002 local session revocation was not exact';
    END IF;
{verification}    IF (SELECT count(*)
          FROM iam_user user_account
          JOIN (VALUES
        {identity_rows}
          ) AS expected(user_id, account_domain, username, local_issuer,
                        oidc_issuer, oidc_subject)
            ON user_account.id = expected.user_id
           AND user_account.account_domain = expected.account_domain
         WHERE {identity_verification}
           AND user_account.status = 'ACTIVE') <> 3 THEN
        RAISE EXCEPTION 'IAM-002 identity synchronization verification failed';
    END IF;
END
$iam002_local$;
COMMIT;
"""


def _synchronize_local_password_login(
    private: Mapping[str, str], authentication_mode: str,
) -> None:
    backend_profile(authentication_mode)
    try:
        password_copy_data = (
            _local_password_copy_data(private) if authentication_mode == "local" else None
        )
        statement = local_password_login_statement(
            enabled=authentication_mode == "local",
            password_copy_data=password_copy_data,
        )
        _psql(statement, capture=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError("IAM-002 local credential synchronization failed") from error


def classify_fixture_state(counts: Mapping[str, int]) -> str:
    table_count = int(counts.get("table_count", -1))
    if table_count == 0:
        return "EMPTY"
    user_count = int(counts.get("user_count", -1))
    local_count = int(counts.get("local_count", -1))
    local_auth_count = int(counts.get("local_auth_count", 0))
    oidc_count = int(counts.get("oidc_count", -1))
    entry_host_count = int(counts.get("entry_host_count", -1))
    baseline_entry_host_count = int(counts.get("baseline_entry_host_count", entry_host_count))
    if (user_count == 0 and local_count == 0 and local_auth_count == 0
        and oidc_count == 0 and entry_host_count == 0):
        return "EMPTY"
    if (user_count == 3 and local_count == 3 and local_auth_count == 0
        and oidc_count == 0 and entry_host_count == 0):
        return "LOCAL"
    if (user_count >= 3 and local_count == 0 and local_auth_count == 0
        and oidc_count == 3
        and entry_host_count >= 3 and baseline_entry_host_count == 3):
        return "OIDC"
    if (user_count >= 3 and local_count == 0 and local_auth_count == 3
        and oidc_count == 0 and entry_host_count >= 3
        and baseline_entry_host_count == 3):
        return "LOCAL_AUTH"
    raise RuntimeError("IAM-002 database is in an unknown or mixed fixture state")


def process_matches(record: Mapping[str, object], observed: Mapping[str, object]) -> bool:
    try:
        marker = str(record["commandMarker"])
        return (
            int(record["pid"]) == int(observed["pid"])
            and int(record["pgid"]) == int(observed["pgid"])
            and Path(str(record["cwd"])).resolve() == Path(str(observed["cwd"])).resolve()
            and marker in str(observed["command"])
        )
    except (KeyError, TypeError, ValueError, OSError):
        return False


def _run(command: Sequence[str], *, cwd: Path = REPOSITORY_ROOT,
         environment: Mapping[str, str] | None = None, input_text: str | None = None,
         capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=cwd,
        env=dict(environment) if environment is not None else None,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        check=True,
    )


def _compose_command(*arguments: str) -> list[str]:
    return [
        "docker", "compose", "--env-file", str(ENV_PATH),
        "-f", str(COMPOSE_PATH), *arguments,
    ]


def prepare() -> None:
    _run(
        [
            sys.executable,
            str(REPOSITORY_ROOT / "scripts/dev/prepare_iam002_local.py"),
            "--repository-root", str(REPOSITORY_ROOT),
            "--output-root", str(STATE_ROOT),
        ]
    )
    _run(_compose_command("config", "--quiet"))


def _wait_for_tcp(host: str, port: int, description: str, timeout: float = 120.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return
        except OSError:
            time.sleep(1)
    raise RuntimeError(f"timed out waiting for {description}")


def _request(url: str, *, host: str | None = None, timeout: float = 3.0) -> int:
    headers = {"Host": host} if host else {}
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def _wait_for_http(url: str, description: str, *, host: str | None = None,
                   timeout: float = 180.0,
                   process: subprocess.Popen[str] | None = None) -> None:
    deadline = time.monotonic() + timeout
    last_status: int | None = None
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"{description} exited before becoming ready")
        try:
            last_status = _request(url, host=host)
            if 200 <= last_status < 400:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    suffix = f" (last HTTP status {last_status})" if last_status is not None else ""
    raise RuntimeError(f"timed out waiting for {description}{suffix}")


def _find_java_home() -> Path:
    candidates = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        candidates.append(Path(configured))
    candidates.extend(
        [
            Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"),
            Path("/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"),
        ]
    )
    java_home_tool = shutil.which("/usr/libexec/java_home")
    if java_home_tool:
        completed = subprocess.run(
            [java_home_tool, "-v", "25"], text=True, capture_output=True, check=False
        )
        if completed.returncode == 0 and completed.stdout.strip():
            candidates.append(Path(completed.stdout.strip()))
    for candidate in candidates:
        java = candidate / "bin/java"
        if not java.is_file():
            continue
        completed = subprocess.run(
            [str(java), "-version"], text=True, capture_output=True, check=False
        )
        if completed.returncode == 0 and re.search(r'version "25(?:\.|\")', completed.stderr):
            return candidate.resolve()
    raise RuntimeError("JDK 25 is required for the IAM-002 backend")


def _find_node_home() -> Path:
    try:
        version = (FRONTEND_ROOT / ".node-version").read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise RuntimeError("cannot read the frontend Node.js baseline") from error
    if re.fullmatch(r"24\.\d+\.\d+", version) is None:
        raise RuntimeError("the frontend Node.js baseline must be an exact Node 24 version")
    candidates = [
        Path.home() / f".nvm/versions/node/v{version}",
        Path(f"/opt/homebrew/Cellar/node@24/{version}"),
        Path("/opt/homebrew/opt/node@24"),
    ]
    for candidate in candidates:
        node = candidate / "bin/node"
        if not node.is_file():
            continue
        completed = subprocess.run(
            [str(node), "--version"], text=True, capture_output=True, check=False
        )
        if completed.returncode == 0 and completed.stdout.strip() == f"v{version}":
            return candidate.resolve()
    raise RuntimeError(f"Node.js {version} is required for the IAM-002 frontends")


def _ensure_mailpit() -> Path:
    architecture = os.uname().machine
    if architecture not in MAILPIT_ARCHIVES:
        raise RuntimeError(f"no pinned Mailpit local artifact for architecture: {architecture}")
    archive_name, expected_digest = MAILPIT_ARCHIVES[architecture]
    binary = STATE_ROOT / "bin/mailpit"
    digest_file = STATE_ROOT / "bin/mailpit.sha256"
    if binary.is_file() and digest_file.is_file():
        actual = hashlib.sha256(binary.read_bytes()).hexdigest()
        if digest_file.read_text(encoding="ascii").strip() == actual:
            return binary
    download_url = (
        f"https://github.com/axllent/mailpit/releases/download/v{MAILPIT_VERSION}/{archive_name}"
    )
    bin_root = binary.parent
    bin_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(bin_root, 0o700)
    with tempfile.TemporaryDirectory(prefix="mailpit-", dir=STATE_ROOT) as directory:
        archive = Path(directory) / archive_name
        with urllib.request.urlopen(download_url, timeout=120) as response, archive.open("wb") as stream:
            shutil.copyfileobj(response, stream)
        actual_archive_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual_archive_digest != expected_digest:
            raise RuntimeError("Mailpit release archive digest does not match the pinned digest")
        with tarfile.open(archive, mode="r:gz") as bundle:
            member = bundle.getmember("mailpit")
            if not member.isfile() or Path(member.name).name != member.name:
                raise RuntimeError("Mailpit release archive has an unexpected layout")
            source = bundle.extractfile(member)
            if source is None:
                raise RuntimeError("Mailpit binary is missing from the release archive")
            descriptor, temporary = tempfile.mkstemp(prefix=".mailpit.", dir=bin_root)
            try:
                with os.fdopen(descriptor, "wb") as stream:
                    shutil.copyfileobj(source, stream)
                os.chmod(temporary, 0o700)
                binary_digest = hashlib.sha256(Path(temporary).read_bytes()).hexdigest()
                os.replace(temporary, binary)
            except BaseException:
                try:
                    os.close(descriptor)
                except OSError:
                    pass
                Path(temporary).unlink(missing_ok=True)
                raise
    digest_file.write_text(binary_digest + "\n", encoding="ascii")
    os.chmod(digest_file, 0o600)
    return binary


def _build_backend(java_home: Path) -> None:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java_home)
    environment["PATH"] = f"{java_home / 'bin'}:{environment.get('PATH', '')}"
    _run(["./mvnw", "-DskipTests", "package"], cwd=BACKEND_ROOT, environment=environment)


def _jar_path(artifact: str) -> Path:
    matches = sorted((BACKEND_ROOT / f"applications/{artifact}/target").glob(f"{artifact}-*.jar"))
    matches = [path for path in matches if not path.name.endswith(".jar.original")]
    if len(matches) != 1:
        raise RuntimeError(f"expected exactly one executable jar for {artifact}")
    return matches[0].resolve()


def _start_process(name: str, command: Sequence[str], cwd: Path,
                   environment: Mapping[str, str], marker: str,
                   records: list[dict[str, object]],
                   authentication_mode: str | None = None) -> subprocess.Popen[str]:
    RUN_ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(RUN_ROOT, 0o700)
    log_path = RUN_ROOT / f"{name}.log"
    log_stream = log_path.open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            list(command),
            cwd=cwd,
            env=dict(environment),
            stdin=subprocess.DEVNULL,
            stdout=log_stream,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
    finally:
        log_stream.close()
    record: dict[str, object] = {
        "name": name,
        "pid": process.pid,
        "pgid": os.getpgid(process.pid),
        "cwd": str(cwd.resolve()),
        "commandMarker": marker,
        "log": str(log_path.resolve()),
    }
    if authentication_mode is not None:
        record["authenticationMode"] = authentication_mode
    records.append(record)
    _atomic_private_json(PROCESS_PATH, {"schemaVersion": 1, "processes": records})
    return process


def _observe_process(pid: int) -> dict[str, object] | None:
    completed = subprocess.run(
        ["ps", "-o", "pid=,pgid=,command=", "-p", str(pid)],
        text=True, capture_output=True, check=False,
    )
    line = completed.stdout.strip()
    if completed.returncode != 0 or not line:
        return None
    match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)", line)
    if match is None:
        return None
    cwd_completed = subprocess.run(
        ["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
        text=True, capture_output=True, check=False,
    )
    cwd = ""
    for cwd_line in cwd_completed.stdout.splitlines():
        if cwd_line.startswith("n"):
            cwd = cwd_line[1:]
    if not cwd:
        return None
    return {
        "pid": int(match.group(1)),
        "pgid": int(match.group(2)),
        "command": match.group(3),
        "cwd": cwd,
    }


def _stop_record(record: Mapping[str, object], timeout: float = 15.0) -> bool:
    observed = _observe_process(int(record["pid"]))
    if observed is None:
        return False
    if not process_matches(record, observed):
        raise RuntimeError(f"refusing to stop process with mismatched identity: {record.get('name')}")
    pgid = int(record["pgid"])
    os.killpg(pgid, signal.SIGTERM)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if _observe_process(int(record["pid"])) is None:
            return True
        time.sleep(0.25)
    observed = _observe_process(int(record["pid"]))
    if observed is not None and process_matches(record, observed):
        os.killpg(pgid, signal.SIGKILL)
        return True
    raise RuntimeError(f"process identity changed while stopping: {record.get('name')}")


def _read_records() -> list[dict[str, object]]:
    if not PROCESS_PATH.exists():
        return []
    try:
        content = json.loads(PROCESS_PATH.read_text(encoding="utf-8"))
        processes = content["processes"]
        if not isinstance(processes, list) or not all(isinstance(item, dict) for item in processes):
            raise ValueError
        return processes
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, ValueError) as error:
        raise RuntimeError("IAM-002 process ownership file is malformed") from error


def current_authentication_mode() -> str | None:
    modes = {
        str(record["authenticationMode"])
        for record in _read_records()
        if record.get("authenticationMode") is not None
    }
    if not modes:
        return None
    if len(modes) != 1:
        raise RuntimeError("IAM-002 runtime contains mixed authentication modes")
    authentication_mode = modes.pop()
    backend_profile(authentication_mode)
    return authentication_mode


def _stop_owned_processes() -> None:
    records = _read_records()
    errors = []
    for record in reversed(records):
        try:
            _stop_record(record)
        except (OSError, RuntimeError) as error:
            errors.append(str(error))
    if not errors:
        PROCESS_PATH.unlink(missing_ok=True)
    if errors:
        raise RuntimeError("; ".join(errors))


def _psql(sql: str, *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    command = _compose_command(
        "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "payment_dev", "-d", "payment_platform", "-At", "-F", "|",
    )
    return _run(command, input_text=sql, capture=capture)


def postgres_password_statement(password: str) -> str:
    if not password or any(character in password for character in ("\x00", "\n", "\r")):
        raise RuntimeError("invalid PostgreSQL password in private environment")
    escaped = password.replace("'", "''")
    return (
        "SET statement_timeout='10s'; SET lock_timeout='3s'; "
        f"ALTER ROLE payment_dev WITH PASSWORD '{escaped}';\n"
    )


def _synchronize_postgres_password(private: Mapping[str, str]) -> None:
    _psql(postgres_password_statement(private["PAYMENT_IAM002_POSTGRES_PASSWORD"]))


def local_merchant_v32_upgrade_statement() -> str:
    return """SET statement_timeout='30s';
SET lock_timeout='3s';
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('iam002-local-merchant-v32-upgrade', 0));
DO $iam002_merchant_v32$
DECLARE
    linked_count INTEGER;
    changed INTEGER;
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NULL
       OR to_regclass('public.iam_role_menu') IS NULL THEN
        RETURN;
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
         WHERE version = '32' AND success
    ) THEN
        RETURN;
    END IF;
    IF EXISTS (
        SELECT 1 FROM flyway_schema_history
         WHERE success AND version ~ '^[0-9]+$' AND version::integer > 31
    ) THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade found an unexpected migration version';
    END IF;
    IF (SELECT count(*)
          FROM iam_tenant tenant
          JOIN iam_role role_row ON role_row.tenant_id = tenant.id
         WHERE tenant.id = 2
           AND tenant.tenant_code = 'local-merchant'
           AND tenant.tenant_type = 'DIRECT_MERCHANT'
           AND tenant.account_domain = 'MERCHANT'
           AND tenant.status = 'ACTIVE'
           AND role_row.id = 2200
           AND role_row.role_code = 'merchant-admin'
           AND role_row.system_role
           AND NOT role_row.assignable
           AND role_row.status = 'ACTIVE'
           AND role_row.deleted_at IS NULL) <> 1 THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade identity was not exact';
    END IF;
    IF (SELECT count(*)
          FROM iam_menu menu
         WHERE menu.tenant_id = 2
           AND menu.system_managed
           AND menu.status = 'ACTIVE'
           AND menu.deleted_at IS NULL
           AND ((menu.id = 6200
                 AND menu.parent_id IS NULL
                 AND menu.route_name = 'MerchantDashboard'
                 AND menu.route_path = '/dashboard'
                 AND menu.component_path IS NULL
                 AND menu.redirect_path = '/dashboard/workspace'
                 AND menu.sort_order = 10
                 AND menu.auth_code IS NULL
                 AND menu.menu_type = 'DIRECTORY')
                OR (menu.id = 6201
                    AND menu.parent_id = 6200
                    AND menu.route_name = 'MerchantWorkspace'
                    AND menu.route_path = '/dashboard/workspace'
                    AND menu.component_path = '/dashboard/workspace/index'
                    AND menu.redirect_path IS NULL
                    AND menu.sort_order = 20
                    AND menu.auth_code IS NULL
                    AND menu.menu_type = 'PAGE'))) <> 2 THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade menus were not exact';
    END IF;
    SELECT count(*) INTO linked_count
      FROM iam_role_menu role_menu
      JOIN iam_menu menu
        ON menu.tenant_id = role_menu.tenant_id AND menu.id = role_menu.menu_id
     WHERE role_menu.tenant_id = 2
       AND role_menu.role_id = 2200
       AND menu.route_name LIKE 'Merchant%';
    IF linked_count NOT IN (0, 2) THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade role-menu state was not exact';
    END IF;
    IF linked_count = 0 THEN
        RETURN;
    END IF;
    IF (SELECT count(*) FROM iam_role_menu
         WHERE tenant_id = 2 AND role_id = 2200 AND menu_id IN (6200, 6201)) <> 2 THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade links were not exact';
    END IF;
    DELETE FROM iam_role_menu
     WHERE tenant_id = 2 AND role_id = 2200 AND menu_id IN (6200, 6201);
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed <> 2 THEN
        RAISE EXCEPTION 'IAM-002 local Merchant upgrade did not remove exactly two links';
    END IF;
END
$iam002_merchant_v32$;
COMMIT;
"""


def _prepare_local_merchant_v32_upgrade() -> None:
    try:
        _psql(local_merchant_v32_upgrade_statement(), capture=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError("IAM-002 local Merchant V32 upgrade preparation failed") from error


def _fixture_state() -> str:
    table_probe = _psql(
        "SET statement_timeout='10s'; SET lock_timeout='3s'; "
        "SELECT count(*) FROM information_schema.tables "
        "WHERE table_schema='public' AND table_name='iam_user';\n",
        capture=True,
    ).stdout.strip().splitlines()[-1]
    if table_probe == "0":
        return classify_fixture_state({"table_count": 0})
    counts_output = _psql(
        """
SET statement_timeout='10s';
SET lock_timeout='3s';
SELECT
  count(*) FILTER (WHERE (id, account_domain, idp_issuer, idp_subject, idp_provisioning_status) IN (
    (100, 'PLATFORM', 'local', 'admin@platform.localhost', 'LOCAL_ONLY'),
    (200, 'MERCHANT', 'local', 'admin@merchant.localhost', 'LOCAL_ONLY'),
    (300, 'AGENT', 'local', 'admin@agent.localhost', 'LOCAL_ONLY'))),
  count(*) FILTER (WHERE (id, account_domain, idp_issuer, idp_subject, idp_provisioning_status) IN (
    (100, 'PLATFORM', 'local:platform', 'admin@platform.localhost', 'LOCAL_ONLY'),
    (200, 'MERCHANT', 'local:merchant', 'admin@merchant.localhost', 'LOCAL_ONLY'),
    (300, 'AGENT', 'local:agent', 'admin@agent.localhost', 'LOCAL_ONLY'))),
  count(*) FILTER (WHERE (id, account_domain, idp_issuer, idp_subject, idp_provisioning_status) IN (
    (100, 'PLATFORM', 'http://127.0.0.1:18080/realms/PLATFORM', '10000000-0000-4000-8000-000000000100', 'PROVISIONED'),
    (200, 'MERCHANT', 'http://127.0.0.1:18080/realms/MERCHANT', '20000000-0000-4000-8000-000000000200', 'PROVISIONED'),
    (300, 'AGENT', 'http://127.0.0.1:18080/realms/AGENT', '30000000-0000-4000-8000-000000000300', 'PROVISIONED'))),
  (SELECT count(*) FROM iam_tenant_entry_host),
  count(*),
  (SELECT count(*) FROM iam_tenant_entry_host WHERE (account_domain, entry_host) IN (
    ('PLATFORM', 'platform.localhost'),
    ('MERCHANT', 'merchant.localhost'),
    ('AGENT', 'agent.localhost')))
FROM iam_user;
""",
        capture=True,
    ).stdout.strip().splitlines()[-1]
    parts = counts_output.split("|")
    if len(parts) != 6:
        raise RuntimeError("cannot classify IAM-002 database fixture")
    return classify_fixture_state(
        {
            "table_count": 1,
            "local_count": int(parts[0]),
            "local_auth_count": int(parts[1]),
            "oidc_count": int(parts[2]),
            "entry_host_count": int(parts[3]),
            "user_count": int(parts[4]),
            "baseline_entry_host_count": int(parts[5]),
        }
    )


def _wait_for_local_fixture(process: subprocess.Popen[str], timeout: float = 90.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("local fixture bootstrap exited before committing the fixture")
        try:
            if _fixture_state() == "LOCAL":
                return
        except RuntimeError:
            pass
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for the committed local identity fixture")


def _bootstrap_local_fixture(java_home: Path, private: Mapping[str, str]) -> None:
    records: list[dict[str, object]] = []
    environment = os.environ.copy()
    environment.update(backend_environment(private))
    environment.update(
        {
            "JAVA_HOME": str(java_home),
            "PATH": f"{java_home / 'bin'}:{environment.get('PATH', '')}",
            "PAYMENT_DB_URL": "jdbc:postgresql://127.0.0.1:25432/payment_platform",
            "PAYMENT_DB_USERNAME": "payment_dev",
            "PAYMENT_DB_PASSWORD": private["PAYMENT_IAM002_POSTGRES_PASSWORD"],
            "PAYMENT_REDIS_HOST": "127.0.0.1",
            "PAYMENT_REDIS_PORT": "26379",
            "PAYMENT_REDIS_PASSWORD": private["PAYMENT_IAM002_REDIS_PASSWORD"],
            "PAYMENT_BOOTSTRAP_PASSWORD": private["PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD"],
            "PAYMENT_PLATFORM_PORT": "18091",
            "PAYMENT_PLATFORM_ALLOWED_ORIGIN": "http://platform.localhost:15999",
        }
    )
    jar = _jar_path("platform-admin-api")
    process = _start_process(
        "fixture-bootstrap", [str(java_home / "bin/java"), "-jar", str(jar),
                              "--spring.profiles.active=local"],
        BACKEND_ROOT, environment, jar.name, records,
    )
    try:
        _wait_for_http(
            "http://127.0.0.1:18091/api/health", "local fixture bootstrap",
            timeout=120, process=process,
        )
        _wait_for_local_fixture(process)
        if process.poll() is not None:
            raise RuntimeError("local fixture bootstrap exited unexpectedly")
    finally:
        for record in reversed(records):
            _stop_record(record)
        PROCESS_PATH.unlink(missing_ok=True)


def _initialize_database(java_home: Path, private: Mapping[str, str]) -> None:
    state = _fixture_state()
    if state == "EMPTY":
        _bootstrap_local_fixture(java_home, private)
        state = _fixture_state()
    if state == "LOCAL_AUTH":
        _synchronize_local_password_login(private, "oidc")
        state = _fixture_state()
    if state == "LOCAL":
        conversion = CONVERSION_PATH.read_text(encoding="utf-8")
        _psql("SET statement_timeout='30s'; SET lock_timeout='3s';\n" + conversion)
        state = _fixture_state()
    if state != "OIDC":
        raise RuntimeError("IAM-002 local OIDC fixture did not reach the expected state")
    convergence = DELEGATED_GOVERNANCE_CONVERGENCE_PATH.read_text(encoding="utf-8")
    _psql("SET statement_timeout='30s'; SET lock_timeout='3s';\n" + convergence)
    dictionary_sample = DICTIONARY_SAMPLE_PATH.read_text(encoding="utf-8")
    _psql("SET statement_timeout='30s'; SET lock_timeout='3s';\n" + dictionary_sample)


def _start_backends(java_home: Path, private: Mapping[str, str],
                    records: list[dict[str, object]], authentication_mode: str) -> None:
    environment = os.environ.copy()
    environment.update(backend_environment(private))
    environment["JAVA_HOME"] = str(java_home)
    environment["PATH"] = f"{java_home / 'bin'}:{environment.get('PATH', '')}"
    profile = backend_profile(authentication_mode)
    for application in BACKENDS:
        jar = _jar_path(str(application["artifact"]))
        process = _start_process(
            str(application["name"]),
            [str(java_home / "bin/java"), "-jar", str(jar),
             f"--spring.profiles.active={profile}"],
            BACKEND_ROOT, environment, jar.name, records, authentication_mode,
        )
        _wait_for_http(
            f"http://127.0.0.1:{application['port']}/api/health",
            str(application["name"]), host=str(application["host"]), timeout=150,
            process=process,
        )


def _prepare_frontend_tooling(pnpm: str, environment: Mapping[str, str]) -> None:
    _run(
        [pnpm, "--filter", "@vben/vite-config", "run", "stub"],
        cwd=FRONTEND_ROOT,
        environment=environment,
    )


def _start_frontends(records: list[dict[str, object]], private: Mapping[str, str],
                     authentication_mode: str) -> None:
    node_home = _find_node_home()
    environment_base = os.environ.copy()
    environment_base["PATH"] = f"{node_home / 'bin'}:{environment_base.get('PATH', '')}"
    pnpm = shutil.which("pnpm", path=environment_base["PATH"])
    if pnpm is None:
        raise RuntimeError("pnpm is required for the IAM-002 frontends")
    _prepare_frontend_tooling(pnpm, environment_base)
    for application in FRONTENDS:
        environment = frontend_environment(
            environment_base, application, private, authentication_mode
        )
        marker = f"--port {application['port']}"
        process = _start_process(
            str(application["name"]),
            [pnpm, "--filter", str(application["package"]), "exec", "vite",
             "--mode", "development", "--host", "127.0.0.1",
             "--port", str(application["port"]), "--strictPort"],
            FRONTEND_ROOT / "apps" / str(application["directory"]),
            environment, marker, records, authentication_mode,
        )
        _wait_for_http(
            f"http://127.0.0.1:{application['port']}/auth/login",
            str(application["name"]), host=f"{application['host']}:{application['port']}", timeout=120,
            process=process,
        )


def _start_mailpit(records: list[dict[str, object]]) -> None:
    binary = _ensure_mailpit()
    environment = os.environ.copy()
    process = _start_process(
        "mailpit",
        [str(binary), "--disable-version-check", "--database", str(STATE_ROOT / "mailpit.db"),
         "--listen", "127.0.0.1:18025", "--smtp", "0.0.0.0:11025"],
        REPOSITORY_ROOT, environment, str(binary), records,
    )
    _wait_for_http(
        "http://127.0.0.1:18025/api/v1/info", "IAM-002 Mailpit", timeout=60, process=process
    )


def up(authentication_mode: str = "local") -> None:
    backend_profile(authentication_mode)
    prepare()
    existing = _read_records()
    live = [record for record in existing if _observe_process(int(record["pid"])) is not None]
    if live:
        raise RuntimeError("IAM-002 runtime already has recorded live processes; run status or down")
    PROCESS_PATH.unlink(missing_ok=True)
    records: list[dict[str, object]] = []
    try:
        _start_mailpit(records)
        _run(_compose_command("up", "-d"))
        _wait_for_tcp("127.0.0.1", 25432, "IAM-002 PostgreSQL")
        _wait_for_tcp("127.0.0.1", 26379, "IAM-002 Valkey")
        _wait_for_http(
            "http://127.0.0.1:18080/realms/PLATFORM/.well-known/openid-configuration",
            "IAM-002 Keycloak", timeout=240,
        )
        java_home = _find_java_home()
        private = _read_private_environment()
        _synchronize_postgres_password(private)
        _build_backend(java_home)
        _prepare_local_merchant_v32_upgrade()
        _initialize_database(java_home, private)
        _synchronize_local_password_login(private, authentication_mode)
        _start_backends(java_home, private, records, authentication_mode)
        _start_frontends(records, private, authentication_mode)
    except BaseException:
        for record in reversed(records):
            try:
                _stop_record(record)
            except (OSError, RuntimeError):
                pass
        PROCESS_PATH.unlink(missing_ok=True)
        raise


def down() -> None:
    _stop_owned_processes()
    if ENV_PATH.exists():
        _run(_compose_command("down"))


def status() -> bool:
    records = _read_records()
    all_healthy = bool(records)
    authentication_mode = current_authentication_mode()
    print(f"authentication-mode: {authentication_mode or 'unknown'}")
    for record in records:
        observed = _observe_process(int(record["pid"]))
        healthy = observed is not None and process_matches(record, observed)
        all_healthy = all_healthy and healthy
        print(f"{record.get('name')}: {'running' if healthy else 'not-running'}")
    for application in BACKENDS:
        try:
            healthy = 200 <= _request(
                f"http://127.0.0.1:{application['port']}/api/health",
                host=str(application["host"]), timeout=1,
            ) < 400
        except (OSError, urllib.error.URLError):
            healthy = False
        all_healthy = all_healthy and healthy
        print(f"{application['name']}-health: {'ready' if healthy else 'unavailable'}")
    return all_healthy


def local_test_commands(node_home: Path) -> list[list[str]]:
    return [
        [
            sys.executable,
            "-m",
            "unittest",
            "scripts.tests.test_iam002_keycloak_realms",
            "scripts.tests.test_iam002_local_runtime",
        ],
        [str(node_home / "bin/node"), "--test", str(VERIFICATION_TEST_PATH)],
    ]


def test() -> None:
    node_home = _find_node_home()
    for command in local_test_commands(node_home):
        _run(command)


def verify() -> None:
    if not status():
        raise RuntimeError("IAM-002 local runtime is not ready; run up first")
    node_home = _find_node_home()
    authentication_mode = current_authentication_mode()
    if authentication_mode == "local":
        verification = LOCAL_VERIFICATION_PATH
    elif authentication_mode == "oidc":
        verification = VERIFICATION_PATH
    else:
        raise RuntimeError("IAM-002 runtime authentication mode is unavailable")
    _run([str(node_home / "bin/node"), str(verification)])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("prepare", "up", "down", "status", "test", "verify", "login-info"),
    )
    parser.add_argument(
        "--auth-mode", choices=("local", "oidc"), default="local",
        help="authentication mode used by `up` (default: local)",
    )
    arguments = parser.parse_args()
    if arguments.command == "prepare":
        prepare()
        print("IAM-002 local artifacts are ready")
        return 0
    if arguments.command == "up":
        up(arguments.auth_mode)
        print(f"IAM-002 local runtime is ready ({arguments.auth_mode} authentication)")
        return 0
    if arguments.command == "down":
        down()
        print("IAM-002 local runtime is stopped; persistent volumes were preserved")
        return 0
    if arguments.command == "status":
        return 0 if status() else 1
    if arguments.command == "test":
        test()
        print("IAM-002 local runtime unit tests passed")
        return 0
    if arguments.command == "login-info":
        login_info()
        return 0
    verify()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"IAM-002 local runtime failed: {error}", file=sys.stderr)
        raise SystemExit(1)
