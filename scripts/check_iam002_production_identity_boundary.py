#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


ADR_PATH = Path(
    "docs/adr/0009-separate-backoffice-applications-and-production-identity-boundaries.md"
)
RULE_PATH = Path(".agents/payment-modernization/rules/IAM-002.json")
CHECK_ID = "IAM-002-DECISION-CONTRACT"
JUDGE_TESTS = [
    CHECK_ID,
    "IAM-002-KEYCLOAK-CONFIG",
    "IAM-002-BACKEND-VERIFY",
]
DECISION_ID = "IAM-PRODUCTION-IDENTITY-BOUNDARY"

REQUIRED_INVARIANTS = {
    "IAM-002-R1": "PLATFORM, MERCHANT, and AGENT realms are logical security partitions inside one shared Keycloak infrastructure; they are not complete infrastructure isolation.",
    "IAM-002-R2": "Every local application session must implement both validated Keycloak back-channel logout and per-request identity-version revocation; either signal invalidates the local session.",
    "IAM-002-R3": "Origin validation applies only to appropriate browser-facing requests; OIDC callbacks and server-to-server back-channel logout do not depend on Origin and must use protocol-specific validation.",
    "IAM-002-R4": "Every state-changing cookie-authenticated browser request must pass an independent CSRF control; SameSite cookies and Origin validation are defense in depth and cannot replace the CSRF control.",
    "IAM-002-R5": "An application User is identified uniquely by the exact canonical issuer and subject pair; email, username, realm label, and account domain are not identity mapping keys.",
    "IAM-002-R6": "MFA recovery must revoke the affected Keycloak credentials, every recovery code, every Keycloak session, and every application session before recovery can complete; partial failure remains fail closed and retries are idempotent.",
}

REQUIRED_COUNTEREXAMPLES = {
    "IAM-002-R1": "documentation or deployment evidence describes three realms in one Keycloak cluster as complete infrastructure isolation",
    "IAM-002-R2": "a local session remains valid after a verified back-channel logout or after its captured identity version becomes stale",
    "IAM-002-R3": "an OIDC callback is rejected only because Origin is absent or is accepted because Origin appears trusted without state, nonce, PKCE, issuer, and audience validation",
    "IAM-002-R4": "a state-changing cookie-authenticated request succeeds with only SameSite or Origin enforcement and no independent CSRF token",
    "IAM-002-R5": "an IdP login maps an application User by email, username, realm label, or account domain instead of the exact issuer and subject pair",
    "IAM-002-R6": "MFA recovery reports success while an affected Keycloak credential, recovery code, Keycloak session, or application session remains usable",
}

RULE_FIELDS = {
    "ruleId",
    "status",
    "statement",
    "scope",
    "given",
    "when",
    "then",
    "counterexamples",
    "evidence",
    "confidence",
    "judgeTests",
}


class ContractError(RuntimeError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read_regular_file(repository: Path, relative_path: Path) -> str:
    repository = repository.resolve()
    target = repository / relative_path
    current = repository
    for component in relative_path.parts:
        current = current / component
        if current.is_symlink():
            raise ContractError(f"contract path must not contain a symbolic link: {relative_path}")
    if not target.is_file():
        raise ContractError(f"contract file is missing: {relative_path}")
    return target.read_text(encoding="utf-8")


def _parse_rule(repository: Path) -> Mapping[str, Any]:
    try:
        payload = json.loads(
            _read_regular_file(repository, RULE_PATH),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (json.JSONDecodeError, UnicodeError, OSError, ContractError) as error:
        raise ContractError(f"IAM-002 Rule Card cannot be parsed: {error}") from error
    if not isinstance(payload, Mapping):
        raise ContractError("IAM-002 Rule Card must be a JSON object")
    return payload


def _validate_adr(repository: Path) -> list[str]:
    try:
        content = _read_regular_file(repository, ADR_PATH)
    except (OSError, UnicodeError, ContractError) as error:
        return [str(error)]

    errors: list[str] = []
    if len(re.findall(r"^Status: accepted\.$", content, re.MULTILINE)) != 1:
        errors.append("ADR-0009 must declare exactly one accepted status")
    if len(
        re.findall(
            rf"^Decision-ID: {re.escape(DECISION_ID)}$", content, re.MULTILINE
        )
    ) != 1:
        errors.append(f"ADR-0009 must declare Decision-ID {DECISION_ID}")

    section_matches = list(
        re.finditer(
            r"^### (?P<id>IAM-002-R[1-6]):[^\n]*\n(?P<body>.*?)(?=^### |^## |\Z)",
            content,
            re.MULTILINE | re.DOTALL,
        )
    )
    sections: dict[str, str] = {}
    for match in section_matches:
        invariant_id = match.group("id")
        if invariant_id in sections:
            errors.append(f"ADR-0009 contains duplicate normative section {invariant_id}")
            continue
        sections[invariant_id] = match.group("body")
    for invariant_id, statement in REQUIRED_INVARIANTS.items():
        body = sections.get(invariant_id)
        if body is None:
            errors.append(f"ADR-0009 is missing normative section {invariant_id}")
            continue
        normative_statements = re.findall(
            r"^Normative statement: (?P<statement>.+)$", body, re.MULTILINE
        )
        if normative_statements != [statement]:
            errors.append(
                f"ADR-0009 {invariant_id} must contain its exact normative statement"
            )
    return errors


def _validate_rule(repository: Path) -> list[str]:
    try:
        payload = _parse_rule(repository)
    except ContractError as error:
        return [str(error)]

    errors: list[str] = []
    if set(payload) != RULE_FIELDS:
        errors.append("IAM-002 Rule Card must use the exact normative field set")
    if payload.get("ruleId") != "IAM-002":
        errors.append("IAM-002 Rule Card ruleId must be IAM-002")
    if payload.get("status") not in {"candidate", "approved"}:
        errors.append("IAM-002 Rule Card status must be candidate or approved")

    then = payload.get("then")
    if not isinstance(then, list):
        errors.append("IAM-002 Rule Card then must be a list")
        then = []
    counterexamples = payload.get("counterexamples")
    if not isinstance(counterexamples, list):
        errors.append("IAM-002 Rule Card counterexamples must be a list")
        counterexamples = []

    for invariant_id, statement in REQUIRED_INVARIANTS.items():
        if then.count(statement) != 1:
            errors.append(
                f"IAM-002 Rule Card {invariant_id} must contain its exact invariant once"
            )
    for invariant_id, counterexample in REQUIRED_COUNTEREXAMPLES.items():
        if counterexamples.count(counterexample) != 1:
            errors.append(
                f"IAM-002 Rule Card {invariant_id} must contain its exact counterexample once"
            )
    if then != list(REQUIRED_INVARIANTS.values()):
        errors.append("IAM-002 Rule Card then must equal the exact ordered invariant set")
    if counterexamples != list(REQUIRED_COUNTEREXAMPLES.values()):
        errors.append(
            "IAM-002 Rule Card counterexamples must equal the exact ordered counterexample set"
        )
    if payload.get("judgeTests") != JUDGE_TESTS:
        errors.append("IAM-002 Rule Card must bind the exact ordered Judge set")
    return errors


def validate_contract(repository: Path) -> list[str]:
    return [*_validate_adr(repository), *_validate_rule(repository)]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate the IAM-002 production identity decision contract."
    )
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
    )
    arguments = parser.parse_args()
    errors = validate_contract(arguments.repository_root)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"{CHECK_ID} failed with {len(errors)} problem(s).",
            file=sys.stderr,
        )
        return 1
    print(f"{CHECK_ID} passed: six normative invariants are present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
