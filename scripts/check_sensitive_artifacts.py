#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path


DEFAULT_TARGETS = (".agents", "docs", "AGENTS.md", "README.md")
MAX_TEXT_FILE_BYTES = 5 * 1024 * 1024
SAFE_LITERAL_VALUES = {
    "cookie-session",
    "disabled",
    "false",
    "masked",
    "none",
    "null",
    "redacted",
}
SAFE_EMAIL_ADDRESSES = {"git@github.com"}
SAFE_EMAIL_SUFFIXES = (".example", ".invalid", ".test")
APPROVED_FINDING_HASHES = {
    (
        "README.md",
        60,
        "INLINE_CREDENTIAL_PAIR",
        "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d",
    ): "Loopback-only admin identity development fixture",
    (
        "README.md",
        71,
        "GENERIC_SECRET_ASSIGNMENT",
        "7ec5c1aa2c76dee076328709c7c0816a6afe1d0dccbec0d7df3a72aea9c72431",
    ): "Loopback-only PostgreSQL development fixture documented for Navicat",
    (
        "docs/ai-context/backend/README.md",
        299,
        "USER_PASSWORD_PAIR",
        "7ec5c1aa2c76dee076328709c7c0816a6afe1d0dccbec0d7df3a72aea9c72431",
    ): "Loopback-only PostgreSQL development fixture context",
    (
        "docs/ai-context/backend/README.md",
        310,
        "INLINE_CREDENTIAL_PAIR",
        "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d",
    ): "Loopback-only admin identity development fixture context",
    (
        "docs/ai-contract/identity-admin-api-contract.md",
        281,
        "DOCUMENTED_PASSWORD",
        "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d",
    ): "Local-profile API contract fixture",
}
PLACEHOLDER_PATTERN = re.compile(
    r"(?:\$\{[A-Z][A-Z0-9_]*\}|\{\{[A-Z][A-Z0-9_]*\}\}|<[A-Z][A-Z0-9_]*>)"
)
GENERIC_SECRET_ASSIGNMENT_PATTERN = re.compile(
    r"\b(?:api[_-]?key|client[_-]?secret|password|passwd|"
    r"access[_-]?token|refresh[_-]?token|auth[_-]?token)\b"
    r"\s*[:=]\s*(?P<quote>[\"'])(?P<value>.*?)(?P=quote)",
    re.IGNORECASE,
)
UNQUOTED_SECRET_ASSIGNMENT_PATTERN = re.compile(
    r"\b(?:api[_-]?key|client[_-]?secret|password|passwd|"
    r"access[_-]?token|refresh[_-]?token|auth[_-]?token)\b"
    r"\s*[:=]\s*(?P<value>(?![\"'])[^\s,;#`]+)",
    re.IGNORECASE,
)
INLINE_CREDENTIAL_PAIR_PATTERN = re.compile(
    r"\b[A-Za-z0-9._-]{3,}\s+/\s+(?P<value>[^\s`,;，。]{8,})"
)
USER_PASSWORD_PAIR_PATTERN = re.compile(
    r"用户/密码\s*`?[^`\s/]+\s*/\s*(?P<value>[^`\s，。；;]+)"
)
DOCUMENTED_PASSWORD_PATTERN = re.compile(
    r"(?:默认)?(?:开发)?密码(?:为|[:：])\s*`(?P<value>[^`\s，。；;]{4,})`"
)
CREDENTIAL_URI_PATTERN = re.compile(
    r"\b(?:amqps?|mongodb(?:\+srv)?|mysql|postgres(?:ql)?|redis)://"
    r"[^\s:/]+:(?P<value>[^@\s/]+)@",
    re.IGNORECASE,
)
EMAIL_ADDRESS_PATTERN = re.compile(
    r"\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
    r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
    r"\.[A-Za-z]{2,63}\b"
)
CN_MOBILE_NUMBER_PATTERN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
PAYMENT_CARD_CANDIDATE_PATTERN = re.compile(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)")
HIGH_CONFIDENCE_PATTERNS = (
    (
        "PRIVATE_KEY",
        re.compile(r"-----BEGIN (?:EC |OPENSSH |RSA )?PRIVATE KEY-----"),
    ),
    ("AWS_ACCESS_KEY", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("GITHUB_TOKEN", re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b")),
    ("SLACK_TOKEN", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("STRIPE_LIVE_SECRET", re.compile(r"\b(?:rk|sk)_live_[A-Za-z0-9]{12,}\b")),
    (
        "JWT",
        re.compile(
            r"\beyJ[A-Za-z0-9_-]{8,}\."
            r"[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"
        ),
    ),
)


def is_safe_placeholder(value: str) -> bool:
    stripped = value.strip()
    return (
        stripped.casefold() in SAFE_LITERAL_VALUES
        or PLACEHOLDER_PATTERN.fullmatch(stripped) is not None
    )


def is_safe_email(value: str) -> bool:
    lowered = value.casefold()
    domain = lowered.rsplit("@", 1)[-1]
    return lowered in SAFE_EMAIL_ADDRESSES or domain.endswith(SAFE_EMAIL_SUFFIXES)


def passes_luhn(value: str) -> bool:
    digits = [int(character) for character in value if character.isdigit()]
    if not 13 <= len(digits) <= 19:
        return False
    total = 0
    for index, digit in enumerate(reversed(digits)):
        if index % 2 == 1:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
    return total % 10 == 0


def looks_like_password(value: str) -> bool:
    return (
        any(character.islower() for character in value)
        and any(character.isupper() for character in value)
        and any(character.isdigit() for character in value)
        and any(not character.isalnum() for character in value)
    )


def is_approved_finding(
    path: str,
    line_number: int,
    rule_id: str,
    value: str,
) -> bool:
    digest = hashlib.sha256(value.strip().encode("utf-8")).hexdigest()
    return (path, line_number, rule_id, digest) in APPROVED_FINDING_HASHES


def display_path(path: Path, repository: Path) -> str:
    try:
        return str(path.relative_to(repository))
    except ValueError:
        return str(path)


def collect_files(
    repository: Path,
    targets: tuple[str, ...],
) -> tuple[list[Path], list[str]]:
    files: list[Path] = []
    errors: list[str] = []
    for target in targets:
        path = repository / target
        label = display_path(path, repository)
        if path.is_symlink():
            errors.append(f"{label}: SYMLINK: sensitive-scan targets must not be links")
            continue
        try:
            path.resolve().relative_to(repository)
        except ValueError:
            errors.append(
                f"{label}: OUTSIDE_REPOSITORY: sensitive-scan target escapes the repository"
            )
            continue
        if not path.exists():
            errors.append(f"{label}: MISSING_TARGET: sensitive-scan target is missing")
            continue
        if path.is_file():
            files.append(path)
            continue

        for entry in sorted(path.rglob("*")):
            entry_label = display_path(entry, repository)
            if entry.is_symlink():
                errors.append(
                    f"{entry_label}: SYMLINK: sensitive-scan targets must not contain links"
                )
            elif entry.is_file():
                files.append(entry)
    return files, errors


def scan_file(path: Path, repository: Path) -> list[str]:
    label = display_path(path, repository)
    if path.stat().st_size > MAX_TEXT_FILE_BYTES:
        return [
            f"{label}: OVERSIZE: file exceeds {MAX_TEXT_FILE_BYTES} bytes and was not scanned"
        ]

    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return [f"{label}: NON_UTF8: artifact requires an approved binary scanner"]

    errors: list[str] = []
    for line_number, line in enumerate(content.splitlines(), start=1):
        for rule_id, pattern in HIGH_CONFIDENCE_PATTERNS:
            if pattern.search(line):
                errors.append(f"{label}:{line_number}: {rule_id}")

        for match in GENERIC_SECRET_ASSIGNMENT_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "GENERIC_SECRET_ASSIGNMENT",
                value,
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")

        for match in UNQUOTED_SECRET_ASSIGNMENT_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "GENERIC_SECRET_ASSIGNMENT",
                value,
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")

        for match in INLINE_CREDENTIAL_PAIR_PATTERN.finditer(line):
            value = match.group("value")
            if looks_like_password(value) and not is_approved_finding(
                label,
                line_number,
                "INLINE_CREDENTIAL_PAIR",
                value,
            ):
                errors.append(f"{label}:{line_number}: INLINE_CREDENTIAL_PAIR")

        for match in USER_PASSWORD_PAIR_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "USER_PASSWORD_PAIR",
                value,
            ):
                errors.append(f"{label}:{line_number}: USER_PASSWORD_PAIR")

        for match in DOCUMENTED_PASSWORD_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "DOCUMENTED_PASSWORD",
                value,
            ):
                errors.append(f"{label}:{line_number}: DOCUMENTED_PASSWORD")

        for match in CREDENTIAL_URI_PATTERN.finditer(line):
            if not is_safe_placeholder(match.group("value")):
                errors.append(f"{label}:{line_number}: CREDENTIAL_URI")

        for match in EMAIL_ADDRESS_PATTERN.finditer(line):
            if not is_safe_email(match.group(0)):
                errors.append(f"{label}:{line_number}: EMAIL_ADDRESS")

        if CN_MOBILE_NUMBER_PATTERN.search(line):
            errors.append(f"{label}:{line_number}: CN_MOBILE_NUMBER")

        for match in PAYMENT_CARD_CANDIDATE_PATTERN.finditer(line):
            if passes_luhn(match.group(0)):
                errors.append(f"{label}:{line_number}: PAYMENT_CARD_NUMBER")
    return errors


def scan_repository(
    repository: Path,
    targets: tuple[str, ...] = DEFAULT_TARGETS,
) -> list[str]:
    repository = repository.resolve()
    files, errors = collect_files(repository, targets)
    for path in files:
        errors.extend(scan_file(path, repository))
    return errors


def main() -> int:
    repository = Path(__file__).resolve().parent.parent
    targets = tuple(sys.argv[1:]) or DEFAULT_TARGETS
    errors = scan_repository(repository, targets)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"Sensitive artifact check failed with {len(errors)} problem(s).",
            file=sys.stderr,
        )
        return 1

    print(f"Sensitive artifact scan passed for {len(targets)} target(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
