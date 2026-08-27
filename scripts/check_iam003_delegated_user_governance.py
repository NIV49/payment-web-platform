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
    "docs/adr/0010-centralize-tenant-administrator-provisioning-with-delegated-user-governance.md"
)
RULE_PATH = Path(".agents/payment-modernization/rules/IAM-003.json")
CHECK_ID = "IAM-003-DECISION-CONTRACT"
DECISION_ID = "IAM-DELEGATED-USER-GOVERNANCE"
JUDGE_TESTS = [
    CHECK_ID,
    "IAM-003-BACKEND-VERIFY",
    "IAM-003-FRONTEND-VERIFY",
]

REQUIRED_ADR_DECISIONS = {
    "IAM-003-D1": "All three applications expose navigation named **User Management** and **Role Management**.",
    "IAM-003-D2": "A PLATFORM system administrator may read a cross-domain user directory covering PLATFORM, MERCHANT, and AGENT.",
    "IAM-003-D3": "PLATFORM cross-domain writes are limited to creating and maintaining protected system-administrator Memberships for an existing MERCHANT or AGENT Tenant, plus resetting an ACTIVE local/test password for a precisely bound MERCHANT or AGENT User.",
    "IAM-003-D4": "Ordinary User and Role administration is same-tenant only.",
    "IAM-003-D5": "Target affiliation is represented by `Membership(tenantId, userId)` and the Tenant's account domain.",
    "IAM-003-D6": "A Platform operator who provisions a target administrator does not receive a Membership, Role, session, or permission in the target Tenant.",
    "IAM-003-D7": "A new login identifier is a normalized email address:",
    "IAM-003-D8": "Email is a mutable login/profile attribute, not an identity key.",
}

REQUIRED_RESULTS = {
    "IAM-003-R1": "Only PLATFORM system administrators may read the cross-domain directory, and its account-domain filter defaults to PLATFORM.",
    "IAM-003-R2": "PLATFORM cross-domain writes may affect only protected system-administrator Memberships in an existing MERCHANT or AGENT Tenant, except that a protected PLATFORM system administrator may reset an ACTIVE local/test password for a precisely bound MERCHANT or AGENT User without changing lifecycle status or roles.",
    "IAM-003-R3": "Ordinary user and role administration derives tenant and account domain from the trusted Session and rejects client tenant selectors.",
    "IAM-003-R4": "Tenant affiliation is stored as Membership and is not duplicated as merchantId, agentId, tenantId, portal, or realm fields on User.",
    "IAM-003-R5": "A PLATFORM provisioning actor never receives or impersonates a target-tenant Membership, and cross-domain audit records do not forge a target-tenant operator.",
    "IAM-003-R6": "New login identifiers are normalized email addresses, while identity mapping remains the exact issuer and subject pair and the same email may represent independent Users in different account domains.",
}

REQUIRED_COUNTEREXAMPLES = {
    "IAM-003-R1": "a merchant or agent request selects another tenant with tenantId",
    "IAM-003-R2": "a platform operator assigns arbitrary ordinary roles to a target tenant user through the cross-domain endpoint",
    "IAM-003-R3": "merchantId or agentId is added to User as the authorization source",
    "IAM-003-R4": "a platform actor is stored as though it held a Membership in the target tenant",
    "IAM-003-R5": "email is used instead of issuer and subject to merge or map identities",
    "IAM-003-R6": "a new non-email username is accepted",
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
    current = repository
    for component in relative_path.parts:
        current = current / component
        if current.is_symlink():
            raise ContractError(
                f"contract path must not contain a symbolic link: {relative_path}"
            )
    if not current.is_file():
        raise ContractError(f"contract file is missing: {relative_path}")
    return current.read_text(encoding="utf-8")


def _parse_rule(repository: Path) -> Mapping[str, Any]:
    try:
        payload = json.loads(
            _read_regular_file(repository, RULE_PATH),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (json.JSONDecodeError, UnicodeError, OSError, ContractError) as error:
        raise ContractError(f"IAM-003 Rule Card cannot be parsed: {error}") from error
    if not isinstance(payload, Mapping):
        raise ContractError("IAM-003 Rule Card must be a JSON object")
    return payload


def _validate_adr(repository: Path) -> list[str]:
    try:
        content = _read_regular_file(repository, ADR_PATH)
    except (OSError, UnicodeError, ContractError) as error:
        return [str(error)]

    errors: list[str] = []
    if len(re.findall(r"^Status: accepted\.$", content, re.MULTILINE)) != 1:
        errors.append("ADR-0010 must declare exactly one accepted status")
    if len(
        re.findall(
            rf"^Decision-ID: {re.escape(DECISION_ID)}$", content, re.MULTILINE
        )
    ) != 1:
        errors.append(f"ADR-0010 must declare Decision-ID {DECISION_ID}")
    for decision_id, statement in REQUIRED_ADR_DECISIONS.items():
        if content.count(statement) != 1:
            errors.append(f"ADR-0010 {decision_id} must contain its exact decision once")
    return errors


def _validate_rule(repository: Path) -> list[str]:
    try:
        payload = _parse_rule(repository)
    except ContractError as error:
        return [str(error)]

    errors: list[str] = []
    if set(payload) != RULE_FIELDS:
        errors.append("IAM-003 Rule Card must use the exact normative field set")
    if payload.get("ruleId") != "IAM-003":
        errors.append("IAM-003 Rule Card ruleId must be IAM-003")
    if payload.get("status") not in {"candidate", "approved"}:
        errors.append("IAM-003 Rule Card status must be candidate or approved")

    then = payload.get("then")
    if not isinstance(then, list):
        errors.append("IAM-003 Rule Card then must be a list")
        then = []
    counterexamples = payload.get("counterexamples")
    if not isinstance(counterexamples, list):
        errors.append("IAM-003 Rule Card counterexamples must be a list")
        counterexamples = []

    for result_id, statement in REQUIRED_RESULTS.items():
        if then.count(statement) != 1:
            errors.append(
                f"IAM-003 Rule Card {result_id} must contain its exact result once"
            )
    for result_id, counterexample in REQUIRED_COUNTEREXAMPLES.items():
        if counterexamples.count(counterexample) != 1:
            errors.append(
                f"IAM-003 Rule Card {result_id} must contain its exact counterexample once"
            )
    if then != list(REQUIRED_RESULTS.values()):
        errors.append("IAM-003 Rule Card then must equal the exact ordered result set")
    if counterexamples != list(REQUIRED_COUNTEREXAMPLES.values()):
        errors.append(
            "IAM-003 Rule Card counterexamples must equal the exact ordered counterexample set"
        )
    if payload.get("judgeTests") != JUDGE_TESTS:
        errors.append("IAM-003 Rule Card must bind the exact ordered Judge set")
    return errors


def validate_contract(repository: Path) -> list[str]:
    return [*_validate_adr(repository), *_validate_rule(repository)]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate the IAM-003 delegated user governance decision contract."
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
        print(f"{CHECK_ID} failed with {len(errors)} problem(s).", file=sys.stderr)
        return 1
    print(f"{CHECK_ID} passed: delegated user governance is explicit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
