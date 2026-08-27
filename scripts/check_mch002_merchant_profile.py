#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


ADR_PATH = Path("docs/adr/0014-platform-maintained-merchant-profile-and-operating-markets.md")
CONTRACT_PATH = Path("docs/ai-contract/merchant-lifecycle-api-contract.md")
PRODUCT_PATH = Path("docs/product/merchant-management.md")
CONTEXT_PATH = Path("docs/ai-context/merchant/README.md")
STATUS_PATH = Path("docs/ai-context/current-status.md")
BACKEND_CONTEXT_PATH = Path("docs/ai-context/backend/README.md")
FRONTEND_CONTEXT_PATH = Path("docs/ai-context/frontend/README.md")
IDENTITY_CONTRACT_PATH = Path("docs/ai-contract/identity-admin-api-contract.md")
DICTIONARY_CONTRACT_PATH = Path("docs/ai-contract/system-dictionary-api-contract.md")
WORKFLOW_PATH = Path("docs/ai-context/development-workflow.md")
DOC_SYNC_PATH = Path("scripts/check_doc_code_sync.py")
RULE_PATH = Path(".agents/payment-modernization/rules/MCH-002.json")
POLICY_PATH = Path(".agents/payment-modernization-policy.json")
REGISTRY_PATH = Path(".agents/payment-modernization-judge-registry.json")
CHECKER_PATH = Path("scripts/check_mch002_merchant_profile.py")
TEST_PATH = Path("scripts/tests/test_mch002_merchant_profile.py")
V34_BRIDGE_CALLBACK_PATH = Path(
    "backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
    "beforeEachMigrate__prepare_v34_status_reason_backfill.sql"
)
V34_BRIDGE_CALLBACK_SHA256 = "54bf11e04a6e76efbda570a4cb52dce82104244d21d242bd0359a5bd116eae07"
V34_AUDIT_PREFLIGHT_CALLBACK_PATH = Path(
    "backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
    "beforeEachMigrate__reject_ambiguous_v34_status_reason_evidence.sql"
)
V34_AUDIT_PREFLIGHT_CALLBACK_SHA256 = "529200d8175b7dd4c88be8a44292c61786a2a5a888dfdb81cbe105f4abf8c5dc"
V36_AUDIT_UNIQUENESS_PATH = Path(
    "backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
    "V36__enforce_unique_merchant_audit_versions.sql"
)
V36_AUDIT_UNIQUENESS_SHA256 = "35cb295498ee063870b6dbbb038f85389c8c13980b9adb215f6338efed38b6e0"

CONTRACT_PATHS = (
    ADR_PATH, CONTRACT_PATH, PRODUCT_PATH, CONTEXT_PATH, STATUS_PATH,
    BACKEND_CONTEXT_PATH, FRONTEND_CONTEXT_PATH, IDENTITY_CONTRACT_PATH,
    DICTIONARY_CONTRACT_PATH, WORKFLOW_PATH, DOC_SYNC_PATH, RULE_PATH,
    POLICY_PATH, REGISTRY_PATH,
    V34_BRIDGE_CALLBACK_PATH,
    V34_AUDIT_PREFLIGHT_CALLBACK_PATH,
    V36_AUDIT_UNIQUENESS_PATH,
)

CHECK_ID = "MCH-002-DECISION-CONTRACT"
ENDPOINT = "PUT /api/platform/merchants/{merchantId}/profile"
PERMISSION = "merchant:update"
REQUIRED_LITERALS = (
    "MCH-002",
    ENDPOINT,
    PERMISSION,
    "expectedVersion",
    "Idempotency-Key",
    "legalName",
    "displayName",
    "merchantTypeCode",
    "legalPersonName",
    "authenticationType",
    "DIRECT",
    "INDIRECT",
    "COMMISSION",
    "SALES",
    "PLATFORM",
    "ENTERPRISE",
    "NON_PROFIT_ORGANIZATIONS",
    "CLIQUE",
    "INDIVIDUAL",
    "INDIVIDUAL_HOUSEHOLD",
    "remarks",
    "marketCodes",
    "ACTIVE",
    "DISABLED",
    "no-op",
    "rowVersion",
    "statusReasonCode",
    "PLATFORM_PROFILE_UPDATED",
    "ISO 3166-1 alpha-2",
    "ISO 3166-1 alpha-3",
    "BRA",
    "PHL",
    "recent step-up",
    "V35",
    "V36",
    "command schema 1",
    "replay-only",
)
ADR_MARKERS = {
    "<!-- MCH-002-D1 -->": ("registrationCountry", "ISO 3166-1 alpha-2", "not a market"),
    "<!-- MCH-002-D2 -->": ("marketCodes", "ISO 3166-1 alpha-3", "BRA", "PHL", "multiple"),
    "<!-- MCH-002-D3 -->": (PERMISSION, "protected PLATFORM system administrator", "recent step-up"),
    "<!-- MCH-002-D4 -->": (ENDPOINT, "expectedVersion", "Idempotency-Key"),
    "<!-- MCH-002-D5 -->": ("ACTIVE", "DISABLED", "no-op", "rowVersion"),
    "<!-- MCH-002-D6 -->": ("UPDATE_PROFILE", "PLATFORM_PROFILE_UPDATED", "same PostgreSQL transaction"),
    "<!-- MCH-002-D7 -->": ("Switch", "Tag", "allowClear", "terminate"),
    "<!-- MCH-002-D8 -->": ("merchantTypeCode", "legalPersonName", "authenticationType", "business email", "DIRECT", "INDIRECT", "COMMISSION", "SALES", "PLATFORM", "ENTERPRISE", "NON_PROFIT_ORGANIZATIONS", "CLIQUE", "INDIVIDUAL", "INDIVIDUAL_HOUSEHOLD", "AgentRelation", "MerchantMarket"),
}
RULE_FIELDS = {
    "ruleId", "status", "statement", "scope", "given", "when", "then",
    "counterexamples", "evidence", "confidence", "judgeTests",
}


class ContractError(RuntimeError):
    pass


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read(repository: Path, path: Path) -> str:
    current = repository.resolve()
    for part in path.parts:
        current = current / part
        if current.is_symlink():
            raise ContractError(f"contract path must not contain a symbolic link: {path}")
    if not current.is_file():
        raise ContractError(f"contract file is missing: {path}")
    return current.read_text(encoding="utf-8")


def _json(repository: Path, path: Path) -> Mapping[str, Any]:
    payload = json.loads(_read(repository, path), object_pairs_hook=_reject_duplicates)
    if not isinstance(payload, Mapping):
        raise ContractError(f"JSON contract must be an object: {path}")
    return payload


def _validate_adr(repository: Path) -> list[str]:
    try:
        content = _read(repository, ADR_PATH)
    except (ContractError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    if content.count("Status: accepted.") != 1:
        errors.append("ADR-0014 must declare exactly one accepted status")
    if content.count("Decision-ID: MCH-MERCHANT-PROFILE-MARKETS") != 1:
        errors.append("ADR-0014 must declare the MCH-002 decision ID")
    positions: list[int] = []
    for marker, literals in ADR_MARKERS.items():
        if content.count(marker) != 1:
            errors.append(f"ADR-0014 marker must appear exactly once: {marker}")
            continue
        start = content.index(marker)
        positions.append(start)
        later = [content.find(other, start + len(marker)) for other in ADR_MARKERS]
        end = min((position for position in later if position >= 0), default=len(content))
        section = content[start:end]
        for literal in literals:
            if literal not in section:
                errors.append(f"ADR-0014 {marker} must contain {literal}")
    if positions != sorted(positions):
        errors.append("ADR-0014 markers must remain in D1-D8 order")
    return errors


def _validate_contract(repository: Path) -> list[str]:
    try:
        content = _read(repository, CONTRACT_PATH)
    except (ContractError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    for literal in REQUIRED_LITERALS:
        if literal not in content:
            errors.append(f"Merchant contract must contain MCH-002 literal: {literal}")
    if content.count(f"`{ENDPOINT}`") != 1:
        errors.append("Merchant contract must contain the exact MCH-002 endpoint once")
    if content.count(f"`{PERMISSION}`") < 3:
        errors.append("Merchant contract must bind the MCH-002 permission in authorization, catalog and compatibility sections")
    return errors


def _validate_rule(repository: Path) -> list[str]:
    try:
        payload = _json(repository, RULE_PATH)
    except (ContractError, json.JSONDecodeError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    if set(payload) != RULE_FIELDS:
        errors.append("MCH-002 Rule Card must use the exact normative field set")
    if payload.get("ruleId") != "MCH-002" or payload.get("status") != "candidate":
        errors.append("MCH-002 Rule Card must remain candidate")
    if payload.get("confidence") != "high" or payload.get("evidence") != []:
        errors.append("MCH-002 candidate evidence must remain empty with high confidence")
    if payload.get("judgeTests") != [CHECK_ID, "MCH-002-BACKEND-VERIFY", "MCH-002-FRONTEND-VERIFY"]:
        errors.append("MCH-002 Rule Card must bind the exact three Judge checks")
    then = payload.get("then")
    if not isinstance(then, list) or not all(any(literal in str(item) for item in then) for literal in REQUIRED_LITERALS):
        errors.append("MCH-002 Rule Card must contain every normative contract literal")
    return errors


def _validate_governance(repository: Path) -> list[str]:
    errors: list[str] = []
    try:
        policy = _json(repository, POLICY_PATH)
        registry = _json(repository, REGISTRY_PATH)
    except (ContractError, json.JSONDecodeError, OSError, UnicodeError) as error:
        return [str(error)]
    required_rulebooks = {str(ADR_PATH), str(CONTRACT_PATH), str(RULE_PATH)}
    required_judges = {str(CHECKER_PATH), str(TEST_PATH), "backend/pom.xml", "frontend/admin/package.json"}
    if not required_rulebooks.issubset(set(policy.get("rulebookPaths", []))):
        errors.append("policy is missing MCH-002 rulebook paths")
    if str(RULE_PATH) not in set(policy.get("ruleCardPaths", [])):
        errors.append("policy is missing the MCH-002 Rule Card")
    if not required_judges.issubset(set(policy.get("judgePaths", []))):
        errors.append("policy is missing MCH-002 Judge paths")
    checks = registry.get("checks", [])
    checks_by_id = {item.get("checkId"): item for item in checks if isinstance(item, Mapping)}
    expected = {
        CHECK_ID: {"checkId": CHECK_ID, "path": str(CHECKER_PATH), "command": "python3 -I scripts/check_mch002_merchant_profile.py --repository-root .", "ruleIds": ["MCH-002"]},
        "MCH-002-BACKEND-VERIFY": {"checkId": "MCH-002-BACKEND-VERIFY", "path": "backend/pom.xml", "command": "backend/mvnw -f backend/pom.xml clean verify", "ruleIds": ["MCH-002"]},
        "MCH-002-FRONTEND-VERIFY": {"checkId": "MCH-002-FRONTEND-VERIFY", "path": "frontend/admin/package.json", "command": "pnpm --dir frontend/admin test:unit && pnpm --dir frontend/admin check:type && pnpm --dir frontend/admin test:production-safety && pnpm --dir frontend/admin build:all", "ruleIds": ["MCH-002"]},
    }
    for check_id, value in expected.items():
        if checks_by_id.get(check_id) != value:
            errors.append(f"Judge registry must contain exact MCH-002 check: {check_id}")
    return errors


def _validate_supporting_docs(repository: Path) -> list[str]:
    requirements = {
        PRODUCT_PATH: ("MCH-002", "商户号", "商户名称", "商户类型", "法人名称", "认证类型", "主体名称", "市场", "商户状态", "状态备注", "商户备注", "创建时间", "更新时间", "allowClear"),
        CONTEXT_PATH: ("MCH-002", str(ADR_PATH), ENDPOINT, "V34", "V35", "V36"),
        STATUS_PATH: ("MCH-002", "V36", "Candidate implementation", "Production NO-GO"),
        BACKEND_CONTEXT_PATH: ("MCH-002", ENDPOINT, "V34", "V35", "V36"),
        FRONTEND_CONTEXT_PATH: ("MCH-002", "Switch", "Tag", "allowClear"),
        IDENTITY_CONTRACT_PATH: ("MCH-002", PERMISSION, "recent step-up"),
        DICTIONARY_CONTRACT_PATH: ("DICT-016", "MERCHANT_TYPE_CODE", "MERCHANT_AUTH_TYPE", "V35"),
        WORKFLOW_PATH: ("MCH-002", "ADR-0014"),
        DOC_SYNC_PATH: ('"DOC-SYNC-MERCHANT-LIFECYCLE"', 'b"merchant:"', '"frontend/admin/apps/platform-admin/src/views/merchant/"'),
    }
    errors: list[str] = []
    for path, literals in requirements.items():
        try:
            content = _read(repository, path)
        except (ContractError, OSError, UnicodeError) as error:
            errors.append(str(error))
            continue
        for literal in literals:
            if literal not in content:
                errors.append(f"{path} must contain MCH-002 literal: {literal}")
    return errors


def _validate_v34_bridge_callback(repository: Path) -> list[str]:
    try:
        content = _read(repository, V34_BRIDGE_CALLBACK_PATH)
    except (ContractError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    if digest != V34_BRIDGE_CALLBACK_SHA256:
        errors.append("V34 bridge callback content must remain frozen at its reviewed SHA-256")
    for literal in (
        "current_version IS DISTINCT FROM '33'",
        "V34 bridge blocked: expected exact V33 Merchant schema",
        "V34 bridge blocked: V33 Merchant lifecycle function is not canonical",
        "to_jsonb(OLD) ? 'status_reason_code'",
        "jsonb_typeof(to_jsonb(NEW)->'status_reason_code') = 'string'",
        "(to_jsonb(OLD) - 'status_reason_code')",
        "(to_jsonb(NEW) - 'status_reason_code')",
    ):
        if content.count(literal) != 1:
            errors.append(f"V34 bridge callback must contain exact frozen guard once: {literal}")
    return errors


def _validate_audit_evidence_migration_resources(repository: Path) -> list[str]:
    resources = (
        (
            V34_AUDIT_PREFLIGHT_CALLBACK_PATH,
            V34_AUDIT_PREFLIGHT_CALLBACK_SHA256,
            "V34 audit preflight callback",
            (
                "current_version IS DISTINCT FROM '33'",
                "expected exact V33 Merchant schema",
                "Merchant audit columns are not canonical",
                "Merchant audit constraints are not canonical",
                "Merchant audit evidence constraints drifted",
                "GROUP BY audit.merchant_id, audit.merchant_version",
                "HAVING count(*) > 1",
                "ambiguous Merchant audit version evidence",
            ),
        ),
        (
            V36_AUDIT_UNIQUENESS_PATH,
            V36_AUDIT_UNIQUENESS_SHA256,
            "V36 Merchant audit uniqueness migration",
            (
                "current_version IS DISTINCT FROM '35'",
                "GROUP BY audit.merchant_id, audit.merchant_version",
                "HAVING count(*) > 1",
                "duplicate Merchant audit version evidence exists",
                "ADD CONSTRAINT uk_merchant_audit_merchant_version",
                "UNIQUE (merchant_id, merchant_version)",
            ),
        ),
    )
    errors: list[str] = []
    for path, expected_digest, label, literals in resources:
        try:
            content = _read(repository, path)
        except (ContractError, OSError, UnicodeError) as error:
            errors.append(str(error))
            continue
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if digest != expected_digest:
            errors.append(f"{label} content must remain frozen at its reviewed SHA-256")
        for literal in literals:
            if content.count(literal) != 1:
                errors.append(f"{label} must contain exact frozen guard once: {literal}")
    return errors


def validate_contract(repository: Path) -> list[str]:
    return [
        *_validate_adr(repository),
        *_validate_contract(repository),
        *_validate_rule(repository),
        *_validate_governance(repository),
        *_validate_supporting_docs(repository),
        *_validate_v34_bridge_callback(repository),
        *_validate_audit_evidence_migration_resources(repository),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate MCH-002 merchant profile and market decisions.")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    errors = validate_contract(args.repository_root)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"{CHECK_ID} failed with {len(errors)} problem(s).", file=sys.stderr)
        return 1
    print(f"{CHECK_ID} passed: merchant profile and operating-market decisions are explicit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
