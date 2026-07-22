#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import importlib.util
import json
import math
import os
import re
import stat
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path, PurePosixPath
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
EVIDENCE_MODULE_PATH = SCRIPT_DIRECTORY / "check_modernization_evidence.py"
_evidence_spec = importlib.util.spec_from_file_location(
    "_payment_modernization_evidence", EVIDENCE_MODULE_PATH
)
if _evidence_spec is None or _evidence_spec.loader is None:
    raise RuntimeError("immutable evidence helper cannot be loaded")
_evidence = importlib.util.module_from_spec(_evidence_spec)
_evidence_spec.loader.exec_module(_evidence)

DEFAULT_MAX_EVIDENCE_BYTES = _evidence.DEFAULT_MAX_EVIDENCE_BYTES
EvidenceError = _evidence.EvidenceError
canonical_repository_path = _evidence.canonical_repository_path
changed_paths_for_commit = _evidence.changed_paths_for_commit
commit_parents = _evidence.commit_parents
commits_touching_paths = _evidence.commits_touching_paths
git_path_exists = _evidence.git_path_exists
list_git_files = _evidence.list_git_files
read_git_evidence = _evidence.read_git_evidence
resolve_head_commit = _evidence.resolve_head_commit
validate_git_ancestor = _evidence.validate_git_ancestor


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = PurePosixPath(".agents/payment-modernization-policy.json")
JUDGE_REGISTRY_PATH = PurePosixPath(
    ".agents/payment-modernization-judge-registry.json"
)
DEFAULT_ARTIFACT_ROOT = PurePosixPath(".agents/payment-modernization/artifacts")
ARTIFACT_README_PATH = DEFAULT_ARTIFACT_ROOT / "README.md"
CODEOWNERS_PATH = PurePosixPath(".github/CODEOWNERS")
CODEOWNERS_BOOTSTRAP_PATH = "docs/governance/codeowners-bootstrap.md"
MAX_CODEOWNERS_BYTES = 3 * 1024 * 1024
DEFAULT_LEGACY_WORKSPACE = Path("/Users/mac/Documents/work/backend")

FULL_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
RAW_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
RULE_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9_-]*-[0-9]{3,}$")
CODEOWNER_ACCOUNT_PATTERN = re.compile(
    r"^@[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"
    r"(?:/[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})?)?$"
)
CODEOWNER_EMAIL_PATTERN = re.compile(
    r"^[A-Za-z0-9.!$%&'*+/=?^_{|}~-]+@"
    r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"
)

READ_METHODS = {
    "validated-git-object",
    "validated-git-archive",
    "isolated-clone",
}
FAILURE_SOURCE_TYPES = {"rule", "judge", "build", "test", "review"}
SOURCE_SNAPSHOT_FIELDS = {
    "sourceSnapshotId",
    "repositoryPath",
    "sourceCommitSha",
    "evidencePaths",
    "readMethod",
}
NON_GIT_EVIDENCE_FIELDS = {"absolutePath", "sha256", "purpose"}
POLICY_FIELDS = {
    "schemaVersion",
    "targetRepositoryId",
    "canonicalRepositoryPath",
    "rulebookPaths",
    "ruleCardPaths",
    "judgePaths",
    "trustedReviewers",
}
TRUSTED_REVIEWER_FIELDS = {
    "reviewerId",
    "reviewerRole",
    "keyId",
    "signatureAlgorithm",
    "publicKey",
}
CAPABILITY_REQUIRED_FIELDS = {
    "turnId",
    "sliceId",
    "path",
    "sourceSnapshots",
    "targetRepositoryPath",
    "targetRepositoryId",
    "targetBaseSha",
    "rulebookManifest",
    "judgeManifest",
    "taskIdentityKey",
    "nonGitEvidence",
    "ruleIds",
    "actors",
    "inputs",
    "outputs",
    "dependencies",
    "ownedPaths",
    "forbiddenChanges",
    "entryCriteria",
    "exitCriteria",
    "judgeCommands",
}
CAPABILITY_OPTIONAL_FIELDS: set[str] = set()
EVALUATED_SNAPSHOT_FIELDS = {
    "targetCommitSha",
    "rulebookManifest",
    "judgeManifest",
    "evaluatedVersionKey",
}
REVIEW_FIELDS = {
    "reviewResultId",
    "taskIdentityKey",
    "evaluatedVersionKey",
    "reviewerId",
    "reviewerRole",
    "reviewIdempotencyKey",
    "targetCommitSha",
    "rulebookDigest",
    "judgeDigest",
    "queueDigest",
    "startCommitSha",
    "endCommitSha",
    "snapshotValid",
    "verdict",
    "findings",
    "commandsRun",
    "limitations",
    "keyId",
    "signatureAlgorithm",
    "signature",
    "reviewPurpose",
    "approvalSubjects",
}
REVIEW_FINDING_FIELDS = {
    "findingId",
    "severity",
    "status",
    "repositoryRelativePath",
    "line",
    "symbol",
    "controlFlow",
    "trigger",
    "impact",
    "evidence",
    "verification",
    "targetCommitSha",
    "evaluatedVersionKey",
    "rulebookDigest",
    "judgeDigest",
    "resolution",
}
COMMAND_EXECUTION_FIELDS = {
    "checkId",
    "command",
    "targetCommitSha",
    "exitCode",
    "resultDigest",
}
JUDGE_REGISTRY_FIELDS = {"schemaVersion", "checks"}
JUDGE_CHECK_FIELDS = {"checkId", "path", "command", "ruleIds"}
APPROVAL_SUBJECT_FIELDS = {"rulePath", "ruleId", "rulePayloadDigest"}
RULE_PAYLOAD_FIELDS = {
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
RULE_ENVELOPE_BASE_FIELDS = {"rulePath", "rulePayload"}
RULE_APPROVAL_FIELDS = {"approvalCommit", "approvedBy", "approvalReviewRefs"}
BUNDLE_FIELDS = {
    "lifecycleStatus",
    "capabilitySlice",
    "evaluatedSnapshot",
    "ruleCards",
    "reviewResults",
    "queueItems",
}

NonGitResolver = Callable[[Mapping[str, Any]], bytes]


class ContractError(ValueError):
    """Raised when an artifact cannot satisfy the immutable contract."""


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON object member: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise ContractError(f"non-finite JSON number is forbidden: {value}")


def _finite_json_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ContractError("non-finite JSON number is forbidden")
    return parsed


def _strict_json_loads(raw: str) -> Any:
    try:
        return json.loads(
            raw,
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
            parse_float=_finite_json_float,
        )
    except ContractError:
        raise
    except (json.JSONDecodeError, OverflowError, TypeError, ValueError) as error:
        raise ContractError("content is not strict JSON") from error


def _canonical_json_bytes(payload: Any) -> bytes:
    try:
        rendered = json.dumps(
            payload,
            allow_nan=False,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as error:
        raise ContractError("canonical JSON payload is invalid") from error
    return rendered.encode("utf-8")


def _canonical_digest(namespace: str, payload: Mapping[str, Any]) -> str:
    encoded = _canonical_json_bytes({"namespace": namespace, **payload})
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _require_full_sha(value: Any, field: str) -> None:
    if not isinstance(value, str) or FULL_SHA_PATTERN.fullmatch(value) is None:
        raise ContractError(f"{field} must be a lowercase full 40-character SHA")


def _require_digest(value: Any, field: str) -> None:
    if not isinstance(value, str) or DIGEST_PATTERN.fullmatch(value) is None:
        raise ContractError(f"{field} must be a sha256 digest")


def _canonical_repository_relative_path(raw_path: Any, field: str) -> str:
    if (
        not isinstance(raw_path, str)
        or not raw_path
        or "\x00" in raw_path
        or "\\" in raw_path
    ):
        raise ContractError(
            f"{field} must be a canonical repository-relative POSIX path"
        )
    parts = raw_path.split("/")
    path = PurePosixPath(raw_path)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in parts):
        raise ContractError(
            f"{field} must be a canonical repository-relative POSIX path"
        )
    return raw_path


def content_bundle_digest(contents: Mapping[str, bytes]) -> str:
    """Hash canonical paths and bytes with unambiguous length framing."""

    if not contents:
        raise ContractError("content bundle must not be empty")
    digest = hashlib.sha256()
    digest.update(b"payment-modernization-content-bundle-v2\x00")
    for raw_path in sorted(contents):
        path = _canonical_repository_relative_path(raw_path, "bundle path")
        content = contents[raw_path]
        if not isinstance(content, bytes):
            raise ContractError("bundle content must be bytes")
        path_bytes = path.encode("utf-8")
        digest.update(len(path_bytes).to_bytes(8, "big"))
        digest.update(path_bytes)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return f"sha256:{digest.hexdigest()}"


def _canonical_source_snapshots(
    source_snapshots: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for snapshot in source_snapshots:
        if set(snapshot) != SOURCE_SNAPSHOT_FIELDS:
            raise ContractError("sourceSnapshots must use the exact v2 schema")
        normalized.append(
            {
                "sourceSnapshotId": snapshot["sourceSnapshotId"],
                "repositoryPath": snapshot["repositoryPath"],
                "sourceCommitSha": snapshot["sourceCommitSha"],
                "evidencePaths": sorted(snapshot["evidencePaths"]),
                "readMethod": snapshot["readMethod"],
            }
        )
    return sorted(normalized, key=lambda item: item["sourceSnapshotId"])


def _canonical_non_git_evidence(
    entries: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for entry in entries:
        if set(entry) != NON_GIT_EVIDENCE_FIELDS:
            raise ContractError("nonGitEvidence must use the exact v2 schema")
        normalized.append(dict(entry))
    return sorted(normalized, key=lambda item: item["absolutePath"])


def task_identity_key(
    *,
    turn_id: str,
    slice_id: str,
    target_base_sha: str,
    source_snapshots: Sequence[Mapping[str, Any]],
    rulebook_digest: str,
    judge_digest: str,
    non_git_evidence: Sequence[Mapping[str, Any]] = (),
    target_repository_id: str = "",
    modernization_path: str = "reimagine",
    rulebook_manifest: Mapping[str, Any] | None = None,
    judge_manifest: Mapping[str, Any] | None = None,
    actors: Sequence[Any] = (),
    inputs: Sequence[Any] = (),
    outputs: Sequence[Any] = (),
    rule_ids: Sequence[Any] = (),
    dependencies: Sequence[Any] = (),
    owned_paths: Sequence[Any] = (),
    forbidden_changes: Sequence[Any] = (),
    entry_criteria: Sequence[Any] = (),
    exit_criteria: Sequence[Any] = (),
    judge_commands: Sequence[Any] = (),
) -> str:
    """Derive a pre-output identity from every immutable declared input."""

    if not isinstance(turn_id, str) or not turn_id:
        raise ContractError("turnId must not be empty")
    if not isinstance(slice_id, str) or not slice_id:
        raise ContractError("sliceId must not be empty")
    if (
        target_repository_id
        and re.fullmatch(r"[a-z0-9][a-z0-9._-]*", target_repository_id) is None
    ):
        raise ContractError("targetRepositoryId is invalid")
    _require_full_sha(target_base_sha, "targetBaseSha")
    _require_digest(rulebook_digest, "rulebookDigest")
    _require_digest(judge_digest, "judgeDigest")
    if modernization_path not in {"reimagine", "transform"}:
        raise ContractError("path must be reimagine or transform")
    normative_lists = {
        "actors": actors,
        "inputs": inputs,
        "outputs": outputs,
        "ruleIds": rule_ids,
        "dependencies": dependencies,
        "ownedPaths": owned_paths,
        "forbiddenChanges": forbidden_changes,
        "entryCriteria": entry_criteria,
        "exitCriteria": exit_criteria,
        "judgeCommands": judge_commands,
    }
    if not all(isinstance(value, (list, tuple)) for value in normative_lists.values()):
        raise ContractError("Capability Slice normative fields must be arrays")
    raw_rulebook_manifest = rulebook_manifest or {
        "paths": [],
        "rulebookDigest": rulebook_digest,
    }
    raw_judge_manifest = judge_manifest or {
        "paths": [],
        "judgeDigest": judge_digest,
    }
    canonical_rulebook_manifest = {
        "paths": raw_rulebook_manifest.get("paths"),
        "rulebookDigest": raw_rulebook_manifest.get("rulebookDigest"),
    }
    canonical_judge_manifest = {
        "paths": raw_judge_manifest.get("paths"),
        "judgeDigest": raw_judge_manifest.get("judgeDigest"),
    }
    return _canonical_digest(
        "payment-modernization-task-v2",
        {
            "turnId": turn_id,
            "sliceId": slice_id,
            "targetBaseSha": target_base_sha,
            "targetRepositoryId": target_repository_id,
            "path": modernization_path,
            "sourceSnapshots": _canonical_source_snapshots(source_snapshots),
            "nonGitEvidence": _canonical_non_git_evidence(non_git_evidence),
            "rulebookManifest": canonical_rulebook_manifest,
            "judgeManifest": canonical_judge_manifest,
            **{key: list(value) for key, value in normative_lists.items()},
        },
    )


def evaluated_version_key(
    task_identity: str,
    target_commit_sha: str,
    rulebook_digest: str,
    judge_digest: str,
) -> str:
    _require_digest(task_identity, "taskIdentityKey")
    _require_full_sha(target_commit_sha, "targetCommitSha")
    _require_digest(rulebook_digest, "rulebookDigest")
    _require_digest(judge_digest, "judgeDigest")
    return _canonical_digest(
        "payment-modernization-evaluated-version-v2",
        {
            "taskIdentityKey": task_identity,
            "targetCommitSha": target_commit_sha,
            "rulebookDigest": rulebook_digest,
            "judgeDigest": judge_digest,
        },
    )


def review_idempotency_key(
    evaluated_version: str, reviewer_id: str, reviewer_role: str
) -> str:
    _require_digest(evaluated_version, "evaluatedVersionKey")
    if not isinstance(reviewer_id, str) or not reviewer_id:
        raise ContractError("reviewerId must not be empty")
    if not isinstance(reviewer_role, str) or not reviewer_role:
        raise ContractError("reviewerRole must not be empty")
    return _canonical_digest(
        "payment-modernization-review-v2",
        {
            "evaluatedVersionKey": evaluated_version,
            "reviewerId": reviewer_id,
            "reviewerRole": reviewer_role,
        },
    )


def rule_payload_digest(payload: Mapping[str, Any]) -> str:
    """Content-address one normative Rule Card payload."""

    return _canonical_digest(
        "payment-modernization-rule-payload-v2", {"rulePayload": payload}
    )


def validate_source_snapshots(
    source_snapshots: Any,
    *,
    trusted_legacy_workspace: Path | str = DEFAULT_LEGACY_WORKSPACE,
) -> list[str]:
    """Validate exact snapshot schemas and resolve every evidence path by Git object."""

    if not isinstance(source_snapshots, list):
        return ["Capability Slice sourceSnapshots must be a list"]
    errors: list[str] = []
    seen_ids: set[str] = set()
    workspace: Path | None = None
    if source_snapshots:
        try:
            workspace = Path(trusted_legacy_workspace).resolve(strict=True)
        except OSError:
            errors.append("trusted legacy workspace cannot be resolved")
        else:
            if not workspace.is_dir():
                errors.append("trusted legacy workspace must be a directory")
    for index, snapshot in enumerate(source_snapshots):
        prefix = f"sourceSnapshots[{index}]"
        if not isinstance(snapshot, Mapping):
            errors.append(f"{prefix} must be a mapping")
            continue
        if set(snapshot) != SOURCE_SNAPSHOT_FIELDS:
            errors.append(f"{prefix} must contain exactly the v2 snapshot fields")
            continue
        snapshot_id = snapshot["sourceSnapshotId"]
        if not isinstance(snapshot_id, str) or not snapshot_id:
            errors.append(f"{prefix}.sourceSnapshotId must not be empty")
        elif snapshot_id in seen_ids:
            errors.append(f"duplicate sourceSnapshotId: {snapshot_id}")
        else:
            seen_ids.add(snapshot_id)
        repository_path = snapshot["repositoryPath"]
        repository: Path | None = None
        if (
            not isinstance(repository_path, str)
            or not Path(repository_path).is_absolute()
        ):
            errors.append(
                f"{prefix}.repositoryPath must be an absolute canonical Git path"
            )
        else:
            try:
                repository = canonical_repository_path(repository_path)
            except EvidenceError:
                errors.append(
                    f"{prefix}.repositoryPath must be an absolute canonical Git path"
                )
            else:
                if os.fspath(repository) != repository_path:
                    errors.append(
                        f"{prefix}.repositoryPath must be an absolute canonical Git path"
                    )
                if (
                    workspace is None
                    or repository.parent != workspace
                    or repository.name == "_worktrees"
                    or not repository.joinpath(".git").is_dir()
                    or repository.joinpath(".git").is_symlink()
                ):
                    errors.append(
                        f"{prefix}.repositoryPath must be a direct owned repository under the trusted legacy workspace"
                    )
        commit = snapshot["sourceCommitSha"]
        if not isinstance(commit, str) or FULL_SHA_PATTERN.fullmatch(commit) is None:
            errors.append(f"{prefix}.sourceCommitSha must be a lowercase full SHA")
        read_method = snapshot["readMethod"]
        if read_method not in READ_METHODS:
            errors.append(f"{prefix}.readMethod is not an approved immutable reader")
        evidence_paths = snapshot["evidencePaths"]
        valid_paths: list[str] = []
        if (
            not isinstance(evidence_paths, list)
            or not evidence_paths
            or not all(isinstance(path, str) for path in evidence_paths)
            or len(set(evidence_paths)) != len(evidence_paths)
        ):
            errors.append(f"{prefix}.evidencePaths must be a non-empty unique list")
        else:
            for path_index, evidence_path in enumerate(evidence_paths):
                try:
                    valid_paths.append(
                        _canonical_repository_relative_path(
                            evidence_path, f"{prefix}.evidencePaths[{path_index}]"
                        )
                    )
                except ContractError as error:
                    errors.append(str(error))
        if (
            repository is not None
            and isinstance(commit, str)
            and FULL_SHA_PATTERN.fullmatch(commit) is not None
        ):
            for evidence_path in valid_paths:
                try:
                    read_git_evidence(repository, commit, evidence_path)
                except EvidenceError:
                    errors.append(
                        f"{prefix} immutable evidence path could not be validated"
                    )
    return errors


def validate_non_git_evidence(
    entries: Any, *, resolver: NonGitResolver | None
) -> list[str]:
    """Require an external preauthorization resolver before reading non-Git bytes."""

    if not isinstance(entries, list):
        return ["Capability Slice nonGitEvidence must be a list"]
    errors: list[str] = []
    seen_paths: set[str] = set()
    for index, entry in enumerate(entries):
        prefix = f"nonGitEvidence[{index}]"
        if not isinstance(entry, Mapping):
            errors.append(f"{prefix} must be a mapping")
            continue
        if set(entry) != NON_GIT_EVIDENCE_FIELDS:
            errors.append(
                f"{prefix} must contain exactly absolutePath, sha256, and purpose"
            )
            continue
        absolute_path = entry["absolutePath"]
        path_is_valid = (
            isinstance(absolute_path, str)
            and "\x00" not in absolute_path
            and Path(absolute_path).is_absolute()
            and os.path.normpath(absolute_path) == absolute_path
        )
        if not path_is_valid:
            errors.append(f"{prefix}.absolutePath must be a canonical absolute path")
        elif absolute_path in seen_paths:
            errors.append(f"duplicate nonGitEvidence absolutePath at {prefix}")
        else:
            seen_paths.add(absolute_path)
        declared_digest = entry["sha256"]
        if (
            not isinstance(declared_digest, str)
            or RAW_SHA256_PATTERN.fullmatch(declared_digest) is None
        ):
            errors.append(
                f"{prefix}.sha256 must be 64 lowercase hexadecimal characters"
            )
        if not isinstance(entry["purpose"], str) or not entry["purpose"].strip():
            errors.append(f"{prefix}.purpose must not be empty")
        if not path_is_valid or not isinstance(declared_digest, str):
            continue
        if resolver is None:
            errors.append(f"{prefix} requires an external preauthorized hash resolver")
            continue
        try:
            resolved = resolver(entry)
        except Exception:  # The resolver is a trust boundary; never echo its details.
            errors.append(f"{prefix} external resolver failed closed")
            continue
        if not isinstance(resolved, bytes):
            errors.append(f"{prefix} external resolver did not return bytes")
        elif hashlib.sha256(resolved).hexdigest() != declared_digest:
            errors.append(f"{prefix}.sha256 does not match preauthorized bytes")
    return errors


def _validate_policy_path_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list) or not value:
        return [f"policy {field} must be a non-empty list"]
    if not all(isinstance(path, str) for path in value):
        return [f"policy {field} must contain only paths"]
    errors: list[str] = []
    for index, path in enumerate(value):
        try:
            _canonical_repository_relative_path(path, f"policy {field}[{index}]")
        except ContractError as error:
            errors.append(str(error))
    if len(set(value)) != len(value):
        errors.append(f"policy {field} paths must be unique")
    if value != sorted(value):
        errors.append(f"policy {field} paths must use canonical sorted order")
    return errors


def validate_policy(policy: Any) -> list[str]:
    if not isinstance(policy, Mapping):
        return ["payment modernization policy must be a JSON object"]
    if set(policy) != POLICY_FIELDS:
        return ["payment modernization policy must use the exact v2 schema"]
    errors: list[str] = []
    if policy["schemaVersion"] != 2:
        errors.append("payment modernization policy schemaVersion must be 2")
    if not isinstance(policy["targetRepositoryId"], str) or not re.fullmatch(
        r"[a-z0-9][a-z0-9._-]*", policy["targetRepositoryId"]
    ):
        errors.append("policy targetRepositoryId is invalid")
    canonical_path = policy["canonicalRepositoryPath"]
    if (
        not isinstance(canonical_path, str)
        or "\x00" in canonical_path
        or not Path(canonical_path).is_absolute()
        or os.path.normpath(canonical_path) != canonical_path
    ):
        errors.append(
            "policy canonicalRepositoryPath must be a canonical absolute path"
        )
    errors.extend(_validate_policy_path_list(policy["rulebookPaths"], "rulebookPaths"))
    if not isinstance(policy["ruleCardPaths"], list):
        errors.append("policy ruleCardPaths must be a list")
    elif policy["ruleCardPaths"]:
        errors.extend(
            _validate_policy_path_list(policy["ruleCardPaths"], "ruleCardPaths")
        )
    errors.extend(_validate_policy_path_list(policy["judgePaths"], "judgePaths"))
    if (
        isinstance(policy["rulebookPaths"], list)
        and POLICY_PATH.as_posix() not in policy["rulebookPaths"]
    ):
        errors.append("policy rulebookPaths must contain the bootstrap policy itself")
    if (
        isinstance(policy["rulebookPaths"], list)
        and "AGENTS.md" not in policy["rulebookPaths"]
    ):
        errors.append("policy rulebookPaths must contain AGENTS.md")
    if (
        isinstance(policy["ruleCardPaths"], list)
        and isinstance(policy["rulebookPaths"], list)
        and not set(policy["ruleCardPaths"]).issubset(policy["rulebookPaths"])
    ):
        errors.append("policy ruleCardPaths must be a subset of rulebookPaths")

    reviewers = policy["trustedReviewers"]
    if not isinstance(reviewers, list):
        errors.append("policy trustedReviewers must be a list")
        return errors
    seen_ids: set[str] = set()
    seen_keys: set[str] = set()
    seen_public_keys: set[bytes] = set()
    for index, reviewer in enumerate(reviewers):
        prefix = f"trustedReviewers[{index}]"
        if (
            not isinstance(reviewer, Mapping)
            or set(reviewer) != TRUSTED_REVIEWER_FIELDS
        ):
            errors.append(f"{prefix} must use the exact trusted reviewer schema")
            continue
        for field in ("reviewerId", "reviewerRole", "keyId"):
            if not isinstance(reviewer[field], str) or not reviewer[field]:
                errors.append(f"{prefix}.{field} must not be empty")
        if reviewer["signatureAlgorithm"] != "Ed25519":
            errors.append(f"{prefix}.signatureAlgorithm must be Ed25519")
        if isinstance(reviewer["reviewerId"], str):
            if reviewer["reviewerId"] in seen_ids:
                errors.append("policy trusted reviewer IDs must be unique")
            seen_ids.add(reviewer["reviewerId"])
        if isinstance(reviewer["keyId"], str):
            if reviewer["keyId"] in seen_keys:
                errors.append("policy trusted reviewer key IDs must be unique")
            seen_keys.add(reviewer["keyId"])
        try:
            public_key = base64.b64decode(reviewer["publicKey"], validate=True)
            Ed25519PublicKey.from_public_bytes(public_key)
        except (TypeError, ValueError, binascii.Error):
            errors.append(f"{prefix}.publicKey must be a base64 Ed25519 public key")
        else:
            if public_key in seen_public_keys:
                errors.append("policy trusted reviewer public keys must be unique")
            seen_public_keys.add(public_key)
    return errors


def load_policy(repository: Path | str, commit_sha: str) -> dict[str, Any]:
    try:
        raw_policy = read_git_evidence(repository, commit_sha, POLICY_PATH.as_posix())
        policy = _strict_json_loads(raw_policy.decode("utf-8"))
    except (EvidenceError, UnicodeError, ContractError) as error:
        raise ContractError(
            "immutable payment modernization policy cannot be resolved"
        ) from error
    errors = validate_policy(policy)
    if errors:
        raise ContractError("; ".join(errors))
    return dict(policy)


def load_judge_registry(
    repository: Path, commit_sha: str, policy: Mapping[str, Any]
) -> tuple[dict[str, Mapping[str, Any]], list[str]]:
    registry_path = JUDGE_REGISTRY_PATH.as_posix()
    if registry_path not in policy.get("judgePaths", []):
        return {}, ["evaluated policy must register the immutable Judge registry"]
    try:
        raw = read_git_evidence(repository, commit_sha, registry_path)
        payload = _strict_json_loads(raw.decode("utf-8"))
    except (EvidenceError, UnicodeError, ContractError):
        return {}, ["immutable Judge registry cannot be resolved"]
    if not isinstance(payload, Mapping) or set(payload) != JUDGE_REGISTRY_FIELDS:
        return {}, ["Judge registry must use the exact schema"]
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("checks"), list):
        return {}, ["Judge registry schemaVersion/checks are invalid"]
    errors: list[str] = []
    checks: dict[str, Mapping[str, Any]] = {}
    for index, check in enumerate(payload["checks"]):
        prefix = f"Judge registry checks[{index}]"
        if not isinstance(check, Mapping) or set(check) != JUDGE_CHECK_FIELDS:
            errors.append(f"{prefix} must use the exact schema")
            continue
        check_id = check["checkId"]
        if not isinstance(check_id, str) or not check_id.strip():
            errors.append(f"{prefix}.checkId must not be empty")
            continue
        if check_id in checks:
            errors.append("Judge registry checkIds must be unique")
        checks[check_id] = check
        try:
            judge_path = _canonical_repository_relative_path(
                check["path"], f"{prefix}.path"
            )
        except ContractError as error:
            errors.append(str(error))
            continue
        if judge_path == registry_path or judge_path not in policy["judgePaths"]:
            errors.append(f"{prefix}.path must resolve through policy judgePaths")
        else:
            try:
                read_git_evidence(repository, commit_sha, judge_path)
            except EvidenceError:
                errors.append(f"{prefix}.path cannot be resolved from the evaluated commit")
        if not isinstance(check["command"], str) or not check["command"].strip():
            errors.append(f"{prefix}.command must not be empty")
        rule_ids = check["ruleIds"]
        if (
            not isinstance(rule_ids, list)
            or not rule_ids
            or len(set(rule_ids)) != len(rule_ids)
            or not all(
                isinstance(rule_id, str)
                and RULE_ID_PATTERN.fullmatch(rule_id) is not None
                for rule_id in rule_ids
            )
        ):
            errors.append(f"{prefix}.ruleIds must contain unique valid Rule IDs")
    return checks, errors


def _resolve_content_manifest(
    repository: Path,
    commit_sha: str,
    declared: Any,
    policy_paths: Sequence[str],
    *,
    manifest_name: str,
    digest_field: str,
) -> tuple[dict[str, bytes] | None, list[str]]:
    errors: list[str] = []
    expected_fields = {"label", "paths", digest_field}
    if not isinstance(declared, Mapping) or set(declared) != expected_fields:
        return None, [f"{manifest_name} must use the exact manifest schema"]
    if not isinstance(declared["label"], str) or not declared["label"]:
        errors.append(f"{manifest_name}.label must not be empty")
    if declared["paths"] != list(policy_paths):
        errors.append(
            f"{manifest_name}.paths must exactly equal the immutable policy registry"
        )
    digest = declared[digest_field]
    if not isinstance(digest, str) or DIGEST_PATTERN.fullmatch(digest) is None:
        errors.append(f"{manifest_name}.{digest_field} must be a sha256 digest")
    contents: dict[str, bytes] = {}
    try:
        for path in policy_paths:
            contents[path] = read_git_evidence(repository, commit_sha, path)
    except EvidenceError:
        errors.append(f"{manifest_name} immutable content cannot be resolved")
        return None, errors
    actual_digest = content_bundle_digest(contents)
    if digest != actual_digest:
        errors.append(
            f"{manifest_name}.{digest_field} does not match immutable Git content"
        )
    return contents, errors


def canonical_review_payload(review: Mapping[str, Any]) -> bytes:
    payload = {key: value for key, value in review.items() if key != "signature"}
    return _canonical_json_bytes(payload)


def queue_items_digest(queue_items: Any) -> str:
    if not isinstance(queue_items, list):
        raise ContractError("queueItems must be a list before digesting")
    return _canonical_digest(
        "payment-modernization-queue-v2", {"queueItems": queue_items}
    )


def validate_review_finding(
    finding: Any, review: Mapping[str, Any]
) -> list[str]:
    if not isinstance(finding, Mapping) or set(finding) != REVIEW_FINDING_FIELDS:
        return ["Review Result finding must use the exact schema"]
    errors: list[str] = []
    for field in (
        "findingId",
        "symbol",
        "trigger",
        "impact",
        "verification",
    ):
        if not isinstance(finding[field], str) or not finding[field].strip():
            errors.append(f"Review Result finding {field} must not be empty")
    try:
        _canonical_repository_relative_path(
            finding["repositoryRelativePath"],
            "Review Result finding repositoryRelativePath",
        )
    except ContractError as error:
        errors.append(str(error))
    if type(finding["line"]) is not int or finding["line"] <= 0:
        errors.append("Review Result finding line must be a positive integer")
    for field in ("controlFlow", "evidence"):
        values = finding[field]
        if (
            not isinstance(values, list)
            or not values
            or not all(isinstance(value, str) and value.strip() for value in values)
        ):
            errors.append(
                f"Review Result finding {field} must contain non-empty strings"
            )
    severity = finding["severity"]
    status = finding["status"]
    resolution = finding["resolution"]
    if severity not in {"BLOCKER", "SHOULD_FIX"}:
        errors.append("Review Result finding severity is invalid")
    if status not in {
        "open",
        "implementing",
        "reviewing",
        "closed",
        "human-decision",
    }:
        errors.append("Review Result finding status is invalid")
    if resolution not in {"unresolved", "fixed", "rejected", "deferred"}:
        errors.append("Review Result finding resolution is invalid")
    expected_resolutions = {
        "open": {"unresolved"},
        "implementing": {"unresolved"},
        "reviewing": {"unresolved"},
        "closed": {"fixed", "rejected"},
        "human-decision": {"deferred"},
    }
    if status in expected_resolutions and resolution not in expected_resolutions[status]:
        errors.append("Review Result finding status and resolution conflict")
    for field in (
        "targetCommitSha",
        "evaluatedVersionKey",
        "rulebookDigest",
        "judgeDigest",
    ):
        if finding[field] != review.get(field):
            errors.append(f"Review Result finding {field} does not bind its review")
    return errors


def validate_command_execution(
    execution: Any, review: Mapping[str, Any]
) -> list[str]:
    if not isinstance(execution, Mapping) or set(execution) != COMMAND_EXECUTION_FIELDS:
        return ["Review Result commandsRun entry must use the exact execution schema"]
    errors: list[str] = []
    for field in ("checkId", "command"):
        if not isinstance(execution[field], str) or not execution[field].strip():
            errors.append(f"Review Result commandsRun {field} must not be empty")
    if execution["targetCommitSha"] != review.get("targetCommitSha"):
        errors.append("Review Result commandsRun targetCommitSha does not bind its review")
    if type(execution["exitCode"]) is not int:
        errors.append("Review Result commandsRun exitCode must be an integer")
    try:
        _require_digest(execution["resultDigest"], "commandsRun.resultDigest")
    except ContractError as error:
        errors.append(str(error))
    return errors


def verify_review_signature(
    review: Mapping[str, Any], trusted_reviewer: Mapping[str, Any]
) -> list[str]:
    errors: list[str] = []
    for field in ("reviewerId", "reviewerRole", "keyId", "signatureAlgorithm"):
        if review.get(field) != trusted_reviewer.get(field):
            errors.append(f"Review Result {field} does not match the trusted reviewer")
    if errors:
        return errors
    try:
        public_key_bytes = base64.b64decode(
            trusted_reviewer["publicKey"], validate=True
        )
        signature = base64.b64decode(review.get("signature"), validate=True)
        public_key = Ed25519PublicKey.from_public_bytes(public_key_bytes)
        public_key.verify(signature, canonical_review_payload(review))
    except (TypeError, ValueError, binascii.Error, InvalidSignature, ContractError):
        errors.append("Review Result signature verification failed")
    return errors


def validate_review_result(
    review: Mapping[str, Any],
    *,
    trusted_reviewers: Mapping[str, Mapping[str, Any]] | None = None,
    expected_task_identity_key: str | None = None,
    expected_snapshot: Mapping[str, Any] | None = None,
    expected_queue_digest: str | None = None,
    expected_judge_registry: Mapping[str, Mapping[str, Any]] | None = None,
) -> list[str]:
    if set(review) != REVIEW_FIELDS:
        return ["Review Result must use the exact signed v2 schema"]
    errors: list[str] = []
    for field in ("reviewResultId", "reviewerId", "reviewerRole", "keyId"):
        if not isinstance(review[field], str) or not review[field]:
            errors.append(f"Review Result {field} must not be empty")
    for field in (
        "taskIdentityKey",
        "evaluatedVersionKey",
        "reviewIdempotencyKey",
        "rulebookDigest",
        "judgeDigest",
        "queueDigest",
    ):
        if (
            not isinstance(review[field], str)
            or DIGEST_PATTERN.fullmatch(review[field]) is None
        ):
            errors.append(f"Review Result {field} must be a sha256 value")
    for field in ("targetCommitSha", "startCommitSha", "endCommitSha"):
        if (
            not isinstance(review[field], str)
            or FULL_SHA_PATTERN.fullmatch(review[field]) is None
        ):
            errors.append(f"Review Result {field} must be a lowercase full SHA")
    for field in ("findings", "commandsRun", "limitations"):
        if not isinstance(review[field], list):
            errors.append(f"Review Result {field} must be a list")
    if review["snapshotValid"] is not True:
        errors.append("Review Result snapshotValid must be true")
    if review["verdict"] not in {"PASS", "FAIL"}:
        errors.append("Review Result verdict must be PASS or FAIL")
    if isinstance(review["findings"], list):
        for index, finding in enumerate(review["findings"]):
            finding_errors = validate_review_finding(finding, review)
            errors.extend(
                f"Review Result findings[{index}]: {error}"
                for error in finding_errors
            )
            if (
                review["verdict"] == "PASS"
                and isinstance(finding, Mapping)
                and finding.get("severity") == "BLOCKER"
                and finding.get("status") != "closed"
            ):
                errors.append("PASS Review Result contains an unresolved BLOCKER finding")
    seen_check_ids: set[str] = set()
    if isinstance(review["commandsRun"], list):
        for index, execution in enumerate(review["commandsRun"]):
            execution_errors = validate_command_execution(execution, review)
            errors.extend(
                f"Review Result commandsRun[{index}]: {error}"
                for error in execution_errors
            )
            if not isinstance(execution, Mapping):
                continue
            check_id = execution.get("checkId")
            if isinstance(check_id, str):
                if check_id in seen_check_ids:
                    errors.append("Review Result commandsRun checkIds must be unique")
                seen_check_ids.add(check_id)
                registered = (
                    expected_judge_registry.get(check_id)
                    if expected_judge_registry is not None
                    else None
                )
                if expected_judge_registry is not None and registered is None:
                    errors.append("Review Result commandsRun checkId is not in the Judge registry")
                elif registered is not None and execution.get("command") != registered.get("command"):
                    errors.append("Review Result commandsRun command does not match the Judge registry")
            if review["verdict"] == "PASS" and execution.get("exitCode") != 0:
                errors.append("PASS Review Result contains an unsuccessful command execution")
    if review["signatureAlgorithm"] != "Ed25519":
        errors.append("Review Result signatureAlgorithm must be Ed25519")
    if review["reviewPurpose"] not in {"implementation", "rule-approval"}:
        errors.append("Review Result reviewPurpose is invalid")
    approval_subjects = review["approvalSubjects"]
    if not isinstance(approval_subjects, list):
        errors.append("Review Result approvalSubjects must be a list")
    else:
        seen_subjects: set[tuple[str, str, str]] = set()
        for index, subject in enumerate(approval_subjects):
            if (
                not isinstance(subject, Mapping)
                or set(subject) != APPROVAL_SUBJECT_FIELDS
            ):
                errors.append(
                    f"Review Result approvalSubjects[{index}] must use the exact schema"
                )
                continue
            try:
                subject_path = _canonical_repository_relative_path(
                    subject["rulePath"],
                    f"Review Result approvalSubjects[{index}].rulePath",
                )
            except ContractError as error:
                errors.append(str(error))
                continue
            if (
                not isinstance(subject["ruleId"], str)
                or RULE_ID_PATTERN.fullmatch(subject["ruleId"]) is None
            ):
                errors.append(
                    f"Review Result approvalSubjects[{index}].ruleId is invalid"
                )
            if (
                not isinstance(subject["rulePayloadDigest"], str)
                or DIGEST_PATTERN.fullmatch(subject["rulePayloadDigest"]) is None
            ):
                errors.append(
                    f"Review Result approvalSubjects[{index}].rulePayloadDigest is invalid"
                )
            identity = (
                subject_path,
                str(subject["ruleId"]),
                str(subject["rulePayloadDigest"]),
            )
            if identity in seen_subjects:
                errors.append("Review Result approvalSubjects must be unique")
            seen_subjects.add(identity)
        if review["reviewPurpose"] == "implementation" and approval_subjects:
            errors.append(
                "Implementation Review Result must not declare approvalSubjects"
            )
        if review["reviewPurpose"] == "rule-approval" and not approval_subjects:
            errors.append("Rule-approval Review Result requires approvalSubjects")
    if (
        review["startCommitSha"] != review["endCommitSha"]
        or review["endCommitSha"] != review["targetCommitSha"]
    ):
        errors.append("Review Result start/end/target commits must be identical")

    if not errors:
        expected_version = evaluated_version_key(
            review["taskIdentityKey"],
            review["targetCommitSha"],
            review["rulebookDigest"],
            review["judgeDigest"],
        )
        if review["evaluatedVersionKey"] != expected_version:
            errors.append("Review Result evaluatedVersionKey does not match its inputs")
        expected_review_key = review_idempotency_key(
            review["evaluatedVersionKey"], review["reviewerId"], review["reviewerRole"]
        )
        if review["reviewIdempotencyKey"] != expected_review_key:
            errors.append(
                "Review Result reviewIdempotencyKey does not match its inputs"
            )

    if (
        expected_task_identity_key is not None
        and review.get("taskIdentityKey") != expected_task_identity_key
    ):
        errors.append(
            "Review Result taskIdentityKey does not bind this Capability Slice"
        )
    if expected_snapshot is not None:
        bindings = {
            "evaluatedVersionKey": "evaluatedVersionKey",
            "targetCommitSha": "targetCommitSha",
            "rulebookDigest": "rulebookDigest",
            "judgeDigest": "judgeDigest",
        }
        for review_field, snapshot_field in bindings.items():
            expected_value = expected_snapshot.get(snapshot_field)
            if review_field in {"rulebookDigest", "judgeDigest"}:
                manifest_name = (
                    "rulebookManifest"
                    if review_field == "rulebookDigest"
                    else "judgeManifest"
                )
                expected_value = expected_snapshot.get(manifest_name, {}).get(
                    review_field
                )
            if review.get(review_field) != expected_value:
                errors.append(
                    f"Review Result {review_field} does not bind evaluatedSnapshot"
                )
    if expected_queue_digest is not None and review.get("queueDigest") != expected_queue_digest:
        errors.append("Review Result queueDigest does not bind queueItems")

    trusted_reviewer = None
    if trusted_reviewers is not None and isinstance(review.get("keyId"), str):
        trusted_reviewer = trusted_reviewers.get(review["keyId"])
    if trusted_reviewer is None:
        errors.append("Review Result has no matching trusted reviewer")
    else:
        errors.extend(verify_review_signature(review, trusted_reviewer))
    return errors


def _validate_rule_payload(payload: Any) -> list[str]:
    if not isinstance(payload, Mapping) or set(payload) != RULE_PAYLOAD_FIELDS:
        return ["Rule Card rulePayload must use the exact normative schema"]
    errors: list[str] = []
    if (
        not isinstance(payload["ruleId"], str)
        or RULE_ID_PATTERN.fullmatch(payload["ruleId"]) is None
    ):
        errors.append("Rule Card rulePayload.ruleId is invalid")
    if not isinstance(payload["statement"], str) or not payload["statement"]:
        errors.append("Rule Card rulePayload.statement must not be empty")
    if payload["status"] not in {"candidate", "approved"}:
        errors.append(
            "Rule Card rulePayload.status is unsupported without a trusted decision signature"
        )
    for field in ("scope", "given", "when", "then", "counterexamples", "judgeTests"):
        if not isinstance(payload[field], list):
            errors.append(f"Rule Card rulePayload.{field} must be a list")
        elif not all(
            isinstance(item, str) and item.strip() for item in payload[field]
        ) or len(set(payload[field])) != len(payload[field]):
            errors.append(
                f"Rule Card rulePayload.{field} must contain unique non-empty strings"
            )
    if not isinstance(payload["evidence"], list):
        errors.append("Rule Card rulePayload.evidence must be a list")
    if payload["confidence"] not in {"high", "medium"}:
        errors.append("Rule Card rulePayload.confidence is invalid")
    return errors


def validate_rule_evidence(
    evidence_entries: Any,
    *,
    capability: Mapping[str, Any],
    baseline_policy: Mapping[str, Any],
    target_repository: Path,
) -> list[str]:
    """Resolve every Rule evidence reference through its declared trust boundary."""

    if not isinstance(evidence_entries, list):
        return ["Rule Card rulePayload.evidence must be a list"]
    errors: list[str] = []
    snapshots = {
        snapshot.get("sourceSnapshotId"): snapshot
        for snapshot in capability.get("sourceSnapshots", [])
        if isinstance(snapshot, Mapping)
    }
    non_git_entries = {
        (entry.get("absolutePath"), entry.get("sha256"))
        for entry in capability.get("nonGitEvidence", [])
        if isinstance(entry, Mapping)
    }
    for index, entry in enumerate(evidence_entries):
        prefix = f"Rule Card rulePayload.evidence[{index}]"
        if not isinstance(entry, Mapping):
            errors.append(f"{prefix} must be a mapping")
            continue
        kind = entry.get("kind")
        expected_fields = {
            "git": {"kind", "sourceSnapshotId", "evidencePath", "location"},
            "non-git": {"kind", "source", "sha256", "location"},
            "decision": {"kind", "source", "targetBaseSha", "location"},
        }.get(kind)
        if expected_fields is None or set(entry) != expected_fields:
            errors.append(f"{prefix} must use an exact supported evidence schema")
            continue
        if not isinstance(entry["location"], str) or not entry["location"].strip():
            errors.append(f"{prefix}.location must not be empty")
        if kind == "git":
            snapshot = snapshots.get(entry["sourceSnapshotId"])
            if snapshot is None:
                errors.append(f"{prefix}.sourceSnapshotId does not resolve")
                continue
            try:
                evidence_path = _canonical_repository_relative_path(
                    entry["evidencePath"], f"{prefix}.evidencePath"
                )
            except ContractError as error:
                errors.append(str(error))
                continue
            if evidence_path not in snapshot.get("evidencePaths", []):
                errors.append(f"{prefix}.evidencePath is not declared by its snapshot")
                continue
            try:
                read_git_evidence(
                    snapshot["repositoryPath"],
                    snapshot["sourceCommitSha"],
                    evidence_path,
                )
            except (EvidenceError, KeyError, TypeError):
                errors.append(f"{prefix} immutable Git evidence cannot be resolved")
        elif kind == "non-git":
            if (
                entry.get("source"),
                entry.get("sha256"),
            ) not in non_git_entries:
                errors.append(f"{prefix} does not match preauthorized nonGitEvidence")
        else:
            try:
                source = _canonical_repository_relative_path(
                    entry["source"], f"{prefix}.source"
                )
            except ContractError as error:
                errors.append(str(error))
                continue
            if entry.get("targetBaseSha") != capability.get("targetBaseSha"):
                errors.append(f"{prefix}.targetBaseSha does not match Capability Slice")
            if source not in baseline_policy.get("rulebookPaths", []):
                errors.append(f"{prefix}.source is not in the baseline Rulebook")
                continue
            try:
                read_git_evidence(
                    target_repository,
                    capability["targetBaseSha"],
                    source,
                )
            except (EvidenceError, KeyError, TypeError):
                errors.append(
                    f"{prefix} immutable decision evidence cannot be resolved"
                )
    return errors


def validate_rule_card(
    rule: Mapping[str, Any],
    reviews: Mapping[str, Mapping[str, Any]],
    *,
    repository: Path | None = None,
    evaluated_snapshot: Mapping[str, Any] | None = None,
    review_errors: Mapping[str, Sequence[str]] | None = None,
    capability: Mapping[str, Any] | None = None,
    baseline_policy: Mapping[str, Any] | None = None,
    judge_registry: Mapping[str, Mapping[str, Any]] | None = None,
) -> list[str]:
    payload = rule.get("rulePayload")
    requested_status = payload.get("status") if isinstance(payload, Mapping) else None
    expected_fields = set(RULE_ENVELOPE_BASE_FIELDS)
    if requested_status == "approved":
        expected_fields.update(RULE_APPROVAL_FIELDS)
    if set(rule) != expected_fields:
        return ["Rule Card must use the exact payload-plus-approval-envelope schema"]
    errors = _validate_rule_payload(rule.get("rulePayload"))
    try:
        rule_path = _canonical_repository_relative_path(
            rule.get("rulePath"), "Rule Card rulePath"
        )
    except ContractError as error:
        errors.append(str(error))
        rule_path = ""

    if repository is None or evaluated_snapshot is None:
        errors.append("Rule Card requires an immutable evaluatedSnapshot resolver")
        return errors
    if capability is None or baseline_policy is None:
        errors.append("Rule Card requires immutable evidence context")
    elif isinstance(payload, Mapping):
        errors.extend(
            validate_rule_evidence(
                payload.get("evidence"),
                capability=capability,
                baseline_policy=baseline_policy,
                target_repository=repository,
            )
        )
    target_commit = evaluated_snapshot.get("targetCommitSha")
    manifest = evaluated_snapshot.get("rulebookManifest")
    manifest_paths = manifest.get("paths", []) if isinstance(manifest, Mapping) else []
    if rule_path and rule_path not in manifest_paths:
        errors.append("Rule Card rulePath must belong to the evaluated Rulebook")
    if rule_path and isinstance(target_commit, str):
        try:
            raw_payload = read_git_evidence(repository, target_commit, rule_path)
            resolved_payload = _strict_json_loads(raw_payload.decode("utf-8"))
        except (EvidenceError, UnicodeError, ContractError):
            errors.append(
                "Rule Card rulePayload cannot be resolved from targetCommitSha"
            )
        else:
            if resolved_payload != rule.get("rulePayload"):
                errors.append(
                    "Rule Card rulePayload does not equal targetCommitSha content"
                )

    if requested_status != "approved":
        return errors
    payload = rule.get("rulePayload")
    if isinstance(payload, Mapping) and not payload.get("judgeTests"):
        errors.append("Approved Rule Card rulePayload.judgeTests must not be empty")
    if isinstance(payload, Mapping) and not payload.get("evidence"):
        errors.append("Approved Rule Card rulePayload.evidence must not be empty")
    if rule.get("approvalCommit") != target_commit:
        errors.append("Approved Rule Card approvalCommit must equal targetCommitSha")
    approved_by = rule.get("approvedBy")
    review_refs = rule.get("approvalReviewRefs")
    if (
        not isinstance(approved_by, list)
        or len(approved_by) != 2
        or len(set(approved_by)) != 2
    ):
        errors.append(
            "Approved Rule Card requires exactly two distinct approvedBy reviewers"
        )
    if (
        not isinstance(review_refs, list)
        or len(review_refs) != 2
        or len(set(review_refs)) != 2
    ):
        errors.append(
            "Approved Rule Card requires exactly two distinct approvalReviewRefs"
        )
        return errors

    resolved_reviews: list[Mapping[str, Any]] = []
    payload = rule.get("rulePayload")
    expected_subject = None
    if isinstance(payload, Mapping) and rule_path:
        expected_subject = {
            "rulePath": rule_path,
            "ruleId": payload.get("ruleId"),
            "rulePayloadDigest": rule_payload_digest(payload),
        }
    for reference in review_refs:
        review = reviews.get(reference) if isinstance(reference, str) else None
        if review is None:
            errors.append("Approved Rule Card approval review does not resolve")
            continue
        if review_errors is not None and review_errors.get(reference):
            errors.append("Approved Rule Card approval review is not valid")
        if review.get("verdict") != "PASS" or review.get("snapshotValid") is not True:
            errors.append("Approved Rule Card requires valid PASS reviews")
        if review.get("targetCommitSha") != target_commit:
            errors.append("Approved Rule Card approval review commit mismatch")
        if review.get("reviewPurpose") != "rule-approval":
            errors.append("Approved Rule Card requires a rule-approval Review Result")
        if expected_subject is not None and expected_subject not in review.get(
            "approvalSubjects", []
        ):
            errors.append(
                "Approved Rule Card approval subject is not signed by the review"
            )
        resolved_reviews.append(review)
    if isinstance(payload, Mapping):
        required_checks = payload.get("judgeTests", [])
        rule_id = payload.get("ruleId")
        if judge_registry is None:
            errors.append("Approved Rule Card requires an immutable Judge registry")
        elif isinstance(required_checks, list):
            for check_id in required_checks:
                registered = judge_registry.get(check_id)
                if registered is None:
                    errors.append(
                        f"Approved Rule Card Judge registry does not define {check_id}"
                    )
                    continue
                if rule_id not in registered.get("ruleIds", []):
                    errors.append(
                        f"Approved Rule Card Judge registry check {check_id} does not cover its Rule ID"
                    )
                for review in resolved_reviews:
                    executions = review.get("commandsRun", [])
                    matching = [
                        execution
                        for execution in executions
                        if isinstance(execution, Mapping)
                        and execution.get("checkId") == check_id
                        and execution.get("command") == registered.get("command")
                        and execution.get("exitCode") == 0
                    ]
                    if len(matching) != 1:
                        errors.append(
                            f"Approved Rule Card Judge check {check_id} lacks a signed successful execution"
                        )
    if len(resolved_reviews) == 2:
        reviewer_ids = {review.get("reviewerId") for review in resolved_reviews}
        key_ids = {review.get("keyId") for review in resolved_reviews}
        review_keys = {
            review.get("reviewIdempotencyKey") for review in resolved_reviews
        }
        if len(reviewer_ids) != 2 or len(key_ids) != 2 or len(review_keys) != 2:
            errors.append(
                "Approved Rule Card reviews must be cryptographically independent"
            )
        if isinstance(approved_by, list) and set(approved_by) != reviewer_ids:
            errors.append(
                "Approved Rule Card approvedBy must match trusted reviewer identities"
            )
    return errors


def validate_queue_item(item: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    required_strings = {
        "fingerprint",
        "sliceId",
        "evaluatedVersionKey",
        "trigger",
        "impact",
        "verification",
        "status",
        "severity",
    }
    allowed_fields = required_strings | {
        "failureSource",
        "controlFlow",
        "evidence",
        "dependencies",
        "failedReviewRounds",
    }
    if set(item) - allowed_fields:
        errors.append("Queue Item contains unsupported fields")
    for field in sorted(required_strings):
        if not isinstance(item.get(field), str) or not item[field]:
            errors.append(f"Queue Item {field} must be a non-empty string")
    if (
        isinstance(item.get("evaluatedVersionKey"), str)
        and DIGEST_PATTERN.fullmatch(item["evaluatedVersionKey"]) is None
    ):
        errors.append("Queue Item evaluatedVersionKey must be a sha256 key")
    if item.get("severity") not in {"BLOCKER", "SHOULD_FIX"}:
        errors.append("Queue Item severity is invalid")
    if item.get("status") not in {
        "open",
        "implementing",
        "reviewing",
        "closed",
        "human-decision",
    }:
        errors.append("Queue Item status is invalid")
    if (
        type(item.get("failedReviewRounds")) is not int
        or not 0 <= item["failedReviewRounds"] <= 3
    ):
        errors.append("Queue Item failedReviewRounds must be an integer from 0 to 3")
    for field in ("controlFlow", "evidence"):
        if not isinstance(item.get(field), list) or not item[field]:
            errors.append(f"Queue Item {field} must be a non-empty list")
    if "dependencies" in item and not isinstance(item["dependencies"], list):
        errors.append("Queue Item dependencies must be a list")
    source = item.get("failureSource")
    if not isinstance(source, Mapping):
        errors.append("Queue Item failureSource must be a mapping")
        return errors
    source_type = source.get("type")
    if source_type not in FAILURE_SOURCE_TYPES:
        errors.append("Queue Item failureSource.type is invalid")
    elif source_type == "rule":
        if set(source) != {"type", "ruleId"}:
            errors.append("Rule failureSource must contain only type and ruleId")
        elif (
            not isinstance(source["ruleId"], str)
            or RULE_ID_PATTERN.fullmatch(source["ruleId"]) is None
        ):
            errors.append("Rule failureSource.ruleId is invalid")
    else:
        if set(source) != {"type", "checkId"}:
            errors.append(
                f"{source_type} failureSource must contain only type and checkId"
            )
        elif not isinstance(source["checkId"], str) or not source["checkId"]:
            errors.append(f"{source_type} failureSource.checkId must not be empty")
    return errors


def validate_review_finding_queue_traceability(
    reviews: Sequence[Mapping[str, Any]],
    queue_items: Sequence[Any],
) -> list[str]:
    errors: list[str] = []
    findings_by_check_id: dict[str, Mapping[str, Any]] = {}
    review_queue_counts: dict[str, int] = {}
    for review in reviews:
        findings = review.get("findings")
        if not isinstance(findings, list):
            continue
        for finding in findings:
            if not isinstance(finding, Mapping) or not isinstance(
                finding.get("findingId"), str
            ):
                continue
            check_id = f"review:{finding['findingId']}"
            if check_id in findings_by_check_id:
                errors.append(
                    f"Review findingId must be unique across the bundle: "
                    f"{finding['findingId']}"
                )
                continue
            findings_by_check_id[check_id] = finding

    for check_id, finding in findings_by_check_id.items():
        if finding.get("status") == "closed":
            continue
        matches = [
            item
            for item in queue_items
            if isinstance(item, Mapping)
            and item.get("failureSource")
            == {"type": "review", "checkId": check_id}
        ]
        if len(matches) != 1:
            errors.append(
                f"unresolved Review finding {finding['findingId']} must map "
                "to exactly one Queue Item"
            )
            continue
        item = matches[0]
        for field in (
            "severity",
            "status",
            "trigger",
            "controlFlow",
            "evidence",
            "impact",
            "verification",
        ):
            if item.get(field) != finding.get(field):
                errors.append(
                    f"Queue Item {field} does not match unresolved Review "
                    f"finding {finding['findingId']}"
                )

    for item in queue_items:
        if not isinstance(item, Mapping):
            continue
        source = item.get("failureSource")
        if not isinstance(source, Mapping) or source.get("type") != "review":
            continue
        check_id = source.get("checkId")
        if isinstance(check_id, str):
            review_queue_counts[check_id] = review_queue_counts.get(check_id, 0) + 1
        finding = findings_by_check_id.get(check_id) if isinstance(check_id, str) else None
        if finding is None:
            errors.append(
                f"Review Queue Item {check_id!r} does not map to an unresolved "
                "Review finding"
            )
            continue
        if finding.get("status") == "closed":
            for field in (
                "severity",
                "status",
                "trigger",
                "controlFlow",
                "evidence",
                "impact",
                "verification",
            ):
                if item.get(field) != finding.get(field):
                    errors.append(
                        f"Queue Item {field} does not match closed Review "
                        f"finding {finding['findingId']}"
                    )
    for check_id, count in review_queue_counts.items():
        if count != 1:
            errors.append(
                f"Review Queue Item checkId must be unique across the bundle: "
                f"{check_id}"
            )
    return errors


def validate_bundle(
    bundle: Mapping[str, Any],
    *,
    non_git_resolver: NonGitResolver | None = None,
    trusted_legacy_workspace: Path | str = DEFAULT_LEGACY_WORKSPACE,
    target_repository_override: Path | str | None = None,
    trusted_reviewer_registry: Sequence[Mapping[str, Any]] | None = None,
    require_closed: bool = False,
) -> list[str]:
    if set(bundle) != BUNDLE_FIELDS:
        return ["artifact bundle must use the exact v2 top-level schema"]
    errors: list[str] = []
    lifecycle_status = bundle.get("lifecycleStatus")
    if lifecycle_status not in {"draft", "closed"}:
        errors.append("artifact bundle lifecycleStatus must be draft or closed")
    if require_closed and lifecycle_status != "closed":
        errors.append("canonical artifact bundle must be closed")
    raw_queue = bundle.get("queueItems")
    try:
        declared_queue_digest = queue_items_digest(raw_queue)
    except ContractError as error:
        errors.append(str(error))
        declared_queue_digest = None
    capability = bundle.get("capabilitySlice")
    if not isinstance(capability, Mapping):
        return ["capabilitySlice must be a mapping"]
    if not CAPABILITY_REQUIRED_FIELDS.issubset(capability) or set(capability) - (
        CAPABILITY_REQUIRED_FIELDS | CAPABILITY_OPTIONAL_FIELDS
    ):
        errors.append("Capability Slice must use the v2 schema")
        return errors
    for field in ("turnId", "sliceId", "targetRepositoryPath"):
        if not isinstance(capability[field], str) or not capability[field]:
            errors.append(f"Capability Slice {field} must not be empty")
    if capability["path"] not in {"reimagine", "transform"}:
        errors.append("Capability Slice path must be reimagine or transform")
    for field in (
        "actors",
        "inputs",
        "outputs",
        "ruleIds",
        "dependencies",
        "ownedPaths",
        "forbiddenChanges",
        "entryCriteria",
        "exitCriteria",
        "judgeCommands",
    ):
        if not isinstance(capability[field], list):
            errors.append(f"Capability Slice {field} must be a list")
    base_sha = capability["targetBaseSha"]
    if not isinstance(base_sha, str) or FULL_SHA_PATTERN.fullmatch(base_sha) is None:
        errors.append("Capability Slice targetBaseSha must be a lowercase full SHA")
    repository: Path | None = None
    repository_candidate = (
        target_repository_override
        if target_repository_override is not None
        else capability["targetRepositoryPath"]
    )
    if isinstance(repository_candidate, (str, Path)):
        try:
            repository = canonical_repository_path(repository_candidate)
        except EvidenceError:
            errors.append("runtime target repository must be a canonical Git top level")
        else:
            if (
                target_repository_override is None
                and os.fspath(repository) != capability["targetRepositoryPath"]
            ):
                errors.append(
                    "Capability Slice targetRepositoryPath must be a canonical Git top level"
                )

    source_errors = validate_source_snapshots(
        capability["sourceSnapshots"],
        trusted_legacy_workspace=trusted_legacy_workspace,
    )
    errors.extend(source_errors)
    non_git_errors = validate_non_git_evidence(
        capability["nonGitEvidence"], resolver=non_git_resolver
    )
    errors.extend(non_git_errors)
    if (
        repository is None
        or not isinstance(base_sha, str)
        or FULL_SHA_PATTERN.fullmatch(base_sha) is None
    ):
        return errors

    try:
        baseline_policy = load_policy(repository, base_sha)
    except ContractError as error:
        errors.append(f"Capability Slice baseline policy is invalid: {error}")
        return errors
    if capability.get("targetRepositoryId") != baseline_policy["targetRepositoryId"]:
        errors.append(
            "Capability Slice targetRepositoryId does not match immutable policy"
        )
    if (
        capability.get("targetRepositoryPath")
        != baseline_policy["canonicalRepositoryPath"]
    ):
        errors.append(
            "Capability Slice targetRepositoryPath does not match immutable policy"
        )
    if trusted_reviewer_registry is not None and baseline_policy[
        "trustedReviewers"
    ] != list(trusted_reviewer_registry):
        errors.append(
            "Capability Slice baseline trustedReviewers do not match the trusted policy anchor"
        )
    _, baseline_rule_errors = _resolve_content_manifest(
        repository,
        base_sha,
        capability["rulebookManifest"],
        baseline_policy["rulebookPaths"],
        manifest_name="Capability Slice rulebookManifest",
        digest_field="rulebookDigest",
    )
    errors.extend(baseline_rule_errors)
    _, baseline_judge_errors = _resolve_content_manifest(
        repository,
        base_sha,
        capability["judgeManifest"],
        baseline_policy["judgePaths"],
        manifest_name="Capability Slice judgeManifest",
        digest_field="judgeDigest",
    )
    errors.extend(baseline_judge_errors)

    declared_task_key = capability["taskIdentityKey"]
    if (
        not isinstance(declared_task_key, str)
        or DIGEST_PATTERN.fullmatch(declared_task_key) is None
    ):
        errors.append("Capability Slice taskIdentityKey must be a sha256 key")
    elif (
        not source_errors
        and not non_git_errors
        and not baseline_rule_errors
        and not baseline_judge_errors
    ):
        try:
            expected_task_key = task_identity_key(
                turn_id=capability["turnId"],
                slice_id=capability["sliceId"],
                target_base_sha=base_sha,
                target_repository_id=capability["targetRepositoryId"],
                modernization_path=capability["path"],
                rulebook_manifest=capability["rulebookManifest"],
                judge_manifest=capability["judgeManifest"],
                actors=capability["actors"],
                inputs=capability["inputs"],
                outputs=capability["outputs"],
                rule_ids=capability["ruleIds"],
                dependencies=capability["dependencies"],
                owned_paths=capability["ownedPaths"],
                forbidden_changes=capability["forbiddenChanges"],
                entry_criteria=capability["entryCriteria"],
                exit_criteria=capability["exitCriteria"],
                judge_commands=capability["judgeCommands"],
                source_snapshots=capability["sourceSnapshots"],
                non_git_evidence=capability["nonGitEvidence"],
                rulebook_digest=capability["rulebookManifest"]["rulebookDigest"],
                judge_digest=capability["judgeManifest"]["judgeDigest"],
            )
        except (ContractError, KeyError, TypeError):
            errors.append("Capability Slice taskIdentityKey inputs are invalid")
        else:
            if declared_task_key != expected_task_key:
                errors.append(
                    "Capability Slice taskIdentityKey does not match immutable inputs"
                )

    evaluated = bundle.get("evaluatedSnapshot")
    if (
        not isinstance(evaluated, Mapping)
        or set(evaluated) != EVALUATED_SNAPSHOT_FIELDS
    ):
        errors.append("evaluatedSnapshot must use the exact v2 schema")
        return errors
    target_commit = evaluated["targetCommitSha"]
    if (
        not isinstance(target_commit, str)
        or FULL_SHA_PATTERN.fullmatch(target_commit) is None
    ):
        errors.append("evaluatedSnapshot targetCommitSha must be a lowercase full SHA")
        return errors
    try:
        validate_git_ancestor(repository, base_sha, target_commit)
        evaluated_policy = load_policy(repository, target_commit)
    except (EvidenceError, ContractError) as error:
        errors.append(f"evaluatedSnapshot is not an immutable descendant: {error}")
        return errors

    if evaluated_policy["trustedReviewers"] != baseline_policy["trustedReviewers"]:
        errors.append(
            "evaluated policy trustedReviewers must equal the baseline trust registry"
        )
    for field in ("targetRepositoryId", "canonicalRepositoryPath"):
        if evaluated_policy[field] != baseline_policy[field]:
            errors.append(f"evaluated policy {field} must equal the baseline registry")
    for field in ("rulebookPaths", "ruleCardPaths", "judgePaths"):
        if not set(baseline_policy[field]).issubset(evaluated_policy[field]):
            errors.append(f"evaluated policy must not remove baseline {field}")

    _, evaluated_rule_errors = _resolve_content_manifest(
        repository,
        target_commit,
        evaluated["rulebookManifest"],
        evaluated_policy["rulebookPaths"],
        manifest_name="evaluatedSnapshot rulebookManifest",
        digest_field="rulebookDigest",
    )
    errors.extend(evaluated_rule_errors)
    _, evaluated_judge_errors = _resolve_content_manifest(
        repository,
        target_commit,
        evaluated["judgeManifest"],
        evaluated_policy["judgePaths"],
        manifest_name="evaluatedSnapshot judgeManifest",
        digest_field="judgeDigest",
    )
    errors.extend(evaluated_judge_errors)
    raw_rules = bundle.get("ruleCards")
    judge_registry: dict[str, Mapping[str, Any]] = {}
    raw_judge_commands = capability.get("judgeCommands")
    if lifecycle_status == "closed":
        if (
            not isinstance(raw_judge_commands, list)
            or not raw_judge_commands
            or not all(
                isinstance(check_id, str) and check_id.strip()
                for check_id in raw_judge_commands
            )
            or len(set(raw_judge_commands)) != len(raw_judge_commands)
        ):
            errors.append(
                "closed Capability Slice judgeCommands must be a unique non-empty checkId list"
            )
    if (
        lifecycle_status == "closed"
        or (isinstance(raw_rules, list) and bool(raw_rules))
        or (isinstance(raw_judge_commands, list) and bool(raw_judge_commands))
    ):
        judge_registry, judge_registry_errors = load_judge_registry(
            repository, target_commit, evaluated_policy
        )
        errors.extend(judge_registry_errors)
    if isinstance(raw_judge_commands, list):
        for check_id in raw_judge_commands:
            if isinstance(check_id, str) and check_id not in judge_registry:
                errors.append(
                    f"Capability Slice judgeCommands checkId {check_id} is not in the Judge registry"
                )
    declared_evaluated_key = evaluated["evaluatedVersionKey"]
    if (
        not isinstance(declared_evaluated_key, str)
        or DIGEST_PATTERN.fullmatch(declared_evaluated_key) is None
    ):
        errors.append("evaluatedSnapshot evaluatedVersionKey must be a sha256 key")
    elif (
        not evaluated_rule_errors
        and not evaluated_judge_errors
        and isinstance(declared_task_key, str)
    ):
        expected_evaluated_key = evaluated_version_key(
            declared_task_key,
            target_commit,
            evaluated["rulebookManifest"]["rulebookDigest"],
            evaluated["judgeManifest"]["judgeDigest"],
        )
        if declared_evaluated_key != expected_evaluated_key:
            errors.append(
                "evaluatedSnapshot evaluatedVersionKey does not match immutable inputs"
            )

    trusted_reviewers = {
        reviewer["keyId"]: reviewer for reviewer in baseline_policy["trustedReviewers"]
    }
    raw_reviews = bundle.get("reviewResults")
    if not isinstance(raw_reviews, list):
        errors.append("reviewResults must be a list")
        return errors
    reviews: dict[str, Mapping[str, Any]] = {}
    per_review_errors: dict[str, list[str]] = {}
    seen_review_keys: set[str] = set()
    valid_reviews: list[Mapping[str, Any]] = []
    valid_pass_reviews: list[Mapping[str, Any]] = []
    for index, review in enumerate(raw_reviews):
        if not isinstance(review, Mapping):
            errors.append(f"reviewResults[{index}] must be a mapping")
            continue
        review_id = review.get("reviewResultId")
        if isinstance(review_id, str):
            if review_id in reviews:
                errors.append(f"duplicate reviewResultId: {review_id}")
            else:
                reviews[review_id] = review
        review_key = review.get("reviewIdempotencyKey")
        if isinstance(review_key, str):
            if review_key in seen_review_keys:
                errors.append("duplicate reviewIdempotencyKey in artifact bundle")
            seen_review_keys.add(review_key)
        review_validation_errors = validate_review_result(
            review,
            trusted_reviewers=trusted_reviewers,
            expected_task_identity_key=declared_task_key
            if isinstance(declared_task_key, str)
            else None,
            expected_snapshot=evaluated,
            expected_queue_digest=declared_queue_digest,
            expected_judge_registry=judge_registry,
        )
        if isinstance(review_id, str):
            per_review_errors[review_id] = review_validation_errors
        if not review_validation_errors:
            valid_reviews.append(review)
        if not review_validation_errors and review.get("verdict") == "PASS":
            valid_pass_reviews.append(review)
        errors.extend(
            f"reviewResults[{index}]: {error}" for error in review_validation_errors
        )
    if lifecycle_status == "closed":
        distinct_reviewer_ids = {
            review.get("reviewerId") for review in valid_pass_reviews
        }
        distinct_key_ids = {review.get("keyId") for review in valid_pass_reviews}
        if (
            len(raw_reviews) != 2
            or len(valid_pass_reviews) != 2
            or len(distinct_reviewer_ids) != 2
            or len(distinct_key_ids) != 2
        ):
            errors.append(
                "closed artifact bundle requires exactly two independent valid signed PASS reviews"
            )
        if isinstance(raw_judge_commands, list):
            for check_id in raw_judge_commands:
                if not isinstance(check_id, str):
                    continue
                registered = judge_registry.get(check_id)
                for review in valid_pass_reviews:
                    executions = review.get("commandsRun", [])
                    matches = [
                        execution
                        for execution in executions
                        if isinstance(execution, Mapping)
                        and execution.get("checkId") == check_id
                        and execution.get("exitCode") == 0
                        and registered is not None
                        and execution.get("command") == registered.get("command")
                    ]
                    if len(matches) != 1:
                        errors.append(
                            f"closed Capability Slice checkId {check_id} requires one signed successful Judge execution from each PASS reviewer"
                        )

    if not isinstance(raw_rules, list):
        errors.append("ruleCards must be a list")
    else:
        declared_rule_paths: list[str] = []
        declared_rule_ids: list[str] = []
        for index, rule in enumerate(raw_rules):
            if not isinstance(rule, Mapping):
                errors.append(f"ruleCards[{index}] must be a mapping")
                continue
            if isinstance(rule.get("rulePath"), str):
                if rule["rulePath"] in declared_rule_paths:
                    errors.append(f"duplicate Rule Card rulePath: {rule['rulePath']}")
                declared_rule_paths.append(rule["rulePath"])
            payload = rule.get("rulePayload")
            if isinstance(payload, Mapping) and isinstance(payload.get("ruleId"), str):
                if payload["ruleId"] in declared_rule_ids:
                    errors.append(f"duplicate Rule Card ruleId: {payload['ruleId']}")
                declared_rule_ids.append(payload["ruleId"])
            rule_errors = validate_rule_card(
                rule,
                reviews,
                repository=repository,
                evaluated_snapshot=evaluated,
                review_errors=per_review_errors,
                capability=capability,
                baseline_policy=baseline_policy,
                judge_registry=judge_registry,
            )
            errors.extend(f"ruleCards[{index}]: {error}" for error in rule_errors)
        unregistered_paths = set(declared_rule_paths) - set(
            evaluated_policy["ruleCardPaths"]
        )
        if unregistered_paths:
            errors.append(
                "bundle Rule Cards must be a subset of evaluated policy ruleCardPaths"
            )
        if not isinstance(capability["ruleIds"], list) or sorted(
            capability["ruleIds"]
        ) != sorted(declared_rule_ids):
            errors.append(
                "Capability Slice ruleIds must exactly match bundled Rule Cards"
            )

    if not isinstance(raw_queue, list):
        errors.append("queueItems must be a list")
    else:
        seen_queue_fingerprints: set[str] = set()
        for index, item in enumerate(raw_queue):
            if not isinstance(item, Mapping):
                errors.append(f"queueItems[{index}] must be a mapping")
                continue
            item_errors = validate_queue_item(item)
            fingerprint = item.get("fingerprint")
            if isinstance(fingerprint, str):
                if fingerprint in seen_queue_fingerprints:
                    item_errors.append(
                        "Queue Item fingerprint must be unique within a bundle"
                    )
                seen_queue_fingerprints.add(fingerprint)
            if item.get("sliceId") != capability.get("sliceId"):
                item_errors.append(
                    "Queue Item sliceId does not bind this Capability Slice"
                )
            if item.get("evaluatedVersionKey") != evaluated.get("evaluatedVersionKey"):
                item_errors.append(
                    "Queue Item evaluatedVersionKey does not bind evaluatedSnapshot"
                )
            if (
                lifecycle_status == "closed"
                and item.get("severity") == "BLOCKER"
                and item.get("status") != "closed"
            ):
                item_errors.append(
                    "closed artifact bundle contains an unresolved BLOCKER Queue Item"
                )
            errors.extend(f"queueItems[{index}]: {error}" for error in item_errors)
        errors.extend(
            validate_review_finding_queue_traceability(valid_reviews, raw_queue)
        )
    return errors


def discover_artifact_bundles(root: Path | str) -> list[Path]:
    """Discover JSON bundles beneath a fixed root without following any symlink."""

    root_path = Path(root)
    try:
        root_stat = os.lstat(root_path)
    except OSError as error:
        raise ContractError("artifact root does not exist") from error
    if stat.S_ISLNK(root_stat.st_mode):
        raise ContractError("artifact root must not be a symbolic link")
    if not stat.S_ISDIR(root_stat.st_mode):
        raise ContractError("artifact root must be a directory")
    bundles: list[Path] = []
    for current_root, directory_names, file_names in os.walk(
        root_path, followlinks=False
    ):
        current = Path(current_root)
        for name in sorted(directory_names):
            candidate = current / name
            if stat.S_ISLNK(os.lstat(candidate).st_mode):
                raise ContractError("artifact root contains a symbolic link")
        for name in sorted(file_names):
            candidate = current / name
            candidate_stat = os.lstat(candidate)
            if stat.S_ISLNK(candidate_stat.st_mode):
                raise ContractError("artifact root contains a symbolic link")
            if name.endswith(".json"):
                if not stat.S_ISREG(candidate_stat.st_mode):
                    raise ContractError("artifact bundle must be a regular file")
                bundles.append(candidate)
    return sorted(bundles)


def load_bundle_file(path: Path | str) -> Mapping[str, Any]:
    bundle_path = Path(path)
    descriptor: int | None = None
    try:
        path_stat = os.lstat(bundle_path)
        if stat.S_ISLNK(path_stat.st_mode):
            raise ContractError("artifact bundle must not be a symbolic link")
        if not stat.S_ISREG(path_stat.st_mode):
            raise ContractError("artifact bundle must be a regular file")
        if path_stat.st_size > DEFAULT_MAX_EVIDENCE_BYTES:
            raise ContractError("artifact bundle exceeds the configured size limit")
        if not hasattr(os, "O_NOFOLLOW"):
            raise ContractError("platform cannot open artifact bundle safely")
        descriptor = os.open(bundle_path, os.O_RDONLY | os.O_NOFOLLOW)
        opened_stat = os.fstat(descriptor)
        if not stat.S_ISREG(opened_stat.st_mode) or (
            opened_stat.st_dev,
            opened_stat.st_ino,
        ) != (path_stat.st_dev, path_stat.st_ino):
            raise ContractError("artifact bundle changed before safe open")
        if opened_stat.st_size > DEFAULT_MAX_EVIDENCE_BYTES:
            raise ContractError("artifact bundle exceeds the configured size limit")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        raw = b"".join(chunks)
        bundle = _strict_json_loads(raw.decode("utf-8"))
    except ContractError:
        raise
    except (OSError, UnicodeError, ContractError) as error:
        raise ContractError("artifact bundle cannot be parsed as UTF-8 JSON") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if not isinstance(bundle, Mapping):
        raise ContractError("artifact bundle must be a JSON object")
    return bundle


def load_bundle_git(
    repository: Path | str, commit_sha: str, bundle_path: str
) -> Mapping[str, Any]:
    """Parse a bundle only after the immutable Git reader validates mode and size."""

    try:
        raw = read_git_evidence(repository, commit_sha, bundle_path)
        bundle = _strict_json_loads(raw.decode("utf-8"))
    except (EvidenceError, UnicodeError, ContractError) as error:
        raise ContractError("immutable artifact bundle cannot be parsed") from error
    if not isinstance(bundle, Mapping):
        raise ContractError("immutable artifact bundle must be a JSON object")
    return bundle


def _load_registered_rule_payloads(
    repository: Path, commit_sha: str, policy: Mapping[str, Any]
) -> tuple[dict[str, Mapping[str, Any]], list[str]]:
    payloads: dict[str, Mapping[str, Any]] = {}
    errors: list[str] = []
    for rule_path in policy["ruleCardPaths"]:
        try:
            raw_payload = read_git_evidence(repository, commit_sha, rule_path)
            payload = _strict_json_loads(raw_payload.decode("utf-8"))
        except (EvidenceError, UnicodeError, ContractError):
            errors.append("registered Rule Card payload cannot be resolved")
            continue
        payload_errors = _validate_rule_payload(payload)
        errors.extend(
            f"registered Rule Card {rule_path}: {error}" for error in payload_errors
        )
        if isinstance(payload, Mapping):
            payloads[rule_path] = payload
    return payloads, errors


ApprovalIdentity = tuple[str, str, str, tuple[str, ...], tuple[str, ...]]
QUEUE_MUTABLE_FIELDS = {"status", "evaluatedVersionKey", "failedReviewRounds"}
QUEUE_TRANSITIONS = {
    "open": {"open", "implementing", "human-decision"},
    "implementing": {"implementing", "reviewing", "human-decision"},
    "reviewing": {"reviewing", "implementing", "closed", "human-decision"},
    "closed": {"closed"},
    "human-decision": {"human-decision", "implementing"},
}


def validate_queue_history_step(
    previous_states: Mapping[str, Mapping[str, Any]],
    current_states: Mapping[str, Mapping[str, Any]],
) -> list[str]:
    errors: list[str] = []
    for fingerprint in set(previous_states) - set(current_states):
        errors.append(f"Queue Item {fingerprint} was deleted; queue history is append-only")
    for fingerprint, item in current_states.items():
        previous = previous_states.get(fingerprint)
        if previous is None:
            if item.get("status") != "open":
                errors.append(
                    f"Queue Item {fingerprint} must enter the append-only history as open"
                )
            if item.get("failedReviewRounds") != 0:
                errors.append(
                    f"Queue Item {fingerprint} must enter history with zero failed review rounds"
                )
            continue
        immutable_previous = {
            key: value for key, value in previous.items() if key not in QUEUE_MUTABLE_FIELDS
        }
        immutable_current = {
            key: value for key, value in item.items() if key not in QUEUE_MUTABLE_FIELDS
        }
        if immutable_previous != immutable_current:
            errors.append(f"Queue Item {fingerprint} immutable root was rewritten")
        previous_status = previous.get("status")
        current_status = item.get("status")
        if (
            previous_status not in QUEUE_TRANSITIONS
            or current_status not in QUEUE_TRANSITIONS[previous_status]
        ):
            errors.append(f"Queue Item {fingerprint} has an invalid status transition")
        if (
            previous_status != current_status
            and previous.get("evaluatedVersionKey") == item.get("evaluatedVersionKey")
        ):
            errors.append(
                f"Queue Item {fingerprint} status changes require a new evaluatedVersionKey"
            )
        previous_rounds = previous.get("failedReviewRounds")
        current_rounds = item.get("failedReviewRounds")
        if type(previous_rounds) is not int or type(current_rounds) is not int:
            errors.append(
                f"Queue Item {fingerprint} failed review round state is invalid"
            )
            continue
        if previous_status == "reviewing" and current_status == "implementing":
            if previous_rounds >= 2:
                errors.append(
                    f"Queue Item {fingerprint} third unsuccessful review requires human-decision"
                )
            if current_rounds != min(previous_rounds + 1, 3):
                errors.append(
                    f"Queue Item {fingerprint} failedReviewRounds must increment after an unsuccessful review"
                )
        elif previous_status == "reviewing" and current_status == "human-decision":
            if current_rounds not in {previous_rounds, min(previous_rounds + 1, 3)}:
                errors.append(
                    f"Queue Item {fingerprint} human-decision has an invalid failedReviewRounds value"
                )
        elif previous_status == "human-decision" and current_status == "implementing":
            if current_rounds != 0:
                errors.append(
                    f"Queue Item {fingerprint} authorized continuation must reset failedReviewRounds"
                )
        elif current_rounds != previous_rounds:
            errors.append(
                f"Queue Item {fingerprint} failedReviewRounds changed outside review failure handling"
            )
    return errors


def queue_parent_conflicts(
    parent_states: Sequence[Mapping[str, Mapping[str, Any]]],
) -> dict[str, tuple[Mapping[str, Any], ...]]:
    """Return Queue fingerprints whose direct merge-parent states disagree."""

    return {
        fingerprint: tuple(
            state[fingerprint] for state in parent_states if fingerprint in state
        )
        for fingerprint in {
            candidate
            for state in parent_states
            for candidate in state
            if any(other.get(candidate) != state.get(candidate) for other in parent_states)
        }
    }


def validate_queue_merge_step(
    parent_states: Sequence[Mapping[str, Mapping[str, Any]]],
    current_state: Mapping[str, Mapping[str, Any]],
) -> tuple[dict[str, tuple[Mapping[str, Any], ...]], list[str]]:
    """Validate the non-conflicting portion of a merge and return conflicts."""

    conflicts = queue_parent_conflicts(parent_states)
    conflict_fingerprints = set(conflicts)
    filtered_parents = [
        {
            fingerprint: item
            for fingerprint, item in state.items()
            if fingerprint not in conflict_fingerprints
        }
        for state in parent_states
    ]
    filtered_current = {
        fingerprint: item
        for fingerprint, item in current_state.items()
        if fingerprint not in conflict_fingerprints
    }
    if not filtered_parents:
        return conflicts, []
    if any(state != filtered_parents[0] for state in filtered_parents[1:]):
        return conflicts, ["Queue merge conflict classification is inconsistent"]
    return conflicts, validate_queue_history_step(
        filtered_parents[0], filtered_current
    )


def validate_queue_reconciliation(
    fingerprint: str,
    parent_items: Sequence[Mapping[str, Any]],
    current_item: Mapping[str, Any] | None,
    direct_parent_item: Mapping[str, Any] | None,
) -> list[str]:
    """Validate a signed single-parent state that reconciles divergent merge parents."""

    if current_item is None:
        return [f"Queue Item {fingerprint} is missing from merge reconciliation"]
    if current_item == direct_parent_item:
        return [
            f"Queue Item {fingerprint} reconciliation must change the signed direct-parent state"
        ]
    immutable_roots = [
        {
            key: value
            for key, value in item.items()
            if key not in QUEUE_MUTABLE_FIELDS
        }
        for item in parent_items
    ]
    current_root = {
        key: value
        for key, value in current_item.items()
        if key not in QUEUE_MUTABLE_FIELDS
    }
    errors: list[str] = []
    if not immutable_roots or any(
        root != immutable_roots[0] for root in immutable_roots
    ):
        errors.append(f"Queue Item {fingerprint} merge parents rewrote its immutable root")
        return errors
    if current_root != immutable_roots[0]:
        errors.append(f"Queue Item {fingerprint} reconciliation rewrote its immutable root")
    if current_item.get("evaluatedVersionKey") in {
        item.get("evaluatedVersionKey") for item in parent_items
    }:
        errors.append(
            f"Queue Item {fingerprint} reconciliation requires a new evaluatedVersionKey"
        )
    for parent_item in parent_items:
        errors.extend(
            validate_queue_history_step(
                {fingerprint: parent_item}, {fingerprint: current_item}
            )
        )
    return errors


def _collect_approval_state(
    repository: Path,
    commit_sha: str,
    *,
    trusted_legacy_workspace: Path | str,
    trusted_reviewer_registry: Sequence[Mapping[str, Any]],
) -> tuple[
    list[str],
    dict[str, set[ApprovalIdentity]],
    dict[str, set[str]],
    dict[str, Mapping[str, Any]],
    dict[str, str],
]:
    """Collect only cryptographically valid approvals visible at one commit."""

    errors: list[str] = []
    identities: dict[str, set[ApprovalIdentity]] = {}
    payload_digests: dict[str, set[str]] = {}
    queue_states: dict[str, Mapping[str, Any]] = {}
    review_payloads_by_id: dict[str, str] = {}
    review_payloads_by_key: dict[str, str] = {}
    try:
        tracked_paths = list_git_files(
            repository, commit_sha, DEFAULT_ARTIFACT_ROOT.as_posix()
        )
    except EvidenceError as error:
        return [str(error)], identities, payload_digests, queue_states, {}
    if ARTIFACT_README_PATH.as_posix() not in tracked_paths:
        errors.append("canonical artifact root must contain README.md")
    for tracked_path in tracked_paths:
        if (
            tracked_path != ARTIFACT_README_PATH.as_posix()
            and not tracked_path.endswith(".json")
        ):
            errors.append(
                f"canonical artifact root contains a non-JSON tracked file: {tracked_path}"
            )
    artifact_paths = [path for path in tracked_paths if path.endswith(".json")]
    for artifact_path in artifact_paths:
        try:
            bundle = load_bundle_git(repository, commit_sha, artifact_path)
        except ContractError as error:
            errors.append(str(error))
            continue
        bundle_errors = validate_bundle(
            bundle,
            target_repository_override=repository,
            trusted_legacy_workspace=trusted_legacy_workspace,
            trusted_reviewer_registry=trusted_reviewer_registry,
            require_closed=True,
        )
        raw_reviews = bundle.get("reviewResults")
        if isinstance(raw_reviews, list):
            for review in raw_reviews:
                if not isinstance(review, Mapping):
                    continue
                payload_digest = hashlib.sha256(
                    canonical_review_payload(review)
                ).hexdigest()
                for field, registry in (
                    ("reviewResultId", review_payloads_by_id),
                    ("reviewIdempotencyKey", review_payloads_by_key),
                ):
                    identity = review.get(field)
                    if not isinstance(identity, str):
                        continue
                    previous_digest = registry.setdefault(identity, payload_digest)
                    if previous_digest != payload_digest:
                        bundle_errors.append(
                            f"{field} {identity} has a conflicting canonical signed payload across bundles"
                        )
        evaluated_snapshot = bundle.get("evaluatedSnapshot")
        if isinstance(evaluated_snapshot, Mapping) and isinstance(
            evaluated_snapshot.get("targetCommitSha"), str
        ):
            try:
                validate_git_ancestor(
                    repository,
                    evaluated_snapshot["targetCommitSha"],
                    commit_sha,
                )
            except EvidenceError:
                bundle_errors.append(
                    "evaluated targetCommitSha must be an ancestor of the gate commit"
                )
        errors.extend(f"{artifact_path}: {error}" for error in bundle_errors)
        if bundle_errors:
            continue
        for rule in bundle.get("ruleCards", []):
            if not isinstance(rule, Mapping):
                continue
            rule_path = rule.get("rulePath")
            payload = rule.get("rulePayload")
            approval_refs = rule.get("approvalReviewRefs")
            if (
                not isinstance(rule_path, str)
                or not isinstance(payload, Mapping)
                or payload.get("status") != "approved"
                or not isinstance(approval_refs, list)
            ):
                continue
            payload_digest = rule_payload_digest(payload)
            identities.setdefault(rule_path, set()).add(
                (
                    rule_path,
                    str(rule.get("approvalCommit")),
                    payload_digest,
                    tuple(sorted(approval_refs)),
                    tuple(sorted(rule.get("approvedBy", []))),
                )
            )
            payload_digests.setdefault(rule_path, set()).add(payload_digest)
        for item in bundle.get("queueItems", []):
            if not isinstance(item, Mapping) or not isinstance(
                item.get("fingerprint"), str
            ):
                continue
            fingerprint = item["fingerprint"]
            previous = queue_states.setdefault(fingerprint, item)
            if previous != item:
                errors.append(
                    f"Queue Item {fingerprint} has conflicting current states across bundles"
                )
    signed_review_payloads = {
        **{f"reviewResultId:{key}": value for key, value in review_payloads_by_id.items()},
        **{f"reviewIdempotencyKey:{key}": value for key, value in review_payloads_by_key.items()},
    }
    return errors, identities, payload_digests, queue_states, signed_review_payloads


def _load_optional_policy(
    repository: Path, commit_sha: str
) -> Mapping[str, Any] | None:
    if not git_path_exists(repository, commit_sha, POLICY_PATH.as_posix()):
        return None
    return load_policy(repository, commit_sha)


def parse_codeowners_rules(
    content: str,
) -> tuple[list[tuple[str, tuple[str, ...]]], list[str]]:
    rules: list[tuple[str, tuple[str, ...]]] = []
    errors: list[str] = []
    for line_number, line in enumerate(content.splitlines(), start=1):
        stripped = line.split("#", 1)[0].strip()
        if not stripped or stripped.startswith("#"):
            continue
        fields = stripped.split()
        pattern = fields[0]
        owners = tuple(fields[1:])
        normalized = pattern.strip("/")
        segments = normalized.split("/")
        if (
            pattern.startswith("!")
            or any(character in pattern for character in "[]\\")
            or "***" in pattern
            or pattern in {"", "/"}
            or any(not segment for segment in segments)
            or any("**" in segment and segment != "**" for segment in segments)
        ):
            errors.append(
                f"CODEOWNERS line {line_number} uses an unsupported pattern"
            )
            continue
        if any(
            CODEOWNER_ACCOUNT_PATTERN.fullmatch(owner) is None
            and CODEOWNER_EMAIL_PATTERN.fullmatch(owner) is None
            for owner in owners
        ):
            errors.append(f"CODEOWNERS line {line_number} has an invalid owner")
            continue
        rules.append((pattern, owners))
    return rules, errors


def _codeowners_pattern_matches(pattern: str, path: str) -> bool:
    rooted = pattern.startswith("/")
    directory_pattern = pattern.endswith("/")
    normalized = pattern.lstrip("/").rstrip("/")
    segments = normalized.split("/")
    if any(not segment for segment in segments) or any(
        "**" in segment and segment != "**" for segment in segments
    ):
        return False
    has_internal_slash = "/" in normalized
    prefix = "^" if rooted or has_internal_slash else r"(?:^|.*/)"
    expression: list[str] = [prefix]
    index = 0
    while index < len(normalized):
        character = normalized[index]
        if character == "*":
            if index + 1 < len(normalized) and normalized[index + 1] == "*":
                index += 2
                if index < len(normalized) and normalized[index] == "/":
                    expression.append(r"(?:.*/)?")
                    index += 1
                else:
                    expression.append(".*")
                continue
            expression.append(r"[^/]*")
        elif character == "?":
            expression.append(r"[^/]")
        else:
            expression.append(re.escape(character))
        index += 1
    last_segment = normalized.rsplit("/", 1)[-1]
    if directory_pattern or not any(character in last_segment for character in "*?"):
        expression.append(r"(?:/.*)?")
    expression.append("$")
    return re.match("".join(expression), path) is not None


def validate_governance_coverage(
    repository: Path, commit_sha: str, policy: Mapping[str, Any]
) -> list[str]:
    errors: list[str] = []
    try:
        tracked_scripts = list_git_files(repository, commit_sha, "scripts")
    except EvidenceError:
        return ["tracked scripts cannot be resolved for the closed module registry"]
    unregistered_modules = sorted(
        path
        for path in tracked_scripts
        if path.endswith(".py") and path not in policy["judgePaths"]
    )
    if unregistered_modules:
        errors.append(
            "policy judgePaths must enumerate every tracked Python gate/test module: "
            + ", ".join(unregistered_modules)
        )

    codeowners_path = CODEOWNERS_PATH.as_posix()
    if codeowners_path not in policy["judgePaths"]:
        return errors
    try:
        raw_codeowners = read_git_evidence(repository, commit_sha, codeowners_path)
        workflow_paths = list_git_files(
            repository, commit_sha, ".github/workflows"
        )
        if len(raw_codeowners) >= MAX_CODEOWNERS_BYTES:
            return errors + ["immutable CODEOWNERS exceeds GitHub's size limit"]
        codeowners_content = raw_codeowners.decode("utf-8")
    except (EvidenceError, UnicodeError):
        return errors + ["immutable CODEOWNERS coverage cannot be resolved"]
    rules, codeowners_errors = parse_codeowners_rules(codeowners_content)
    errors.extend(codeowners_errors)
    protected_paths = dict.fromkeys(
        [
            *policy["rulebookPaths"],
            *policy["judgePaths"],
            *workflow_paths,
            codeowners_path,
            CODEOWNERS_BOOTSTRAP_PATH,
        ]
    )
    for path in protected_paths:
        owners: tuple[str, ...] = ()
        for pattern, candidate_owners in rules:
            if _codeowners_pattern_matches(pattern, path):
                owners = candidate_owners
        if "@NIV49" not in owners:
            errors.append(f"CODEOWNERS does not protect governance path {path}")
    return errors


def validate_repository_artifacts(
    repository: Path | str,
    commit_sha: str,
    *,
    trusted_legacy_workspace: Path | str = DEFAULT_LEGACY_WORKSPACE,
    trusted_policy_commit: str | None = None,
) -> list[str]:
    """Validate current artifacts plus append-only trust and activation history."""

    if trusted_policy_commit is None:
        return [
            "repository artifact validation requires an explicit trusted policy anchor"
        ]
    try:
        canonical_repository = canonical_repository_path(repository)
        _require_full_sha(commit_sha, "commitSha")
        parents = commit_parents(canonical_repository, commit_sha)
        anchor_commit = trusted_policy_commit
        _require_full_sha(anchor_commit, "trustedPolicyCommit")
        validate_git_ancestor(canonical_repository, anchor_commit, commit_sha)
        policy = load_policy(canonical_repository, commit_sha)
    except (EvidenceError, ContractError) as error:
        return [f"immutable repository artifact policy cannot be resolved: {error}"]

    errors: list[str] = []
    for policy_path in policy["rulebookPaths"] + policy["judgePaths"]:
        try:
            read_git_evidence(canonical_repository, commit_sha, policy_path)
        except EvidenceError:
            errors.append(
                "current policy contains an unresolved or unsafe registered path"
            )
    errors.extend(validate_governance_coverage(canonical_repository, commit_sha, policy))
    try:
        anchor_policy = _load_optional_policy(canonical_repository, anchor_commit)
    except (EvidenceError, ContractError) as error:
        return [f"trusted policy anchor is present but invalid: {error}"]
    trusted_reviewers: Sequence[Mapping[str, Any]] = []
    if anchor_policy is None:
        if policy["trustedReviewers"]:
            errors.append(
                "trusted reviewer bootstrap requires an external trusted policy anchor"
            )
    else:
        trusted_reviewers = anchor_policy["trustedReviewers"]
        for field in (
            "targetRepositoryId",
            "canonicalRepositoryPath",
            "trustedReviewers",
        ):
            if policy[field] != anchor_policy[field]:
                errors.append(
                    f"current policy {field} does not match the trusted policy anchor"
                )

    try:
        policy_history = [anchor_commit] + commits_touching_paths(
            canonical_repository,
            anchor_commit,
            commit_sha,
            [POLICY_PATH.as_posix()],
        )
        artifact_history = [anchor_commit] + commits_touching_paths(
            canonical_repository,
            anchor_commit,
            commit_sha,
            [DEFAULT_ARTIFACT_ROOT.as_posix()],
        )
    except EvidenceError as error:
        errors.append(f"trusted policy history cannot be resolved: {error}")
        return errors

    cumulative_rulebook_paths: set[str] = set()
    cumulative_judge_paths: set[str] = set()
    for history_commit in dict.fromkeys([*policy_history, commit_sha]):
        try:
            history_policy = _load_optional_policy(canonical_repository, history_commit)
        except (EvidenceError, ContractError):
            errors.append("historical payment modernization policy is invalid")
            continue
        if history_policy is None:
            if history_commit != anchor_commit:
                errors.append("historical payment modernization policy is invalid")
            continue
        if anchor_policy is not None:
            for field in (
                "targetRepositoryId",
                "canonicalRepositoryPath",
                "trustedReviewers",
            ):
                if history_policy[field] != anchor_policy[field]:
                    errors.append(
                        f"historical policy {field} changed without a trusted rotation protocol"
                    )
        elif history_policy["trustedReviewers"]:
            errors.append(
                "historical policy introduced trusted reviewers without an external anchor"
            )
        cumulative_rulebook_paths.update(history_policy["rulebookPaths"])
        cumulative_judge_paths.update(history_policy["judgePaths"])
    if not cumulative_rulebook_paths.issubset(policy["rulebookPaths"]):
        errors.append("current policy cannot remove historical rulebookPaths")
    if not cumulative_judge_paths.issubset(policy["judgePaths"]):
        errors.append("current policy cannot remove historical judgePaths")

    registered_payloads, payload_errors = _load_registered_rule_payloads(
        canonical_repository, commit_sha, policy
    )
    errors.extend(payload_errors)
    (
        current_errors,
        current_identities,
        current_digests,
        _current_queue_states,
        _current_review_payloads,
    ) = _collect_approval_state(
        canonical_repository,
        commit_sha,
        trusted_legacy_workspace=trusted_legacy_workspace,
        trusted_reviewer_registry=trusted_reviewers,
    )
    errors.extend(current_errors)

    historical_identities: dict[str, set[ApprovalIdentity]] = {}
    historical_digests: dict[str, set[str]] = {}
    historical_review_payloads: dict[str, str] = {}
    state_cache: dict[
        str,
        tuple[
            dict[str, set[ApprovalIdentity]],
            dict[str, set[str]],
            dict[str, Mapping[str, Any]],
            dict[str, str],
        ],
    ] = {}
    invalid_history_commits: set[str] = set()
    pending_queue_conflicts: dict[
        str, dict[str, tuple[Mapping[str, Any], ...]]
    ] = {}

    def collect_history_state(
        history_sha: str,
    ) -> tuple[
        dict[str, set[ApprovalIdentity]],
        dict[str, set[str]],
        dict[str, Mapping[str, Any]],
        dict[str, str],
    ]:
        cached = state_cache.get(history_sha)
        if cached is not None:
            return cached
        if history_sha == anchor_commit and anchor_policy is None:
            empty_state: tuple[
                dict[str, set[ApprovalIdentity]],
                dict[str, set[str]],
                dict[str, Mapping[str, Any]],
                dict[str, str],
            ] = ({}, {}, {}, {})
            state_cache[history_sha] = empty_state
            return empty_state
        try:
            history_policy = _load_optional_policy(canonical_repository, history_sha)
        except (EvidenceError, ContractError) as error:
            errors.append(
                f"historical artifact commit {history_sha} has an invalid policy: {error}"
            )
            invalid_history_commits.add(history_sha)
            empty_state = ({}, {}, {}, {})
            state_cache[history_sha] = empty_state
            return empty_state
        if history_policy is None:
            errors.append(
                f"historical artifact commit {history_sha} has no resolvable policy"
            )
            invalid_history_commits.add(history_sha)
            empty_state = ({}, {}, {}, {})
            state_cache[history_sha] = empty_state
            return empty_state
        (
            history_errors,
            identities,
            digests,
            queue_states,
            signed_review_payloads,
        ) = _collect_approval_state(
            canonical_repository,
            history_sha,
            trusted_legacy_workspace=trusted_legacy_workspace,
            trusted_reviewer_registry=trusted_reviewers,
        )
        errors.extend(
            f"historical artifact commit {history_sha}: {error}"
            for error in history_errors
        )
        state = (identities, digests, queue_states, signed_review_payloads)
        state_cache[history_sha] = state
        return state

    for history_commit in dict.fromkeys([*artifact_history, commit_sha]):
        identities, digests, queue_states, signed_review_payloads = (
            collect_history_state(history_commit)
        )
        if history_commit in invalid_history_commits:
            continue
        for identity, payload_digest in signed_review_payloads.items():
            prior_digest = historical_review_payloads.setdefault(
                identity, payload_digest
            )
            if prior_digest != payload_digest:
                errors.append(
                    f"historical signed Review Result identity {identity} was reused with different content"
                )
        flattened_identities = {
            identity for values in identities.values() for identity in values
        }
        activation_parents = (
            []
            if history_commit == anchor_commit
            else commit_parents(canonical_repository, history_commit)
        )
        parent_states: list[
            tuple[
                dict[str, set[ApprovalIdentity]],
                dict[str, set[str]],
                dict[str, Mapping[str, Any]],
                dict[str, str],
            ]
        ] = []
        for parent in activation_parents:
            try:
                validate_git_ancestor(canonical_repository, anchor_commit, parent)
            except EvidenceError:
                errors.append(
                    f"historical merge parent {parent} is outside the trusted policy anchor"
                )
                continue
            parent_states.append(collect_history_state(parent))
        parent_identities = {
            identity
            for parent_identities_by_path, _, _, _ in parent_states
            for values in parent_identities_by_path.values()
            for identity in values
        }
        new_identities = flattened_identities - parent_identities
        if history_commit != anchor_commit and new_identities:
            if len(activation_parents) != 1:
                errors.append(
                    f"approval activation commit {history_commit} must have exactly one parent"
                )
            changed_paths = changed_paths_for_commit(
                canonical_repository, history_commit
            )
            allowed_prefix = DEFAULT_ARTIFACT_ROOT.as_posix() + "/"
            if any(
                not path.startswith(allowed_prefix) or not path.endswith(".json")
                for path in changed_paths
            ):
                errors.append(
                    f"approval activation commit {history_commit} contains changes outside canonical JSON envelopes"
                )

        parent_queue_states = [state[2] for state in parent_states]
        if history_commit != anchor_commit:
            if len(parent_queue_states) == 1:
                errors.extend(
                    validate_queue_history_step(parent_queue_states[0], queue_states)
                )
            elif len(parent_queue_states) > 1:
                first_parent_state = parent_queue_states[0]
                if all(state == first_parent_state for state in parent_queue_states[1:]):
                    errors.extend(
                        validate_queue_history_step(first_parent_state, queue_states)
                    )
                else:
                    merge_conflicts, merge_errors = validate_queue_merge_step(
                        parent_queue_states, queue_states
                    )
                    pending_queue_conflicts[history_commit] = merge_conflicts
                    errors.extend(merge_errors)

        if len(activation_parents) == 1 and pending_queue_conflicts:
            for merge_sha, conflicts in list(pending_queue_conflicts.items()):
                try:
                    validate_git_ancestor(
                        canonical_repository, merge_sha, history_commit
                    )
                except EvidenceError:
                    continue
                unresolved: dict[str, tuple[Mapping[str, Any], ...]] = {}
                for fingerprint, parent_items in conflicts.items():
                    current_item = queue_states.get(fingerprint)
                    if validate_queue_reconciliation(
                        fingerprint,
                        parent_items,
                        current_item,
                        parent_queue_states[0].get(fingerprint),
                    ):
                        unresolved[fingerprint] = parent_items
                if unresolved:
                    pending_queue_conflicts[merge_sha] = unresolved
                else:
                    del pending_queue_conflicts[merge_sha]

        for rule_path, values in identities.items():
            historical_identities.setdefault(rule_path, set()).update(values)
        for rule_path, values in digests.items():
            historical_digests.setdefault(rule_path, set()).update(values)

    for merge_sha, conflicts in pending_queue_conflicts.items():
        errors.append(
            f"Queue history merge {merge_sha} has conflicting parent states for {sorted(conflicts)} and requires a subsequent single-parent reconciliation"
        )

    for activated_path in set(historical_identities) - set(registered_payloads):
        errors.append(
            f"effective approved Rule Card {activated_path} cannot be removed without a trusted retirement protocol"
        )
    for rule_path, current_payload in registered_payloads.items():
        current_digest = rule_payload_digest(current_payload)
        history_approvals = historical_identities.get(rule_path, set())
        current_approvals = current_identities.get(rule_path, set())
        if history_approvals and (
            current_payload.get("status") != "approved"
            or historical_digests.get(rule_path) != {current_digest}
        ):
            errors.append(
                f"effective approved Rule Card {rule_path} cannot be downgraded without a trusted retirement protocol"
            )
        if current_payload.get("status") == "approved" and (
            len(history_approvals) != 1
            or len(current_approvals) != 1
            or current_digests.get(rule_path) != {current_digest}
        ):
            errors.append(
                f"approved policy Rule Card {rule_path} requires exactly one unique valid signed approval envelope"
            )
    return errors


def parse_args(arguments: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate signed payment-modernization v2 artifact bundles."
    )
    parser.add_argument(
        "bundle",
        nargs="*",
        type=Path,
        help="Explicit JSON bundles; omit to scan the fixed repository artifact root",
    )
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=REPOSITORY_ROOT,
        help="Runtime checkout mapped to the immutable policy targetRepositoryId",
    )
    parser.add_argument(
        "--commit",
        help="Full commit to scan through Git objects; defaults to repository HEAD",
    )
    parser.add_argument(
        "--trusted-legacy-workspace",
        type=Path,
        default=DEFAULT_LEGACY_WORKSPACE,
        help="Trusted containment root for direct-child legacy repositories",
    )
    parser.add_argument(
        "--trusted-policy-commit",
        help="External full-SHA trust anchor for policy identity and reviewer keys",
    )
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    options = parse_args(sys.argv[1:] if arguments is None else arguments)
    try:
        bundle_paths = list(options.bundle)
        if bundle_paths:
            all_errors: list[str] = []
            repository_override = (
                options.repository_root
                if options.repository_root != REPOSITORY_ROOT
                else None
            )
            for bundle_path in bundle_paths:
                bundle = load_bundle_file(bundle_path)
                all_errors.extend(
                    validate_bundle(
                        bundle,
                        target_repository_override=repository_override,
                        trusted_legacy_workspace=options.trusted_legacy_workspace,
                    )
                )
        else:
            if not options.trusted_policy_commit:
                raise ContractError(
                    "repository scan requires an external --trusted-policy-commit anchor"
                )
            commit_sha = options.commit or resolve_head_commit(options.repository_root)
            _require_full_sha(commit_sha, "--commit")
            all_errors = validate_repository_artifacts(
                options.repository_root,
                commit_sha,
                trusted_legacy_workspace=options.trusted_legacy_workspace,
                trusted_policy_commit=options.trusted_policy_commit,
            )
    except ContractError as error:
        print(f"Artifact contract check failed: {error}", file=sys.stderr)
        return 1
    if all_errors:
        for error in all_errors:
            print(error, file=sys.stderr)
        return 1
    print("Modernization artifact contract check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
