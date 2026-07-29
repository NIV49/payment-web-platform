#!/usr/bin/env python3

from __future__ import annotations

import argparse
import errno
import os
import re
import stat
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Sequence


FULL_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
REGULAR_BLOB_MODES = {"100644", "100755"}
UNSAFE_GIT_MODES = {"120000": "symbolic link", "160000": "gitlink"}
DEFAULT_MAX_EVIDENCE_BYTES = 5 * 1024 * 1024
FORBIDDEN_GIT_ENVIRONMENT = frozenset(
    {
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
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
SAFE_OPENAT_SUPPORTED = (
    hasattr(os, "O_DIRECTORY")
    and hasattr(os, "O_NOFOLLOW")
    and hasattr(os, "O_NONBLOCK")
    and os.open in os.supports_dir_fd
)


class EvidenceError(ValueError):
    """Raised when evidence cannot be proven to belong to an immutable snapshot."""


def _run_git(repository: Path, arguments: Sequence[str]) -> bytes:
    repository = repository.resolve()
    inherited_environment = os.environ
    forbidden = set(
        FORBIDDEN_GIT_ENVIRONMENT.intersection(inherited_environment)
    )
    forbidden.update(
        key
        for key in inherited_environment
        if key.startswith(("GIT_CONFIG_KEY_", "GIT_CONFIG_VALUE_"))
    )
    if forbidden:
        raise EvidenceError("Git environment overrides are forbidden")
    environment = {
        "PATH": inherited_environment.get("PATH", os.defpath),
        "HOME": os.devnull,
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_ALLOW_PROTOCOL": "",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_LITERAL_PATHSPECS": "1",
    }
    result = subprocess.run(
        [
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
        ],
        cwd=repository,
        check=False,
        capture_output=True,
        env=environment,
    )
    if result.returncode != 0:
        raise EvidenceError(f"Git object validation failed for repository {repository}")
    return result.stdout


def _decode_git_single_line(raw_value: bytes) -> str:
    if not raw_value.endswith(b"\n"):
        raise EvidenceError("Git output is not a single terminated line")
    value = raw_value[:-1]
    if not value or any(character in value for character in (b"\x00", b"\r", b"\n")):
        raise EvidenceError("Git output contains an unsafe line boundary")
    return os.fsdecode(value)


def _canonical_repository(repository: Path | str) -> Path:
    try:
        canonical = Path(repository).resolve(strict=True)
    except OSError as error:
        raise EvidenceError("repository does not exist") from error
    if not canonical.is_dir():
        raise EvidenceError("repository must be a directory")

    top_level = _decode_git_single_line(
        _run_git(canonical, ["rev-parse", "--show-toplevel"])
    )
    try:
        actual = Path(top_level).resolve(strict=True)
    except OSError as error:
        raise EvidenceError("repository top level cannot be resolved") from error
    if actual != canonical:
        raise EvidenceError("repository must be its canonical Git top level")
    return canonical


def _resolve_commit(repository: Path, commit_sha: str) -> str:
    if not FULL_SHA_PATTERN.fullmatch(commit_sha):
        raise EvidenceError("commit SHA must contain exactly 40 hexadecimal characters")
    resolved = _decode_git_single_line(
        _run_git(repository, ["rev-parse", "--verify", f"{commit_sha}^{{commit}}"])
    )
    if resolved != commit_sha:
        raise EvidenceError("commit SHA did not resolve to the declared object")
    return resolved


def _git_metadata_path(repository: Path, path: str) -> Path:
    raw_path = _decode_git_single_line(
        _run_git(repository, ["rev-parse", "--git-path", path])
    )
    metadata_path = Path(raw_path)
    if not metadata_path.is_absolute():
        metadata_path = repository / metadata_path
    return metadata_path


def _assert_complete_commit_graph(repository: Path) -> None:
    shallow = _decode_git_single_line(
        _run_git(repository, ["rev-parse", "--is-shallow-repository"])
    )
    if shallow != "false":
        if shallow == "true":
            raise EvidenceError("shallow Git history cannot prove immutable ancestry")
        raise EvidenceError("Git shallow-repository state could not be validated")

    grafts_path = _git_metadata_path(repository, "info/grafts")
    try:
        os.lstat(grafts_path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise EvidenceError("Git graft metadata could not be validated") from error
    raise EvidenceError("Git graft metadata is forbidden for immutable history")


def _raw_commit_parents(repository: Path, commit_sha: str) -> list[str]:
    raw_commit = _run_git(repository, ["cat-file", "commit", commit_sha])
    parents: list[str] = []
    for line in raw_commit.splitlines():
        if not line:
            break
        if not line.startswith(b"parent "):
            continue
        try:
            parent = line.removeprefix(b"parent ").decode("ascii")
        except UnicodeDecodeError as error:
            raise EvidenceError("Git commit contains an invalid parent header") from error
        if not FULL_SHA_PATTERN.fullmatch(parent):
            raise EvidenceError("Git commit contains an invalid parent header")
        parents.append(_resolve_commit(repository, parent))
    return parents


def canonical_repository_path(repository: Path | str) -> Path:
    """Return the canonical Git top-level or fail closed."""

    return _canonical_repository(repository)


def validate_git_ancestor(
    repository: Path | str, ancestor_sha: str, descendant_sha: str
) -> None:
    """Prove ancestry using immutable commit IDs with replace refs disabled."""

    canonical_repository = _canonical_repository(repository)
    _assert_complete_commit_graph(canonical_repository)
    ancestor = _resolve_commit(canonical_repository, ancestor_sha)
    descendant = _resolve_commit(canonical_repository, descendant_sha)
    try:
        _run_git(
            canonical_repository,
            ["merge-base", "--is-ancestor", ancestor, descendant],
        )
    except EvidenceError as error:
        raise EvidenceError(
            "targetBaseSha must be an ancestor of targetCommitSha"
        ) from error


def resolve_head_commit(repository: Path | str) -> str:
    """Resolve HEAD to a full commit with replace refs disabled."""

    canonical_repository = _canonical_repository(repository)
    resolved = _decode_git_single_line(
        _run_git(canonical_repository, ["rev-parse", "--verify", "HEAD^{commit}"])
    )
    return _resolve_commit(canonical_repository, resolved)


def commit_parents(repository: Path | str, commit_sha: str) -> list[str]:
    """Return immutable direct parents for a full commit SHA."""

    canonical_repository = _canonical_repository(repository)
    _assert_complete_commit_graph(canonical_repository)
    commit = _resolve_commit(canonical_repository, commit_sha)
    return _raw_commit_parents(canonical_repository, commit)


def commits_touching_paths(
    repository: Path | str,
    ancestor_sha: str,
    descendant_sha: str,
    paths: Sequence[str],
) -> list[str]:
    """List commits after an anchor that changed trusted repository paths."""

    if not paths:
        raise EvidenceError("at least one history path is required")
    canonical_repository = _canonical_repository(repository)
    ancestor = _resolve_commit(canonical_repository, ancestor_sha)
    descendant = _resolve_commit(canonical_repository, descendant_sha)
    validate_git_ancestor(canonical_repository, ancestor, descendant)
    normalized_paths = [_normalize_evidence_path(path).as_posix() for path in paths]
    output = _run_git(
        canonical_repository,
        [
            "rev-list",
            "--full-history",
            "--topo-order",
            "--reverse",
            descendant,
            f"^{ancestor}",
            "--",
            *normalized_paths,
        ],
    )
    commits = [line for line in os.fsdecode(output).splitlines() if line]
    return [_resolve_commit(canonical_repository, commit) for commit in commits]


def changed_paths_for_commit(
    repository: Path | str, commit_sha: str
) -> list[str]:
    """Return the complete immutable tree delta for a commit, across all parents."""

    canonical_repository = _canonical_repository(repository)
    _assert_complete_commit_graph(canonical_repository)
    commit = _resolve_commit(canonical_repository, commit_sha)
    output = _run_git(
        canonical_repository,
        [
            "diff-tree",
            "--root",
            "-m",
            "--no-ext-diff",
            "--ignore-submodules=none",
            "--no-commit-id",
            "--name-only",
            "-r",
            "-z",
            commit,
        ],
    )
    paths = {
        _normalize_evidence_path(os.fsdecode(raw_path)).as_posix()
        for raw_path in output.split(b"\x00")
        if raw_path
    }
    return sorted(paths)


def list_git_files(repository: Path | str, commit_sha: str, prefix: str) -> list[str]:
    """List regular blobs below a repository-relative prefix at an exact commit."""

    canonical_repository = _canonical_repository(repository)
    commit = _resolve_commit(canonical_repository, commit_sha)
    normalized_prefix = _normalize_evidence_path(prefix).as_posix()
    output = _run_git(
        canonical_repository,
        ["ls-tree", "-r", "-z", commit, "--", normalized_prefix],
    )
    files: list[str] = []
    for raw_entry in (entry for entry in output.split(b"\x00") if entry):
        metadata, separator, raw_path = raw_entry.partition(b"\t")
        parts = metadata.split(b" ")
        if not separator or len(parts) != 3:
            raise EvidenceError("Git tree returned invalid artifact metadata")
        mode, object_type, _object_id = (os.fsdecode(part) for part in parts)
        path = os.fsdecode(raw_path)
        _normalize_evidence_path(path)
        if mode in UNSAFE_GIT_MODES:
            raise EvidenceError("Git artifact tree contains a forbidden link mode")
        if mode not in REGULAR_BLOB_MODES or object_type != "blob":
            raise EvidenceError("Git artifact tree contains a non-regular entry")
        files.append(path)
    return sorted(files)


def git_path_exists(
    repository: Path | str, commit_sha: str, evidence_path: str
) -> bool:
    """Distinguish an absent tree path from an unsafe or malformed present entry."""

    canonical_repository = _canonical_repository(repository)
    commit = _resolve_commit(canonical_repository, commit_sha)
    path = _normalize_evidence_path(evidence_path).as_posix()
    output = _run_git(canonical_repository, ["ls-tree", "-z", commit, "--", path])
    entries = [entry for entry in output.split(b"\x00") if entry]
    if not entries:
        return False
    if len(entries) != 1:
        raise EvidenceError("Git tree path is ambiguous")
    _parse_tree_entry(output, path)
    return True


def _normalize_evidence_path(evidence_path: str) -> PurePosixPath:
    if not evidence_path or "\x00" in evidence_path or "\\" in evidence_path:
        raise EvidenceError("evidence path must be a non-empty POSIX path")
    raw_components = evidence_path.split("/")
    if any(component in {"", ".", ".."} for component in raw_components):
        raise EvidenceError(
            "evidence path must use canonical repository-relative syntax"
        )
    path = PurePosixPath(evidence_path)
    if path.is_absolute():
        raise EvidenceError("evidence path must stay within the repository")
    return path


def _parse_tree_entry(output: bytes, expected_path: str) -> tuple[str, str, str]:
    entries = [entry for entry in output.split(b"\x00") if entry]
    if len(entries) != 1:
        raise EvidenceError(f"evidence path is missing or ambiguous: {expected_path}")
    metadata, separator, raw_path = entries[0].partition(b"\t")
    if not separator or os.fsdecode(raw_path) != expected_path:
        raise EvidenceError(
            f"Git tree returned an unexpected evidence path: {expected_path}"
        )
    parts = metadata.split(b" ")
    if len(parts) != 3:
        raise EvidenceError(f"Git tree returned invalid metadata: {expected_path}")
    return tuple(os.fsdecode(part) for part in parts)  # type: ignore[return-value]


def _validated_blob(
    repository: Path | str, commit_sha: str, evidence_path: str
) -> tuple[Path, str, str]:
    canonical_repository = _canonical_repository(repository)
    commit = _resolve_commit(canonical_repository, commit_sha)
    path = _normalize_evidence_path(evidence_path)

    components: list[str] = []
    final_object = ""
    for index, component in enumerate(path.parts):
        components.append(component)
        current_path = "/".join(components)
        mode, object_type, object_id = _parse_tree_entry(
            _run_git(
                canonical_repository, ["ls-tree", "-z", commit, "--", current_path]
            ),
            current_path,
        )
        unsafe_description = UNSAFE_GIT_MODES.get(mode)
        if unsafe_description is not None:
            raise EvidenceError(
                f"Git mode {mode} ({unsafe_description}) is forbidden for evidence: {current_path}"
            )

        is_final = index == len(path.parts) - 1
        if is_final:
            if mode not in REGULAR_BLOB_MODES or object_type != "blob":
                raise EvidenceError(
                    f"evidence path must be a regular Git blob: {current_path}"
                )
            final_object = object_id
        elif mode != "040000" or object_type != "tree":
            raise EvidenceError(f"evidence parent must be a Git tree: {current_path}")

    return canonical_repository, commit, final_object


def read_git_evidence(
    repository: Path | str,
    commit_sha: str,
    evidence_path: str,
    *,
    max_bytes: int = DEFAULT_MAX_EVIDENCE_BYTES,
) -> bytes:
    """Read a regular tracked blob without consulting the live checkout."""

    canonical_repository, _commit, object_id = _validated_blob(
        repository, commit_sha, evidence_path
    )
    try:
        blob_size = int(
            _decode_git_single_line(
                _run_git(canonical_repository, ["cat-file", "-s", object_id])
            )
        )
    except ValueError as error:
        raise EvidenceError("Git blob size could not be validated") from error
    if max_bytes < 0 or blob_size > max_bytes:
        raise EvidenceError("evidence exceeds the configured size limit")
    return _run_git(canonical_repository, ["cat-file", "blob", object_id])


def validate_materialized_evidence(
    repository: Path | str,
    commit_sha: str,
    materialized_root: Path | str,
    evidence_path: str,
) -> bytes:
    """Read through directory FDs and compare the same opened file to the Git blob."""

    expected = read_git_evidence(repository, commit_sha, evidence_path)
    path = _normalize_evidence_path(evidence_path)
    raw_root = Path(materialized_root)
    try:
        root_stat = os.lstat(raw_root)
    except OSError as error:
        raise EvidenceError("materialization root does not exist") from error
    if stat.S_ISLNK(root_stat.st_mode):
        raise EvidenceError("materialization root must not be a symbolic link")
    if not stat.S_ISDIR(root_stat.st_mode):
        raise EvidenceError("materialization root must be a directory")
    if not SAFE_OPENAT_SUPPORTED:
        raise EvidenceError("platform does not support safe openat evidence reads")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    try:
        descriptor = os.open(raw_root, directory_flags)
    except OSError as error:
        raise EvidenceError("materialization root cannot be opened safely") from error
    try:
        opened_root_stat = os.fstat(descriptor)
        if not stat.S_ISDIR(opened_root_stat.st_mode) or (
            opened_root_stat.st_dev,
            opened_root_stat.st_ino,
        ) != (root_stat.st_dev, root_stat.st_ino):
            raise EvidenceError("materialization root changed before safe open")
        for index, component in enumerate(path.parts):
            is_final = index == len(path.parts) - 1
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            if not is_final:
                flags |= os.O_DIRECTORY
            try:
                child_descriptor = os.open(component, flags, dir_fd=descriptor)
            except OSError as error:
                if error.errno == errno.ELOOP:
                    raise EvidenceError(
                        f"materialized evidence contains a symbolic link: {evidence_path}"
                    ) from error
                raise EvidenceError(
                    f"materialized evidence cannot be opened safely: {evidence_path}"
                ) from error
            child_stat = os.fstat(child_descriptor)
            if is_final:
                if not stat.S_ISREG(child_stat.st_mode):
                    os.close(child_descriptor)
                    raise EvidenceError(
                        f"materialized evidence must be a regular file: {evidence_path}"
                    )
                if child_stat.st_size > DEFAULT_MAX_EVIDENCE_BYTES:
                    os.close(child_descriptor)
                    raise EvidenceError(
                        "materialized evidence exceeds the configured size limit"
                    )
            elif not stat.S_ISDIR(child_stat.st_mode):
                os.close(child_descriptor)
                raise EvidenceError(
                    f"materialized evidence parent must be a directory: {evidence_path}"
                )
            os.close(descriptor)
            descriptor = child_descriptor

        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        actual = b"".join(chunks)
    finally:
        os.close(descriptor)

    if actual != expected:
        raise EvidenceError(
            f"materialized evidence differs from the declared Git blob: {evidence_path}"
        )
    return actual


def read_target_rules(
    target_repository: Path | str,
    target_base_sha: str,
    rule_paths: Sequence[str],
) -> dict[str, bytes]:
    """Resolve every target rule from targetBaseSha, never from the live checkout."""

    if not rule_paths:
        raise EvidenceError("at least one target rule path is required")
    if len(set(rule_paths)) != len(rule_paths):
        raise EvidenceError("target rule paths must be unique")
    return {
        rule_path: read_git_evidence(target_repository, target_base_sha, rule_path)
        for rule_path in sorted(rule_paths)
    }


def parse_args(arguments: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate immutable modernization evidence without reading live files."
    )
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--path", required=True)
    parser.add_argument("--materialized-root", type=Path)
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    options = parse_args(sys.argv[1:] if arguments is None else arguments)
    try:
        if options.materialized_root is None:
            read_git_evidence(options.repository, options.commit, options.path)
        else:
            validate_materialized_evidence(
                options.repository,
                options.commit,
                options.materialized_root,
                options.path,
            )
    except EvidenceError as error:
        print(f"Immutable evidence check failed: {error}", file=sys.stderr)
        return 1
    print("Immutable evidence check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
