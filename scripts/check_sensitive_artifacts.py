#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml
from yaml.nodes import MappingNode, Node, ScalarNode, SequenceNode


DEFAULT_TARGETS = (".agents", "docs", "AGENTS.md", "README.md")
TRACKED_ARTIFACT_DIRECTORY_NAMES = {
    "__fixtures__",
    "__snapshots__",
    "evidence",
    "evidences",
    "fixture",
    "fixtures",
    "snapshots",
    "test-data",
    "test-fixtures",
    "testdata",
}
TRACKED_ARTIFACT_FILENAME_MARKERS = (
    ".evidence.",
    ".fixture.",
    ".payload.",
    ".snapshot.",
    ".trace.",
)
TRACKED_TEST_ROOT_NAMES = {"__tests__", "test", "tests"}
TRACKED_TEST_ASSET_SUFFIXES = {
    ".bin",
    ".cfg",
    ".conf",
    ".csv",
    ".env",
    ".har",
    ".http",
    ".json",
    ".log",
    ".md",
    ".pem",
    ".properties",
    ".snap",
    ".sql",
    ".txt",
    ".toml",
    ".xml",
    ".yaml",
    ".yml",
}
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
APPROVED_FINDING_CONTEXTS = {
    ("README.md", 60, "INLINE_CREDENTIAL_PAIR"): (
        "exact_line",
        "518f9e3632b794406cbfcf8ad00bebabc838f55dd1063faf347856f674af8a12",
    ),
    ("README.md", 71, "GENERIC_SECRET_ASSIGNMENT"): (
        "host_block",
        "2424bdd7f718ac372ec02673eb89a31ccd105ca26c0454ce21affe6032006bb1",
    ),
    (
        "docs/ai-context/backend/README.md",
        299,
        "USER_PASSWORD_PAIR",
    ): (
        "exact_line",
        "6f9ae979a5eb1d4af1843c938021cf985ed0f8fdba2229c80fb0cd0b7f84efa1",
    ),
    (
        "docs/ai-context/backend/README.md",
        310,
        "INLINE_CREDENTIAL_PAIR",
    ): (
        "exact_line",
        "13accd0745652975ecd5204ad0aa1959706c015a75e162556525d0fa3c1beb49",
    ),
    (
        "docs/ai-contract/identity-admin-api-contract.md",
        281,
        "DOCUMENTED_PASSWORD",
    ): (
        "exact_line",
        "c861e4650c36f90feccd9183f843cae3a1cc9ed83d691b2120ec3117f34103a1",
    ),
}
PLACEHOLDER_PATTERN = re.compile(
    r"(?:\$\{[A-Z][A-Z0-9_]*\}|\{\{[A-Z][A-Z0-9_]*\}\}|<[A-Z][A-Z0-9_]*>)"
)
MASK_PATTERN = re.compile(r"(?:\*{3,}|x{3,}|•{3,})", re.IGNORECASE)
SENSITIVE_KEY_PATTERN = (
    r"(?:[A-Za-z][A-Za-z0-9]*[_.-])*"
    r"(?:api[_-]?key|client[_-]?secret|secret|password|passwd|token|"
    r"access[_-]?token|refresh[_-]?token|auth[_-]?token|"
    r"aws[_-]?secret[_-]?access[_-]?key)"
)
SECRET_ASSIGNMENT_PREFIX = (
    rf"(?<![A-Za-z0-9_])(?:[\"']?){SENSITIVE_KEY_PATTERN}(?:[\"']?)"
    r"\s*[:=]\s*"
)
GENERIC_SECRET_ASSIGNMENT_PATTERN = re.compile(
    SECRET_ASSIGNMENT_PREFIX + r"(?P<quote>[\"'`])(?P<value>.*?)(?P=quote)",
    re.IGNORECASE,
)
UNQUOTED_SECRET_ASSIGNMENT_PATTERN = re.compile(
    SECRET_ASSIGNMENT_PREFIX + r"(?P<value>(?![\"'`])[^\s,;]+)",
    re.IGNORECASE,
)
INLINE_CREDENTIAL_PAIR_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_.@-])(?P<user>[A-Za-z0-9_.@-]{3,128})\s+/\s+"
    r"(?P<value>[^\s`,;，。]{4,})"
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
HOST_FIELD_PATTERN = re.compile(
    r"^\s*(?:Host|主机)\s*[:：]\s*(?P<host>[^\s`]+)",
    re.IGNORECASE,
)
COMMON_ACCOUNT_NAMES = {
    "admin",
    "administrator",
    "root",
    "user",
    "username",
}
COMMON_WEAK_PASSWORDS = {
    "123456",
    "12345678",
    "admin123",
    "changeme",
    "letmein",
    "password",
    "password1",
    "password123",
    "qwerty",
    "qwerty123",
    "welcome",
}
FULL_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")


def is_safe_placeholder(value: str) -> bool:
    stripped = value.strip()
    return (
        stripped.casefold() in SAFE_LITERAL_VALUES
        or PLACEHOLDER_PATTERN.fullmatch(stripped) is not None
        or MASK_PATTERN.fullmatch(stripped) is not None
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


def looks_like_inline_credential(user: str, value: str) -> bool:
    lowered = value.casefold()
    return (
        user.casefold() in COMMON_ACCOUNT_NAMES
        or looks_like_password(value)
        or lowered in COMMON_WEAK_PASSWORDS
        or "password" in lowered
        or "secret" in lowered
        or (value.isdigit() and len(value) >= 4)
        or len(set(lowered)) <= 2
    )


def has_required_approval_context(
    context: tuple[str, str],
    lines: list[str],
    line_number: int,
) -> bool:
    context_kind, expected_digest = context
    line_index = line_number - 1
    if not 0 <= line_index < len(lines):
        return False
    if context_kind == "exact_line":
        material = lines[line_index].strip()
    elif context_kind == "host_block":
        host_index: int | None = None
        for candidate in range(line_index - 1, max(-1, line_index - 9), -1):
            if HOST_FIELD_PATTERN.search(lines[candidate]):
                host_index = candidate
                break
        if host_index is None:
            return False
        material = "\n".join(
            line.strip() for line in lines[host_index : line_index + 1]
        )
    else:
        return False
    actual_digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
    return actual_digest == expected_digest


def is_approved_finding(
    path: str,
    line_number: int,
    rule_id: str,
    value: str,
    lines: list[str] | None = None,
) -> bool:
    digest = hashlib.sha256(value.strip().encode("utf-8")).hexdigest()
    key = (path, line_number, rule_id, digest)
    if key not in APPROVED_FINDING_HASHES or lines is None:
        return False
    required_context = APPROVED_FINDING_CONTEXTS.get((path, line_number, rule_id))
    return required_context is not None and has_required_approval_context(
        required_context,
        lines,
        line_number,
    )


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
    seen_files: set[Path] = set()
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
            if path not in seen_files:
                files.append(path)
                seen_files.add(path)
            continue

        for entry in sorted(path.rglob("*")):
            entry_label = display_path(entry, repository)
            if entry.is_symlink():
                errors.append(
                    f"{entry_label}: SYMLINK: sensitive-scan targets must not contain links"
                )
            elif entry.is_file():
                if entry not in seen_files:
                    files.append(entry)
                    seen_files.add(entry)
    return files, errors


def is_tracked_evidence_or_fixture(relative_path: str) -> bool:
    parts = tuple(part.casefold() for part in Path(relative_path).parts)
    if any(part in TRACKED_ARTIFACT_DIRECTORY_NAMES for part in parts[:-1]):
        return True
    filename = parts[-1] if parts else ""
    if any(marker in filename for marker in TRACKED_ARTIFACT_FILENAME_MARKERS):
        return True
    if any(part in TRACKED_TEST_ROOT_NAMES for part in parts[:-1]):
        if Path(filename).suffix.casefold() in TRACKED_TEST_ASSET_SUFFIXES:
            return True
    return any(
        parts[index : index + 3] == ("src", "test", "resources")
        for index in range(max(0, len(parts) - 2))
    )


def discover_tracked_artifact_targets(
    repository: Path,
) -> tuple[tuple[str, ...], list[str]]:
    result = subprocess.run(
        ("git", "-C", str(repository), "ls-files", "-z", "--cached"),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return (), [
            "repository: GIT_INDEX_UNAVAILABLE: default scan requires a Git index"
        ]

    tracked_paths = result.stdout.decode("utf-8", errors="surrogateescape").split("\0")
    targets = tuple(
        path for path in tracked_paths if path and is_tracked_evidence_or_fixture(path)
    )
    return targets, []


def scan_yaml_sensitive_scalars(
    label: str,
    content: str,
    selected_lines: set[int] | None,
) -> list[str]:
    if Path(label).suffix.casefold() not in {".yaml", ".yml"}:
        return []
    try:
        documents = list(yaml.compose_all(content))
    except yaml.YAMLError:
        return [f"{label}: INVALID_YAML: changed YAML cannot be parsed safely"]
    errors: list[str] = []

    def visit(node: Node | None) -> None:
        if isinstance(node, MappingNode):
            for key_node, value_node in node.value:
                if isinstance(key_node, ScalarNode) and re.fullmatch(
                    SENSITIVE_KEY_PATTERN, key_node.value, re.IGNORECASE
                ):
                    covered = set(
                        range(key_node.start_mark.line + 1, value_node.end_mark.line + 2)
                    )
                    if selected_lines is None or covered & selected_lines:
                        value = value_node.value if isinstance(value_node, ScalarNode) else ""
                        if not value or not is_safe_placeholder(value):
                            errors.append(
                                f"{label}:{key_node.start_mark.line + 1}: YAML_SECRET_SCALAR"
                            )
                visit(value_node)
        elif isinstance(node, SequenceNode):
            for child in node.value:
                visit(child)

    for document in documents:
        visit(document)
    return errors


def scan_text_content(
    label: str,
    content: str,
    *,
    selected_lines: set[int] | None = None,
) -> list[str]:
    if len(content.encode("utf-8")) > MAX_TEXT_FILE_BYTES:
        return [
            f"{label}: OVERSIZE: file exceeds {MAX_TEXT_FILE_BYTES} bytes and was not scanned"
        ]
    if any(
        character == "\x00"
        or (ord(character) < 32 and character not in {"\n", "\r", "\t"})
        or ord(character) == 127
        for character in content
    ):
        return [
            f"{label}: NON_UTF8: artifact contains NUL/control bytes and was not scanned"
        ]

    errors: list[str] = []
    lines = content.splitlines()
    for line_number, line in enumerate(lines, start=1):
        if selected_lines is not None and line_number not in selected_lines:
            continue
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
                lines,
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")

        for match in UNQUOTED_SECRET_ASSIGNMENT_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "GENERIC_SECRET_ASSIGNMENT",
                value,
                lines,
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")

        for match in INLINE_CREDENTIAL_PAIR_PATTERN.finditer(line):
            value = match.group("value")
            if looks_like_inline_credential(
                match.group("user"), value
            ) and not is_approved_finding(
                label,
                line_number,
                "INLINE_CREDENTIAL_PAIR",
                value,
                lines,
            ):
                errors.append(f"{label}:{line_number}: INLINE_CREDENTIAL_PAIR")

        for match in USER_PASSWORD_PAIR_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "USER_PASSWORD_PAIR",
                value,
                lines,
            ):
                errors.append(f"{label}:{line_number}: USER_PASSWORD_PAIR")

        for match in DOCUMENTED_PASSWORD_PATTERN.finditer(line):
            value = match.group("value")
            if not is_safe_placeholder(value) and not is_approved_finding(
                label,
                line_number,
                "DOCUMENTED_PASSWORD",
                value,
                lines,
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
    errors.extend(scan_yaml_sensitive_scalars(label, content, selected_lines))
    return errors


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
    return scan_text_content(label, content)


def _run_immutable_git(repository: Path, arguments: tuple[str, ...]) -> bytes:
    environment = os.environ.copy()
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    environment["GIT_LITERAL_PATHSPECS"] = "1"
    result = subprocess.run(
        ("git", "--no-replace-objects", "--literal-pathspecs", *arguments),
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise ValueError("immutable Git diff cannot be resolved")
    return result.stdout


def scan_git_diff(repository: Path, base_commit: str, commit: str) -> list[str]:
    repository = repository.resolve()
    if not FULL_SHA_PATTERN.fullmatch(base_commit) or not FULL_SHA_PATTERN.fullmatch(commit):
        return ["repository: INVALID_COMMIT: immutable diff requires full lowercase SHAs"]
    try:
        _run_immutable_git(repository, ("merge-base", "--is-ancestor", base_commit, commit))
        raw_paths = _run_immutable_git(
            repository,
            ("diff", "--name-only", "-z", "--diff-filter=AMCR", "--no-renames", base_commit, commit),
        )
    except ValueError as error:
        return [f"repository: IMMUTABLE_DIFF: {error}"]
    errors: list[str] = []
    for raw_path in (value for value in raw_paths.split(b"\x00") if value):
        path = os.fsdecode(raw_path)
        try:
            tree = _run_immutable_git(repository, ("ls-tree", "-z", commit, "--", path))
            metadata = tree.split(b"\t", 1)[0].split()
            if len(metadata) != 3 or metadata[0] not in {b"100644", b"100755"} or metadata[1] != b"blob":
                errors.append(f"{path}: UNSAFE_GIT_MODE: changed path is not a regular blob")
                continue
            object_id = os.fsdecode(metadata[2])
            if FULL_SHA_PATTERN.fullmatch(object_id) is None:
                errors.append(f"{path}: INVALID_GIT_OBJECT: changed blob ID is invalid")
                continue
            raw = _run_immutable_git(repository, ("cat-file", "blob", object_id))
            if len(raw) > MAX_TEXT_FILE_BYTES:
                errors.append(f"{path}: OVERSIZE: changed blob exceeds the scan limit")
                continue
            content = raw.decode("utf-8")
        except UnicodeError:
            errors.append(f"{path}: NON_UTF8: changed blob requires an approved binary scanner")
            continue
        except ValueError as error:
            errors.append(f"{path}: IMMUTABLE_DIFF: {error}")
            continue
        # Scan the complete immutable target blob. Git patches are affected by
        # .gitattributes (for example `-diff`) and therefore are not a trusted
        # source of coverage for a security gate.
        errors.extend(scan_text_content(path, content))
    return errors


def scan_repository(
    repository: Path,
    targets: tuple[str, ...] | None = None,
) -> list[str]:
    repository = repository.resolve()
    discovery_errors: list[str] = []
    if targets is None:
        tracked_targets, discovery_errors = discover_tracked_artifact_targets(
            repository
        )
        targets = DEFAULT_TARGETS + tracked_targets
    files, errors = collect_files(repository, targets)
    errors = discovery_errors + errors
    for path in files:
        errors.extend(scan_file(path, repository))
    return errors


def parse_args(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scan repository artifacts for sensitive data.")
    parser.add_argument("targets", nargs="*")
    parser.add_argument("--repository-root", type=Path)
    parser.add_argument("--base-commit")
    parser.add_argument("--commit")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    options = parse_args(sys.argv[1:] if arguments is None else arguments)
    repository = options.repository_root or Path(__file__).resolve().parent.parent
    diff_values = (options.base_commit, options.commit)
    if any(diff_values) and not all(diff_values):
        print("FAIL: immutable diff mode requires --base-commit and --commit", file=sys.stderr)
        return 1
    targets = tuple(options.targets) or None
    errors = (
        scan_git_diff(repository, options.base_commit, options.commit)
        if all(diff_values)
        else scan_repository(repository, targets)
    )
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"Sensitive artifact check failed with {len(errors)} problem(s).",
            file=sys.stderr,
        )
        return 1

    target_label = str(len(targets)) if targets is not None else "default tracked"
    print(f"Sensitive artifact scan passed for {target_label} target(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
