#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
import unicodedata
from collections.abc import Iterator
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml
from markdown_it import MarkdownIt
from markdown_it.token import Token
from yaml.constructor import ConstructorError
from yaml.nodes import MappingNode, Node, ScalarNode


ALLOWED_FRONTMATTER_KEYS = {"description", "name"}
NAME_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
FRONTMATTER_PATTERN = re.compile(r"\A---\n(?P<header>.*?)\n---(?:\n|\Z)", re.DOTALL)
UNRESOLVED_REFERENCE_LINK_PATTERN = re.compile(r"\[[^\]\n]+\]\[[^\]\n]*\]")
MAX_NAME_LENGTH = 64
MAX_DESCRIPTION_LENGTH = 1024
MARKDOWN = MarkdownIt("commonmark")
RAW_HTML_LINK_ATTRIBUTE_PATTERN = re.compile(
    r"\b(?:href|src)\s*=", re.IGNORECASE
)


class UniqueKeySafeLoader(yaml.SafeLoader):
    pass


def construct_unique_mapping(
    loader: UniqueKeySafeLoader,
    node: MappingNode,
    deep: bool = False,
) -> dict[object, object]:
    mapping: dict[object, object] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in mapping
        except TypeError as error:
            raise ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                "found an unhashable mapping key",
                key_node.start_mark,
            ) from error
        if duplicate:
            raise ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeySafeLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    construct_unique_mapping,
)


def yaml_problem_line(error: yaml.YAMLError, line_offset: int = 0) -> str:
    mark = getattr(error, "problem_mark", None)
    if mark is None:
        return ""
    return f" at line {mark.line + 1 + line_offset}"


def load_yaml_mapping(
    content: str,
    source: Path,
    *,
    line_offset: int = 0,
) -> tuple[dict[object, object], MappingNode | None, list[str]]:
    try:
        value = yaml.load(content, Loader=UniqueKeySafeLoader)
        node = yaml.compose(content, Loader=yaml.SafeLoader)
    except yaml.YAMLError as error:
        return (
            {},
            None,
            [
                f"{source}: content must be a valid YAML mapping"
                f"{yaml_problem_line(error, line_offset)}; ambiguous plain YAML "
                "scalars are not allowed"
            ],
        )

    if not isinstance(value, dict) or not isinstance(node, MappingNode):
        return {}, None, [f"{source}: content must be a valid YAML mapping"]
    return value, node, []


def mapping_value_nodes(node: MappingNode) -> dict[str, Node]:
    values: dict[str, Node] = {}
    for key_node, value_node in node.value:
        if isinstance(key_node, ScalarNode):
            values[key_node.value] = value_node
    return values


def parse_frontmatter(skill_file: Path) -> tuple[dict[str, str], str, list[str]]:
    content = skill_file.read_text(encoding="utf-8")
    match = FRONTMATTER_PATTERN.match(content)
    if match is None:
        return {}, content, [f"{skill_file}: invalid or missing YAML frontmatter"]

    parsed, root_node, errors = load_yaml_mapping(
        match.group("header"),
        skill_file,
        line_offset=1,
    )
    if root_node is None:
        return {}, content[match.end() :], errors

    values: dict[str, str] = {}
    value_nodes = mapping_value_nodes(root_node)
    for key, value in parsed.items():
        if not isinstance(key, str) or key not in ALLOWED_FRONTMATTER_KEYS:
            errors.append(f"{skill_file}: unsupported frontmatter key: {key}")
            continue
        value_node = value_nodes.get(key)
        if (
            not isinstance(value, str)
            or not isinstance(value_node, ScalarNode)
            or value_node.style != '"'
        ):
            errors.append(
                f"{skill_file}: ambiguous plain YAML scalar for {key}; "
                f"{key} must be a double-quoted string"
            )
            continue
        if not value.strip():
            errors.append(f"{skill_file}: {key} must not be empty")
            continue
        values[key] = value.strip()

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
    allowed_fields = {
        "interface": {"default_prompt", "display_name", "short_description"},
        "policy": {"allow_implicit_invocation"},
    }

    loaded, root_node, yaml_errors = load_yaml_mapping(
        metadata_file.read_text(encoding="utf-8"),
        metadata_file,
    )
    if root_node is None:
        return yaml_errors
    errors.extend(yaml_errors)

    sections: dict[str, dict[str, object]] = {}
    section_nodes = mapping_value_nodes(root_node)
    for section, raw_fields in loaded.items():
        if not isinstance(section, str) or section not in allowed_fields:
            errors.append(f"{metadata_file}: unsupported top-level entry: {section}")
            continue
        if not isinstance(raw_fields, dict):
            errors.append(f"{metadata_file}: {section} must be a YAML mapping")
            continue

        section_node = section_nodes.get(section)
        if not isinstance(section_node, MappingNode):
            errors.append(f"{metadata_file}: {section} must be a YAML mapping")
            continue
        field_nodes = mapping_value_nodes(section_node)
        sections[section] = {}
        for field, value in raw_fields.items():
            if not isinstance(field, str) or field not in allowed_fields[section]:
                errors.append(f"{metadata_file}: unsupported {section} field: {field}")
                continue
            value_node = field_nodes.get(field)
            if section == "policy":
                if type(value) is not bool:
                    errors.append(
                        f"{metadata_file}: allow_implicit_invocation must be true or false"
                    )
                    continue
            elif (
                not isinstance(value, str)
                or not value
                or not isinstance(value_node, ScalarNode)
                or value_node.style != '"'
            ):
                errors.append(
                    f"{metadata_file}: interface strings must be double-quoted"
                )
                continue
            sections[section][field] = value

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


def iter_tokens(tokens: list[Token]) -> Iterator[Token]:
    for token in tokens:
        yield token
        if token.children:
            yield from iter_tokens(token.children)


def validate_link_target(
    document: Path,
    repository: Path,
    raw_target: str,
) -> str | None:
    target_text = raw_target.strip()
    if not target_text:
        return None
    if "\\" in target_text or "\x00" in target_text:
        return f"{document}: invalid local link target: {raw_target}"

    parsed = urlsplit(target_text)
    scheme = parsed.scheme.lower()
    if scheme in {"http", "https", "mailto"}:
        return None
    if scheme or parsed.netloc:
        return f"{document}: unsupported link scheme or authority: {raw_target}"

    relative_target = unquote(parsed.path)
    target = document.resolve() if not relative_target else (
        document.parent / relative_target
    ).resolve()
    try:
        target.relative_to(repository)
    except ValueError:
        return f"{document}: local link escapes the repository: {raw_target}"
    if not target.exists():
        return f"{document}: missing local link target: {raw_target}"
    fragment = unquote(parsed.fragment)
    if fragment:
        if not target.is_file() or target.suffix.casefold() != ".md":
            return f"{document}: Markdown heading fragment targets a non-Markdown file: {raw_target}"
        if fragment not in markdown_heading_fragments(target):
            return f"{document}: missing Markdown heading fragment: {raw_target}"
    return None


def github_heading_slug(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).strip().casefold()
    normalized = "".join(
        character
        for character in normalized
        if character in {" ", "-", "_"}
        or not unicodedata.category(character).startswith(("P", "S"))
    )
    return re.sub(r"\s+", "-", normalized)


def markdown_heading_fragments(document: Path) -> set[str]:
    tokens = MARKDOWN.parse(document.read_text(encoding="utf-8"))
    fragments: set[str] = set()
    occurrences: dict[str, int] = {}
    for index, token in enumerate(tokens[:-1]):
        if token.type != "heading_open" or tokens[index + 1].type != "inline":
            continue
        base = github_heading_slug(tokens[index + 1].content)
        occurrence = occurrences.get(base, 0)
        occurrences[base] = occurrence + 1
        fragments.add(base if occurrence == 0 else f"{base}-{occurrence}")
    return fragments


def validate_links(skill_directory: Path, repository: Path) -> list[str]:
    errors: list[str] = []
    repository = repository.resolve()
    for document in sorted(skill_directory.rglob("*.md")):
        content = document.read_text(encoding="utf-8")
        environment: dict[str, object] = {}
        tokens = MARKDOWN.parse(content, environment)
        checked_targets: set[str] = set()
        for inline in (token for token in tokens if token.type == "inline"):
            visible_text = "".join(
                child.content for child in inline.children or [] if child.type == "text"
            )
            reference_match = UNRESOLVED_REFERENCE_LINK_PATTERN.search(visible_text)
            if reference_match is not None:
                errors.append(
                    f"{document}: missing Markdown reference definition: "
                    f"{reference_match.group(0)}"
                )

        for token in iter_tokens(tokens):
            if token.type in {"html_inline", "html_block"} and (
                RAW_HTML_LINK_ATTRIBUTE_PATTERN.search(token.content) is not None
            ):
                errors.append(
                    f"{document}: raw HTML href/src attributes are not allowed; use Markdown links"
                )
                continue
            raw_target: str | None = None
            if token.type == "link_open":
                raw_target = token.attrGet("href")
            elif token.type == "image":
                raw_target = token.attrGet("src")

            if raw_target is None or raw_target in checked_targets:
                continue
            checked_targets.add(raw_target)
            error = validate_link_target(document, repository, raw_target)
            if error is not None:
                errors.append(error)

        references = environment.get("references", {})
        if isinstance(references, dict):
            for reference in references.values():
                if not isinstance(reference, dict):
                    continue
                raw_target = reference.get("href")
                if not isinstance(raw_target, str) or raw_target in checked_targets:
                    continue
                checked_targets.add(raw_target)
                error = validate_link_target(document, repository, raw_target)
                if error is not None:
                    errors.append(error)
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
