#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote


ALLOWED_FRONTMATTER_KEYS = {"description", "name"}
NAME_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
FRONTMATTER_PATTERN = re.compile(r"\A---\n(?P<header>.*?)\n---(?:\n|\Z)", re.DOTALL)
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]*\]\((?P<target>[^)]+)\)")
MAX_NAME_LENGTH = 64
MAX_DESCRIPTION_LENGTH = 1024


def parse_frontmatter(skill_file: Path) -> tuple[dict[str, str], str, list[str]]:
    content = skill_file.read_text(encoding="utf-8")
    match = FRONTMATTER_PATTERN.match(content)
    if match is None:
        return {}, content, [f"{skill_file}: invalid or missing YAML frontmatter"]

    values: dict[str, str] = {}
    errors: list[str] = []
    for line_number, line in enumerate(match.group("header").splitlines(), start=2):
        if not line.strip():
            continue
        if line[:1].isspace() or ":" not in line:
            errors.append(
                f"{skill_file}:{line_number}: frontmatter must use one-line key/value entries"
            )
            continue

        key, raw_value = line.split(":", 1)
        key = key.strip()
        raw_value = raw_value.strip()
        if key not in ALLOWED_FRONTMATTER_KEYS:
            errors.append(
                f"{skill_file}:{line_number}: unsupported frontmatter key: {key}"
            )
            continue
        if key in values:
            errors.append(
                f"{skill_file}:{line_number}: duplicate frontmatter key: {key}"
            )
            continue
        if not raw_value:
            errors.append(f"{skill_file}:{line_number}: {key} must not be empty")
            continue

        if not raw_value.startswith('"'):
            errors.append(
                f"{skill_file}:{line_number}: ambiguous plain YAML scalar for {key}; "
                f"{key} must be a double-quoted string"
            )
            continue
        try:
            parsed = json.loads(raw_value)
        except json.JSONDecodeError:
            errors.append(
                f"{skill_file}:{line_number}: invalid double-quoted value for {key}"
            )
            continue
        if not isinstance(parsed, str):
            errors.append(f"{skill_file}:{line_number}: {key} must be a string")
            continue
        values[key] = parsed.strip()

    return values, content[match.end() :], errors


def validate_frontmatter(skill_directory: Path) -> tuple[str | None, list[str]]:
    skill_file = skill_directory / "SKILL.md"
    if not skill_file.is_file():
        return None, [f"{skill_directory}: SKILL.md is missing"]

    frontmatter, body, errors = parse_frontmatter(skill_file)
    missing = ALLOWED_FRONTMATTER_KEYS - frontmatter.keys()
    for key in sorted(missing):
        errors.append(f"{skill_file}: required frontmatter key is missing: {key}")

    name = frontmatter.get("name")
    if name is not None:
        if not NAME_PATTERN.fullmatch(name) or len(name) > MAX_NAME_LENGTH:
            errors.append(
                f"{skill_file}: name must be 1-{MAX_NAME_LENGTH} characters of lowercase letters, digits, and single hyphens"
            )
        if name != skill_directory.name:
            errors.append(
                f"{skill_file}: frontmatter name {name!r} must match its folder {skill_directory.name!r}"
            )

    description = frontmatter.get("description")
    if description is not None:
        if not description or len(description) > MAX_DESCRIPTION_LENGTH:
            errors.append(
                f"{skill_file}: description must be 1-{MAX_DESCRIPTION_LENGTH} characters"
            )
        if "<" in description or ">" in description:
            errors.append(f"{skill_file}: description must not contain angle brackets")

    if re.search(r"\b(?:TODO|TBD)\b|\[TODO", body, re.IGNORECASE):
        errors.append(f"{skill_file}: unresolved placeholder remains in the skill body")
    if not body.strip():
        errors.append(f"{skill_file}: skill body must not be empty")

    return name, errors


def validate_openai_yaml(skill_directory: Path, skill_name: str | None) -> list[str]:
    metadata_file = skill_directory / "agents" / "openai.yaml"
    if not metadata_file.is_file():
        return [f"{metadata_file}: required UI metadata file is missing"]

    errors: list[str] = []
    sections: dict[str, dict[str, object]] = {}
    current_section: str | None = None
    allowed_fields = {
        "interface": {"default_prompt", "display_name", "short_description"},
        "policy": {"allow_implicit_invocation"},
    }

    for line_number, line in enumerate(
        metadata_file.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "\t" in line:
            errors.append(f"{metadata_file}:{line_number}: tabs are not allowed")
            continue
        if not line.startswith(" "):
            if line not in {"interface:", "policy:"}:
                errors.append(
                    f"{metadata_file}:{line_number}: unsupported top-level entry: {line}"
                )
                current_section = None
                continue
            current_section = line[:-1]
            if current_section in sections:
                errors.append(
                    f"{metadata_file}:{line_number}: duplicate section: {current_section}"
                )
            sections.setdefault(current_section, {})
            continue

        if (
            current_section is None
            or not line.startswith("  ")
            or line.startswith("   ")
        ):
            errors.append(f"{metadata_file}:{line_number}: invalid indentation")
            continue
        field, separator, raw_value = line.strip().partition(":")
        if not separator or field not in allowed_fields[current_section]:
            errors.append(
                f"{metadata_file}:{line_number}: unsupported {current_section} field: {field}"
            )
            continue
        if field in sections[current_section]:
            errors.append(f"{metadata_file}:{line_number}: duplicate field: {field}")
            continue

        raw_value = raw_value.strip()
        if current_section == "policy":
            if raw_value not in {"true", "false"}:
                errors.append(
                    f"{metadata_file}:{line_number}: allow_implicit_invocation must be true or false"
                )
                continue
            sections[current_section][field] = raw_value == "true"
            continue

        try:
            parsed = json.loads(raw_value)
        except json.JSONDecodeError:
            errors.append(
                f"{metadata_file}:{line_number}: interface strings must be double-quoted"
            )
            continue
        if not isinstance(parsed, str) or not parsed:
            errors.append(f"{metadata_file}:{line_number}: {field} must be a string")
            continue
        sections[current_section][field] = parsed

    for section, required_fields in allowed_fields.items():
        if section not in sections:
            errors.append(f"{metadata_file}: required section is missing: {section}")
            continue
        for field in sorted(required_fields - sections[section].keys()):
            errors.append(
                f"{metadata_file}: required field is missing: {section}.{field}"
            )

    interface = sections.get("interface", {})
    short_description = interface.get("short_description")
    if isinstance(short_description, str) and not 25 <= len(short_description) <= 64:
        errors.append(
            f"{metadata_file}: interface.short_description must be 25-64 characters"
        )

    default_prompt = interface.get("default_prompt")
    if skill_name and isinstance(default_prompt, str):
        invocation = f"${skill_name}"
        invocation_pattern = re.compile(rf"(?<![\w-]){re.escape(invocation)}(?![\w-])")
        if invocation_pattern.search(default_prompt) is None:
            errors.append(
                f"{metadata_file}: interface.default_prompt must mention {invocation}"
            )

    return errors


def validate_links(skill_directory: Path, repository: Path) -> list[str]:
    errors: list[str] = []
    repository = repository.resolve()
    for document in sorted(skill_directory.rglob("*.md")):
        content = document.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK_PATTERN.finditer(content):
            raw_target = match.group("target").strip().split(maxsplit=1)[0].strip("<>")
            if raw_target.startswith(("#", "http://", "https://", "mailto:")):
                continue
            relative_target = unquote(raw_target.split("#", 1)[0])
            if not relative_target:
                continue
            target = (document.parent / relative_target).resolve()
            try:
                target.relative_to(repository)
            except ValueError:
                errors.append(
                    f"{document}: local link escapes the repository: {raw_target}"
                )
                continue
            if not target.exists():
                errors.append(f"{document}: missing local link target: {raw_target}")
    return errors


def validate_skill(skill_directory: Path, repository: Path) -> list[str]:
    if skill_directory.is_symlink():
        return [f"{skill_directory}: skill directory must not be a symbolic link"]
    symlink_errors: list[str] = []
    for entry in skill_directory.rglob("*"):
        if entry.is_symlink():
            symlink_errors.append(
                f"{entry}: project skills must not contain symbolic links"
            )
    if symlink_errors:
        return symlink_errors

    errors: list[str] = []
    skill_name, frontmatter_errors = validate_frontmatter(skill_directory)
    errors.extend(frontmatter_errors)
    errors.extend(validate_openai_yaml(skill_directory, skill_name))
    errors.extend(validate_links(skill_directory, repository))
    return errors


def validate_repository(repository: Path) -> list[str]:
    repository = repository.resolve()
    agents_root = repository / ".agents"
    if agents_root.is_symlink():
        return [f"{agents_root}: .agents directory must not be a symbolic link"]

    skills_root = agents_root / "skills"
    if skills_root.is_symlink():
        return [f"{skills_root}: skills directory must not be a symbolic link"]
    if not skills_root.is_dir():
        return [f"{skills_root}: project skills directory is missing"]

    entries = sorted(skills_root.iterdir())
    errors: list[str] = []
    skill_directories: list[Path] = []
    for entry in entries:
        if entry.is_symlink():
            errors.append(f"{entry}: skill directory must not be a symbolic link")
        elif entry.is_dir():
            skill_directories.append(entry)
        else:
            errors.append(
                f"{entry}: only skill directories are allowed under {skills_root}"
            )

    if not skill_directories:
        errors.append(f"{skills_root}: no project skills found")
        return errors

    for skill_directory in skill_directories:
        errors.extend(validate_skill(skill_directory, repository))
    return errors


def main() -> int:
    repository = Path(__file__).resolve().parent.parent
    errors = validate_repository(repository)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"Project skill check failed with {len(errors)} problem(s).",
            file=sys.stderr,
        )
        return 1

    print("Project skills are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
