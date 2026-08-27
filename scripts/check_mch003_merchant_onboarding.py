#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


ADR_PATH = Path("docs/adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md")
CONTRACT_PATH = Path("docs/ai-contract/merchant-lifecycle-api-contract.md")
PRODUCT_PATH = Path("docs/product/merchant-management.md")
CONTEXT_PATH = Path("docs/ai-context/merchant/README.md")
STATUS_PATH = Path("docs/ai-context/current-status.md")
LIFECYCLE_PATH = Path("docs/product/lifecycle.md")
DOCS_INDEX_PATH = Path("docs/README.md")
CONTEXT_INDEX_PATH = Path("docs/ai-context/README.md")
BACKEND_CONTEXT_PATH = Path("docs/ai-context/backend/README.md")
FRONTEND_CONTEXT_PATH = Path("docs/ai-context/frontend/README.md")
IDENTITY_CONTRACT_PATH = Path("docs/ai-contract/identity-admin-api-contract.md")
DICTIONARY_CONTRACT_PATH = Path("docs/ai-contract/system-dictionary-api-contract.md")
WORKFLOW_PATH = Path("docs/ai-context/development-workflow.md")
JUDGE_CHARTER_PATH = Path("docs/judge-charter.md")
RULE_PATH = Path(".agents/payment-modernization/rules/MCH-003.json")
POLICY_PATH = Path(".agents/payment-modernization-policy.json")
REGISTRY_PATH = Path(".agents/payment-modernization-judge-registry.json")
CHECKER_PATH = Path("scripts/check_mch003_merchant_onboarding.py")
TEST_PATH = Path("scripts/tests/test_mch003_merchant_onboarding.py")

CLEAN_VERIFY_PASS_LITERAL = (
    "Unified backend `clean verify`: PASS. "
    "95 XML reports / 678 tests / 0 failures / 0 errors."
)
CLEAN_VERIFY_STATUS_PATHS = (
    STATUS_PATH,
    BACKEND_CONTEXT_PATH,
    CONTEXT_PATH,
    CONTRACT_PATH,
    PRODUCT_PATH,
    LIFECYCLE_PATH,
)

IMMUTABLE_MIGRATION_SHA256 = {
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V37__add_platform_onboarding_amendments_and_documents.sql"):
        "3dcb6254b25fd911fb9c81d363ae4162560412db4e6848d6ca0eb17829a3623c",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V38__close_mch003_onboarding_security_boundaries.sql"):
        "07517085d82a555be47fad3c40f8bfea80414eef16d05610000932009a0ff3cc",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V39__allow_guarded_retained_amendment_rotation.sql"):
        "c4d65c4811aae9a7146c9f686fb3000f2801caa651314bf4d5c57f0c49fdd8db",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V40__canonicalize_merchant_button_i18n_titles.sql"):
        "3e46db0f7b10dd5a5715764f429a0538035da8639cb06c9dae2a317018dab0ac",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V41__verify_canonical_merchant_button_i18n_titles.sql"):
        "d493479ef0b4043f80177a30b22f595abf9e7b3c773c41911d424fd24f32a48e",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V42__add_merchant_amendment_document_references.sql"):
        "b3ae42f24ebee2d5667a824b5aa1f1ff5b4a9ab857b3d0015ae1d37528cdc506",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "V43__guard_merchant_amendment_document_references.sql"):
        "c4982658f6f49dfe2eb9061c775cb8b78053bf5d86bdf8143b29d0c237856eb9",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "beforeEachMigrate__lock_v40_merchant_button_i18n.sql"):
        "f8e367674f30cf16ccfd46ccb964fc5143409377aa368beb65c87403736a6b20",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "beforeEachMigrate__prepare_v37_direct_canonicalization.sql"):
        "28bc55d18b5b90363539cafa4c5425d860bdde0eb8a94c58f32957bed4d732aa",
    Path("backend/modules/merchant/persistence-postgres/src/main/resources/db/migration/"
         "beforeEachMigrate__prepare_v37_submit_author_backfill.sql"):
        "c16595e0acceaeffe5860bc31c433a5800203ce5f3fa1db87a7d2e4a1db4e901",
}

CONTRACT_PATHS = (
    ADR_PATH,
    CONTRACT_PATH,
    PRODUCT_PATH,
    CONTEXT_PATH,
    STATUS_PATH,
    LIFECYCLE_PATH,
    DOCS_INDEX_PATH,
    CONTEXT_INDEX_PATH,
    BACKEND_CONTEXT_PATH,
    FRONTEND_CONTEXT_PATH,
    IDENTITY_CONTRACT_PATH,
    DICTIONARY_CONTRACT_PATH,
    WORKFLOW_PATH,
    JUDGE_CHARTER_PATH,
    RULE_PATH,
    POLICY_PATH,
    REGISTRY_PATH,
)

CHECK_ID = "MCH-003-DECISION-CONTRACT"
ENDPOINTS = (
    "GET /api/platform/merchant-onboarding/eligible-tenants",
    "POST /api/platform/merchant-document-uploads",
    "GET /api/platform/merchant-document-uploads/{documentId}/content",
    "DELETE /api/platform/merchant-document-uploads/{documentId}",
    "GET /api/platform/merchants/{merchantId}/documents/{kind}/content",
    "POST /api/platform/merchants",
    "POST /api/platform/merchants/{merchantId}/amendments",
    "GET /api/platform/merchants/{merchantId}/amendments/pending",
    "POST /api/platform/merchants/{merchantId}/amendments/{amendmentId}/review-decisions",
)
PERMISSIONS = (
    "merchant:create",
    "merchant:amend",
    "merchant:document:upload",
    "merchant:document:view",
    "merchant:view",
    "merchant:review",
)
PROFILE_FIELDS = (
    "displayName",
    "brandName",
    "authenticationType",
    "merchantTypeCode",
    "industryCode",
    "brandLogoDocumentId",
    "legalName",
    "registrationCountry",
    "marketCodes",
    "registeredAddress",
    "operatingAddress",
    "businessLicenseDocumentId",
    "legalPersonName",
    "contactEmail",
    "contactPhone",
    "legalIdTypeCode",
    "legalIdNo",
    "legalIdValidity",
    "legalIdFrontDocumentId",
    "legalIdBackDocumentId",
    "legalIdHoldingDocumentId",
    "remarks",
    "registrationNumber",
)
CURRENT_DETAIL_FIELDS = (
    "brandName",
    "industryCode",
    "registeredAddress",
    "operatingAddress",
    "contactEmail",
    "contactPhone",
    "legalIdTypeCode",
    "legalIdValidity",
    "legalIdNoMasked",
    "brandLogoDocument",
    "businessLicenseDocument",
    "legalIdFrontDocument",
    "legalIdBackDocument",
    "legalIdHoldingDocument",
)
DOCUMENT_SECURITY_LITERALS = (
    "Only PNG and JPEG magic bytes",
    "at most 2 MiB",
    "1..4096",
    "12,000,000",
    "ImageIO",
    "AES-256-GCM",
    "96-bit nonce generated by a CSPRNG",
    "fixed 128-bit tag",
    "private `BYTEA` ciphertext",
    "unique `(key_id, nonce)`",
    "expiry exactly 30 minutes",
    "attach it once",
    "Cache-Control: no-store",
    "Content-Security-Policy: sandbox; default-src 'none'",
)
AMENDMENT_DOCUMENT_LITERALS = (
    "exact current same-kind Merchant binding (`RETAIN`)",
    "same-actor, same-target, same-kind temporary upload (`REPLACE`)",
    "Zero, one or multiple replacements are valid",
    "Approval promotes and binds only `REPLACE` references",
    "does not add a tenth MCH-003 path",
    "?amendmentId={amendmentId}",
    "positive Long-string `amendmentId`",
    "pending amendment attachment",
    "same assigned protected",
    "recent step-up",
    "must not fall back to the current effective document",
    "40401 RESOURCE_NOT_FOUND",
    "Binary response bytes and headers are identical",
)
V40_RELEASE_LITERALS = (
    "deploy the dual-key frontend before V40",
    "stop every `iam_menu` writer",
    "V40 callback and V41 postcondition",
    "must not roll back to a frontend that lacks `merchant.permission.*`",
)
V42_RELEASE_LITERALS = (
    "stop every old Merchant create/amend/review writer",
    "quiesced until V43",
    "old binary is not a writable rollback target",
)
ADR_MARKERS = {
    "<!-- MCH-003-D1 -->": ("eligible-tenants", "ACTIVE", "MERCHANT", "does not create"),
    "<!-- MCH-003-D2 -->": ("merchant:create", "PENDING_REVIEW", "application-author Membership"),
    "<!-- MCH-003-D3 -->": PROFILE_FIELDS + ("receiveEmail",),
    "<!-- MCH-003-D4 -->": ("BRA", "PHL", "PLATFORM", "DIRECT", "V37"),
    "<!-- MCH-003-D5 -->": (
        "merchant:amend", "originMerchantVersion", "originStatus", "does not modify",
        "RETAIN", "REPLACE", "Zero or partial replacements",
    ),
    "<!-- MCH-003-D6 -->": ("APPROVED", "REJECTED", "STALE", "40902 OPTIMISTIC_LOCK_CONFLICT"),
    "<!-- MCH-003-D7 -->": ("full-page", "retained only", "40910 MERCHANT_STATE_CONFLICT", "click-triggered"),
    "<!-- MCH-003-D8 -->": ("merchant:document:upload", "merchant:document:view", "public URL", "amendmentId", "must not fall back", "40401 RESOURCE_NOT_FOUND"),
    "<!-- MCH-003-D9 -->": ("PNG", "JPEG", "2 MiB", "ImageIO", "12,000,000"),
    "<!-- MCH-003-D10 -->": ("AES-256-GCM", "BYTEA", "30 minutes", "exactly once"),
    "<!-- MCH-003-D11 -->": ("legalIdNo", "legalIdNoMasked", "RETAIN", "REPLACE"),
    "<!-- MCH-003-D12 -->": ("lock-complete", "idempotency", "append-only", "AGENT"),
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


def _validate_immutable_migrations(repository: Path) -> list[str]:
    errors: list[str] = []
    for path, expected in IMMUTABLE_MIGRATION_SHA256.items():
        try:
            content = _read(repository, path).encode("utf-8")
        except (ContractError, OSError, UnicodeError) as error:
            errors.append(str(error))
            continue
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            errors.append(
                f"executed MCH-003 migration asset changed: {path} "
                f"(expected sha256 {expected}, got {actual})"
            )
    return errors


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
        errors.append("ADR-0015 must declare exactly one accepted status")
    if content.count("Decision-ID: MCH-PLATFORM-ASSISTED-ONBOARDING") != 1:
        errors.append("ADR-0015 must declare the MCH-003 decision ID")
    positions: list[int] = []
    for marker, literals in ADR_MARKERS.items():
        if content.count(marker) != 1:
            errors.append(f"ADR-0015 marker must appear exactly once: {marker}")
            continue
        start = content.index(marker)
        positions.append(start)
        later = [content.find(other, start + len(marker)) for other in ADR_MARKERS]
        end = min((position for position in later if position >= 0), default=len(content))
        section = content[start:end]
        for literal in literals:
            if literal not in section:
                errors.append(f"ADR-0015 {marker} must contain {literal}")
    if positions != sorted(positions):
        errors.append("ADR-0015 markers must remain in D1-D12 order")
    return errors


def _validate_api_contract(repository: Path) -> list[str]:
    try:
        content = _read(repository, CONTRACT_PATH)
    except (ContractError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    literals = (
        "## 12. MCH-003 assisted onboarding, amendments and documents",
        *ENDPOINTS,
        *PERMISSIONS,
        *PROFILE_FIELDS,
        "All 23 inputs are required",
        "only input that may be the empty string",
        "FINANCIAL_SERVICES",
        "ECOMMERCE",
        "RETAIL",
        "TRAVEL",
        "EDUCATION",
        "OTHER",
        "NATIONAL_ID",
        "PASSPORT",
        "DRIVER_LICENSE",
        "MERCHANT_INDUSTRY_CODE",
        "MERCHANT_LEGAL_ID_TYPE",
        "PENDING_REVIEW",
        "APPROVED",
        "REJECTED",
        "STALE",
        "originMerchantVersion",
        "originStatus",
        "authorMembershipId",
        "canCurrentActorReview",
        "registrationNumberMasked",
        "legalIdNoMasked",
        "merchantRowVersion",
        "AMENDMENT_ALREADY_PENDING",
        "DOCUMENT_ATTACHMENT_CONFLICT",
        "DIRECT is replay-only",
        "PLATFORM` is canonical",
        "receipt replay-only",
        "deduplication miss returns `40910",
        "create and edit to one full-page form component",
        "separate hidden full-page routes",
        "one read-only 23-field/five-document presentation",
        "one `Review` action",
        "click-triggered overflow",
        *DOCUMENT_SECURITY_LITERALS,
        *AMENDMENT_DOCUMENT_LITERALS,
    )
    for literal in literals:
        if literal not in content:
            errors.append(f"Merchant contract must contain MCH-003 literal: {literal}")
    if "MCH-003 is an accepted target with implementation pending" not in content:
        errors.append("Merchant contract must distinguish accepted MCH-003 target from implementation evidence")
    if content.count("DIRECT is replay-only") < 2:
        errors.append("Merchant contract must keep DIRECT is replay-only in value and cutover sections")
    detail_start_marker = "<!-- MCH-003-CURRENT-EFFECTIVE-DETAIL -->"
    detail_end_marker = "<!-- /MCH-003-CURRENT-EFFECTIVE-DETAIL -->"
    if content.count(detail_start_marker) != 1 or content.count(detail_end_marker) != 1:
        errors.append("Merchant contract must contain one bounded MCH-003 current-effective detail DTO")
    else:
        detail = content.split(detail_start_marker, 1)[1].split(detail_end_marker, 1)[0]
        for field in CURRENT_DETAIL_FIELDS:
            if f'"{field}"' not in detail:
                errors.append(f"MCH-003 current-effective detail DTO must contain field: {field}")
        for literal in (
            "GET /api/platform/merchants/{merchantId}",
            "pre-V37",
            "registrationNumberMasked",
            "completion or replacement",
        ):
            if literal not in detail:
                errors.append(f"MCH-003 current-effective detail contract must contain: {literal}")
    if "HTTP `200` with `data:null`" not in content:
        errors.append("MCH-003 pending amendment read must define HTTP 200 data:null for no pending amendment")
    return errors


def _validate_rule(repository: Path) -> list[str]:
    try:
        payload = _json(repository, RULE_PATH)
    except (ContractError, json.JSONDecodeError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    if set(payload) != RULE_FIELDS:
        errors.append("MCH-003 Rule Card must use the exact normative field set")
    if payload.get("ruleId") != "MCH-003" or payload.get("status") != "candidate":
        errors.append("MCH-003 Rule Card must remain candidate")
    if payload.get("confidence") != "high" or payload.get("evidence") != []:
        errors.append("MCH-003 candidate evidence must remain empty with high confidence")
    expected_judges = [CHECK_ID, "MCH-003-BACKEND-VERIFY", "MCH-003-FRONTEND-VERIFY"]
    if payload.get("judgeTests") != expected_judges:
        errors.append("MCH-003 Rule Card must bind the exact three Judge checks")
    normative = " ".join(str(item) for item in payload.get("then", []))
    for literal in (
        *PERMISSIONS,
        "23 required profile inputs",
        "DIRECT is replay-only",
        "PENDING_REVIEW",
        "STALE",
        "AES-256-GCM",
        "receipt replay-only",
        "click-triggered",
        "optional amendmentId",
        "never falls back",
    ):
        if literal not in normative:
            errors.append(f"MCH-003 Rule Card must contain normative literal: {literal}")
    return errors


def _validate_governance(repository: Path) -> list[str]:
    try:
        policy = _json(repository, POLICY_PATH)
        registry = _json(repository, REGISTRY_PATH)
    except (ContractError, json.JSONDecodeError, OSError, UnicodeError) as error:
        return [str(error)]
    errors: list[str] = []
    required_rulebooks = {str(ADR_PATH), str(CONTRACT_PATH), str(RULE_PATH)}
    required_judges = {str(CHECKER_PATH), str(TEST_PATH), "backend/pom.xml", "frontend/admin/package.json"}
    if not required_rulebooks.issubset(set(policy.get("rulebookPaths", []))):
        errors.append("policy is missing MCH-003 rulebook paths")
    if str(RULE_PATH) not in set(policy.get("ruleCardPaths", [])):
        errors.append("policy is missing the MCH-003 Rule Card")
    if not required_judges.issubset(set(policy.get("judgePaths", []))):
        errors.append("policy is missing MCH-003 Judge paths")
    checks = registry.get("checks", [])
    checks_by_id = {item.get("checkId"): item for item in checks if isinstance(item, Mapping)}
    expected = {
        CHECK_ID: {
            "checkId": CHECK_ID,
            "path": str(CHECKER_PATH),
            "command": "python3 -I scripts/check_mch003_merchant_onboarding.py --repository-root .",
            "ruleIds": ["MCH-003"],
        },
        "MCH-003-BACKEND-VERIFY": {
            "checkId": "MCH-003-BACKEND-VERIFY",
            "path": "backend/pom.xml",
            "command": "backend/mvnw -f backend/pom.xml clean verify",
            "ruleIds": ["MCH-003"],
        },
        "MCH-003-FRONTEND-VERIFY": {
            "checkId": "MCH-003-FRONTEND-VERIFY",
            "path": "frontend/admin/package.json",
            "command": "pnpm --dir frontend/admin test:unit && pnpm --dir frontend/admin check:type && pnpm --dir frontend/admin test:production-safety && pnpm --dir frontend/admin build:all",
            "ruleIds": ["MCH-003"],
        },
    }
    for check_id, value in expected.items():
        if checks_by_id.get(check_id) != value:
            errors.append(f"Judge registry must contain exact MCH-003 check: {check_id}")
    return errors


def _validate_supporting_docs(repository: Path) -> list[str]:
    requirements = {
        PRODUCT_PATH: ("MCH-003", "23", "全页", "PENDING_REVIEW", "amendment", "documentId", "amendmentId", "不能退回"),
        CONTEXT_PATH: (
            "MCH-003", str(ADR_PATH), "implementation pending", "V37", "amendment",
            "amendmentId", "never falls back", *V40_RELEASE_LITERALS,
            *V42_RELEASE_LITERALS,
        ),
        STATUS_PATH: ("MCH-003", "accepted target", "implementation pending", "Production NO-GO"),
        LIFECYCLE_PATH: ("MCH-003", "已接受", "实现待完成"),
        DOCS_INDEX_PATH: ("MCH-003", "ADR-0015"),
        CONTEXT_INDEX_PATH: ("ADR-0015", "0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md"),
        BACKEND_CONTEXT_PATH: ("MCH-003", "merchant:create", "amendment", "AES-256-GCM", "implementation pending", "amendmentId", "never fall back"),
        FRONTEND_CONTEXT_PATH: ("MCH-003", "full-page", "click", "implementation pending", "amendmentId", "must not retry"),
        IDENTITY_CONTRACT_PATH: ("MCH-003", "merchant:create", "merchant:amend", "does not create IAM"),
        DICTIONARY_CONTRACT_PATH: ("MCH-003", "MERCHANT_INDUSTRY_CODE", "MERCHANT_LEGAL_ID_TYPE", "DIRECT"),
        WORKFLOW_PATH: ("MCH-003", "ADR-0015"),
        JUDGE_CHARTER_PATH: ("MCH-003", CHECK_ID, "MCH-003-BACKEND-VERIFY", "MCH-003-FRONTEND-VERIFY", "optional `amendmentId`", "never fall back"),
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
                errors.append(f"{path} must contain MCH-003 literal: {literal}")
    return errors


def validate_clean_verify_status(repository: Path) -> list[str]:
    errors: list[str] = []
    for path in CLEAN_VERIFY_STATUS_PATHS:
        try:
            content = _read(repository, path)
        except (ContractError, OSError, UnicodeError) as error:
            errors.append(str(error))
            continue
        if CLEAN_VERIFY_PASS_LITERAL not in content:
            errors.append(
                f"{path} clean verify status must contain: {CLEAN_VERIFY_PASS_LITERAL}"
            )
        remaining = content.replace(CLEAN_VERIFY_PASS_LITERAL, "")
        normalized = " ".join(remaining.casefold().replace("`", "").split())
        if "unified backend clean verify" in normalized:
            errors.append(f"{path} clean verify status contradicts PASS")
    return errors


def validate_contract(repository: Path) -> list[str]:
    return [
        *_validate_immutable_migrations(repository),
        *_validate_adr(repository),
        *_validate_api_contract(repository),
        *_validate_rule(repository),
        *_validate_governance(repository),
        *_validate_supporting_docs(repository),
        *validate_clean_verify_status(repository),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate MCH-003 Merchant onboarding and amendment decisions.")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    errors = validate_contract(args.repository_root)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"{CHECK_ID} failed with {len(errors)} problem(s).", file=sys.stderr)
        return 1
    print(f"{CHECK_ID} passed: assisted onboarding and reviewed amendments are explicit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
