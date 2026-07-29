#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import xml.parsers.expat as expat
import xml.etree.ElementTree as ElementTree
from bisect import bisect_right
from collections.abc import Iterator, Mapping
from heapq import merge
from pathlib import Path
from typing import NamedTuple
from urllib.parse import unquote_to_bytes

import yaml
from yaml.events import (
    AliasEvent,
    MappingEndEvent,
    MappingStartEvent,
    ScalarEvent,
    SequenceEndEvent,
    SequenceStartEvent,
)
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
MAX_STRUCTURED_NODES = 100_000
MAX_STRUCTURED_DEPTH = 128
MAX_STRUCTURED_BYTES = 2 * MAX_TEXT_FILE_BYTES
JSON_SCHEMA_DIRECT_VALUE_KEYS = frozenset(
    {"const", "default", "example", "value"}
)
JSON_SCHEMA_COLLECTION_VALUE_KEYS = frozenset({"enum", "examples"})
JSON_SCHEMA_VALUE_KEYS = (
    JSON_SCHEMA_DIRECT_VALUE_KEYS | JSON_SCHEMA_COLLECTION_VALUE_KEYS
)
JSON_SCHEMA_DEFINITION_MAP_KEYS = frozenset(
    {"$defs", "definitions", "properties", "schemas"}
)
JSON_SCHEMA_SCHEMA_ARRAY_KEYS = frozenset(
    {"allOf", "anyOf", "oneOf", "prefixItems"}
)
JSON_SCHEMA_SCHEMA_MAP_KEYS = frozenset(
    {
        *JSON_SCHEMA_DEFINITION_MAP_KEYS,
        "dependentSchemas",
        "patternProperties",
    }
)
JSON_SCHEMA_SINGLE_SCHEMA_KEYS = frozenset(
    {
        "additionalItems",
        "additionalProperties",
        "contains",
        "contentSchema",
        "else",
        "if",
        "items",
        "not",
        "propertyNames",
        "then",
        "unevaluatedItems",
        "unevaluatedProperties",
    }
)
JSON_SCHEMA_STRUCTURAL_KEYS = frozenset(
    {
        "$anchor",
        "$defs",
        "$dynamicAnchor",
        "$dynamicRef",
        "$id",
        "$ref",
        "$schema",
        "additionalItems",
        "additionalProperties",
        "allOf",
        "anyOf",
        "contains",
        "contentEncoding",
        "contentMediaType",
        "contentSchema",
        "definitions",
        "dependentRequired",
        "dependentSchemas",
        "dependencies",
        "discriminator",
        "else",
        "exclusiveMaximum",
        "exclusiveMinimum",
        "format",
        "if",
        "items",
        "maximum",
        "maxContains",
        "maxItems",
        "maxLength",
        "maxProperties",
        "minimum",
        "minContains",
        "minItems",
        "minLength",
        "minProperties",
        "multipleOf",
        "not",
        "nullable",
        "oneOf",
        "pattern",
        "patternProperties",
        "prefixItems",
        "properties",
        "propertyNames",
        "required",
        "then",
        "type",
        "unevaluatedItems",
        "unevaluatedProperties",
        "uniqueItems",
    }
)
JSON_SCHEMA_ANNOTATION_KEYS = frozenset(
    {
        "$comment",
        "deprecated",
        "description",
        "externalDocs",
        "readOnly",
        "title",
        "writeOnly",
        "xml",
        *JSON_SCHEMA_VALUE_KEYS,
    }
)
JSON_SCHEMA_ALLOWED_KEYS = JSON_SCHEMA_STRUCTURAL_KEYS | JSON_SCHEMA_ANNOTATION_KEYS
JSON_SCHEMA_TYPES = frozenset(
    {"array", "boolean", "integer", "null", "number", "object", "string"}
)
SAFE_LITERAL_VALUES = {
    "cookie-session",
    "disabled",
    "false",
    "masked",
    "none",
    "null",
    "redacted",
    "true",
}
SAFE_EMAIL_ADDRESSES = {"git@github.com"}
SAFE_EMAIL_SUFFIXES = (".example", ".invalid", ".test")
APPROVED_FINDING_HASHES = {
    (
        "frontend/admin/pnpm-lock.yaml",
        7095,
        "EMAIL_ADDRESS",
        "b0373516d594ddd199b25c3655332b354837e065c5cc232f5043189897900ca7",
    ): "Published upstream deprecation contact in the frozen pnpm lockfile",
    (
        "frontend/admin/pnpm-lock.yaml",
        17063,
        "GENERIC_SECRET_ASSIGNMENT",
        "4ccec3fd47ee5162962804420d4b53c8ee5ecfc827f79ffd21e679b305742003",
    ): "Package version mapping in the frozen pnpm lockfile",
    (
        "frontend/admin/pnpm-lock.yaml",
        17063,
        "YAML_SECRET_SCALAR",
        "4ccec3fd47ee5162962804420d4b53c8ee5ecfc827f79ffd21e679b305742003",
    ): "Package version mapping in the frozen pnpm lockfile",
}
APPROVED_FINDING_CONTEXTS = {
    (
        "frontend/admin/pnpm-lock.yaml",
        7095,
        "EMAIL_ADDRESS",
    ): (
        "exact_line",
        "aafd0901253a2ae949eca4fded10be77251c4d25c26bf14269afa2662af1adbc",
    ),
    (
        "frontend/admin/pnpm-lock.yaml",
        17063,
        "GENERIC_SECRET_ASSIGNMENT",
    ): (
        "exact_line",
        "872031be8d2493823658029739fba0029bf4ca47698df8a3efc85c5493068c2f",
    ),
    (
        "frontend/admin/pnpm-lock.yaml",
        17063,
        "YAML_SECRET_SCALAR",
    ): (
        "exact_line",
        "872031be8d2493823658029739fba0029bf4ca47698df8a3efc85c5493068c2f",
    ),
}
PLACEHOLDER_PATTERN = re.compile(
    r"(?:\$\{[A-Z][A-Z0-9_]*\}|\{\{[A-Z][A-Z0-9_]*\}\}|<[A-Z][A-Z0-9_]*>)"
)
MAVEN_ENV_PLACEHOLDER_PATTERN = re.compile(r"\$\{env\.[A-Z][A-Z0-9_]*\}")
ENV_SAFE_DEFAULT_PLACEHOLDER_PATTERN = re.compile(
    r"\$\{(?P<variable>[A-Z][A-Z0-9_]*):(?P<default>[a-z][a-z0-9-]*)\}"
)
MASK_PATTERN = re.compile(r"(?:\*{3,}|x{3,}|•{3,})", re.IGNORECASE)
SENSITIVE_KEY_PATTERN = (
    r"(?:[A-Za-z][A-Za-z0-9]{0,63}[_.-]){0,16}"
    r"(?:api[_-]?key|client[_-]?secret|database[_-]?password|"
    r"db[_-]?password|user[_-]?password|secret[_-]?access[_-]?key|"
    r"session[_-]?token|jwt[_-]?token|secret|password|passwd|token|"
    r"access[_-]?token|refresh[_-]?token|auth[_-]?token|"
    r"aws[_-]?secret[_-]?access[_-]?key)"
)
PYTHON_STRING_PREFIX_PATTERN = (
    r"(?:[rRuUbBfF]|[bB][rR]|[rR][bBfF]|[fF][rR])"
)
PYTHON_OPTIONAL_STRING_PREFIX_PATTERN = rf"(?:{PYTHON_STRING_PREFIX_PATTERN})?"
SECRET_ASSIGNMENT_KEY = (
    rf"(?:{PYTHON_OPTIONAL_STRING_PREFIX_PATTERN}"
    rf'"""{SENSITIVE_KEY_PATTERN}"""|'
    rf"{PYTHON_OPTIONAL_STRING_PREFIX_PATTERN}"
    rf"'''{SENSITIVE_KEY_PATTERN}'''|"
    rf"{PYTHON_OPTIONAL_STRING_PREFIX_PATTERN}"
    rf'"{SENSITIVE_KEY_PATTERN}"|'
    rf"{PYTHON_OPTIONAL_STRING_PREFIX_PATTERN}"
    rf'\'{SENSITIVE_KEY_PATTERN}\'|'
    rf"{SENSITIVE_KEY_PATTERN})"
)
SECRET_ASSIGNMENT_SEPARATOR = r"(?::=|:(?!\s*(?:=|:))|=(?!\s*(?:=|>|&gt;)))"
SECRET_ASSIGNMENT_PREFIX = (
    rf"(?<![A-Za-z0-9_]){SECRET_ASSIGNMENT_KEY}"
    rf"\s*{SECRET_ASSIGNMENT_SEPARATOR}\s*"
)
GENERIC_SECRET_ASSIGNMENT_PATTERN = re.compile(
    SECRET_ASSIGNMENT_PREFIX + r"(?P<quote>[\"'`])(?P<value>.*?)(?P=quote)",
    re.IGNORECASE,
)
UNQUOTED_SECRET_ASSIGNMENT_PATTERN = re.compile(
    SECRET_ASSIGNMENT_PREFIX + r"(?P<value>(?![\"'`])[^\s,;]+)",
    re.IGNORECASE,
)
TYPESCRIPT_SUFFIXES = frozenset({".cts", ".mts", ".ts"})
SHELL_SCRIPT_SUFFIXES = frozenset({".bash", ".sh", ".zsh"})
SHELL_TOKEN_BOUNDARIES = frozenset(" \t\r\n;&|<>()")
MVNW_AUTH_VARIABLE = "MVNW_" + "PASSWORD"
MVNW_AUTH_MARKER_VALUE = "has-password"
MVNW_AUTH_PRESENCE_MARKER = (
    "${" + MVNW_AUTH_VARIABLE + ":+" + MVNW_AUTH_MARKER_VALUE + "}"
)
MVNW_AUTH_VARIABLE_PATTERN = re.compile(
    re.escape("MVNW_") + r"(?:PASSWORD|\$\{PASSWORD)", re.IGNORECASE
)
MVNW_AUTH_MARKER_VALUE_PATTERN = re.compile(
    re.escape(MVNW_AUTH_MARKER_VALUE), re.IGNORECASE
)
SIMPLE_SHELL_PARAMETER_EXPANSION_PATTERN = re.compile(
    r"\$\{[^${}\"'\\\r\n]*\}"
)
TYPESCRIPT_TYPE_SCOPE_PATTERN = re.compile(
    r"^\s*(?:(?:export\s+(?:default\s+|declare\s+)?)|declare\s+)?"
    r"interface\s+[A-Za-z_$][A-Za-z0-9_$]*"
    r"(?:\s*<[^{};=]*>)?(?:\s+extends\s+[^{};=]+)?\s*\{"
    r"|^\s*(?:(?:export\s+(?:declare\s+)?)|declare\s+)?"
    r"type\s+[A-Za-z_$][A-Za-z0-9_$]*"
    r"(?:\s*<[^{};=]*>)?\s*=\s*[^{};]*\{"
)
TYPESCRIPT_TYPE_MEMBER_PATTERN = re.compile(
    rf"^\s*(?:readonly\s+)?(?P<key>{SECRET_ASSIGNMENT_KEY})\s*\??\s*:"
    r"\s*(?P<type>.+?)\s*$",
    re.IGNORECASE,
)
TYPESCRIPT_SIMPLE_TYPE_PATTERN = re.compile(
    r"^(?:[A-Za-z_$]|\{|\[|\()[A-Za-z0-9_$\.\s<>,|&?\[\]():{}]*$"
)
INLINE_CREDENTIAL_PAIR_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_.@-])(?P<user>[A-Za-z0-9_.@-]{3,128})\s+/\s+"
    r"(?P<value>[^\s`'\",;，。()\[\]{}]{4,})"
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
MAX_EMAIL_LOCAL_LENGTH = 64
MAX_EMAIL_DOMAIN_LENGTH = 253
EMAIL_ADDRESS_PATTERN = re.compile(
    rf"\b[A-Za-z0-9.!#$%&'*+/=?^_`{{|}}~-]{{1,{MAX_EMAIL_LOCAL_LENGTH}}}@"
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
JSON_UNICODE_ESCAPE_PATTERN = re.compile(r"\\u(?P<codepoint>[0-9A-Fa-f]{4})")
FORBIDDEN_GIT_ENVIRONMENT = frozenset(
    {
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_ATTR_NOSYSTEM",
        "GIT_ATTR_SOURCE",
        "GIT_CEILING_DIRECTORIES",
        "GIT_COMMON_DIR",
        "GIT_CONFIG_COUNT",
        "GIT_CONFIG_GLOBAL",
        "GIT_CONFIG_NOSYSTEM",
        "GIT_CONFIG_PARAMETERS",
        "GIT_CONFIG_SYSTEM",
        "GIT_DIR",
        "GIT_DISCOVERY_ACROSS_FILESYSTEM",
        "GIT_EXEC_PATH",
        "GIT_GRAFT_FILE",
        "GIT_INDEX_FILE",
        "GIT_INTERNAL_SUPER_PREFIX",
        "GIT_NAMESPACE",
        "GIT_OBJECT_DIRECTORY",
        "GIT_PREFIX",
        "GIT_QUARANTINE_PATH",
        "GIT_REPLACE_REF_BASE",
        "GIT_SHALLOW_FILE",
        "GIT_WORK_TREE",
    }
)


class JsonObject(list[tuple[str, object]]):
    """Preserve duplicate JSON members so an unsafe earlier value cannot vanish."""


class JsonScanFrame(NamedTuple):
    value: object
    depth: int
    required_json: str | None
    har_context: str
    in_har_envelope: bool
    sensitive_container: bool
    schema_sensitive_definition: bool
    schema_reference_context: bool
    named_member_context: str
    schema_document_root: object
    schema_ref_chain: frozenset[int]
    schema_ref_scope_safe: bool


class YamlScanResult(NamedTuple):
    errors: list[str]
    safe_root_assignments: frozenset[tuple[int, int]]


class InvalidStructuredDocument(ValueError):
    pass


class InvalidJsonSyntax(InvalidStructuredDocument):
    def __init__(self, position: int) -> None:
        super().__init__("invalid JSON syntax")
        self.position = position


class UnsafeStructuredDocument(ValueError):
    pass


class StructuredScanLimit(ValueError):
    pass


class StructuredScanBudget:
    def __init__(self) -> None:
        self.bytes = 0
        self.nodes = 0


def _reject_json_constant(_value: str) -> object:
    raise UnsafeStructuredDocument("non-standard JSON constant")


def _parse_finite_json_float(value: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise UnsafeStructuredDocument("non-finite JSON number")
    return number


def _parse_bounded_json_int(value: str) -> int:
    try:
        return int(value)
    except (ValueError, OverflowError) as error:
        raise UnsafeStructuredDocument("JSON integer exceeds runtime limits") from error


def is_safe_placeholder(value: str) -> bool:
    stripped = value.strip()
    env_default = ENV_SAFE_DEFAULT_PLACEHOLDER_PATTERN.fullmatch(stripped)
    return (
        not value
        or stripped.casefold() in SAFE_LITERAL_VALUES
        or PLACEHOLDER_PATTERN.fullmatch(stripped) is not None
        or MAVEN_ENV_PLACEHOLDER_PATTERN.fullmatch(stripped) is not None
        or env_default is not None
        and env_default.group("default") in SAFE_LITERAL_VALUES
        or MASK_PATTERN.fullmatch(stripped) is not None
    )


def _safe_placeholder_spans(content: str) -> tuple[tuple[int, int], ...]:
    spans: set[tuple[int, int]] = set()
    for pattern in (
        PLACEHOLDER_PATTERN,
        MAVEN_ENV_PLACEHOLDER_PATTERN,
        ENV_SAFE_DEFAULT_PLACEHOLDER_PATTERN,
    ):
        for match in pattern.finditer(content):
            if is_safe_placeholder(match.group(0)):
                start, end = match.span()
                if (
                    start > 0
                    and end < len(content)
                    and content[start - 1] == content[end]
                    and content[end] in {"\"", "'", "`"}
                ):
                    start -= 1
                    end += 1
                spans.add((start, end))
    return tuple(sorted(spans))


def _is_mvnw_label(label: str) -> bool:
    return Path(label).name == "mvnw"


def _complete_shell_token_span(
    content: str,
    start: int,
    end: int,
) -> tuple[int, int] | None:
    token_start = start
    token_end = end
    if (
        start > 0
        and end < len(content)
        and content[start - 1] == content[end]
        and content[end] in {"\"", "'"}
    ):
        token_start -= 1
        token_end += 1
    if (
        (token_start == 0 or content[token_start - 1] in SHELL_TOKEN_BOUNDARIES)
        and (
            token_end == len(content)
            or content[token_end] in SHELL_TOKEN_BOUNDARIES
        )
    ):
        return token_start, token_end
    return None


def _mvnw_auth_marker_spans(
    label: str,
    content: str,
) -> tuple[tuple[int, int], ...]:
    if not _is_mvnw_label(label) or _has_ambiguous_shell_parameter_context(
        content
    ):
        return ()
    spans: list[tuple[int, int]] = []
    cursor = 0
    while (start := content.find(MVNW_AUTH_PRESENCE_MARKER, cursor)) >= 0:
        end = start + len(MVNW_AUTH_PRESENCE_MARKER)
        token_span = _complete_shell_token_span(content, start, end)
        if token_span is not None:
            spans.append(token_span)
        cursor = end
    return tuple(spans)


def _has_ambiguous_shell_parameter_context(content: str) -> bool:
    cursor = 0
    while cursor < len(content):
        if content.startswith("${", cursor):
            expansion = SIMPLE_SHELL_PARAMETER_EXPANSION_PATTERN.match(
                content, cursor
            )
            if expansion is None:
                return True
            cursor = expansion.end()
        elif content[cursor] == "}":
            return True
        else:
            cursor += 1
    return False


def _span_is_within_safe_span(
    start_position: int,
    end_position: int,
    spans: tuple[tuple[int, int], ...],
) -> bool:
    predecessor = bisect_right(spans, (start_position, sys.maxsize)) - 1
    if predecessor < 0:
        return False
    start, end = spans[predecessor]
    return start <= start_position and end_position <= end


def _match_is_within_safe_placeholder(
    match: re.Match[str],
    spans: tuple[tuple[int, int], ...],
) -> bool:
    return _span_is_within_safe_span(match.start(), match.end(), spans)


def _has_unsafe_mvnw_auth_marker(
    label: str,
    content: str,
    safe_marker_spans: tuple[tuple[int, int], ...],
) -> bool:
    if not _is_mvnw_label(label):
        return False
    unsafe_variable = next(
        (
            match
            for match in MVNW_AUTH_VARIABLE_PATTERN.finditer(content)
            if not _span_is_within_safe_span(
                match.start(), match.end(), safe_marker_spans
            )
        ),
        None,
    )
    if unsafe_variable is None:
        return False
    return any(
        not _span_is_within_safe_span(
            match.start(), match.end(), safe_marker_spans
        )
        for match in MVNW_AUTH_MARKER_VALUE_PATTERN.finditer(
            content, unsafe_variable.end()
        )
    )


def _quoted_assignment_has_direct_suffix(
    content: str,
    match: re.Match[str],
    *,
    shell_like: bool = False,
) -> bool:
    end = match.end()
    if end >= len(content):
        return False
    if shell_like:
        return content[end] not in " \t\r\n;&|<>()"
    return not (
        content[end] in " \t\r\n,;:)]}>"
        or content.startswith("/>", end)
    )


def _is_shell_like_label(label: str) -> bool:
    filename = Path(label).name.casefold()
    return (
        filename in {"gradlew", "mvnw"}
        or Path(filename).suffix in SHELL_SCRIPT_SUFFIXES
        or filename == ".env"
        or filename.startswith(".env.")
    )


def is_safe_email(value: str) -> bool:
    lowered = value.casefold()
    domain = lowered.rsplit("@", 1)[-1]
    return lowered in SAFE_EMAIL_ADDRESSES or domain.endswith(SAFE_EMAIL_SUFFIXES)


def _iter_email_addresses(line: str) -> Iterator[str]:
    cursor = 0
    yielded_spans: set[tuple[int, int]] = set()
    while (at_index := line.find("@", cursor)) >= 0:
        window_start = max(0, at_index - MAX_EMAIL_LOCAL_LENGTH - 1)
        window_end = min(
            len(line), at_index + 1 + MAX_EMAIL_DOMAIN_LENGTH + 1
        )
        window = line[window_start:window_end]
        relative_at = at_index - window_start
        for match in EMAIL_ADDRESS_PATTERN.finditer(window):
            if not match.start() <= relative_at < match.end():
                continue
            span = (
                window_start + match.start(),
                window_start + match.end(),
            )
            if span not in yielded_spans:
                yielded_spans.add(span)
                yield match.group(0)
        cursor = at_index + 1


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
    if context_kind != "exact_line":
        return False
    material = lines[line_index].strip()
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


def _isolated_git_environment(
    inherited_environment: Mapping[str, str],
) -> dict[str, str]:
    forbidden = set(
        FORBIDDEN_GIT_ENVIRONMENT.intersection(inherited_environment)
    )
    forbidden.update(
        key
        for key in inherited_environment
        if key.startswith(("GIT_CONFIG_KEY_", "GIT_CONFIG_VALUE_"))
    )
    if forbidden:
        raise ValueError("Git environment overrides are not allowed")
    return {
        "PATH": inherited_environment.get("PATH", os.defpath),
        "HOME": os.devnull,
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_LITERAL_PATHSPECS": "1",
        "GIT_ALLOW_PROTOCOL": "",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_TERMINAL_PROMPT": "0",
    }


def _discovery_git_command(repository: Path, *arguments: str) -> tuple[str, ...]:
    return (
        "git",
        "-C",
        str(repository),
        "-c",
        f"safe.directory={repository}",
        "-c",
        "core.fsmonitor=",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "core.commitGraph=false",
        "-c",
        "core.useReplaceRefs=false",
        "-c",
        "submodule.recurse=false",
        "--no-replace-objects",
        "--literal-pathspecs",
        *arguments,
    )


def discover_tracked_artifact_targets(
    repository: Path,
) -> tuple[tuple[str, ...], list[str]]:
    repository = repository.resolve()
    try:
        environment = _isolated_git_environment(os.environ)
    except ValueError:
        return (), [
            "repository: GIT_ENVIRONMENT_OVERRIDE: "
            "Git environment overrides are not allowed"
        ]
    top_level = subprocess.run(
        _discovery_git_command(repository, "rev-parse", "--show-toplevel"),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=environment,
        check=False,
    )
    raw_top_level = top_level.stdout
    if (
        top_level.returncode != 0
        or not raw_top_level.endswith(b"\n")
        or any(
            character in raw_top_level[:-1]
            for character in (b"\x00", b"\r", b"\n")
        )
        or Path(os.fsdecode(raw_top_level[:-1])).resolve() != repository
    ):
        return (), [
            "repository: GIT_REPOSITORY_MISMATCH: "
            "default scan requires the exact repository root"
        ]
    result = subprocess.run(
        _discovery_git_command(repository, "ls-files", "-z", "--cached"),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=environment,
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


def _are_schema_keywords(keys: Iterator[str]) -> bool:
    return all(
        key in JSON_SCHEMA_ALLOWED_KEYS or key.startswith("x-")
        for key in keys
    )


def _schema_value_keyword(value: str) -> str | None:
    if value in JSON_SCHEMA_VALUE_KEYS:
        return value
    if not value.startswith("x-"):
        return None
    candidate = value.rsplit("-", 1)[-1]
    return candidate if candidate in JSON_SCHEMA_VALUE_KEYS else None


def _is_yaml_merge_key(node: Node) -> bool:
    return (
        isinstance(node, ScalarNode)
        and node.value == "<<"
        and node.tag == "tag:yaml.org,2002:merge"
    )


def _is_yaml_boolean_schema(node: Node) -> bool:
    if (
        not isinstance(node, ScalarNode)
        or node.tag != "tag:yaml.org,2002:bool"
    ):
        return False
    value = yaml.SafeLoader.bool_values.get(node.value.casefold())
    return type(value) is bool


def _is_json_schema_definition(
    value: object,
    depth: int = 0,
    active: set[int] | None = None,
    memo: dict[int, tuple[bool, int]] | None = None,
) -> bool:
    if isinstance(value, bool):
        return True
    if depth > MAX_STRUCTURED_DEPTH:
        return False
    if not isinstance(value, JsonObject):
        return False
    if active is None:
        active = set()
    if memo is None:
        memo = {}

    def classify(
        current: object,
        current_depth: int,
    ) -> tuple[bool, int]:
        if isinstance(current, bool):
            return True, 0
        if not isinstance(current, JsonObject):
            return False, 0
        identity = id(current)
        cached = memo.get(identity)
        if cached is not None:
            valid, height = cached
            return (
                valid
                and current_depth + height <= MAX_STRUCTURED_DEPTH,
                height,
            )
        if identity in active:
            return False, 0

        active.add(identity)
        keys: list[str] = []
        maximum_height = 0

        def remember(valid: bool) -> tuple[bool, int]:
            result = valid, maximum_height
            memo[identity] = result
            return (
                valid
                and current_depth + maximum_height
                <= MAX_STRUCTURED_DEPTH,
                maximum_height,
            )

        def include_schema(
            schema: object,
        ) -> bool:
            nonlocal maximum_height
            valid, child_height = classify(
                schema,
                current_depth + 1,
            )
            maximum_height = max(
                maximum_height,
                child_height + 1,
            )
            return valid

        def include_schema_array(candidate: object) -> bool:
            return (
                type(candidate) is list
                and bool(candidate)
                and all(include_schema(item) for item in candidate)
            )

        try:
            for key, child in current:
                if not isinstance(key, str):
                    return remember(False)
                if key == "type" and not (
                    isinstance(child, str)
                    and child in JSON_SCHEMA_TYPES
                    or type(child) is list
                    and bool(child)
                    and all(
                        isinstance(item, str)
                        and item in JSON_SCHEMA_TYPES
                        for item in child
                    )
                ):
                    return remember(False)
                if (
                    key in JSON_SCHEMA_COLLECTION_VALUE_KEYS
                    and type(child) is not list
                ):
                    return remember(False)
                if key == "required" and not (
                    type(child) is list
                    and all(isinstance(item, str) for item in child)
                ):
                    return remember(False)
                if key in JSON_SCHEMA_SCHEMA_MAP_KEYS:
                    if not isinstance(child, JsonObject):
                        return remember(False)
                    for name, definition in child:
                        if (
                            not isinstance(name, str)
                            or not include_schema(definition)
                        ):
                            return remember(False)
                elif key in JSON_SCHEMA_SCHEMA_ARRAY_KEYS:
                    if not include_schema_array(child):
                        return remember(False)
                elif key in JSON_SCHEMA_SINGLE_SCHEMA_KEYS:
                    if (
                        key == "items"
                        and type(child) is list
                    ):
                        if not include_schema_array(child):
                            return remember(False)
                    elif not include_schema(child):
                        return remember(False)
                elif key == "dependencies":
                    if not isinstance(child, JsonObject):
                        return remember(False)
                    for name, dependency in child:
                        if not isinstance(name, str):
                            return remember(False)
                        if type(dependency) is list:
                            if not dependency or not all(
                                isinstance(item, str)
                                for item in dependency
                            ):
                                return remember(False)
                        elif not include_schema(dependency):
                            return remember(False)
                elif key == "dependentRequired":
                    if not isinstance(child, JsonObject) or any(
                        not isinstance(name, str)
                        or type(required) is not list
                        or not required
                        or not all(
                            isinstance(item, str)
                            for item in required
                        )
                        for name, required in child
                    ):
                        return remember(False)
                keys.append(key)
            return remember(_are_schema_keywords(iter(keys)))
        finally:
            active.remove(identity)

    return classify(value, depth)[0]


def _is_yaml_schema_definition(
    node: Node,
    depth: int = 0,
    active: set[int] | None = None,
    *,
    budget: StructuredScanBudget | None = None,
    memo: dict[int, tuple[bool, int]] | None = None,
    accounted: set[int] | None = None,
) -> bool:
    if budget is None:
        budget = StructuredScanBudget()
    if memo is None:
        memo = {}
    if accounted is None:
        accounted = set()
    if active is None:
        active = set()

    def account(current: Node, current_depth: int) -> None:
        identity = id(current)
        if identity in accounted:
            if current_depth > MAX_STRUCTURED_DEPTH:
                raise StructuredScanLimit
            return
        _reserve_structured_node(budget, current_depth)
        accounted.add(identity)

    def classify(current: Node, current_depth: int) -> tuple[bool, int]:
        identity = id(current)
        cached = memo.get(identity)
        if cached is not None:
            valid, height = cached
            return valid and current_depth + height <= MAX_STRUCTURED_DEPTH, height
        account(current, current_depth)
        if _is_yaml_boolean_schema(current):
            memo[identity] = (True, 0)
            return True, 0
        if not isinstance(current, MappingNode):
            memo[identity] = (False, 0)
            return False, 0
        if identity in active:
            return False, 0

        active.add(identity)
        keys: list[str] = []
        maximum_height = 0

        def remember(valid: bool) -> tuple[bool, int]:
            result = valid, maximum_height
            memo[identity] = result
            return result

        def include_schema(
            schema: Node,
            schema_depth: int = current_depth + 1,
        ) -> bool:
            nonlocal maximum_height
            valid, child_height = classify(schema, schema_depth)
            maximum_height = max(
                maximum_height,
                child_height + 1,
            )
            return valid

        def include_schema_sequence(candidate: Node) -> bool:
            if not isinstance(candidate, SequenceNode) or not candidate.value:
                return False
            return all(include_schema(item) for item in candidate.value)

        try:
            for key_node, value_node in current.value:
                account(key_node, current_depth + 1)
                account(value_node, current_depth + 1)
                if not isinstance(key_node, ScalarNode):
                    return remember(False)
                if _is_yaml_merge_key(key_node):
                    if isinstance(value_node, MappingNode):
                        merge_sources = (value_node,)
                    elif isinstance(value_node, SequenceNode):
                        merge_sources = tuple(value_node.value)
                    else:
                        return remember(False)
                    for source in merge_sources:
                        account(source, current_depth + 2)
                        if not isinstance(source, MappingNode):
                            return remember(False)
                        valid, child_height = classify(
                            source,
                            current_depth,
                        )
                        maximum_height = max(
                            maximum_height,
                            child_height,
                        )
                        if not valid:
                            return remember(False)
                    continue
                if key_node.value == "type":
                    if isinstance(value_node, ScalarNode):
                        if value_node.value not in JSON_SCHEMA_TYPES:
                            return remember(False)
                    elif isinstance(value_node, SequenceNode):
                        if not value_node.value:
                            return remember(False)
                        for item in value_node.value:
                            account(item, current_depth + 2)
                            if (
                                not isinstance(item, ScalarNode)
                                or item.value not in JSON_SCHEMA_TYPES
                            ):
                                return remember(False)
                    else:
                        return remember(False)
                if (
                    key_node.value in JSON_SCHEMA_COLLECTION_VALUE_KEYS
                    and not isinstance(value_node, SequenceNode)
                ):
                    return remember(False)
                if key_node.value in JSON_SCHEMA_SCHEMA_MAP_KEYS:
                    if not isinstance(value_node, MappingNode):
                        return remember(False)
                    for name, definition in value_node.value:
                        account(name, current_depth + 2)
                        if (
                            not isinstance(name, ScalarNode)
                            or not include_schema(definition)
                        ):
                            return remember(False)
                elif key_node.value in JSON_SCHEMA_SCHEMA_ARRAY_KEYS:
                    if not include_schema_sequence(value_node):
                        return remember(False)
                elif key_node.value in JSON_SCHEMA_SINGLE_SCHEMA_KEYS:
                    if (
                        key_node.value == "items"
                        and isinstance(value_node, SequenceNode)
                    ):
                        if not include_schema_sequence(value_node):
                            return remember(False)
                    elif not include_schema(value_node):
                        return remember(False)
                elif key_node.value == "dependencies":
                    if not isinstance(value_node, MappingNode):
                        return remember(False)
                    for name, dependency in value_node.value:
                        account(name, current_depth + 2)
                        account(dependency, current_depth + 2)
                        if not isinstance(name, ScalarNode):
                            return remember(False)
                        if isinstance(dependency, SequenceNode):
                            if not dependency.value:
                                return remember(False)
                            for item in dependency.value:
                                account(item, current_depth + 3)
                                if not isinstance(item, ScalarNode):
                                    return remember(False)
                        elif not include_schema(dependency):
                            return remember(False)
                elif key_node.value == "dependentRequired":
                    if not isinstance(value_node, MappingNode):
                        return remember(False)
                    for name, required in value_node.value:
                        account(name, current_depth + 2)
                        account(required, current_depth + 2)
                        if (
                            not isinstance(name, ScalarNode)
                            or not isinstance(required, SequenceNode)
                            or not required.value
                        ):
                            return remember(False)
                        for item in required.value:
                            account(item, current_depth + 3)
                            if not isinstance(item, ScalarNode):
                                return remember(False)
                keys.append(key_node.value)
            return remember(
                _are_schema_keywords(iter(keys))
                and current_depth + maximum_height
                <= MAX_STRUCTURED_DEPTH
            )
        finally:
            active.remove(identity)

    return classify(node, depth)[0]


def _yaml_descriptor_origins(
    node: MappingNode,
    budget: StructuredScanBudget,
    memo: dict[int, dict[str, ScalarNode | None]],
    accounted: set[int],
    active: set[int] | None = None,
    depth: int = 0,
) -> tuple[ScalarNode, ...]:
    if active is None:
        active = set()

    def account(current: Node, current_depth: int) -> None:
        identity = id(current)
        if identity in accounted:
            if current_depth > MAX_STRUCTURED_DEPTH:
                raise StructuredScanLimit
            return
        _reserve_structured_node(budget, current_depth)
        accounted.add(identity)

    def fields(
        current: MappingNode,
        current_depth: int,
    ) -> dict[str, ScalarNode | None]:
        identity = id(current)
        cached = memo.get(identity)
        if cached is not None:
            return cached
        account(current, current_depth)
        if identity in active:
            raise UnsafeStructuredDocument("cyclic YAML merge")
        active.add(identity)
        try:
            result: dict[str, ScalarNode | None] = {}
            merge_groups: list[tuple[MappingNode, ...]] = []
            for key_node, value_node in current.value:
                account(key_node, current_depth + 1)
                if not isinstance(key_node, ScalarNode):
                    continue
                if _is_yaml_merge_key(key_node):
                    if isinstance(value_node, MappingNode):
                        merge_groups.append((value_node,))
                    elif isinstance(value_node, SequenceNode):
                        account(value_node, current_depth + 1)
                        sources: list[MappingNode] = []
                        for source in value_node.value:
                            account(source, current_depth + 2)
                            if not isinstance(source, MappingNode):
                                raise UnsafeStructuredDocument(
                                    "invalid YAML merge source"
                                )
                            sources.append(source)
                        merge_groups.append(tuple(sources))
                    else:
                        raise UnsafeStructuredDocument(
                            "invalid YAML merge source"
                        )
                    continue
                descriptor_name = key_node.value.casefold()
                if descriptor_name not in {"key", "name"}:
                    continue
                account(value_node, current_depth + 1)
                sensitive_origin = (
                    key_node
                    if isinstance(value_node, ScalarNode)
                    and _is_sensitive_structured_key(value_node.value)
                    else None
                )
                if (
                    descriptor_name not in result
                    or sensitive_origin is not None
                ):
                    result[descriptor_name] = sensitive_origin

            for group in reversed(merge_groups):
                for source in group:
                    inherited = fields(source, current_depth + 1)
                    for descriptor_name, descriptor in inherited.items():
                        if descriptor_name not in result:
                            result[descriptor_name] = descriptor
            memo[identity] = result
            return result
        finally:
            active.remove(identity)

    return tuple(
        origin
        for origin in fields(node, depth).values()
        if origin is not None
    )


def _resolve_local_yaml_schema_ref(
    reference: str,
    document: Node,
    budget: StructuredScanBudget,
    depth: int,
) -> Node:
    tokens = _decode_local_json_pointer(reference)
    _reserve_structured_node(budget, depth)
    target = document
    for offset, token in enumerate(tokens, start=1):
        _reserve_structured_node(budget, depth + offset)
        if isinstance(target, MappingNode):
            matches = [
                child
                for key, child in target.value
                if isinstance(key, ScalarNode)
                and key.tag == "tag:yaml.org,2002:str"
                and key.value == token
            ]
            if len(matches) != 1:
                raise UnsafeStructuredDocument(
                    "ambiguous or missing YAML JSON Pointer member"
                )
            target = matches[0]
            continue
        if isinstance(target, SequenceNode):
            if re.fullmatch(r"(?:0|[1-9][0-9]*)", token) is None:
                raise UnsafeStructuredDocument(
                    "invalid YAML JSON Pointer index"
                )
            try:
                index = int(token)
            except (ValueError, OverflowError) as error:
                raise UnsafeStructuredDocument(
                    "invalid YAML JSON Pointer index"
                ) from error
            if index >= len(target.value):
                raise UnsafeStructuredDocument(
                    "missing YAML JSON Pointer array item"
                )
            target = target.value[index]
            continue
        raise UnsafeStructuredDocument(
            "YAML JSON Pointer traverses a scalar value"
        )
    if not (
        isinstance(target, MappingNode)
        or _is_yaml_boolean_schema(target)
    ):
        raise UnsafeStructuredDocument(
            "YAML Schema reference target is not a schema"
        )
    return target


def _preflight_yaml_source(
    content: str,
    budget: StructuredScanBudget,
) -> None:
    _reserve_structured_bytes(content, budget)
    depth = 0
    for event in yaml.parse(content):
        if isinstance(
            event,
            (AliasEvent, MappingStartEvent, ScalarEvent, SequenceStartEvent),
        ):
            _reserve_structured_node(budget, depth)
        if isinstance(event, (MappingStartEvent, SequenceStartEvent)):
            depth += 1
        elif isinstance(event, (MappingEndEvent, SequenceEndEvent)):
            depth -= 1
            if depth < 0:
                raise InvalidStructuredDocument


def scan_yaml_sensitive_scalars(
    label: str,
    content: str,
    selected_lines: set[int] | None,
) -> YamlScanResult:
    if Path(label).suffix.casefold() not in {".yaml", ".yml"}:
        return YamlScanResult([], frozenset())
    budget = StructuredScanBudget()
    try:
        _preflight_yaml_source(content, budget)
        documents = list(yaml.compose_all(content))
    except (InvalidStructuredDocument, yaml.YAMLError, RecursionError):
        return YamlScanResult(
            [f"{label}: INVALID_YAML: changed YAML cannot be parsed safely"],
            frozenset(),
        )
    except StructuredScanLimit:
        return YamlScanResult(
            [f"{label}: INVALID_YAML: changed YAML exceeds structural limits"],
            frozenset(),
        )
    errors: list[str] = []
    safe_root_assignments: set[tuple[int, int]] = set()
    lines = content.splitlines()
    budget.nodes = 0
    schema_memo: dict[int, tuple[bool, int]] = {}
    schema_accounted: set[int] = set()
    descriptor_memo: dict[
        int,
        dict[str, ScalarNode | None],
    ] = {}
    descriptor_accounted: set[int] = set()
    expanded_schema_refs: set[tuple[int, int]] = set()

    def framework_root_assignment(
        key_node: ScalarNode,
    ) -> tuple[int, int] | None:
        source = content[key_node.start_mark.index : key_node.end_mark.index]
        for token in ('"sa-token"', "'sa-token'", "sa-token"):
            if not source.endswith(token):
                continue
            column = key_node.end_mark.column - len(token)
            if column < 0:
                return None
            token_start = key_node.end_mark.index - len(token)
            if content[token_start : key_node.end_mark.index] != token:
                return None
            return key_node.end_mark.line + 1, column
        return None

    def add_scalar_finding(origin: ScalarNode, value_node: ScalarNode) -> None:
        if selected_lines is not None:
            start_line = origin.start_mark.line + 1
            end_line = value_node.end_mark.line + 1
            if not any(
                start_line <= line_number <= end_line
                for line_number in selected_lines
            ):
                return
        if not is_safe_placeholder(value_node.value) and not is_approved_finding(
            label,
            origin.start_mark.line + 1,
            "YAML_SECRET_SCALAR",
            value_node.value,
            lines,
        ):
            errors.append(
                f"{label}:{origin.start_mark.line + 1}: YAML_SECRET_SCALAR"
            )

    def visit(
        node: Node | None,
        *,
        sensitive_container: bool = False,
        sensitive_origin: ScalarNode | None = None,
        schema_sensitive_definition: bool = False,
        schema_origin: ScalarNode | None = None,
        schema_document_root: Node | None = None,
        schema_ref_chain: frozenset[int] = frozenset(),
        schema_ref_scope_safe: bool = True,
        depth: int = 0,
    ) -> None:
        if node is not None:
            _reserve_structured_node(budget, depth)
        if isinstance(node, ScalarNode):
            if sensitive_container:
                add_scalar_finding(sensitive_origin or node, node)
            return
        if isinstance(node, MappingNode):
            current_ref_scope_safe = schema_ref_scope_safe and not (
                schema_document_root is not None
                and node is not schema_document_root
                and any(
                    isinstance(key_node, ScalarNode)
                    and key_node.value == "$id"
                    for key_node, _value_node in node.value
                )
            )
            if schema_sensitive_definition:
                if any(
                    isinstance(key_node, ScalarNode)
                    and key_node.value
                    in {"$dynamicAnchor", "$dynamicRef"}
                    for key_node, _value_node in node.value
                ):
                    raise UnsafeStructuredDocument(
                        "dynamic YAML Schema references are unsupported"
                    )
                for ref_node, target_node in (
                    (key_node, value_node)
                    for key_node, value_node in node.value
                    if isinstance(key_node, ScalarNode)
                    and key_node.value == "$ref"
                ):
                    if (
                        not current_ref_scope_safe
                        or schema_document_root is None
                        or not isinstance(target_node, ScalarNode)
                        or target_node.tag != "tag:yaml.org,2002:str"
                    ):
                        raise UnsafeStructuredDocument(
                            "sensitive YAML Schema reference "
                            "cannot be resolved safely"
                        )
                    target = _resolve_local_yaml_schema_ref(
                        target_node.value,
                        schema_document_root,
                        budget,
                        depth + 1,
                    )
                    visited_refs = schema_ref_chain | {id(node)}
                    if id(target) in visited_refs:
                        raise UnsafeStructuredDocument(
                            "cyclic sensitive YAML Schema reference"
                        )
                    if not _is_yaml_schema_definition(
                        target,
                        budget=budget,
                        memo=schema_memo,
                        accounted=schema_accounted,
                    ):
                        raise UnsafeStructuredDocument(
                            "sensitive YAML Schema reference "
                            "target cannot be verified"
                        )
                    ref_identity = (
                        id(schema_document_root),
                        id(target),
                    )
                    if ref_identity in expanded_schema_refs:
                        continue
                    expanded_schema_refs.add(ref_identity)
                    visit(
                        target,
                        schema_sensitive_definition=True,
                        schema_origin=schema_origin or ref_node,
                        schema_document_root=schema_document_root,
                        schema_ref_chain=visited_refs | {id(target)},
                        schema_ref_scope_safe=current_ref_scope_safe,
                        depth=depth + 1,
                    )
            descriptor_origin = next(
                iter(
                    _yaml_descriptor_origins(
                        node,
                        budget,
                        descriptor_memo,
                        descriptor_accounted,
                        depth=depth,
                    )
                ),
                None,
            )
            for key_node, value_node in node.value:
                if (
                    sensitive_container
                    and not _is_yaml_merge_key(key_node)
                    and (
                        not isinstance(value_node, ScalarNode)
                        or _is_safe_structured_secret(value_node.value)
                    )
                ):
                    visit(
                        key_node,
                        sensitive_container=True,
                        sensitive_origin=sensitive_origin,
                        depth=depth + 1,
                    )
                schema_key = (
                    key_node.value
                    if isinstance(key_node, ScalarNode)
                    else ""
                )
                schema_value_key = _schema_value_keyword(schema_key)
                merge_key = _is_yaml_merge_key(key_node)
                framework_root_assignment_start = (
                    framework_root_assignment(key_node)
                    if depth == 0
                    and isinstance(key_node, ScalarNode)
                    and key_node.tag == "tag:yaml.org,2002:str"
                    and key_node.value == "sa-token"
                    and isinstance(value_node, MappingNode)
                    else None
                )
                framework_root = (
                    framework_root_assignment_start is not None
                )
                if framework_root:
                    safe_root_assignments.add(framework_root_assignment_start)
                sensitive_key = (
                    isinstance(key_node, ScalarNode)
                    and _is_sensitive_structured_key(key_node.value)
                    and not framework_root
                )
                sensitive_schema = (
                    sensitive_key
                    and _is_yaml_schema_definition(
                        value_node,
                        budget=budget,
                        memo=schema_memo,
                        accounted=schema_accounted,
                    )
                )
                child_schema_sensitive = (
                    sensitive_schema
                    or descriptor_origin is not None
                    and (
                        merge_key
                        or schema_key in {"content", "schema"}
                        or schema_value_key == "examples"
                        and isinstance(value_node, MappingNode)
                    )
                    and isinstance(value_node, (MappingNode, SequenceNode))
                    or schema_sensitive_definition
                    and schema_key not in JSON_SCHEMA_DEFINITION_MAP_KEYS
                )
                child_schema_origin = (
                    key_node
                    if sensitive_schema
                    else descriptor_origin
                    if descriptor_origin is not None
                    and (
                        merge_key
                        or schema_key in {"content", "schema"}
                        or schema_value_key == "examples"
                        and isinstance(value_node, MappingNode)
                    )
                    else schema_origin
                )
                if schema_key in JSON_SCHEMA_DEFINITION_MAP_KEYS:
                    child_schema_origin = None

                child_sensitive = sensitive_container or (
                    sensitive_key and not sensitive_schema
                ) or (
                    schema_sensitive_definition
                    and schema_value_key is not None
                ) or (
                    descriptor_origin is not None
                    and (
                        schema_key.casefold() == "value"
                        or schema_value_key in JSON_SCHEMA_DIRECT_VALUE_KEYS
                        or schema_value_key in JSON_SCHEMA_COLLECTION_VALUE_KEYS
                        and isinstance(value_node, SequenceNode)
                    )
                )
                child_sensitive_origin = sensitive_origin
                if sensitive_key and not sensitive_schema:
                    child_sensitive_origin = key_node
                elif (
                    schema_sensitive_definition
                    and schema_value_key is not None
                ):
                    child_sensitive_origin = schema_origin or key_node
                elif descriptor_origin is not None and (
                    schema_key.casefold() == "value"
                    or schema_value_key in JSON_SCHEMA_DIRECT_VALUE_KEYS
                    or schema_value_key in JSON_SCHEMA_COLLECTION_VALUE_KEYS
                    and isinstance(value_node, SequenceNode)
                ):
                    child_sensitive_origin = descriptor_origin

                visit(
                    value_node,
                    sensitive_container=child_sensitive,
                    sensitive_origin=child_sensitive_origin,
                    schema_sensitive_definition=child_schema_sensitive,
                    schema_origin=child_schema_origin,
                    schema_document_root=schema_document_root,
                    schema_ref_chain=schema_ref_chain,
                    schema_ref_scope_safe=current_ref_scope_safe,
                    depth=depth + 1,
                )
        elif isinstance(node, SequenceNode):
            for child in node.value:
                visit(
                    child,
                    sensitive_container=sensitive_container,
                    sensitive_origin=sensitive_origin,
                    schema_sensitive_definition=schema_sensitive_definition,
                    schema_origin=schema_origin,
                    schema_document_root=schema_document_root,
                    schema_ref_chain=schema_ref_chain,
                    schema_ref_scope_safe=schema_ref_scope_safe,
                    depth=depth + 1,
                )

    try:
        for document in documents:
            visit(
                document,
                schema_document_root=document,
            )
    except (StructuredScanLimit, RecursionError):
        return YamlScanResult(
            [f"{label}: INVALID_YAML: changed YAML exceeds structural limits"],
            frozenset(),
        )
    except UnsafeStructuredDocument:
        return YamlScanResult(
            [f"{label}: INVALID_YAML: changed YAML cannot be parsed safely"],
            frozenset(),
        )
    return YamlScanResult(errors, frozenset(safe_root_assignments))


def _is_sensitive_structured_key(value: object) -> bool:
    return (
        isinstance(value, str)
        and re.fullmatch(SENSITIVE_KEY_PATTERN, value, re.IGNORECASE) is not None
    )


def _is_safe_structured_secret(value: object) -> bool:
    if value is None or isinstance(value, bool):
        return True
    return isinstance(value, str) and is_safe_placeholder(value)


def _contains_secret_assignment(content: str) -> bool:
    def decode_ascii_escape(match: re.Match[str]) -> str:
        codepoint = int(match.group("codepoint"), 16)
        return chr(codepoint) if codepoint < 128 else match.group(0)

    normalized = JSON_UNICODE_ESCAPE_PATTERN.sub(decode_ascii_escape, content)
    safe_placeholder_spans = _safe_placeholder_spans(normalized)
    for match in GENERIC_SECRET_ASSIGNMENT_PATTERN.finditer(normalized):
        if _quoted_assignment_has_direct_suffix(normalized, match) or (
            not _match_is_within_safe_placeholder(match, safe_placeholder_spans)
            and not is_safe_placeholder(match.group("value"))
        ):
            return True
    for match in UNQUOTED_SECRET_ASSIGNMENT_PATTERN.finditer(normalized):
        if (
            not _match_is_within_safe_placeholder(match, safe_placeholder_spans)
            and not is_safe_placeholder(match.group("value"))
        ):
            return True
    return False


def _typescript_lex_line(
    line: str,
    block_comment: bool,
    quote: str | None,
) -> tuple[str, str, bool, str | None]:
    comment_free = [" "] * len(line)
    structural = [" "] * len(line)
    index = 0
    while index < len(line):
        if block_comment:
            if line.startswith("*/", index):
                block_comment = False
                index += 2
            else:
                index += 1
            continue
        if quote is not None:
            comment_free[index] = line[index]
            if line[index] == "\\" and index + 1 < len(line):
                comment_free[index + 1] = line[index + 1]
                index += 2
                continue
            if line[index] == quote:
                quote = None
            index += 1
            continue
        if line.startswith("//", index):
            break
        if line.startswith("/*", index):
            block_comment = True
            index += 2
            continue
        character = line[index]
        comment_free[index] = character
        if character in {"\"", "'", "`"}:
            quote = character
        else:
            structural[index] = character
        index += 1
    return "".join(comment_free), "".join(structural), block_comment, quote


def _typescript_type_member_key_start(line: str) -> int | None:
    match = TYPESCRIPT_TYPE_MEMBER_PATTERN.fullmatch(line)
    if match is None:
        return None
    type_expression = match.group("type").rstrip(";, ")
    if TYPESCRIPT_SIMPLE_TYPE_PATTERN.fullmatch(type_expression) is None:
        return None
    return match.start("key")


def _typescript_type_member_assignments(
    label: str,
    lines: list[str],
) -> set[tuple[int, int]]:
    if Path(label).suffix.casefold() not in TYPESCRIPT_SUFFIXES:
        return set()
    safe_assignments: set[tuple[int, int]] = set()
    scope_stack: list[bool] = []
    block_comment = False
    quote: str | None = None
    invalid_structure = False
    for line_number, line in enumerate(lines, start=1):
        comment_free, structural, block_comment, quote = _typescript_lex_line(
            line,
            block_comment,
            quote,
        )
        if scope_stack[-1:] == [True]:
            key_start = _typescript_type_member_key_start(comment_free)
            if key_start is not None:
                safe_assignments.add((line_number, key_start))

        type_scope_openers = {
            match.end() - 1
            for match in TYPESCRIPT_TYPE_SCOPE_PATTERN.finditer(structural)
        }
        for index, character in enumerate(structural):
            if character == "{":
                scope_stack.append(
                    index in type_scope_openers
                    or bool(scope_stack and scope_stack[-1])
                )
            elif character == "}":
                if not scope_stack:
                    invalid_structure = True
                else:
                    scope_stack.pop()

    if invalid_structure or scope_stack or block_comment or quote is not None:
        return set()
    return safe_assignments


def _reserve_structured_bytes(
    source: str,
    budget: StructuredScanBudget,
) -> None:
    try:
        source_bytes = len(source.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise InvalidStructuredDocument from error
    budget.bytes += source_bytes
    if budget.bytes > MAX_STRUCTURED_BYTES:
        raise StructuredScanLimit


def _reserve_structured_node(
    budget: StructuredScanBudget,
    depth: int,
) -> None:
    budget.nodes += 1
    if budget.nodes > MAX_STRUCTURED_NODES or depth > MAX_STRUCTURED_DEPTH:
        raise StructuredScanLimit


def _skip_json_whitespace(source: str, index: int) -> int:
    while index < len(source) and source[index] in " \t\r\n":
        index += 1
    return index


def _validate_json_value(
    source: str,
    start: int,
    budget: StructuredScanBudget,
    root_depth: int,
) -> int:
    def fail(position: int) -> None:
        raise InvalidJsonSyntax(position)

    def reserve_node(depth: int) -> None:
        _reserve_structured_node(budget, depth)

    def parse_string(index: int, depth: int) -> int:
        reserve_node(depth)
        index += 1
        while index < len(source):
            character = source[index]
            if character == '"':
                return index + 1
            if ord(character) < 32:
                fail(index)
            if character != "\\":
                index += 1
                continue
            index += 1
            if index >= len(source):
                fail(index)
            escape = source[index]
            if escape == "u":
                hexadecimal = source[index + 1 : index + 5]
                if len(hexadecimal) != 4 or any(
                    character not in "0123456789abcdefABCDEF"
                    for character in hexadecimal
                ):
                    fail(index)
                index += 5
            elif escape in '"\\/bfnrt':
                index += 1
            else:
                fail(index)
        fail(index)

    def parse_number(index: int, depth: int) -> int:
        reserve_node(depth)
        if source[index] == "-":
            index += 1
            if index >= len(source):
                fail(index)
        if source[index] == "0":
            index += 1
        elif source[index] in "123456789":
            index += 1
            while index < len(source) and source[index] in "0123456789":
                index += 1
        else:
            fail(index)
        if index < len(source) and source[index] == ".":
            index += 1
            if index >= len(source) or source[index] not in "0123456789":
                fail(index)
            while index < len(source) and source[index] in "0123456789":
                index += 1
        if index < len(source) and source[index] in "eE":
            index += 1
            if index < len(source) and source[index] in "+-":
                index += 1
            if index >= len(source) or source[index] not in "0123456789":
                fail(index)
            while index < len(source) and source[index] in "0123456789":
                index += 1
        return index

    def parse_value(index: int, depth: int) -> int:
        if index >= len(source):
            fail(index)
        for constant in ("NaN", "Infinity", "-Infinity"):
            constant_end = index + len(constant)
            if source.startswith(constant, index) and (
                constant_end == len(source)
                or source[constant_end] in " \t\r\n,]}"
            ):
                raise UnsafeStructuredDocument("non-standard JSON constant")
        character = source[index]
        if character == '"':
            return parse_string(index, depth)
        if character == "{":
            reserve_node(depth)
            index = _skip_json_whitespace(source, index + 1)
            if index < len(source) and source[index] == "}":
                return index + 1
            while True:
                if index >= len(source) or source[index] != '"':
                    fail(index)
                index = _skip_json_whitespace(
                    source, parse_string(index, depth + 1)
                )
                if index >= len(source) or source[index] != ":":
                    fail(index)
                index = _skip_json_whitespace(source, index + 1)
                index = _skip_json_whitespace(
                    source, parse_value(index, depth + 1)
                )
                if index >= len(source):
                    fail(index)
                if source[index] == "}":
                    return index + 1
                if source[index] != ",":
                    fail(index)
                index = _skip_json_whitespace(source, index + 1)
        if character == "[":
            reserve_node(depth)
            index = _skip_json_whitespace(source, index + 1)
            if index < len(source) and source[index] == "]":
                return index + 1
            while True:
                index = _skip_json_whitespace(
                    source, parse_value(index, depth + 1)
                )
                if index >= len(source):
                    fail(index)
                if source[index] == "]":
                    return index + 1
                if source[index] != ",":
                    fail(index)
                index = _skip_json_whitespace(source, index + 1)
        if character == "-" or character in "0123456789":
            return parse_number(index, depth)
        for literal in ("true", "false", "null"):
            if source.startswith(literal, index):
                reserve_node(depth)
                return index + len(literal)
        fail(index)

    return parse_value(start, root_depth)


def _reserve_json_source(
    source: str,
    budget: StructuredScanBudget,
    root_depth: int,
) -> None:
    index = _skip_json_whitespace(source, 0)
    end = _validate_json_value(source, index, budget, root_depth)
    if _skip_json_whitespace(source, end) != len(source):
        raise InvalidJsonSyntax(end)
    _reserve_structured_bytes(source, budget)


def _parse_json_source(source: str) -> object:
    try:
        return json.loads(
            source,
            object_pairs_hook=JsonObject,
            parse_constant=_reject_json_constant,
            parse_float=_parse_finite_json_float,
            parse_int=_parse_bounded_json_int,
        )
    except (StructuredScanLimit, UnsafeStructuredDocument):
        raise
    except RecursionError as error:
        raise StructuredScanLimit from error
    except json.JSONDecodeError as error:
        raise InvalidStructuredDocument from error
    except (TypeError, ValueError, OverflowError) as error:
        raise UnsafeStructuredDocument from error


def _load_json_document(
    source: str,
    budget: StructuredScanBudget,
    root_depth: int,
) -> object:
    candidate_budget = StructuredScanBudget()
    candidate_budget.bytes = budget.bytes
    candidate_budget.nodes = budget.nodes
    _reserve_json_source(source, candidate_budget, root_depth)
    document = _parse_json_source(source)
    budget.bytes = candidate_budget.bytes
    budget.nodes = candidate_budget.nodes
    return document


def _looks_like_embedded_json(value: str) -> bool:
    if len(value) < 2:
        return False
    if (value[0], value[-1]) in {("{", "}"), ("[", "]")}:
        return True
    return value[0] == value[-1] == '"' and any(
        marker in value for marker in ("\\", "{", "[")
    )


def _is_json_media_type(value: object) -> bool:
    if not isinstance(value, str):
        return False
    media_type = value.partition(";")[0].strip().casefold()
    _top_level_type, separator, subtype = media_type.partition("/")
    return bool(separator) and (subtype == "json" or subtype.endswith("+json"))


def _har_json_text_mode(value: JsonObject) -> str | None:
    if not any(
        isinstance(key, str)
        and key.casefold() == "mimetype"
        and _is_json_media_type(child)
        for key, child in value
    ):
        return None
    encodings = [
        child
        for key, child in value
        if isinstance(key, str) and key.casefold() == "encoding"
    ]
    if not encodings:
        return "plain"
    if all(
        isinstance(encoding, str) and encoding.strip().casefold() == "base64"
        for encoding in encodings
    ):
        return "base64"
    return "unsupported"


def _decode_base64_json_source(value: str) -> str:
    try:
        encoded = value.encode("ascii")
        return base64.b64decode(encoded, validate=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError, UnicodeEncodeError, ValueError) as error:
        raise InvalidStructuredDocument from error


def _decode_local_json_pointer(reference: str) -> tuple[str, ...]:
    if not reference.startswith("#"):
        raise UnsafeStructuredDocument("external JSON Schema reference")
    fragment = reference[1:]
    cursor = 0
    while (percent := fragment.find("%", cursor)) >= 0:
        escape = fragment[percent + 1 : percent + 3]
        if len(escape) != 2 or any(
            character not in "0123456789abcdefABCDEF"
            for character in escape
        ):
            raise UnsafeStructuredDocument("malformed JSON Schema fragment")
        cursor = percent + 3
    try:
        pointer = unquote_to_bytes(fragment).decode("utf-8")
    except (UnicodeDecodeError, UnicodeEncodeError, ValueError) as error:
        raise UnsafeStructuredDocument(
            "malformed JSON Schema fragment"
        ) from error
    if not pointer:
        return ()
    if not pointer.startswith("/"):
        raise UnsafeStructuredDocument("unsupported JSON Schema fragment")

    tokens: list[str] = []
    for encoded_token in pointer[1:].split("/"):
        component: list[str] = []
        index = 0
        while index < len(encoded_token):
            character = encoded_token[index]
            if character != "~":
                component.append(character)
                index += 1
                continue
            if index + 1 >= len(encoded_token):
                raise UnsafeStructuredDocument("malformed JSON Pointer escape")
            escaped = encoded_token[index + 1]
            if escaped == "0":
                component.append("~")
            elif escaped == "1":
                component.append("/")
            else:
                raise UnsafeStructuredDocument("malformed JSON Pointer escape")
            index += 2
        tokens.append("".join(component))
    return tuple(tokens)


def _resolve_local_json_schema_ref(
    reference: str,
    document: object,
    budget: StructuredScanBudget,
    depth: int,
) -> object:
    tokens = _decode_local_json_pointer(reference)
    _reserve_structured_node(budget, depth)
    target = document
    for offset, token in enumerate(tokens, start=1):
        _reserve_structured_node(budget, depth + offset)
        if isinstance(target, JsonObject):
            matches = [
                child
                for key, child in target
                if isinstance(key, str) and key == token
            ]
            if len(matches) != 1:
                raise UnsafeStructuredDocument(
                    "ambiguous or missing JSON Pointer member"
                )
            target = matches[0]
            continue
        if type(target) is list:
            if re.fullmatch(r"(?:0|[1-9][0-9]*)", token) is None:
                raise UnsafeStructuredDocument("invalid JSON Pointer index")
            try:
                index = int(token)
            except (ValueError, OverflowError) as error:
                raise UnsafeStructuredDocument(
                    "invalid JSON Pointer index"
                ) from error
            if index >= len(target):
                raise UnsafeStructuredDocument(
                    "missing JSON Pointer array item"
                )
            target = target[index]
            continue
        raise UnsafeStructuredDocument(
            "JSON Pointer traverses a scalar value"
        )
    if not isinstance(target, (JsonObject, bool)):
        raise UnsafeStructuredDocument(
            "JSON Schema reference target is not a schema"
        )
    return target


def _har_child_context(
    key: object,
    current_context: str,
    in_har_envelope: bool,
) -> str:
    if not in_har_envelope or not isinstance(key, str):
        return ""
    folded_key = key.casefold()
    if current_context == "har-root" and folded_key == "log":
        return "log"
    if current_context == "log" and folded_key == "entries":
        return "entries"
    if current_context == "entry" and folded_key == "request":
        return "request"
    if current_context == "entry" and folded_key == "response":
        return "response"
    if current_context == "request" and folded_key == "postdata":
        return "body"
    if current_context == "response" and folded_key == "content":
        return "body"
    return ""


def _scan_loaded_json(
    label: str,
    document: object,
    budget: StructuredScanBudget,
    suffix: str,
    initial_depth: int = 0,
) -> list[str]:
    errors: list[str] = []
    schema_definition_memo: dict[int, tuple[bool, int]] = {}
    expanded_schema_refs: set[tuple[int, int]] = set()
    pending = [
        JsonScanFrame(
            document,
            initial_depth,
            None,
            "har-root" if suffix == ".har" else "",
            suffix == ".har",
            False,
            False,
            False,
            "",
            document,
            frozenset(),
            True,
        )
    ]
    while pending:
        (
            value,
            depth,
            required_json,
            har_context,
            in_har_envelope,
            sensitive_container,
            schema_sensitive_definition,
            schema_reference_context,
            named_member_context,
            schema_document_root,
            schema_ref_chain,
            schema_ref_scope_safe,
        ) = pending.pop()
        if depth > MAX_STRUCTURED_DEPTH:
            return [f"{label}: INVALID_JSON: changed JSON exceeds structural limits"]
        if required_json is not None and not isinstance(value, str):
            return [
                f"{label}: INVALID_JSON: declared JSON text cannot be parsed safely"
            ]
        if har_context == "entries" and type(value) is not list:
            return [
                f"{label}: INVALID_JSON: HAR entries must be an array"
            ]
        if har_context in {
            "har-root",
            "log",
            "entry",
            "request",
            "response",
            "body",
        } and not isinstance(value, JsonObject):
            return [
                f"{label}: INVALID_JSON: HAR {har_context} carrier must be an object"
            ]
        if (
            sensitive_container
            and not isinstance(value, (JsonObject, list))
            and not _is_safe_structured_secret(value)
        ):
            errors.append(f"{label}: JSON_SECRET_SCALAR")
        if isinstance(value, JsonObject):
            opaque_member_names = bool(named_member_context)
            current_ref_scope_safe = schema_ref_scope_safe and not (
                not opaque_member_names
                and value is not schema_document_root
                and any(key == "$id" for key, _child in value)
            )
            if sensitive_container and not opaque_member_names:
                errors.extend(
                    f"{label}: JSON_SECRET_SCALAR"
                    for key, child in value
                    if (
                        isinstance(child, (JsonObject, list))
                        or _is_safe_structured_secret(child)
                    )
                    and not _is_safe_structured_secret(key)
                )
            if schema_sensitive_definition or schema_reference_context:
                if any(
                    key in {"$dynamicAnchor", "$dynamicRef"}
                    for key, _child in value
                ):
                    return [
                        f"{label}: INVALID_JSON: dynamic sensitive schema "
                        "references are unsupported"
                    ]
                for key, child in value:
                    if key != "$ref":
                        continue
                    if not current_ref_scope_safe:
                        return [
                            f"{label}: INVALID_JSON: sensitive schema reference "
                            "scope cannot be verified"
                        ]
                    if not isinstance(child, str):
                        return [
                            f"{label}: INVALID_JSON: sensitive schema reference "
                            "cannot be resolved safely"
                        ]
                    try:
                        target = _resolve_local_json_schema_ref(
                            child,
                            schema_document_root,
                            budget,
                            depth + 1,
                        )
                    except UnsafeStructuredDocument:
                        return [
                            f"{label}: INVALID_JSON: sensitive schema reference "
                            "cannot be resolved safely"
                        ]
                    except StructuredScanLimit:
                        return [
                            f"{label}: INVALID_JSON: changed JSON exceeds "
                            "structural limits"
                        ]
                    visited_refs = schema_ref_chain | {id(value)}
                    if id(target) in visited_refs:
                        return [
                            f"{label}: INVALID_JSON: cyclic sensitive schema "
                            "reference"
                        ]
                    if not _is_json_schema_definition(
                        target,
                        memo=schema_definition_memo,
                    ):
                        return [
                            f"{label}: INVALID_JSON: sensitive schema reference "
                            "target cannot be verified"
                        ]
                    ref_identity = (
                        id(schema_document_root),
                        id(target),
                    )
                    if ref_identity in expanded_schema_refs:
                        continue
                    expanded_schema_refs.add(ref_identity)
                    pending.append(
                        JsonScanFrame(
                            target,
                            depth + 1,
                            None,
                            "",
                            False,
                            False,
                            True,
                            True,
                            "",
                            schema_document_root,
                            visited_refs | {id(target)},
                            current_ref_scope_safe,
                        )
                    )
            descriptors = (
                []
                if opaque_member_names
                else [
                    child
                    for key, child in value
                    if isinstance(key, str)
                    and key.casefold() in {"key", "name"}
                    and _is_sensitive_structured_key(child)
                ]
            )
            if descriptors and not sensitive_container:
                structured_values = [
                    child
                    for key, child in value
                    if isinstance(key, str)
                    and (
                        key.casefold() == "value"
                        or _schema_value_keyword(key) is not None
                    )
                ]
                if structured_values and any(
                    not isinstance(child, (JsonObject, list))
                    and not _is_safe_structured_secret(child)
                    for child in structured_values
                ):
                    errors.append(f"{label}: JSON_SECRET_SCALAR")
            for key, child in value:
                if (
                    _is_sensitive_structured_key(key)
                    and not opaque_member_names
                    and not sensitive_container
                    and not isinstance(child, (JsonObject, list))
                    and not _is_safe_structured_secret(child)
                ):
                    errors.append(f"{label}: JSON_SECRET_SCALAR")
                if (
                    schema_sensitive_definition
                    and isinstance(key, str)
                    and _schema_value_keyword(key) is not None
                    and not isinstance(child, (JsonObject, list))
                    and not _is_safe_structured_secret(child)
                ):
                    errors.append(f"{label}: JSON_SECRET_SCALAR")
            json_text_mode = (
                _har_json_text_mode(value) if har_context == "body" else None
            )
            for key, child in value:
                folded_key = (
                    key.casefold()
                    if isinstance(key, str) and not opaque_member_names
                    else ""
                )
                schema_key = (
                    key
                    if isinstance(key, str) and not opaque_member_names
                    else ""
                )
                schema_value_key = _schema_value_keyword(schema_key)
                child_context = _har_child_context(
                    key, har_context, in_har_envelope
                )
                child_is_container = isinstance(child, (JsonObject, list))
                sensitive_schema = (
                    not opaque_member_names
                    and _is_sensitive_structured_key(key)
                    and _is_json_schema_definition(
                        child,
                        memo=schema_definition_memo,
                    )
                )
                descriptor_context = (
                    bool(descriptors)
                    and (
                        schema_key in {"content", "schema"}
                        or schema_value_key == "examples"
                        and isinstance(child, JsonObject)
                    )
                    and child_is_container
                )
                schema_map_carrier = (
                    (
                        schema_sensitive_definition
                        or schema_reference_context
                    )
                    and schema_key in JSON_SCHEMA_SCHEMA_MAP_KEYS
                    and isinstance(child, JsonObject)
                )
                named_value_map_carrier = (
                    schema_value_key == "examples"
                    and isinstance(child, JsonObject)
                    and (
                        schema_sensitive_definition
                        or bool(descriptors)
                    )
                )
                child_named_member_context = (
                    "schema"
                    if schema_map_carrier
                    else "value"
                    if named_value_map_carrier
                    else ""
                )
                descriptor_value_container = bool(descriptors) and (
                    folded_key == "value"
                    or schema_value_key in JSON_SCHEMA_DIRECT_VALUE_KEYS
                    or schema_value_key in JSON_SCHEMA_COLLECTION_VALUE_KEYS
                    and type(child) is list
                )
                if named_member_context == "schema":
                    child_schema_sensitive_definition = (
                        isinstance(key, str)
                        and _is_sensitive_structured_key(key)
                        and _is_json_schema_definition(
                            child,
                            memo=schema_definition_memo,
                        )
                    )
                elif named_member_context == "value":
                    child_schema_sensitive_definition = isinstance(
                        child, JsonObject
                    )
                elif child_named_member_context:
                    child_schema_sensitive_definition = False
                else:
                    child_schema_sensitive_definition = (
                        sensitive_schema
                        or descriptor_context
                        or schema_sensitive_definition
                    )
                if named_member_context == "schema":
                    child_schema_reference_context = True
                elif named_member_context == "value":
                    child_schema_reference_context = isinstance(
                        child, JsonObject
                    )
                elif child_named_member_context:
                    child_schema_reference_context = False
                else:
                    child_schema_reference_context = (
                        sensitive_schema
                        or descriptor_context
                        or schema_sensitive_definition
                        or schema_reference_context
                    )
                child_sensitive_container = sensitive_container
                if (
                    named_member_context == "value"
                    and not isinstance(child, JsonObject)
                ):
                    child_sensitive_container = True
                elif (
                    not child_named_member_context
                    and child_is_container
                    and not opaque_member_names
                    and (
                        _is_sensitive_structured_key(key)
                        and not sensitive_schema
                        or descriptor_value_container
                        or schema_sensitive_definition
                        and schema_value_key is not None
                    )
                ):
                    child_sensitive_container = True
                pending.append(
                    JsonScanFrame(
                        child,
                        depth + 1,
                        json_text_mode
                        if json_text_mode is not None
                        and isinstance(key, str)
                        and key.casefold() == "text"
                        else None,
                        child_context,
                        in_har_envelope,
                        child_sensitive_container,
                        child_schema_sensitive_definition,
                        child_schema_reference_context,
                        child_named_member_context,
                        schema_document_root,
                        schema_ref_chain,
                        current_ref_scope_safe,
                    )
                )
        elif isinstance(value, list):
            child_context = "entry" if har_context == "entries" else ""
            pending.extend(
                JsonScanFrame(
                    child,
                    depth + 1,
                    None,
                    child_context,
                    in_har_envelope,
                    sensitive_container,
                    schema_sensitive_definition,
                    schema_reference_context,
                    "",
                    schema_document_root,
                    schema_ref_chain,
                    schema_ref_scope_safe,
                )
                for child in value
            )
        elif isinstance(value, str):
            if required_json == "unsupported":
                return [
                    f"{label}: INVALID_JSON: declared JSON text cannot be parsed safely"
                ]
            try:
                source = (
                    _decode_base64_json_source(value)
                    if required_json == "base64"
                    else value
                    if required_json is not None
                    else value.strip(" \t\r\n")
                )
            except InvalidStructuredDocument:
                return [
                    f"{label}: INVALID_JSON: declared JSON text cannot be parsed safely"
                ]
            if required_json is None and not (
                source.startswith('"')
                and source.endswith('"')
                and _looks_like_embedded_json(source)
            ):
                candidate_errors = _scan_json_candidates_in_text(
                    label, source, budget, depth + 1, "json"
                )
                if any("INVALID_JSON" in error for error in candidate_errors):
                    return candidate_errors
                errors.extend(candidate_errors)
                if not candidate_errors and _contains_secret_assignment(source):
                    errors.append(f"{label}: JSON_SECRET_SCALAR")
                continue
            try:
                embedded = _load_json_document(source, budget, depth + 1)
            except InvalidStructuredDocument:
                if required_json is not None:
                    return [
                        f"{label}: INVALID_JSON: declared JSON text cannot be parsed safely"
                    ]
                candidate_errors = _scan_json_candidates_in_text(
                    label, source, budget, depth + 1, "json"
                )
                if any("INVALID_JSON" in error for error in candidate_errors):
                    return candidate_errors
                errors.extend(candidate_errors)
                if not candidate_errors and _contains_secret_assignment(source):
                    errors.append(f"{label}: JSON_SECRET_SCALAR")
                continue
            except UnsafeStructuredDocument:
                return [
                    f"{label}: INVALID_JSON: changed JSON cannot be parsed safely"
                ]
            except StructuredScanLimit:
                return [
                    f"{label}: INVALID_JSON: changed JSON exceeds structural limits"
                ]
            pending.append(
                JsonScanFrame(
                    embedded,
                    depth + 1,
                    None,
                    "",
                    False,
                    False,
                    False,
                    False,
                    "",
                    embedded,
                    frozenset(),
                    True,
                )
            )
    return errors


def _next_json_container_start(content: str, index: int) -> int:
    object_start = content.find("{", index)
    array_start = content.find("[", index)
    if object_start < 0:
        return array_start
    if array_start < 0:
        return object_start
    return min(object_start, array_start)


def _structured_json_error(label: str, finding_kind: str, limit: bool) -> str:
    if finding_kind == "xml":
        detail = (
            "embedded JSON exceeds structural limits"
            if limit
            else "embedded JSON cannot be parsed safely"
        )
        return f"{label}: INVALID_XML: {detail}"
    detail = (
        "changed JSON exceeds structural limits"
        if limit
        else "changed JSON cannot be parsed safely"
    )
    return f"{label}: INVALID_JSON: {detail}"


def _scan_json_candidates_in_text(
    label: str,
    content: str,
    budget: StructuredScanBudget,
    depth: int,
    finding_kind: str,
) -> list[str]:
    errors: list[str] = []
    cursor = 0
    while (start := _next_json_container_start(content, cursor)) >= 0:
        candidate_budget = StructuredScanBudget()
        candidate_budget.bytes = budget.bytes
        candidate_budget.nodes = budget.nodes
        try:
            end = _validate_json_value(
                content, start, candidate_budget, depth
            )
        except InvalidJsonSyntax as error:
            examined_end = max(start + 1, min(len(content), error.position + 1))
            try:
                examined_bytes = len(content[start:examined_end].encode("utf-8"))
            except UnicodeEncodeError:
                return [_structured_json_error(label, finding_kind, False)]
            budget.bytes += max(1, examined_bytes)
            budget.nodes += max(1, candidate_budget.nodes - budget.nodes)
            if (
                budget.bytes > MAX_STRUCTURED_BYTES
                or budget.nodes > MAX_STRUCTURED_NODES
            ):
                return [_structured_json_error(label, finding_kind, True)]
            cursor = start + 1
            continue
        except UnsafeStructuredDocument:
            return [_structured_json_error(label, finding_kind, False)]
        except StructuredScanLimit:
            return [_structured_json_error(label, finding_kind, True)]

        candidate = content[start:end]
        try:
            _reserve_structured_bytes(candidate, candidate_budget)
            document = _parse_json_source(candidate)
        except StructuredScanLimit:
            return [_structured_json_error(label, finding_kind, True)]
        except (InvalidStructuredDocument, UnsafeStructuredDocument):
            return [_structured_json_error(label, finding_kind, False)]

        budget.bytes = candidate_budget.bytes
        budget.nodes = candidate_budget.nodes
        findings = _scan_loaded_json(label, document, budget, "", depth)
        if any("INVALID_JSON" in finding for finding in findings):
            limit = any("exceeds structural limits" in finding for finding in findings)
            return [_structured_json_error(label, finding_kind, limit)]
        if finding_kind == "xml":
            errors.extend(
                f"{label}: XML_SECRET_SCALAR"
                for finding in findings
                if "JSON_SECRET_SCALAR" in finding
            )
        else:
            errors.extend(
                finding
                for finding in findings
                if "JSON_SECRET_SCALAR" in finding
            )
        cursor = end
    return errors


def scan_json_sensitive_scalars(label: str, content: str) -> list[str]:
    suffix = Path(label).suffix.casefold()
    if suffix not in {".har", ".json"}:
        return []
    budget = StructuredScanBudget()
    try:
        document = _load_json_document(content, budget, 0)
    except (InvalidStructuredDocument, UnsafeStructuredDocument):
        return [f"{label}: INVALID_JSON: changed JSON cannot be parsed safely"]
    except StructuredScanLimit:
        return [f"{label}: INVALID_JSON: changed JSON exceeds structural limits"]
    return _scan_loaded_json(label, document, budget, suffix)


def _xml_expanded_name(value: str) -> tuple[str, str]:
    if value.startswith("{"):
        namespace, separator, local_name = value[1:].partition("}")
        if separator:
            return namespace, local_name
    return "", value.rsplit(":", 1)[-1]


def _xml_local_name(value: str) -> str:
    return _xml_expanded_name(value)[1]


XML_DESCRIPTOR_VALUE_NAMES = {
    "key": "value",
    "name": "value",
    "param-name": "param-value",
    "property-name": "property-value",
}
XML_SENSITIVE_ELEMENT_VALUE_ATTRIBUTES = {
    "content",
    "data",
    "default",
    "text",
    "value",
}
XML_HIDDEN_DELIMITERS = {
    "comment": ("<!--", "-->"),
    "cdata": ("<![CDATA[", "]]>"),
    "pi": ("<?", "?>"),
}
XML_NAMESPACE_URI = "http://www.w3.org/XML/1998/namespace"
XMLNS_NAMESPACE_URI = "http://www.w3.org/2000/xmlns/"
XML_NAME_START_CHAR_CLASS = (
    r":A-Z_a-z"
    r"\u00C0-\u00D6"
    r"\u00D8-\u00F6"
    r"\u00F8-\u02FF"
    r"\u0370-\u037D"
    r"\u037F-\u1FFF"
    r"\u200C-\u200D"
    r"\u2070-\u218F"
    r"\u2C00-\u2FEF"
    r"\u3001-\uD7FF"
    r"\uF900-\uFDCF"
    r"\uFDF0-\uFFFD"
    r"\U00010000-\U000EFFFF"
)
XML_NAME_CHAR_CLASS = (
    XML_NAME_START_CHAR_CLASS
    + r"\-.0-9\u00B7\u0300-\u036F\u203F-\u2040"
)
XML_NAME_START_CHARACTER_PATTERN = re.compile(
    rf"[{XML_NAME_START_CHAR_CLASS}]"
)
XML_NAME_CHARACTER_PATTERN = re.compile(rf"[{XML_NAME_CHAR_CLASS}]")
XML_HIDDEN_TAG_PATTERN = re.compile(
    r"<\s*(?P<closing>/)?\s*"
    rf"(?P<name>[{XML_NAME_START_CHAR_CLASS}]"
    rf"[{XML_NAME_CHAR_CLASS}]*)(?P<body>[^<>]*)>",
    re.DOTALL,
)
XML_CHARACTER_REFERENCE_PATTERN = re.compile(
    r"&(?:#(?P<decimal>[0-9]+)|#(?:x|X)(?P<hexadecimal>[0-9A-Fa-f]+)|"
    r"(?P<named>amp|lt|gt|apos|quot));"
)
XML_FORBIDDEN_DECLARATION_PATTERN = re.compile(
    r"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE
)


class XmlHiddenSection(NamedTuple):
    kind: str
    content: str
    namespaces: dict[str, str]


def _preflight_xml_source(
    source: str,
    budget: StructuredScanBudget,
) -> list[XmlHiddenSection]:
    parser = expat.ParserCreate(namespace_separator="}")
    depth = 0
    active_namespaces = {"xml": XML_NAMESPACE_URI}
    namespace_stack: list[list[tuple[str, bool, str]]] = []
    pending_namespaces: dict[str, str] = {}
    hidden_sections: list[XmlHiddenSection] = []
    cdata_parts: list[str] | None = None

    def namespace_snapshot(content: str) -> dict[str, str]:
        referenced_prefixes: set[str] = set()
        index = 0
        while index < len(content):
            if XML_NAME_START_CHARACTER_PATTERN.fullmatch(content[index]) is None:
                index += 1
                continue
            name_start = index
            index += 1
            while (
                index < len(content)
                and XML_NAME_CHARACTER_PATTERN.fullmatch(content[index]) is not None
            ):
                index += 1
            prefix, separator, local_name = content[name_start:index].partition(":")
            if separator and prefix and local_name and ":" not in local_name:
                referenced_prefixes.add(prefix)
        referenced_prefixes.update({"", "xml"})
        return {
            prefix: active_namespaces[prefix]
            for prefix in referenced_prefixes
            if prefix in active_namespaces
        }

    def start_namespace(prefix: str | None, namespace: str | None) -> None:
        pending_namespaces[prefix or ""] = namespace or ""

    def start_element(_name: str, _attributes: dict[str, str]) -> None:
        nonlocal depth
        _reserve_structured_node(budget, depth)
        namespace_changes = [
            (
                prefix,
                prefix in active_namespaces,
                active_namespaces.get(prefix, ""),
            )
            for prefix in pending_namespaces
        ]
        active_namespaces.update(pending_namespaces)
        pending_namespaces.clear()
        namespace_stack.append(namespace_changes)
        depth += 1

    def end_element(_name: str) -> None:
        nonlocal depth
        depth -= 1
        if depth < 0 or not namespace_stack:
            raise InvalidStructuredDocument
        for prefix, had_previous, previous_namespace in reversed(
            namespace_stack.pop()
        ):
            if had_previous:
                active_namespaces[prefix] = previous_namespace
            else:
                del active_namespaces[prefix]

    def reject_declaration(*_arguments: object) -> None:
        raise InvalidStructuredDocument

    def append_hidden(kind: str, content: str) -> None:
        _reserve_structured_node(budget, depth)
        hidden_sections.append(
            XmlHiddenSection(kind, content, namespace_snapshot(content))
        )

    def start_cdata() -> None:
        nonlocal cdata_parts
        cdata_parts = []

    def append_character_data(content: str) -> None:
        if cdata_parts is not None:
            cdata_parts.append(content)

    def end_cdata() -> None:
        nonlocal cdata_parts
        if cdata_parts is None:
            raise InvalidStructuredDocument
        append_hidden("cdata", "".join(cdata_parts))
        cdata_parts = None

    def processing_instruction(target: str, data: str) -> None:
        append_hidden("pi", f"{target} {data}" if data else target)

    parser.StartNamespaceDeclHandler = start_namespace
    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element
    parser.CommentHandler = lambda content: append_hidden("comment", content)
    parser.StartCdataSectionHandler = start_cdata
    parser.CharacterDataHandler = append_character_data
    parser.EndCdataSectionHandler = end_cdata
    parser.ProcessingInstructionHandler = processing_instruction
    parser.StartDoctypeDeclHandler = reject_declaration
    parser.EntityDeclHandler = reject_declaration
    parser.UnparsedEntityDeclHandler = reject_declaration
    parser.ExternalEntityRefHandler = lambda *_arguments: 0
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    try:
        parser.Parse(source, True)
    except (InvalidStructuredDocument, StructuredScanLimit):
        raise
    except (expat.ExpatError, TypeError, ValueError) as error:
        raise InvalidStructuredDocument from error
    if (
        depth != 0
        or namespace_stack
        or pending_namespaces
        or cdata_parts is not None
    ):
        raise InvalidStructuredDocument
    return hidden_sections


def _load_xml_document(
    source: str,
    budget: StructuredScanBudget,
) -> tuple[ElementTree.Element, list[XmlHiddenSection]]:
    candidate_budget = StructuredScanBudget()
    candidate_budget.bytes = budget.bytes
    candidate_budget.nodes = budget.nodes
    _reserve_structured_bytes(source, candidate_budget)
    hidden_sections = _preflight_xml_source(source, candidate_budget)
    try:
        root = ElementTree.fromstring(source)
    except RecursionError as error:
        raise StructuredScanLimit from error
    except (ElementTree.ParseError, TypeError, ValueError) as error:
        raise InvalidStructuredDocument from error
    budget.bytes = candidate_budget.bytes
    budget.nodes += len(hidden_sections)
    return root, hidden_sections


def _xml_namespaces_pair(
    kind: str,
    namespace: str,
    descriptor_kind: str,
    descriptor_namespace: str,
) -> bool:
    if namespace == descriptor_namespace:
        return True
    if kind == descriptor_kind:
        return False
    return (
        kind == "attribute" and not namespace
    ) or descriptor_kind == "attribute" and not descriptor_namespace


def _scan_xml_tree(
    label: str,
    root: ElementTree.Element,
    budget: StructuredScanBudget,
    root_depth: int = 0,
) -> list[str]:
    errors: list[str] = []
    pending: list[tuple[ElementTree.Element, int]] = [(root, root_depth)]
    while pending:
        element, depth = pending.pop()
        try:
            _reserve_structured_node(budget, depth)
        except StructuredScanLimit:
            return [f"{label}: INVALID_XML: changed XML exceeds structural limits"]

        attributes = [
            (*_xml_expanded_name(name), value)
            for name, value in element.attrib.items()
        ]
        children = list(element)
        child_names = [
            (child, *_xml_expanded_name(child.tag))
            for child in children
        ]
        direct_text_segments = [
            element.text or "",
            *(child.tail or "" for child in children),
        ]
        for text_segment in direct_text_segments:
            json_errors = _scan_json_candidates_in_text(
                label, text_segment, budget, depth + 1, "xml"
            )
            if any("INVALID_XML" in error for error in json_errors):
                return json_errors
            errors.extend(json_errors)

        sensitive_element = _is_sensitive_structured_key(
            _xml_local_name(element.tag)
        )
        if sensitive_element:
            text_value = "".join(element.itertext()).strip()
            if not _is_safe_structured_secret(text_value):
                errors.append(f"{label}: XML_SECRET_SCALAR")
            for _namespace, local_name, value in attributes:
                if (
                    local_name.casefold()
                    in XML_SENSITIVE_ELEMENT_VALUE_ATTRIBUTES
                    and not _is_safe_structured_secret(value)
                ):
                    errors.append(f"{label}: XML_SECRET_SCALAR")
        for _namespace, local_name, value in attributes:
            if _is_sensitive_structured_key(
                local_name
            ) and not _is_safe_structured_secret(value):
                errors.append(f"{label}: XML_SECRET_SCALAR")

        attribute_descriptor_names = {
            ("attribute", namespace, local_name.casefold())
            for namespace, local_name, value in attributes
            if local_name.casefold() in XML_DESCRIPTOR_VALUE_NAMES
            and _is_sensitive_structured_key(value)
        }
        descriptor_names = set(attribute_descriptor_names)
        descriptor_names.update(
            ("element", namespace, local_name.casefold())
            for child, namespace, local_name in child_names
            if local_name.casefold() in XML_DESCRIPTOR_VALUE_NAMES
            and _is_sensitive_structured_key(
                "".join(child.itertext()).strip()
            )
        )
        if descriptor_names:
            value_names = tuple(
                (kind, namespace, XML_DESCRIPTOR_VALUE_NAMES[local_name])
                for kind, namespace, local_name in descriptor_names
            )

            def is_descriptor_value(
                kind: str,
                namespace: str,
                local_name: str,
            ) -> bool:
                folded_name = local_name.casefold()
                return any(
                    folded_name == value_name
                    and _xml_namespaces_pair(
                        kind,
                        namespace,
                        descriptor_kind,
                        descriptor_namespace,
                    )
                    for (
                        descriptor_kind,
                        descriptor_namespace,
                        value_name,
                    ) in value_names
                )

            structured_values = [
                value
                for namespace, local_name, value in attributes
                if is_descriptor_value("attribute", namespace, local_name)
            ]
            structured_values.extend(
                "".join(child.itertext()).strip()
                for child, namespace, local_name in child_names
                if is_descriptor_value("element", namespace, local_name)
            )
            direct_text = "".join(direct_text_segments).strip()
            if direct_text:
                structured_values.append(direct_text)
            if structured_values and any(
                not _is_safe_structured_secret(value) for value in structured_values
            ):
                errors.append(f"{label}: XML_SECRET_SCALAR")

        pending.extend((child, depth + 1) for child in children)
    return errors


def _contains_unstructured_secret(content: str) -> bool:
    if any(pattern.search(content) for _, pattern in HIGH_CONFIDENCE_PATTERNS):
        return True
    return any(
        not is_safe_placeholder(match.group("value"))
        for match in CREDENTIAL_URI_PATTERN.finditer(content)
    )


def _contains_sensitive_pi_target(hidden: str) -> bool:
    parts = hidden.strip().split(None, 1)
    if len(parts) != 2:
        return False
    target, value = parts
    return _is_sensitive_structured_key(
        _xml_local_name(target)
    ) and not _is_safe_structured_secret(value)


def _iter_xml_hidden_sections(content: str) -> Iterator[tuple[str, str]]:
    positions = {
        kind: content.find(opener)
        for kind, (opener, _closer) in XML_HIDDEN_DELIMITERS.items()
    }
    while active := [
        (position, kind) for kind, position in positions.items() if position >= 0
    ]:
        start, kind = min(active)
        opener, closer = XML_HIDDEN_DELIMITERS[kind]
        end = content.find(closer, start + len(opener))
        if end < 0:
            positions[kind] = -1
            continue
        yield kind, content[start + len(opener) : end]
        cursor = end + len(closer)
        positions = {
            candidate_kind: content.find(candidate_opener, cursor)
            for candidate_kind, (
                candidate_opener,
                _candidate_closer,
            ) in XML_HIDDEN_DELIMITERS.items()
        }


def _decode_xml_character_references(value: str) -> str:
    named_references = {
        "amp": "&",
        "lt": "<",
        "gt": ">",
        "apos": "'",
        "quot": '"',
    }

    def replace(match: re.Match[str]) -> str:
        if decimal := match.group("decimal"):
            if len(decimal) > 7:
                raise UnsafeStructuredDocument("oversized XML character reference")
            try:
                codepoint = int(decimal, 10)
            except (ValueError, OverflowError) as error:
                raise UnsafeStructuredDocument from error
        elif hexadecimal := match.group("hexadecimal"):
            if len(hexadecimal) > 6:
                raise UnsafeStructuredDocument("oversized XML character reference")
            try:
                codepoint = int(hexadecimal, 16)
            except (ValueError, OverflowError) as error:
                raise UnsafeStructuredDocument from error
        else:
            return named_references[match.group("named")]
        if codepoint > 0x10FFFF or 0xD800 <= codepoint <= 0xDFFF:
            return match.group(0)
        return chr(codepoint)

    return XML_CHARACTER_REFERENCE_PATTERN.sub(replace, value)


def _find_encoded_hidden_tag_end(
    content: str,
    start: int,
) -> tuple[int, int] | None:
    index = start
    while index < len(content) and content[index].isspace():
        index += 1
    if index < len(content) and content[index] == "/":
        index += 1
        while index < len(content) and content[index].isspace():
            index += 1
    if (
        index >= len(content)
        or XML_NAME_START_CHARACTER_PATTERN.fullmatch(content[index]) is None
    ):
        return None
    index += 1
    while (
        index < len(content)
        and XML_NAME_CHARACTER_PATTERN.fullmatch(content[index]) is not None
    ):
        index += 1
    quote: str | None = None
    while index < len(content):
        character = content[index]
        if quote is not None:
            if character == quote:
                quote = None
                index += 1
                continue
            reference = XML_CHARACTER_REFERENCE_PATTERN.match(content, index)
            if reference is not None and _decode_xml_character_references(
                reference.group(0)
            ) == quote:
                quote = None
                index = reference.end()
                continue
            index += 1
            continue
        if character in "\"'`":
            quote = character
            index += 1
            continue
        if character == ">":
            return index, index + 1
        if character == "<":
            raise UnsafeStructuredDocument("unclosed encoded XML hidden element")
        reference = XML_CHARACTER_REFERENCE_PATTERN.match(content, index)
        if reference is not None:
            decoded = _decode_xml_character_references(reference.group(0))
            if decoded == ">":
                return reference.start(), reference.end()
            if decoded == "<":
                raise UnsafeStructuredDocument(
                    "unclosed encoded XML hidden element"
                )
            if decoded in {"\"", "'", "`"}:
                quote = decoded
            index = reference.end()
            continue
        index += 1
    raise UnsafeStructuredDocument("unclosed encoded XML hidden element")


def _decode_hidden_markup_tags(
    content: str,
    budget: StructuredScanBudget,
    depth: int,
) -> str:
    pieces: list[str] = []
    cursor = 0
    search_start = 0
    candidate_budget = StructuredScanBudget()
    candidate_budget.bytes = budget.bytes
    candidate_budget.nodes = budget.nodes
    normalized_tag_tokens = 0
    while (
        reference := XML_CHARACTER_REFERENCE_PATTERN.search(content, search_start)
    ) is not None:
        if _decode_xml_character_references(reference.group(0)) != "<":
            search_start = reference.end()
            continue
        tag_end = _find_encoded_hidden_tag_end(
            content,
            reference.end(),
        )
        if tag_end is None:
            search_start = reference.end()
            continue
        closing_start, closing_end = tag_end
        normalized_tag_tokens += 1
        if normalized_tag_tokens > 2 * MAX_STRUCTURED_NODES:
            raise StructuredScanLimit
        tag_body = content[reference.end() : closing_start]
        if not tag_body.lstrip().startswith("/"):
            _reserve_structured_node(candidate_budget, depth)
        pieces.append(content[cursor : reference.start()])
        pieces.append("<" + tag_body + ">")
        cursor = closing_end
        search_start = closing_end
    if not pieces:
        return content
    pieces.append(content[cursor:])
    return "".join(pieces)


def _hidden_qualified_name(value: str) -> tuple[str, str]:
    prefix, separator, local_name = value.rpartition(":")
    return (prefix if separator else "", local_name.casefold())


def _hidden_scalar_value(value: str) -> str:
    value = _decode_xml_character_references(value.strip())
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'`":
        value = value[1:-1]
    return value.strip()


def _iter_hidden_assignments(content: str) -> Iterator[tuple[str, int, int]]:
    last_quotes = {quote: content.rfind(quote) for quote in "\"'`"}
    index = 0
    while index < len(content):
        name_quote = content[index] if content[index] in "\"'`" else None
        if name_quote is not None:
            index += 1
        if index >= len(content) or not XML_NAME_START_CHARACTER_PATTERN.fullmatch(
            content[index]
        ):
            index += 1
            continue
        name_start = index
        index += 1
        while index < len(content) and XML_NAME_CHARACTER_PATTERN.fullmatch(
            content[index]
        ):
            index += 1
        name = content[name_start:index]
        if name_quote is not None:
            if index >= len(content) or content[index] != name_quote:
                continue
            index += 1
        cursor = index
        while cursor < len(content) and content[cursor].isspace():
            cursor += 1
        operator_in_name = name.endswith(":")
        if operator_in_name:
            name = name[:-1]
        if not operator_in_name and (
            cursor >= len(content) or content[cursor] not in "=:"
        ):
            continue
        if not operator_in_name:
            cursor += 1
        while cursor < len(content) and content[cursor].isspace():
            cursor += 1
        if cursor >= len(content):
            return
        if content[cursor] in "\"'`":
            quote = content[cursor]
            value_start = cursor + 1
            if last_quotes[quote] < value_start:
                _prefix, local_name = _hidden_qualified_name(name)
                if _is_hidden_interesting_element(local_name):
                    raise UnsafeStructuredDocument(
                        "unclosed quoted XML hidden value"
                    )
                value_end = value_start
                while value_end < len(content) and (
                    not content[value_end].isspace()
                    and content[value_end] not in "<>"
                ):
                    value_end += 1
                if value_end > value_start:
                    yield name, value_start, value_end
                index = max(value_end, value_start + 1)
                continue
            value_end = content.find(quote, value_start)
            if value_end < 0:
                index = value_start
                continue
            yield name, cursor, value_end + 1
            index = value_end + 1
            continue
        value_start = cursor
        while cursor < len(content) and (
            not content[cursor].isspace()
            and content[cursor] not in "\"'=<>"
        ):
            cursor += 1
        if cursor > value_start:
            yield name, value_start, cursor
        index = max(cursor, value_start + 1)


def _extract_hidden_fragment(
    content: str,
    start: int,
    end: int,
    budget: StructuredScanBudget,
) -> str:
    if end - start > MAX_STRUCTURED_BYTES - budget.bytes:
        raise StructuredScanLimit
    value = content[start:end]
    _reserve_structured_bytes(value, budget)
    return value


def _extract_hidden_scalar(
    content: str,
    start: int,
    end: int,
    budget: StructuredScanBudget,
) -> str:
    value = _extract_hidden_fragment(content, start, end, budget)
    return _hidden_scalar_value(value)


def _extract_hidden_element_text(
    content: str,
    start: int,
    end: int,
    budget: StructuredScanBudget,
) -> str:
    value = _extract_hidden_fragment(content, start, end, budget)
    text_parts: list[str] = []
    cursor = 0
    for match in XML_HIDDEN_TAG_PATTERN.finditer(value):
        text_parts.append(value[cursor : match.start()])
        cursor = match.end()
    text_parts.append(value[cursor:])
    return _hidden_scalar_value("".join(text_parts))


def _is_hidden_interesting_element(local_name: str) -> bool:
    return local_name in {
        *XML_DESCRIPTOR_VALUE_NAMES,
        *XML_DESCRIPTOR_VALUE_NAMES.values(),
    } or _is_sensitive_structured_key(local_name)


def _hidden_namespace_identity(
    prefix: str,
    namespaces: dict[str, str],
    *,
    use_default: bool,
) -> tuple[str, str]:
    namespace = (
        namespaces.get(prefix)
        if prefix
        else namespaces.get("", "")
        if use_default
        else ""
    )
    if namespace:
        return "uri", namespace
    if prefix:
        return "prefix", prefix
    return "uri", ""


def _hidden_namespaces_pair(
    kind: str,
    namespace: tuple[str, str],
    descriptor_kind: str,
    descriptor_namespace: tuple[str, str],
) -> bool:
    unqualified = ("uri", "")
    if namespace == descriptor_namespace:
        return True
    if kind == descriptor_kind:
        return False
    return (
        kind == "attribute" and namespace == unqualified
    ) or descriptor_kind == "attribute" and descriptor_namespace == unqualified


def _apply_hidden_namespace_declarations(
    assignments: list[tuple[str, str]],
    inherited_namespaces: dict[str, str],
) -> dict[str, str]:
    namespaces = dict(inherited_namespaces)
    for name, value in assignments:
        raw_prefix, separator, local_name = name.rpartition(":")
        if name == "xmlns":
            prefix = ""
        elif separator and raw_prefix == "xmlns":
            prefix = local_name
        else:
            continue
        invalid_binding = (
            prefix == "xmlns"
            or (prefix == "xml" and value != XML_NAMESPACE_URI)
            or (
                prefix != "xml"
                and value in {XML_NAMESPACE_URI, XMLNS_NAMESPACE_URI}
            )
            or (bool(prefix) and not value)
        )
        if invalid_binding:
            raise UnsafeStructuredDocument("invalid reserved XML namespace binding")
        namespaces[prefix] = value
    return namespaces


def _scan_hidden_structured_tokens(
    label: str,
    content: str,
    budget: StructuredScanBudget,
    depth: int,
    inherited_namespaces: dict[str, str],
) -> list[str]:
    content = _decode_hidden_markup_tags(content, budget, depth)
    records: list[tuple[str, tuple[str, str], str, str]] = []
    open_elements: dict[
        str,
        list[tuple[int, tuple[str, str], str]],
    ] = {}
    scope_stack: list[
        tuple[
            str,
            dict[str, str],
            tuple[tuple[str, str], str] | None,
        ]
    ] = []

    def parse_assignments(
        fragment: str,
        inherited_namespaces: dict[str, str],
    ) -> tuple[list[tuple[str, str]], dict[str, str]]:
        assignments: list[tuple[str, str]] = []
        for name, value_start, value_end in _iter_hidden_assignments(fragment):
            _reserve_structured_node(budget, depth + len(scope_stack))
            assignments.append(
                (
                    name,
                    _extract_hidden_scalar(
                        fragment, value_start, value_end, budget
                    ),
                )
            )
        namespaces = _apply_hidden_namespace_declarations(
            assignments,
            inherited_namespaces,
        )
        return assignments, namespaces

    def append_attribute_records(
        assignments: list[tuple[str, str]],
        namespaces: dict[str, str],
        sensitive_element: tuple[tuple[str, str], str] | None = None,
    ) -> None:
        for name, value in assignments:
            raw_prefix, separator, _raw_local_name = name.rpartition(":")
            if name == "xmlns" or separator and raw_prefix == "xmlns":
                continue
            prefix, local_name = _hidden_qualified_name(name)
            records.append(
                (
                    "attribute",
                    _hidden_namespace_identity(
                        prefix, namespaces, use_default=False
                    ),
                    local_name,
                    value,
                )
            )
            if (
                sensitive_element is not None
                and local_name in XML_SENSITIVE_ELEMENT_VALUE_ATTRIBUTES
            ):
                records.append(("element", *sensitive_element, value))

    cursor = 0
    for match in XML_HIDDEN_TAG_PATTERN.finditer(content):
        current_namespaces = (
            scope_stack[-1][1]
            if scope_stack
            else inherited_namespaces
        )
        assignments, fragment_namespaces = parse_assignments(
            content[cursor : match.start()], current_namespaces
        )
        append_attribute_records(assignments, fragment_namespaces)
        cursor = match.end()

        qualified_name = match.group("name")
        prefix, local_name = _hidden_qualified_name(qualified_name)
        element_key = qualified_name
        if match.group("closing"):
            if match.group("body").strip():
                raise UnsafeStructuredDocument(
                    "malformed XML hidden closing tag"
                )
            if scope_stack and scope_stack[-1][0] != element_key:
                if any(scope[0] == element_key for scope in scope_stack):
                    raise UnsafeStructuredDocument(
                        "mismatched XML hidden closing tag"
                    )
                continue
            if not scope_stack:
                continue
            if _is_hidden_interesting_element(local_name) and (
                openings := open_elements.get(element_key)
            ):
                value_start, opening_prefix, opening_local_name = openings.pop()
                records.append(
                    (
                        "element",
                        opening_prefix,
                        opening_local_name,
                        _extract_hidden_element_text(
                            content, value_start, match.start(), budget
                        ),
                    )
                )
                if not openings:
                    del open_elements[element_key]
            scope_stack.pop()
            continue

        _reserve_structured_node(budget, depth + len(scope_stack))
        tag_body = match.group("body")
        assignments, element_namespaces = parse_assignments(
            tag_body, current_namespaces
        )
        element_namespace = _hidden_namespace_identity(
            prefix, element_namespaces, use_default=True
        )
        inherited_sensitive_element = (
            scope_stack[-1][2] if scope_stack else None
        )
        sensitive_element = (
            (element_namespace, local_name)
            if _is_sensitive_structured_key(local_name)
            else inherited_sensitive_element
        )
        append_attribute_records(
            assignments,
            element_namespaces,
            sensitive_element,
        )
        self_closing = tag_body.rstrip().endswith("/")
        if not self_closing:
            scope_stack.append(
                (element_key, element_namespaces, sensitive_element)
            )
            if _is_hidden_interesting_element(local_name):
                open_elements.setdefault(element_key, []).append(
                    (match.end(), element_namespace, local_name)
                )

    current_namespaces = (
        scope_stack[-1][1]
        if scope_stack
        else inherited_namespaces
    )
    assignments, fragment_namespaces = parse_assignments(
        content[cursor:], current_namespaces
    )
    append_attribute_records(assignments, fragment_namespaces)

    for openings in open_elements.values():
        for value_start, namespace, local_name in openings:
            if local_name in XML_DESCRIPTOR_VALUE_NAMES:
                raise UnsafeStructuredDocument(
                    "unclosed XML hidden descriptor element"
                )
            records.append(
                (
                    "element",
                    namespace,
                    local_name,
                    _extract_hidden_element_text(
                        content, value_start, len(content), budget
                    ),
                )
            )

    errors = [
        f"{label}: XML_SECRET_SCALAR"
        for _kind, _namespace, local_name, value in records
        if _is_sensitive_structured_key(local_name)
        and not _is_safe_structured_secret(value)
    ]
    descriptor_names = {
        (kind, namespace, local_name)
        for kind, namespace, local_name, value in records
        if local_name in XML_DESCRIPTOR_VALUE_NAMES
        and _is_sensitive_structured_key(value)
    }
    value_names = tuple(
        (kind, namespace, XML_DESCRIPTOR_VALUE_NAMES[local_name])
        for kind, namespace, local_name in descriptor_names
    )
    structured_values = [
        value
        for kind, namespace, local_name, value in records
        if any(
            local_name == value_name
            and _hidden_namespaces_pair(
                kind,
                namespace,
                descriptor_kind,
                descriptor_namespace,
            )
            for (
                descriptor_kind,
                descriptor_namespace,
                value_name,
            ) in value_names
        )
    ]
    if structured_values and any(
        not _is_safe_structured_secret(value) for value in structured_values
    ):
        errors.append(f"{label}: XML_SECRET_SCALAR")
    return errors


def _contains_forbidden_xml_declaration(content: str) -> bool:
    index = 0
    while (index := content.find("<", index)) >= 0:
        skipped = False
        for opener, closer in (
            ("<!--", "-->"),
            ("<![CDATA[", "]]>"),
            ("<?", "?>"),
        ):
            if not content.startswith(opener, index):
                continue
            end = content.find(closer, index + len(opener))
            if end < 0:
                return False
            index = end + len(closer)
            skipped = True
            break
        if skipped:
            continue
        if XML_FORBIDDEN_DECLARATION_PATTERN.match(content, index):
            return True
        index += 1
    return False


def _contains_secret_assignment_outside_valid_json(
    content: str,
    depth: int,
) -> bool:
    fragments: list[str] = []
    fragment_start = 0
    cursor = 0
    while (start := _next_json_container_start(content, cursor)) >= 0:
        candidate_budget = StructuredScanBudget()
        try:
            end = _validate_json_value(content, start, candidate_budget, depth)
        except InvalidJsonSyntax:
            cursor = start + 1
            continue
        except (UnsafeStructuredDocument, StructuredScanLimit):
            return _contains_secret_assignment(content)
        fragments.append(content[fragment_start:start])
        fragment_start = end
        cursor = end
    fragments.append(content[fragment_start:])
    return _contains_secret_assignment("".join(fragments))


def _scan_xml_hidden_content(
    label: str,
    content: str,
    budget: StructuredScanBudget,
    depth: int,
    inherited_namespaces: dict[str, str] | None = None,
    sections: list[XmlHiddenSection] | None = None,
) -> list[str]:
    if depth > MAX_STRUCTURED_DEPTH:
        return [f"{label}: INVALID_XML: hidden content exceeds structural limits"]
    if depth > 1:
        try:
            _reserve_structured_bytes(content, budget)
        except (InvalidStructuredDocument, StructuredScanLimit):
            return [f"{label}: INVALID_XML: hidden content exceeds structural limits"]
    errors: list[str] = []
    if inherited_namespaces is None:
        inherited_namespaces = {"xml": XML_NAMESPACE_URI}
    preflighted_sections = sections is not None
    hidden_sections: Iterator[XmlHiddenSection] = (
        iter(sections)
        if sections is not None
        else (
            XmlHiddenSection(kind, hidden, inherited_namespaces)
            for kind, hidden in _iter_xml_hidden_sections(content)
        )
    )
    for kind, hidden, hidden_namespaces in hidden_sections:
        if not preflighted_sections:
            try:
                _reserve_structured_node(budget, depth)
            except StructuredScanLimit:
                return [
                    f"{label}: INVALID_XML: hidden content exceeds structural limits"
                ]
        if (kind == "pi" and _contains_sensitive_pi_target(hidden)) or (
            _contains_unstructured_secret(hidden)
        ):
            errors.append(f"{label}: XML_SECRET_SCALAR")
            continue

        try:
            token_errors = _scan_hidden_structured_tokens(
                label,
                hidden,
                budget,
                depth,
                hidden_namespaces,
            )
        except UnsafeStructuredDocument:
            return [f"{label}: INVALID_XML: hidden content cannot be parsed safely"]
        except StructuredScanLimit:
            return [f"{label}: INVALID_XML: hidden content exceeds structural limits"]
        if token_errors:
            errors.extend(token_errors)
            continue

        json_errors: list[str] = []
        tree_scanned_cdata = kind == "cdata" and depth == 1
        if not tree_scanned_cdata:
            json_errors = _scan_json_candidates_in_text(
                label, hidden, budget, depth, "xml"
            )
        if any("INVALID_XML" in error for error in json_errors):
            return json_errors
        if json_errors:
            errors.extend(json_errors)
            continue
        contains_secret_assignment = (
            _contains_secret_assignment_outside_valid_json(hidden, depth)
            if tree_scanned_cdata
            else _contains_secret_assignment(hidden)
        )
        if contains_secret_assignment:
            errors.append(f"{label}: XML_SECRET_SCALAR")
            continue

        if any(
            opener in hidden
            for opener, _closer in XML_HIDDEN_DELIMITERS.values()
        ):
            nested_errors = _scan_xml_hidden_content(
                label,
                hidden,
                budget,
                depth + 1,
                hidden_namespaces,
            )
            if any("INVALID_XML" in error for error in nested_errors):
                return nested_errors
            errors.extend(nested_errors)
    return errors


def scan_xml_sensitive_scalars(label: str, content: str) -> list[str]:
    if Path(label).suffix.casefold() != ".xml":
        return []
    if _contains_forbidden_xml_declaration(content):
        return [f"{label}: INVALID_XML: declarations and entities are not allowed"]
    budget = StructuredScanBudget()
    try:
        root, hidden_sections = _load_xml_document(content, budget)
    except InvalidStructuredDocument:
        return [f"{label}: INVALID_XML: changed XML cannot be parsed safely"]
    except StructuredScanLimit:
        return [f"{label}: INVALID_XML: changed XML exceeds structural limits"]

    errors = _scan_xml_tree(label, root, budget)
    if any("INVALID_XML" in error for error in errors):
        return errors
    errors.extend(
        _scan_xml_hidden_content(
            label,
            content,
            budget,
            1,
            sections=hidden_sections,
        )
    )
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
    json_structured_label = Path(label).suffix.casefold() in {".har", ".json"}
    shell_like_label = _is_shell_like_label(label)
    yaml_result = scan_yaml_sensitive_scalars(label, content, selected_lines)
    typescript_type_member_assignments = _typescript_type_member_assignments(
        label, lines
    )
    for line_number, line in enumerate(lines, start=1):
        if selected_lines is not None and line_number not in selected_lines:
            continue
        safe_marker_spans = _mvnw_auth_marker_spans(label, line)
        safe_assignment_spans = tuple(
            merge(_safe_placeholder_spans(line), safe_marker_spans)
        )
        generic_assignment_found = False
        for rule_id, pattern in HIGH_CONFIDENCE_PATTERNS:
            if pattern.search(line):
                errors.append(f"{label}:{line_number}: {rule_id}")

        for match in GENERIC_SECRET_ASSIGNMENT_PATTERN.finditer(line):
            if _match_is_within_safe_placeholder(match, safe_assignment_spans):
                continue
            value = match.group("value")
            if (
                (line_number, match.start())
                not in typescript_type_member_assignments
                and (line_number, match.start())
                not in yaml_result.safe_root_assignments
                and (
                    _quoted_assignment_has_direct_suffix(
                        line,
                        match,
                        shell_like=shell_like_label,
                    )
                    or not is_safe_placeholder(value)
                )
                and not is_approved_finding(
                    label,
                    line_number,
                    "GENERIC_SECRET_ASSIGNMENT",
                    value,
                    lines,
                )
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")
                generic_assignment_found = True

        for match in UNQUOTED_SECRET_ASSIGNMENT_PATTERN.finditer(line):
            if _match_is_within_safe_placeholder(match, safe_assignment_spans):
                continue
            value = match.group("value")
            if json_structured_label and value.startswith(("{", "[")):
                continue
            checked_value = value.rstrip("}]") if json_structured_label else value
            if (
                (line_number, match.start())
                not in typescript_type_member_assignments
                and (line_number, match.start())
                not in yaml_result.safe_root_assignments
                and not is_safe_placeholder(checked_value)
                and not is_approved_finding(
                    label,
                    line_number,
                    "GENERIC_SECRET_ASSIGNMENT",
                    checked_value,
                    lines,
                )
            ):
                errors.append(f"{label}:{line_number}: GENERIC_SECRET_ASSIGNMENT")
                generic_assignment_found = True

        if (
            not generic_assignment_found
            and _has_unsafe_mvnw_auth_marker(label, line, safe_marker_spans)
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

        for email_address in _iter_email_addresses(line):
            if not is_safe_email(email_address) and not is_approved_finding(
                label,
                line_number,
                "EMAIL_ADDRESS",
                email_address,
                lines,
            ):
                errors.append(f"{label}:{line_number}: EMAIL_ADDRESS")

        if CN_MOBILE_NUMBER_PATTERN.search(line):
            errors.append(f"{label}:{line_number}: CN_MOBILE_NUMBER")

        for match in PAYMENT_CARD_CANDIDATE_PATTERN.finditer(line):
            if passes_luhn(match.group(0)):
                errors.append(f"{label}:{line_number}: PAYMENT_CARD_NUMBER")
    errors.extend(yaml_result.errors)
    errors.extend(scan_json_sensitive_scalars(label, content))
    errors.extend(scan_xml_sensitive_scalars(label, content))
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
    repository = repository.resolve()
    environment = _isolated_git_environment(os.environ)
    result = subprocess.run(
        (
            "git",
            "-c",
            f"safe.directory={repository}",
            "-c",
            "core.fsmonitor=",
            "-c",
            "core.hooksPath=/dev/null",
            "-c",
            "core.commitGraph=false",
            "-c",
            "core.useReplaceRefs=false",
            "-c",
            "submodule.recurse=false",
            "--no-replace-objects",
            "--literal-pathspecs",
            *arguments,
        ),
        cwd=repository,
        env=environment,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise ValueError("immutable Git diff cannot be resolved")
    return result.stdout


def _decode_git_single_line(raw_value: bytes) -> str:
    if not raw_value.endswith(b"\n"):
        raise ValueError("immutable Git output is not a single terminated line")
    value = raw_value[:-1]
    if not value or any(character in value for character in (b"\x00", b"\r", b"\n")):
        raise ValueError("immutable Git output contains an unsafe line boundary")
    return os.fsdecode(value)


def _git_metadata_path(repository: Path, name: str) -> Path:
    raw_path = _run_immutable_git(repository, ("rev-parse", "--git-path", name))
    decoded = _decode_git_single_line(raw_path)
    path = Path(decoded)
    return path if path.is_absolute() else repository / path


def _raw_commit_parents(repository: Path, commit: str) -> tuple[str, ...]:
    raw_commit = _run_immutable_git(repository, ("cat-file", "commit", commit))
    headers = raw_commit.split(b"\n\n", 1)[0].splitlines()
    parents: list[str] = []
    for header in headers:
        if not header.startswith(b"parent "):
            continue
        parent = os.fsdecode(header.removeprefix(b"parent "))
        if FULL_SHA_PATTERN.fullmatch(parent) is None:
            raise ValueError("raw commit contains an invalid parent object ID")
        parents.append(parent)
    return tuple(parents)


def scan_git_diff(repository: Path, base_commit: str, commit: str) -> list[str]:
    repository = repository.resolve()
    if not FULL_SHA_PATTERN.fullmatch(base_commit) or not FULL_SHA_PATTERN.fullmatch(commit):
        return ["repository: INVALID_COMMIT: immutable diff requires full lowercase SHAs"]
    try:
        shallow = _decode_git_single_line(
            _run_immutable_git(
                repository, ("rev-parse", "--is-shallow-repository")
            )
        )
        if shallow != "false":
            raise ValueError("shallow repositories cannot prove complete commit history")
        grafts_path = _git_metadata_path(repository, "info/grafts")
        try:
            os.lstat(grafts_path)
        except FileNotFoundError:
            pass
        except OSError as error:
            raise ValueError("Git graft state cannot be inspected safely") from error
        else:
            raise ValueError("Git grafts are not allowed in immutable diff mode")
        _run_immutable_git(repository, ("merge-base", "--is-ancestor", base_commit, commit))
        raw_history = _run_immutable_git(
            repository,
            (
                "rev-list",
                "--full-history",
                "--topo-order",
                "--reverse",
                "--parents",
                f"{base_commit}..{commit}",
            ),
        )
    except ValueError as error:
        return [f"repository: IMMUTABLE_DIFF: {error}"]
    errors: list[str] = []
    commits_with_parents: list[tuple[str, tuple[str, ...]]] = []
    for raw_line in raw_history.splitlines():
        values = tuple(os.fsdecode(value) for value in raw_line.split())
        if not values or any(FULL_SHA_PATTERN.fullmatch(value) is None for value in values):
            return ["repository: IMMUTABLE_DIFF: commit history contains an invalid object ID"]
        try:
            raw_parents = _raw_commit_parents(repository, values[0])
        except ValueError as error:
            return [f"repository: IMMUTABLE_DIFF: {error}"]
        if values[1:] != raw_parents:
            return [
                "repository: IMMUTABLE_DIFF: commit parent graph differs "
                "from the raw commit object"
            ]
        commits_with_parents.append((values[0], values[1:]))

    changed_paths: list[tuple[str, bytes]] = []
    seen_paths: set[tuple[str, bytes]] = set()
    try:
        for child_commit, parents in commits_with_parents:
            edges: tuple[str | None, ...] = parents or (None,)
            for parent_commit in edges:
                if parent_commit is None:
                    raw_paths = _run_immutable_git(
                        repository,
                        (
                            "diff-tree",
                            "--root",
                            "--no-commit-id",
                            "--name-only",
                            "-z",
                            "--diff-filter=ACMRT",
                            "--no-renames",
                            "--no-ext-diff",
                            "--ignore-submodules=none",
                            "-r",
                            child_commit,
                        ),
                    )
                else:
                    raw_paths = _run_immutable_git(
                        repository,
                        (
                            "diff",
                            "--name-only",
                            "-z",
                            "--diff-filter=ACMRT",
                            "--no-renames",
                            "--no-ext-diff",
                            "--ignore-submodules=none",
                            parent_commit,
                            child_commit,
                        ),
                    )
                for raw_path in (
                    value for value in raw_paths.split(b"\x00") if value
                ):
                    identity = (child_commit, raw_path)
                    if identity not in seen_paths:
                        seen_paths.add(identity)
                        changed_paths.append(identity)
    except ValueError as error:
        return [f"repository: IMMUTABLE_DIFF: {error}"]

    for child_commit, raw_path in changed_paths:
        path = os.fsdecode(raw_path)
        try:
            tree = _run_immutable_git(
                repository, ("ls-tree", "-z", child_commit, "--", path)
            )
            records = [record for record in tree.split(b"\x00") if record]
            if len(records) != 1 or b"\t" not in records[0]:
                errors.append(f"{path}: UNSAFE_GIT_MODE: changed path is not a regular blob")
                continue
            raw_metadata, resolved_path = records[0].split(b"\t", 1)
            metadata = raw_metadata.split()
            if (
                resolved_path != raw_path
                or len(metadata) != 3
                or metadata[0] not in {b"100644", b"100755"}
                or metadata[1] != b"blob"
            ):
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
