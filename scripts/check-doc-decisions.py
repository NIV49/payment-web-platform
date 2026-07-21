#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from pathlib import Path


DECISION_DOCUMENTS = {
    "IAM-GLOBAL-USER-MULTI-TENANT": (
        "docs/permission-refactor-product-requirements.md",
        "docs/new-payment-system-target-architecture.md",
        "docs/ai-context/permission/01-current-state.md",
        "docs/ai-context/permission/09-migration-plan.md",
        "docs/ai-context/known-deviations.md",
    ),
}
ALLOWED_STATUSES = {"accepted", "pending", "superseded"}
MARKER_PATTERN = re.compile(r"<!--\s*decision-status\s+(?P<attributes>.*?)\s*-->")
ATTRIBUTE_PATTERN = re.compile(r"(?P<name>[a-z]+)=(?P<value>[^\s]+)")
ACCEPTED_ADR_PATTERN = re.compile(r"^Status:\s*accepted\.\s*$", re.MULTILINE)


def parse_markers(document: Path) -> list[dict[str, str]]:
    content = document.read_text(encoding="utf-8")
    markers: list[dict[str, str]] = []
    for match in MARKER_PATTERN.finditer(content):
        attributes = {
            item.group("name"): item.group("value")
            for item in ATTRIBUTE_PATTERN.finditer(match.group("attributes"))
        }
        markers.append(attributes)
    return markers


def validate_reference(
    repo_root: Path,
    decision_id: str,
    status: str,
    reference: str,
) -> list[str]:
    if status == "pending":
        if reference != "none":
            return [f"{decision_id}: pending decision must use ref=none"]
        return []

    if reference == "none":
        return [f"{decision_id}: {status} decision must reference its evidence"]

    reference_path = Path(reference)
    if reference_path.is_absolute() or ".." in reference_path.parts:
        return [f"{decision_id}: ref must be a repository-relative path: {reference}"]

    evidence = (repo_root / reference_path).resolve()
    try:
        evidence.relative_to(repo_root)
    except ValueError:
        return [f"{decision_id}: ref escapes repository root: {reference}"]

    if not evidence.is_file():
        return [f"{decision_id}: ref does not exist: {reference}"]

    if status == "accepted" and not ACCEPTED_ADR_PATTERN.search(
        evidence.read_text(encoding="utf-8")
    ):
        return [f"{decision_id}: accepted ref is not an accepted ADR: {reference}"]

    return []


def validate_decision(
    repo_root: Path,
    decision_id: str,
    documents: tuple[str, ...],
) -> list[str]:
    errors: list[str] = []
    states: set[tuple[str, str]] = set()

    for relative_path in documents:
        document = repo_root / relative_path
        if not document.is_file():
            errors.append(f"{decision_id}: required document is missing: {relative_path}")
            continue

        matching = [
            marker
            for marker in parse_markers(document)
            if marker.get("id") == decision_id
        ]
        if len(matching) != 1:
            errors.append(
                f"{decision_id}: expected exactly one marker in {relative_path}, "
                f"found {len(matching)}"
            )
            continue

        marker = matching[0]
        missing = {"id", "status", "ref"} - marker.keys()
        if missing:
            errors.append(
                f"{decision_id}: marker in {relative_path} is missing "
                f"{', '.join(sorted(missing))}"
            )
            continue

        status = marker["status"]
        reference = marker["ref"]
        if status not in ALLOWED_STATUSES:
            errors.append(
                f"{decision_id}: unsupported status in {relative_path}: {status}"
            )
            continue
        states.add((status, reference))

    if len(states) > 1:
        rendered = ", ".join(
            f"status={status} ref={reference}" for status, reference in sorted(states)
        )
        errors.append(f"{decision_id}: inconsistent decision states: {rendered}")
    elif len(states) == 1:
        status, reference = next(iter(states))
        errors.extend(validate_reference(repo_root, decision_id, status, reference))

    return errors


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    errors: list[str] = []
    for decision_id, documents in DECISION_DOCUMENTS.items():
        errors.extend(validate_decision(repo_root, decision_id, documents))

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"Documentation decision check failed with {len(errors)} problem(s).",
            file=sys.stderr,
        )
        return 1

    print("Documentation decision states are consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
