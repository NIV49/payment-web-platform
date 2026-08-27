#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any


ADR_PATH = Path(
    "docs/adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy.md"
)
API_CONTRACT_PATH = Path("docs/ai-contract/merchant-lifecycle-api-contract.md")
PRODUCT_PATH = Path("docs/product/merchant-management.md")
CONTEXT_PATH = Path("docs/ai-context/merchant/README.md")
DEVELOPMENT_WORKFLOW_PATH = Path("docs/ai-context/development-workflow.md")
DOC_SYNC_CHECKER_PATH = Path("scripts/check_doc_code_sync.py")
RULE_PATH = Path(".agents/payment-modernization/rules/MCH-001.json")
POLICY_PATH = Path(".agents/payment-modernization-policy.json")
REGISTRY_PATH = Path(".agents/payment-modernization-judge-registry.json")
TEST_PATH = Path("scripts/tests/test_mch001_merchant_lifecycle.py")
CHECKER_PATH = Path("scripts/check_mch001_merchant_lifecycle.py")
ROLE_BOOTSTRAP_PATH = Path(
    "backend/scripts/mch001-bootstrap-registration-rotation-role.sql"
)
MIGRATION_PREFLIGHT_PATH = Path(
    "backend/scripts/mch001-migration-principal-preflight.sql"
)

CONTRACT_PATHS = (
    ADR_PATH,
    API_CONTRACT_PATH,
    PRODUCT_PATH,
    CONTEXT_PATH,
    RULE_PATH,
    POLICY_PATH,
    REGISTRY_PATH,
    DEVELOPMENT_WORKFLOW_PATH,
    DOC_SYNC_CHECKER_PATH,
    ROLE_BOOTSTRAP_PATH,
    MIGRATION_PREFLIGHT_PATH,
)

CHECK_ID = "MCH-001-DECISION-CONTRACT"
DECISION_ID = "MCH-MERCHANT-LIFECYCLE"
JUDGE_TESTS = [
    CHECK_ID,
    "MCH-001-BACKEND-VERIFY",
    "MCH-001-FRONTEND-VERIFY",
]

MERCHANT_STATES = (
    "PENDING_REVIEW",
    "REVIEW_REJECTED",
    "ACTIVE",
    "DISABLED",
    "TERMINATED",
)
PERMISSION_CODES = (
    "merchant:self-view",
    "merchant:submit",
    "merchant:resubmit",
    "merchant:view",
    "merchant:review",
    "merchant:disable",
    "merchant:enable",
    "merchant:terminate",
)
API_ENDPOINTS = (
    "GET /api/merchant/application",
    "POST /api/merchant/application/submissions",
    "GET /api/platform/merchants",
    "GET /api/platform/merchants/{merchantId}",
    "POST /api/platform/merchants/{merchantId}/review-decisions",
    "POST /api/platform/merchants/{merchantId}/disable",
    "POST /api/platform/merchants/{merchantId}/enable",
    "POST /api/platform/merchants/{merchantId}/terminate",
)
ERROR_CODES = (
    "INVALID_REQUEST",
    "AUTH_REQUIRED",
    "SESSION_INVALID",
    "PERMISSION_DENIED",
    "RESOURCE_NOT_FOUND",
    "OPTIMISTIC_LOCK_CONFLICT",
    "MERCHANT_STATE_CONFLICT",
    "IDEMPOTENCY_CONFLICT",
)
REASON_CODES = (
    "APPLICATION_SUBMITTED",
    "APPLICATION_RESUBMITTED",
    "PROFILE_VERIFIED",
    "PROFILE_MISMATCH",
    "REGISTRATION_UNVERIFIED",
    "COMPLIANCE_REJECTED",
    "COMPLIANCE_HOLD",
    "RISK_CONTROL",
    "COMPLIANCE_CLEARED",
    "RISK_CLEARED",
    "BUSINESS_CLOSED",
    "COMPLIANCE_TERMINATION",
)
REASON_CODES_BY_COMMAND = {
    "submit": ("APPLICATION_SUBMITTED",),
    "resubmit": ("APPLICATION_RESUBMITTED",),
    "approve": ("PROFILE_VERIFIED",),
    "reject": (
        "PROFILE_MISMATCH",
        "REGISTRATION_UNVERIFIED",
        "COMPLIANCE_REJECTED",
    ),
    "disable": ("COMPLIANCE_HOLD", "RISK_CONTROL"),
    "enable": ("COMPLIANCE_CLEARED", "RISK_CLEARED"),
    "terminate": ("BUSINESS_CLOSED", "COMPLIANCE_TERMINATION"),
}
REASON_CODE_TABLE_LINES = (
    "| Command | Allowed `reasonCode` |",
    "| --- | --- |",
    *(
        f"| {command} | {', '.join(f'`{code}`' for code in codes)} |"
        for command, codes in REASON_CODES_BY_COMMAND.items()
    ),
)
TENANT_ID_PROHIBITION = (
    "Request bodies MUST NOT contain `tenantId`; merchant scope is derived from "
    "the trusted Session or the server-resolved path resource."
)
FRONTEND_JUDGE_COMMAND = (
    "pnpm --dir frontend/admin test:unit && "
    "pnpm --dir frontend/admin check:type && "
    "pnpm --dir frontend/admin test:production-safety && "
    "pnpm --dir frontend/admin build:all"
)
API_SECURITY_REQUIREMENTS = (
    "Changing `registrationCountry` requires a present nonblank `registrationNumber`",
    "Registration ciphertext is mandatory",
    "fixed 128-bit authentication tag",
    "CSPRNG 96-bit nonce",
    "MCH-REG-AEAD-v1",
    "Search-HMAC, idempotency-HMAC and AEAD keys are separate purposes",
    "A nonce collision fails closed and rolls back the entire command",
    "dedicated versioned idempotency key purpose",
    "that same Role must hold the exact non-delegable permission",
    "source Tenant, source Membership, User, Credential, Membership-to-Role assignment",
    "a later database statement obtains current database time",
    "command-schema version",
    "canonical-digest-scheme version",
    "registration-normalization version",
    "command decoder, canonical-digest implementation and registration-normalization implementation remains available",
    "transaction-level advisory registration-write fence",
    "atomically switches the active-key metadata",
    "leaves the old search key authoritative",
    "leaves the old AEAD key authoritative",
    "exactly one AEAD key for new ciphertext",
    "re-encrypts it with a fresh nonce",
    "decrypt key remains available until no row references it",
    "never accept free text",
    "MCH-001 never deletes deduplication rows",
    "row's recorded versions and key ID",
    "explicit JSON `expectedVersion: null` selects `submit`",
    "Only a deduplication miss may read the Merchant",
    "first-submit response-loss retry keeps command type `submit`",
)

ADR_MARKER_REQUIREMENTS = {
    "<!-- MCH-001-D1 -->": ("`Merchant`", "`Tenant`", "distinct aggregates"),
    "<!-- MCH-001-D2 -->": ("one-to-one", "immutable", "permanent"),
    "<!-- MCH-001-D3 -->": (
        "first submission",
        "atomically",
        "never creates IAM records",
    ),
    "<!-- MCH-001-D4 -->": (*MERCHANT_STATES, "`DRAFT`"),
    "<!-- MCH-001-D5 -->": (
        "absent to `PENDING_REVIEW`",
        "`PENDING_REVIEW` to `ACTIVE` or `REVIEW_REJECTED`",
        "`REVIEW_REJECTED` to `PENDING_REVIEW`",
        "`ACTIVE` to `DISABLED` or `TERMINATED`",
        "`DISABLED` to `ACTIVE` or `TERMINATED`",
        "`REVIEW_REJECTED` to `TERMINATED`",
        "`TERMINATED` is terminal",
    ),
    "<!-- MCH-001-D6 -->": (
        "first submission",
        "`REVIEW_REJECTED`",
        "profile",
        "resubmit",
    ),
    "<!-- MCH-001-D7 -->": (
        "MERCHANT-domain actor",
        "PLATFORM-domain actor",
        "natural-person",
    ),
    "<!-- MCH-001-D8 -->": (
        "trusted server Session",
        "`tenantId`",
        "`merchantId`",
        "Realm",
        "portal",
    ),
    "<!-- MCH-001-D9 -->": (
        *PERMISSION_CODES,
        "non-delegable",
        "same protected PLATFORM system Role",
        "cannot be combined",
    ),
    "<!-- MCH-001-D10 -->": (
        "`expectedVersion`",
        "idempotency",
        "dedicated versioned HMAC key",
        "revalidates",
        "exact permission",
        "source Tenant first",
        "Tenant `ACTIVE` status",
        "Membership-to-Role assignment",
        "later database statement",
        "command-schema",
        "canonical-digest-scheme",
        "registration-normalization",
        "reasonCode",
        "audit",
        "PostgreSQL transaction",
        "different payload",
        "Command type is never re-derived",
        "permanent deduplication",
    ),
    "<!-- MCH-001-D11 -->": (
        "`merchantCode`",
        "registrationNumber",
        "HMAC-SHA-256",
        "AES-256-GCM",
        "CSPRNG 96-bit nonce",
        "fixed 128-bit authentication tag",
        "AAD",
        "separate cryptographic purposes",
        "transaction-level advisory write fence",
        "active-key metadata",
        "old key authoritative",
        "Keys never enter",
        "log",
    ),
    "<!-- MCH-001-D12 -->": (
        "MerchantMarket",
        "Agent",
        "rate",
        "limit",
        "settlement accounts",
        "fund accounts",
        "integration",
        "payment",
        "ledger",
        "not physically deleted",
        "history",
    ),
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
RULE_ORDERED_ARRAYS = {
    "scope": [
        "merchant business identity and its one-to-one Identity Tenant binding",
        "merchant application submission and resubmission",
        "platform merchant review and lifecycle administration",
        "merchant lifecycle authorization and tenant isolation",
        "merchant lifecycle concurrency, idempotency, and audit evidence",
    ],
    "given": [
        "a MERCHANT account-domain Session bound to one trusted Identity Tenant",
        "a PLATFORM account-domain Session with explicit merchant lifecycle permission",
        "a Merchant business identity that is not an IAM User, Membership, Role, or Tenant",
        "an append-only database migration chain and server-owned authorization boundary",
    ],
    "when": [
        "a merchant reads or submits its own application",
        "a rejected merchant corrects and resubmits its application",
        "a platform operator lists, reads, or reviews a merchant",
        "a platform operator disables, enables, or terminates a merchant",
    ],
    "then": [
        "Merchant is a business aggregate distinct from the Identity Tenant authorization-workspace aggregate.",
        "Each Merchant has one immutable one-to-one binding to one MERCHANT Tenant, and that binding is never reassigned or reused.",
        "The first submission atomically creates and binds the Merchant but never creates an IAM Tenant, User, Membership, Role, Realm, or credential.",
        "The only Merchant states are PENDING_REVIEW, REVIEW_REJECTED, ACTIVE, DISABLED, and TERMINATED; DRAFT is not a state.",
        "Only the exact accepted transition matrix is legal, and TERMINATED is terminal.",
        "Only first submission and correction of a REVIEW_REJECTED Merchant may change the profile through the submission endpoint.",
        "MERCHANT submits its own application and PLATFORM reviews or governs it; Realm separation does not prove a natural-person four-eyes guarantee.",
        "Merchant scope comes from the trusted Session or server-owned path resource and never from tenantId, merchantId, accountDomain, realm, or portal request selectors.",
        "The exact eight merchant permissions require one assigned protected Role to hold the exact permission, preserving PLATFORM-only non-delegable governance and MERCHANT-only self-service.",
        "Expected-version comparison, versioned idempotency result, lifecycle mutation, allowlisted-reason audit event, and lock-complete authorization recheck commit atomically in one PostgreSQL transaction; request shape permanently selects submit or resubmit, authorized replay resolves permanent deduplication before current Merchant state, submit and resubmit derive APPLICATION_SUBMITTED and APPLICATION_RESUBMITTED on the server, while PLATFORM commands accept only their exact action-specific reason; different payload reuse fails closed.",
        "Merchant code and registration identity follow stable-code, separated search/idempotency/AEAD keys, HMAC uniqueness, mandatory AES-256-GCM ciphertext, serialized search-key rotation, country-change replacement, masking, and no-secret-log requirements.",
        "MCH-001 excludes MerchantMarket, Agent, rate, limit, account, integration, payment, and ledger behavior; Merchant lifecycle is never physically deleted and history is retained.",
    ],
    "counterexamples": [
        "Merchant is implemented as an alias for Identity Tenant.",
        "A DRAFT state is introduced before PENDING_REVIEW.",
        "A merchant request reads or changes another merchant by submitting tenantId.",
        "A merchant approves its own application or a PLATFORM review uses a MERCHANT Session.",
        "A stale expectedVersion overwrites a newer merchant transition.",
        "One idempotency key is reused with another command payload and accepted.",
        "A lifecycle transition succeeds without an audit event in the same transaction.",
        "MCH-001 creates rates, accounts, balances, payment behavior, or ledger truth.",
    ],
    "judgeTests": JUDGE_TESTS,
}

REQUIRED_RULEBOOK_PATHS = {
    str(RULE_PATH),
    str(ADR_PATH),
    str(API_CONTRACT_PATH),
    str(PRODUCT_PATH),
    str(CONTEXT_PATH),
    "docs/AGENTS.md",
}
REQUIRED_JUDGE_PATHS = {
    str(CHECKER_PATH),
    str(TEST_PATH),
    "backend/pom.xml",
    "frontend/admin/package.json",
}
HISTORICAL_RULE_CARDS = {
    ".agents/payment-modernization/rules/IAM-001.json",
    ".agents/payment-modernization/rules/IAM-002.json",
    ".agents/payment-modernization/rules/IAM-003.json",
}
HISTORICAL_RULEBOOK_PATHS = {
    ".agents/payment-modernization-policy.json",
    ".agents/payment-modernization/rules/IAM-001.json",
    ".agents/payment-modernization/rules/IAM-002.json",
    ".agents/payment-modernization/rules/IAM-003.json",
    ".agents/skills/payment-modernization/SKILL.md",
    ".agents/skills/payment-modernization/references/artifact-contracts.md",
    ".agents/skills/payment-modernization/references/judge-gates.md",
    ".agents/skills/payment-modernization/references/reimagine.md",
    ".agents/skills/payment-modernization/references/transform.md",
    "AGENTS.md",
    "README.md",
    "docs/adr/0001-separate-authorization-workspace-from-resource-owner-tenant.md",
    "docs/adr/0002-append-only-outbox-with-separate-relay-state.md",
    "docs/adr/0003-java-spring-jooq-postgresql-baseline.md",
    "docs/adr/0004-external-idp-and-application-authorization-boundary.md",
    "docs/adr/0005-bounded-context-owned-adapters-and-composition-roots.md",
    "docs/adr/0006-redis-protocol-cache-product-boundary.md",
    "docs/adr/0007-separate-production-migrations-from-local-fixtures.md",
    "docs/adr/0008-isolate-three-backoffice-account-domains-and-sessions.md",
    "docs/adr/0009-separate-backoffice-applications-and-production-identity-boundaries.md",
    "docs/adr/0010-centralize-tenant-administrator-provisioning-with-delegated-user-governance.md",
    "docs/adr/0011-centralize-system-dictionaries-with-cross-domain-read-only-access.md",
    "docs/adr/0012-expose-platform-cross-domain-role-and-menu-directories.md",
    "docs/ai-context/README.md",
    "docs/ai-context/backend/README.md",
    "docs/ai-context/current-status.md",
    "docs/ai-context/development-workflow.md",
    "docs/ai-context/frontend/README.md",
    "docs/ai-context/known-deviations.md",
    "docs/ai-context/permission/01-current-state.md",
    "docs/ai-context/permission/02-reference-ruoyi.md",
    "docs/ai-context/permission/03-reference-continew.md",
    "docs/ai-context/permission/04-comparison.md",
    "docs/ai-context/permission/05-target-permission-model.md",
    "docs/ai-context/permission/06-database-design.md",
    "docs/ai-context/permission/07-api-permission-design.md",
    "docs/ai-context/permission/08-data-scope-design.md",
    "docs/ai-context/permission/09-migration-plan.md",
    "docs/ai-context/vben/README.md",
    "docs/ai-contract/identity-admin-api-contract.md",
    "docs/ai-contract/system-dictionary-api-contract.md",
    "docs/governance/codeowners-bootstrap.md",
    "docs/judge-charter.md",
    "docs/new-payment-system-target-architecture.md",
    "docs/permission-refactor-product-requirements.md",
}
HISTORICAL_JUDGE_PATHS = {
    ".agents/payment-modernization-judge-registry.json",
    ".agents/payment-modernization/artifacts/README.md",
    ".github/CODEOWNERS",
    ".github/workflows/documentation.yml",
    "scripts/check-doc-decisions.py",
    "scripts/check_doc_code_sync.py",
    "scripts/check_iam001_three_backoffice_boundary.py",
    "scripts/check_iam002_keycloak_realms.py",
    "scripts/check_iam002_production_identity_boundary.py",
    "scripts/check_iam003_delegated_user_governance.py",
    "scripts/check_modernization_artifacts.py",
    "scripts/check_modernization_evidence.py",
    "scripts/check_project_skills.py",
    "scripts/check_sensitive_artifacts.py",
    "scripts/ci_repository_guard.sh",
    "scripts/dev/iam002_local.py",
    "scripts/dev/prepare_iam002_local.py",
    "scripts/requirements-documentation.txt",
    "scripts/tests/test_check_doc_code_sync.py",
    "scripts/tests/test_check_doc_decisions.py",
    "scripts/tests/test_check_project_skills.py",
    "scripts/tests/test_check_sensitive_artifacts.py",
    "scripts/tests/test_documentation_python_runtime_security.py",
    "scripts/tests/test_documentation_workflow_security.py",
    "scripts/tests/test_iam001_three_backoffice_boundary.py",
    "scripts/tests/test_iam002_keycloak_realms.py",
    "scripts/tests/test_iam002_local_runtime.py",
    "scripts/tests/test_iam002_production_identity_boundary.py",
    "scripts/tests/test_iam003_delegated_user_governance.py",
    "scripts/tests/test_modernization_contracts.py",
    "scripts/tests/test_payment_modernization_governance.py",
}
HISTORICAL_CHECK_IDS = {
    "IAM-001-MUTATION-SENSITIVITY",
    "IAM-001-PROCESS-BOUNDARY",
    "IAM-002-DECISION-CONTRACT",
    "IAM-002-KEYCLOAK-CONFIG",
    "IAM-002-BACKEND-VERIFY",
    "IAM-003-DECISION-CONTRACT",
    "IAM-003-BACKEND-VERIFY",
    "IAM-003-FRONTEND-VERIFY",
}
HISTORICAL_CHECK_DIGESTS = {
    "IAM-001-MUTATION-SENSITIVITY": "29c949b07bb3295616ba5d049c48d8803112ec0a067ac911b2c119f3d403a8e5",
    "IAM-001-PROCESS-BOUNDARY": "b04afcf56009982f8c1e25893c71a63e49848b8ff59d61dddacc36ea76022ea4",
    "IAM-002-DECISION-CONTRACT": "6831a7e35a75331359db26e7121a162fe634e97b002d60bed86c38204950af89",
    "IAM-002-KEYCLOAK-CONFIG": "86189b053105f3c2059edae00de3b0f97862b4039808bbcde0862700d712dfba",
    "IAM-002-BACKEND-VERIFY": "6b4dcb893d03569614f32b065b85299ffeb0c6f0bf0cee64c16b283c2fb7b5b7",
    "IAM-003-DECISION-CONTRACT": "18a7d93e9130fdfced2fceb0b89af87fef0cfaee0adcf78564789f5b84bece81",
    "IAM-003-BACKEND-VERIFY": "22cfa0f1e78c149fc9c4a629e3e33dee4dfdb2a11febe77d95db66dcdb66a6c7",
    "IAM-003-FRONTEND-VERIFY": "a108e2906dc7ebc13e809c4dae124055b1110573f69d7b015a5ac3ccb5c3e089",
}
TRUSTED_REVIEWERS = [
    {
        "reviewerId": "iam001-business-security",
        "reviewerRole": "business-security",
        "keyId": "iam001-business-security-ed25519-v1",
        "signatureAlgorithm": "Ed25519",
        "publicKey": "2pyZPATp3ukJW7NrV1f82lzBGGRX9pZfRRsJXz+B7CM=",
    },
    {
        "reviewerId": "iam001-implementation-adversary",
        "reviewerRole": "implementation-adversary",
        "keyId": "iam001-implementation-adversary-ed25519-v1",
        "signatureAlgorithm": "Ed25519",
        "publicKey": "1MaJ2rM2QzxUje3rClS+2bh67R6LFj4aBWS9uRX1GTM=",
    },
]

EXPECTED_CHECKS = {
    "MCH-001-DECISION-CONTRACT": {
        "checkId": "MCH-001-DECISION-CONTRACT",
        "path": str(CHECKER_PATH),
        "command": "python3 -I scripts/check_mch001_merchant_lifecycle.py --repository-root .",
        "ruleIds": ["MCH-001"],
    },
    "MCH-001-BACKEND-VERIFY": {
        "checkId": "MCH-001-BACKEND-VERIFY",
        "path": "backend/pom.xml",
        "command": "backend/mvnw -f backend/pom.xml clean verify",
        "ruleIds": ["MCH-001"],
    },
    "MCH-001-FRONTEND-VERIFY": {
        "checkId": "MCH-001-FRONTEND-VERIFY",
        "path": "frontend/admin/package.json",
        "command": FRONTEND_JUDGE_COMMAND,
        "ruleIds": ["MCH-001"],
    },
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


def _parse_json(repository: Path, relative_path: Path, label: str) -> Mapping[str, Any]:
    try:
        payload = json.loads(
            _read_regular_file(repository, relative_path),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (json.JSONDecodeError, UnicodeError, OSError, ContractError) as error:
        raise ContractError(f"{label} cannot be parsed: {error}") from error
    if not isinstance(payload, Mapping):
        raise ContractError(f"{label} must be a JSON object")
    return payload


def _marker_section(content: str, marker: str) -> str | None:
    if content.count(marker) != 1:
        return None
    start = content.index(marker)
    next_markers = [
        position
        for other_marker in ADR_MARKER_REQUIREMENTS
        if other_marker != marker
        and (position := content.find(other_marker, start + len(marker))) >= 0
    ]
    end = min(next_markers, default=len(content))
    return content[start:end]


def _validate_adr(repository: Path) -> list[str]:
    try:
        content = _read_regular_file(repository, ADR_PATH)
    except (OSError, UnicodeError, ContractError) as error:
        return [str(error)]
    errors: list[str] = []
    if len(re.findall(r"^Status: accepted\.$", content, re.MULTILINE)) != 1:
        errors.append("ADR-0013 must declare exactly one accepted status")
    if len(
        re.findall(rf"^Decision-ID: {re.escape(DECISION_ID)}$", content, re.MULTILINE)
    ) != 1:
        errors.append(f"ADR-0013 must declare Decision-ID {DECISION_ID}")
    marker_positions: list[int] = []
    for marker, literals in ADR_MARKER_REQUIREMENTS.items():
        section = _marker_section(content, marker)
        if section is None:
            errors.append(f"ADR-0013 {marker} must appear exactly once")
            continue
        marker_positions.append(content.index(marker))
        for literal in literals:
            if literal not in section:
                errors.append(f"ADR-0013 {marker} must contain literal {literal}")
    if marker_positions != sorted(marker_positions):
        errors.append("ADR-0013 MCH-001 decision markers must remain in D1-D12 order")
    return errors


def _backtick_values(content: str, pattern: str) -> set[str]:
    return set(re.findall(rf"`({pattern})`", content))


def _validate_api_contract(repository: Path) -> list[str]:
    try:
        content = _read_regular_file(repository, API_CONTRACT_PATH)
    except (OSError, UnicodeError, ContractError) as error:
        return [str(error)]
    errors: list[str] = []
    status_section = re.search(
        r"^### 2\.1 Merchant status\s*$.*?```text\s*\n(.*?)```",
        content,
        re.MULTILINE | re.DOTALL,
    )
    states = (
        set(status_section.group(1).split())
        if status_section is not None
        else set()
    )
    if states != set(MERCHANT_STATES):
        errors.append("Merchant API contract state literals must equal the exact MCH-001 state set")
    permission_section = re.search(
        r"^## 8\. Permission catalog\s*$.*?MCH-002 adds exactly one protected permission",
        content,
        re.MULTILINE | re.DOTALL,
    )
    permission_content = permission_section.group(0) if permission_section is not None else ""
    permissions = _backtick_values(permission_content, r"merchant:[a-z][a-z0-9-]*")
    if permissions != set(PERMISSION_CODES):
        errors.append("Merchant API contract permission literals must equal the exact MCH-001 permission set")
    reason_section = re.search(
        r"^### 2\.3 Lifecycle reason codes\s*$.*?^### 2\.4 ",
        content,
        re.MULTILINE | re.DOTALL,
    )
    reason_table_lines = (
        tuple(
            line
            for line in reason_section.group(0).splitlines()
            if line.lstrip().startswith("|")
        )
        if reason_section is not None
        else ()
    )
    if reason_table_lines != REASON_CODE_TABLE_LINES:
        errors.append(
            "Merchant API contract reason-code table must equal the exact action-specific mapping"
        )
    endpoint_section = re.search(
        r"^## 9\. Exact endpoint inventory\s*$.*?^## 10\.",
        content,
        re.MULTILINE | re.DOTALL,
    )
    endpoint_content = endpoint_section.group(0) if endpoint_section is not None else ""
    endpoint_pattern = re.compile(
        r"\b((?:GET|POST|PUT|PATCH|DELETE) "
        r"/api/(?:merchant/application|platform/merchants)[^`\s]*)"
    )
    endpoints = set(endpoint_pattern.findall(endpoint_content))
    if endpoints != set(API_ENDPOINTS):
        errors.append("Merchant API contract endpoint literals must equal the exact MCH-001 endpoint set")
    for error_code in ERROR_CODES:
        if len(re.findall(rf"`{re.escape(error_code)}`", content)) < 1:
            errors.append(f"Merchant API contract error literal is missing: {error_code}")
    for code, status in (
        ("OPTIMISTIC_LOCK_CONFLICT", "40902"),
        ("MERCHANT_STATE_CONFLICT", "40910"),
        ("IDEMPOTENCY_CONFLICT", "40911"),
    ):
        if not re.search(rf"\|\s*409\s*\|\s*{status}\s*\|\s*`{code}`\s*\|", content):
            errors.append(f"Merchant API contract must bind {code} to {status}")
    if content.count(TENANT_ID_PROHIBITION) != 1:
        errors.append("Merchant API contract must contain the exact tenantId request-body prohibition once")
    for literal in API_SECURITY_REQUIREMENTS:
        if content.count(literal) != 1:
            errors.append(
                "Merchant API contract must contain the exact security requirement once: "
                f"{literal}"
            )
    for literal in ("expectedVersion", "Idempotency-Key", "traceId", "audit"):
        if literal not in content:
            errors.append(f"Merchant API contract must contain lifecycle literal {literal}")
    return errors


def _validate_supporting_docs(repository: Path) -> list[str]:
    errors: list[str] = []
    for path, literals in (
        (PRODUCT_PATH, ("MCH-001", "PENDING_REVIEW", "PLATFORM", "MERCHANT")),
        (CONTEXT_PATH, ("MCH-001", str(ADR_PATH), str(API_CONTRACT_PATH))),
    ):
        try:
            content = _read_regular_file(repository, path)
        except (OSError, UnicodeError, ContractError) as error:
            errors.append(str(error))
            continue
        for literal in literals:
            if literal not in content:
                errors.append(f"{path} must contain {literal}")
    for path, literals in (
        (
            DEVELOPMENT_WORKFLOW_PATH,
            (
                "Merchant Lifecycle Contract",
                "Merchant 工程上下文",
                "商户管理产品页",
            ),
        ),
        (
            DOC_SYNC_CHECKER_PATH,
            (
                '"DOC-SYNC-MERCHANT-LIFECYCLE"',
                '"docs/ai-context/merchant/README.md"',
                '"docs/ai-contract/merchant-lifecycle-api-contract.md"',
                '"docs/product/merchant-management.md"',
                '"frontend/admin/packages/effects/backoffice-runtime/src/api/permission-codes.ts"',
                '"frontend/admin/packages/effects/backoffice-runtime/src/common-pages.ts"',
                '"frontend/admin/packages/effects/backoffice-runtime/src/router/routes/modules/merchant.ts"',
                'b"merchant:"',
                "_merchant_identity_migration_matches",
            ),
        ),
    ):
        try:
            content = _read_regular_file(repository, path)
        except (OSError, UnicodeError, ContractError) as error:
            errors.append(str(error))
            continue
        for literal in literals:
            if literal not in content:
                errors.append(f"{path} must contain {literal}")
    return errors


def _validate_database_principal_bootstrap(repository: Path) -> list[str]:
    errors: list[str] = []
    requirements = {
        ROLE_BOOTSTRAP_PATH: (
            "psql -X -v ON_ERROR_STOP=1",
            "BEGIN;",
            "pg_advisory_xact_lock",
            "current_user <> session_user OR NOT executor_is_superuser",
            "CREATE ROLE payment_merchant_registration_rotation",
            "NOLOGIN NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE",
            "WHERE roleid = capability_oid OR member = capability_oid",
            "MCH-001 cluster bootstrap requires the rotation role to have zero memberships",
            "COMMIT;",
        ),
        MIGRATION_PREFLIGHT_PATH: (
            "psql -X -v ON_ERROR_STOP=1",
            "BEGIN READ ONLY;",
            "current_user <> session_user",
            "migration_principal.rolsuper OR migration_principal.rolcreaterole",
            "MCH-001 migration principal must be NOSUPERUSER and NOCREATEROLE",
            "WHERE roleid = capability.oid OR member = capability.oid",
            "pg_has_role(session_user, capability.oid, 'MEMBER')",
            "pg_has_role(session_user, capability.oid, 'USAGE')",
            "pg_has_role(session_user, capability.oid, 'SET')",
            "COMMIT;",
        ),
    }
    for path, literals in requirements.items():
        try:
            content = _read_regular_file(repository, path)
        except (OSError, UnicodeError, ContractError) as error:
            errors.append(str(error))
            continue
        for literal in literals:
            if content.count(literal) != 1:
                errors.append(f"{path} must contain exact database-principal guard: {literal}")
    return errors


def _validate_rule(repository: Path) -> list[str]:
    try:
        payload = _parse_json(repository, RULE_PATH, "MCH-001 Rule Card")
    except ContractError as error:
        return [str(error)]
    errors: list[str] = []
    if set(payload) != RULE_FIELDS:
        errors.append("MCH-001 Rule Card must use the exact normative field set")
    if payload.get("ruleId") != "MCH-001":
        errors.append("MCH-001 Rule Card ruleId must be MCH-001")
    if payload.get("status") != "candidate":
        errors.append("MCH-001 Rule Card must remain candidate")
    if payload.get("confidence") != "high":
        errors.append("MCH-001 Rule Card confidence must be high")
    if payload.get("evidence") != []:
        errors.append("MCH-001 candidate Rule Card evidence must remain empty")
    for field, expected in RULE_ORDERED_ARRAYS.items():
        if payload.get(field) != expected:
            errors.append(f"MCH-001 Rule Card {field} must equal the exact ordered set")
    return errors


def _is_sorted_unique_strings(value: Any) -> bool:
    return (
        isinstance(value, list)
        and all(isinstance(item, str) for item in value)
        and value == sorted(set(value))
    )


def _validate_policy(repository: Path) -> list[str]:
    try:
        payload = _parse_json(repository, POLICY_PATH, "modernization policy")
    except ContractError as error:
        return [str(error)]
    errors: list[str] = []
    for field in ("rulebookPaths", "ruleCardPaths", "judgePaths"):
        if not _is_sorted_unique_strings(payload.get(field)):
            errors.append(f"policy {field} must use canonical sorted unique paths")
    rulebook_paths = set(payload.get("rulebookPaths", []))
    rule_card_paths = set(payload.get("ruleCardPaths", []))
    judge_paths = set(payload.get("judgePaths", []))
    for path in sorted(REQUIRED_RULEBOOK_PATHS - rulebook_paths):
        errors.append(f"policy rulebookPaths is missing MCH-001 path: {path}")
    if str(RULE_PATH) not in rule_card_paths:
        errors.append("policy ruleCardPaths is missing MCH-001 Rule Card")
    for path in sorted(REQUIRED_JUDGE_PATHS - judge_paths):
        errors.append(f"policy judgePaths is missing MCH-001 path: {path}")
    if not HISTORICAL_RULEBOOK_PATHS.issubset(rulebook_paths):
        errors.append("policy must preserve every historical rulebook path")
    if not HISTORICAL_JUDGE_PATHS.issubset(judge_paths):
        errors.append("policy must preserve every historical Judge path")
    if not HISTORICAL_RULE_CARDS.issubset(rule_card_paths):
        errors.append("policy must preserve every historical IAM Rule Card")
    if not rule_card_paths.issubset(rulebook_paths):
        errors.append("policy ruleCardPaths must remain a subset of rulebookPaths")
    if payload.get("trustedReviewers") != TRUSTED_REVIEWERS:
        errors.append("policy trustedReviewers must remain unchanged")
    return errors


def _validate_registry(repository: Path) -> list[str]:
    try:
        payload = _parse_json(repository, REGISTRY_PATH, "Judge registry")
    except ContractError as error:
        return [str(error)]
    errors: list[str] = []
    if set(payload) != {"schemaVersion", "checks"} or payload.get("schemaVersion") != 1:
        errors.append("Judge registry must use the exact schemaVersion 1 object")
    checks = payload.get("checks")
    if not isinstance(checks, list):
        return [*errors, "Judge registry checks must be a list"]
    checks_by_id: dict[str, Any] = {}
    for check in checks:
        if not isinstance(check, Mapping) or set(check) != {
            "checkId",
            "path",
            "command",
            "ruleIds",
        }:
            errors.append("Judge registry checks must use the exact field set")
            continue
        check_id = check.get("checkId")
        if not isinstance(check_id, str) or check_id in checks_by_id:
            errors.append("Judge registry checkIds must be unique strings")
            continue
        checks_by_id[check_id] = check
    if not HISTORICAL_CHECK_IDS.issubset(checks_by_id):
        errors.append("Judge registry must preserve every historical IAM check")
    for check_id, digest in HISTORICAL_CHECK_DIGESTS.items():
        check = checks_by_id.get(check_id)
        if check is None:
            continue
        canonical = json.dumps(
            check,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        if hashlib.sha256(canonical).hexdigest() != digest:
            errors.append(f"Judge registry historical check changed: {check_id}")
    for check_id, expected in EXPECTED_CHECKS.items():
        if checks_by_id.get(check_id) != expected:
            errors.append(f"Judge registry must contain exact MCH-001 check: {check_id}")
    return errors


def validate_contract(repository: Path) -> list[str]:
    return [
        *_validate_adr(repository),
        *_validate_api_contract(repository),
        *_validate_supporting_docs(repository),
        *_validate_database_principal_bootstrap(repository),
        *_validate_rule(repository),
        *_validate_policy(repository),
        *_validate_registry(repository),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate the MCH-001 merchant lifecycle decision contract."
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
    print(f"{CHECK_ID} passed: merchant lifecycle decisions are explicit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
