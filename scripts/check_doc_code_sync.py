#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


FULL_COMMIT = re.compile(r"^[0-9a-f]{40}$")
CURRENT_STATUS = "docs/ai-context/current-status.md"
SYSTEM_MANAGEMENT_PRODUCT = "docs/product/system-management.md"
MERCHANT_CONTEXT = "docs/ai-context/merchant/README.md"
MERCHANT_CONTRACT = "docs/ai-contract/merchant-lifecycle-api-contract.md"
MERCHANT_PRODUCT = "docs/product/merchant-management.md"
IDENTITY_MIGRATION_PREFIX = (
    "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/"
)
MERCHANT_IDENTITY_MIGRATION_MARKERS = (
    b"merchant:",
    b"MerchantManagement",
    b"/merchant",
)


@dataclass(frozen=True)
class SyncRule:
    rule_id: str
    triggers: tuple[str, ...]
    required_docs: tuple[str, ...]
    exclusions: tuple[str, ...] = ()
    excluded_suffixes: tuple[str, ...] = ()


@dataclass(frozen=True)
class GitContext:
    repository: Path
    git_dir: Path
    work_tree: Path
    common_dir: Path
    object_directory: Path
    executable: Path
    safe_path: str
    safe_home: Path


@dataclass(frozen=True)
class TreeEntry:
    mode: str
    kind: str
    object_id: str


RULES = (
    SyncRule(
        "DOC-SYNC-BACKEND",
        ("backend/",),
        (CURRENT_STATUS, "docs/ai-context/backend/README.md"),
    ),
    SyncRule(
        "DOC-SYNC-FRONTEND-ADMIN",
        ("frontend/admin/",),
        (CURRENT_STATUS, "docs/ai-context/frontend/README.md"),
    ),
    SyncRule(
        "DOC-SYNC-IDENTITY-CONTRACT",
        (
            "backend/applications/platform-admin-api/",
            "backend/applications/merchant-admin-api/",
            "backend/applications/agent-admin-api/",
            "backend/modules/identity/",
            "frontend/admin/apps/platform-admin/src/api/",
            "frontend/admin/apps/platform-admin/src/views/system/",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/",
            "frontend/admin/packages/effects/backoffice-runtime/src/router/",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/",
        ),
        ("docs/ai-contract/identity-admin-api-contract.md",),
    ),
    SyncRule(
        "DOC-SYNC-SYSTEM-DICTIONARY",
        (
            "backend/modules/system-dictionary/",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/SystemDictionaryController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeSystemDictionaryReadController.java",
            "frontend/admin/apps/platform-admin/src/api/system/dictionary",
            "frontend/admin/apps/platform-admin/src/views/system/dict/",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/system/dictionary",
            "frontend/admin/packages/effects/backoffice-runtime/src/components/account-domain",
            "frontend/admin/packages/effects/backoffice-runtime/src/components/common-status-dictionary",
            "frontend/admin/packages/effects/backoffice-runtime/src/components/dictionary-type-selector",
            "frontend/admin/packages/effects/backoffice-runtime/src/composables/use-account-domain-dictionary",
            "frontend/admin/packages/effects/backoffice-runtime/src/composables/use-common-status-dictionary",
            "frontend/admin/packages/effects/backoffice-runtime/src/composables/use-system-dictionaries",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/dictionary-data/",
        ),
        ("docs/ai-contract/system-dictionary-api-contract.md",),
    ),
    SyncRule(
        "DOC-SYNC-SYSTEM-MANAGEMENT-PRODUCT",
        (
            "frontend/admin/apps/platform-admin/src/deployment.ts",
            "frontend/admin/apps/merchant-admin/src/deployment.ts",
            "frontend/admin/apps/agent-admin/src/deployment.ts",
            "frontend/admin/apps/platform-admin/src/api/system/",
            "frontend/admin/apps/platform-admin/src/views/system/",
            "frontend/admin/packages/effects/backoffice-runtime/src/common-pages.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/system/",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/AdminApiPermissionPolicy.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/SystemAdministrationController.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/PlatformAdministrationDirectoryController.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/PlatformUserGovernanceController.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/RoleGrantAdministrationController.java",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/SystemDictionaryController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeAdministrationPermissionPolicy.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeRoleGrantAdministrationController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeSystemDictionaryReadController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeUserRoleAdministrationController.java",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeWebConfiguration.java",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V2__iam_admin_api.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V3__dashboard_menu.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V4__align_vben_menu_contract.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V14__granular_administration_permissions.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V15__expand_legacy_administration_permission_compatibility.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V16__enforce_exact_administration_permission_catalog.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V17__add_administration_tombstones.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V18__isolate_backoffice_account_domains.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V19__protect_backoffice_access_grants.sql",
            "backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V20__converge_reserved_backoffice_grant_keys.sql",
            "backend/modules/system-dictionary/src/main/java/",
            "backend/modules/system-dictionary/src/main/resources/db/migration/",
            "backend/applications/platform-admin-api/src/main/resources/db/local/iam-local-bootstrap.sql",
        ),
        (SYSTEM_MANAGEMENT_PRODUCT,),
        (
            "frontend/admin/apps/platform-admin/src/views/system/permission-dependencies",
            "frontend/admin/apps/platform-admin/src/views/system/platform-directory",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/system/identity-status",
        ),
        (".test.ts", ".spec.ts"),
    ),
    SyncRule(
        "DOC-SYNC-MERCHANT-LIFECYCLE",
        (
            "backend/modules/merchant/",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/merchant/",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/web/Merchant",
            "backend/applications/platform-admin-api/src/main/java/com/niv/payment/adminapi/config/AdminApiPermissionPolicy.java",
            "backend/applications/platform-admin-api/src/main/resources/db/local/iam-local-bootstrap.sql",
            "backend/applications/merchant-admin-api/src/main/java/com/niv/payment/merchantadminapi/merchant/",
            "backend/applications/merchant-admin-api/src/main/java/com/niv/payment/merchantadminapi/web/Merchant",
            "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeAdministrationPermissionPolicy.java",
            "backend/modules/identity/persistence-postgres/src/main/java/com/niv/payment/identity/lifecycle/JooqIdentityInvitationRepository.java",
            "frontend/admin/apps/platform-admin/src/api/merchant/",
            "frontend/admin/apps/platform-admin/src/views/merchant/",
            "frontend/admin/apps/platform-admin/src/deployment.ts",
            "frontend/admin/apps/merchant-admin/src/api/merchant/",
            "frontend/admin/apps/merchant-admin/src/views/merchant/",
            "frontend/admin/apps/merchant-admin/src/deployment.ts",
            "frontend/admin/apps/agent-admin/src/deployment.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/permission-codes.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/common-pages.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/api/merchant/",
            "frontend/admin/packages/effects/backoffice-runtime/src/router/merchant",
            "frontend/admin/packages/effects/backoffice-runtime/src/router/routes/modules/merchant.ts",
            "frontend/admin/packages/effects/backoffice-runtime/src/views/merchant/",
            "frontend/admin/packages/effects/backoffice-runtime/src/locales/langs/en-US/merchant.json",
            "frontend/admin/packages/effects/backoffice-runtime/src/locales/langs/zh-CN/merchant.json",
        ),
        (
            CURRENT_STATUS,
            MERCHANT_CONTEXT,
            MERCHANT_CONTRACT,
            MERCHANT_PRODUCT,
        ),
        (
            "backend/modules/merchant/core/src/test/",
            "backend/modules/merchant/persistence-postgres/src/test/",
            "backend/modules/merchant/web/src/test/",
        ),
        excluded_suffixes=(".test.ts", ".spec.ts"),
    ),
    SyncRule(
        "DOC-SYNC-IDENTITY-INFRA",
        ("infra/",),
        (
            CURRENT_STATUS,
            "docs/ai-context/backend/README.md",
            "docs/ai-contract/identity-admin-api-contract.md",
        ),
    ),
    SyncRule(
        "DOC-SYNC-GOVERNANCE",
        (
            ".agents/",
            ".github/CODEOWNERS",
            ".github/workflows/documentation.yml",
            "scripts/check_",
            "scripts/ci_repository_guard.sh",
            "scripts/requirements-documentation.txt",
        ),
        (
            CURRENT_STATUS,
            "docs/ai-context/development-workflow.md",
            "docs/judge-charter.md",
        ),
        ("scripts/tests/", "scripts/dev/"),
    ),
    SyncRule(
        "DOC-SYNC-BACKEND-WORKFLOW",
        (".github/workflows/backend.yml",),
        (
            CURRENT_STATUS,
            "docs/ai-context/backend/README.md",
            "docs/ai-context/development-workflow.md",
        ),
    ),
    SyncRule(
        "DOC-SYNC-FRONTEND-WORKFLOW",
        (".github/workflows/frontend.yml",),
        (
            CURRENT_STATUS,
            "docs/ai-context/frontend/README.md",
            "docs/ai-context/development-workflow.md",
        ),
    ),
)


class CheckError(RuntimeError):
    pass


def _git_environment(context: GitContext) -> dict[str, str]:
    return {
        "PATH": context.safe_path,
        "HOME": str(context.safe_home),
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_ATTR_NOSYSTEM": "1",
        "GIT_ALLOW_PROTOCOL": "",
        "GIT_PROTOCOL_FROM_USER": "0",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_GRAFT_FILE": os.devnull,
        "GIT_DIR": str(context.git_dir),
        "GIT_WORK_TREE": str(context.work_tree),
        "GIT_COMMON_DIR": str(context.common_dir),
        "GIT_OBJECT_DIRECTORY": str(context.object_directory),
    }


def _git_command(context: GitContext, *arguments: str) -> tuple[str, ...]:
    return (
        str(context.executable),
        f"--git-dir={context.git_dir}",
        f"--work-tree={context.work_tree}",
        "-c",
        f"safe.directory={context.repository}",
        "-c",
        "core.quotepath=false",
        "-c",
        "core.fsmonitor=",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "submodule.recurse=false",
        "-c",
        "core.commitGraph=false",
        "-c",
        "core.useReplaceRefs=false",
        "-c",
        "diff.external=",
        "-c",
        "diff.noprefix=false",
        "--no-replace-objects",
        "--literal-pathspecs",
        *arguments,
    )


def _run(command: tuple[str, ...], environment: dict[str, str]) -> bytes:
    try:
        result = subprocess.run(
            command,
            check=True,
            capture_output=True,
            env=environment,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        detail = ""
        if isinstance(error, subprocess.CalledProcessError):
            detail = error.stderr.decode("utf-8", errors="replace").strip()
        raise CheckError(f"git command failed: {detail or error}") from error
    return result.stdout


def _git(context: GitContext, *arguments: str) -> bytes:
    return _run(_git_command(context, *arguments), _git_environment(context))


def _discovery_environment(path: str, home: Path) -> dict[str, str]:
    return {
        "PATH": path,
        "HOME": str(home),
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_ATTR_NOSYSTEM": "1",
        "GIT_ALLOW_PROTOCOL": "",
        "GIT_PROTOCOL_FROM_USER": "0",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_GRAFT_FILE": os.devnull,
    }


def _discover_context(repository: Path) -> GitContext:
    executable_raw = shutil.which("git")
    if executable_raw is None:
        raise CheckError("git executable is unavailable")
    executable = Path(executable_raw).resolve()
    safe_path = str(executable.parent)
    safe_home = repository
    prefix = (
        str(executable),
        "-C",
        str(repository),
        "-c",
        f"safe.directory={repository}",
        "-c",
        "core.commitGraph=false",
        "-c",
        "core.useReplaceRefs=false",
        "--no-replace-objects",
        "--literal-pathspecs",
    )
    environment = _discovery_environment(safe_path, safe_home)

    def resolve(*arguments: str) -> Path:
        value = _run((*prefix, *arguments), environment).decode("utf-8").strip()
        return Path(value).resolve()

    work_tree = resolve("rev-parse", "--show-toplevel")
    git_dir = resolve("rev-parse", "--absolute-git-dir")
    common_dir = resolve("rev-parse", "--path-format=absolute", "--git-common-dir")
    object_directory = resolve(
        "rev-parse", "--path-format=absolute", "--git-path", "objects"
    )
    return GitContext(
        repository=repository,
        git_dir=git_dir,
        work_tree=work_tree,
        common_dir=common_dir,
        object_directory=object_directory,
        executable=executable,
        safe_path=safe_path,
        safe_home=safe_home,
    )


def _verify_context(context: GitContext) -> None:
    expected = {
        "repository": context.repository,
        "work tree": context.work_tree,
        "git directory": context.git_dir,
        "git common directory": context.common_dir,
        "object directory": context.object_directory,
    }
    if context.repository != context.work_tree:
        raise CheckError("repository root must equal the bound work tree")
    for label, path in expected.items():
        if not path.is_dir():
            raise CheckError(f"bound {label} is not a directory: {path}")
    actual = {
        "work tree": _git(context, "rev-parse", "--show-toplevel"),
        "git directory": _git(context, "rev-parse", "--absolute-git-dir"),
        "git common directory": _git(
            context, "rev-parse", "--path-format=absolute", "--git-common-dir"
        ),
        "object directory": _git(
            context, "rev-parse", "--path-format=absolute", "--git-path", "objects"
        ),
    }
    for label, raw_value in actual.items():
        if Path(raw_value.decode("utf-8").strip()).resolve() != expected[label]:
            raise CheckError(f"bound {label} does not match the repository")
    if _git(context, "for-each-ref", "--format=%(refname)", "refs/replace/").strip():
        raise CheckError("replace refs are forbidden")


def _require_full_commit(commit: str, label: str) -> None:
    if FULL_COMMIT.fullmatch(commit) is None:
        raise CheckError(f"{label} must be a full lowercase SHA-1 commit id")


def _verify_commit(context: GitContext, commit: str, label: str) -> None:
    resolved = _git(context, "rev-parse", "--verify", f"{commit}^{{commit}}")
    if resolved.decode("ascii").strip() != commit:
        raise CheckError(f"{label} does not resolve to itself")


def _changed_paths(context: GitContext, base: str, commit: str) -> set[str]:
    try:
        _git(context, "merge-base", "--is-ancestor", base, commit)
    except CheckError as error:
        raise CheckError("base commit must be an ancestor of target commit") from error
    output = _git(
        context,
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--no-renames",
        "--name-only",
        "-z",
        base,
        commit,
        "--",
    )
    return {raw.decode("utf-8") for raw in output.split(b"\0") if raw}


def _tree_entry(context: GitContext, commit: str, path: str) -> TreeEntry | None:
    output = _git(context, "ls-tree", "-z", commit, "--", path)
    records = [record for record in output.split(b"\0") if record]
    if not records:
        return None
    if len(records) != 1:
        raise CheckError(f"tree path is ambiguous: {path}")
    metadata, separator, actual_path = records[0].partition(b"\t")
    if not separator or actual_path.decode("utf-8") != path:
        raise CheckError(f"tree path does not resolve exactly: {path}")
    fields = metadata.decode("ascii").split()
    if len(fields) != 3:
        raise CheckError(f"tree entry is malformed: {path}")
    return TreeEntry(fields[0], fields[1], fields[2])


def _rule_matches(rule: SyncRule, path: str) -> bool:
    return (
        not path.startswith("docs/")
        and any(path.startswith(prefix) for prefix in rule.triggers)
        and not any(path.startswith(prefix) for prefix in rule.exclusions)
        and not any(path.endswith(suffix) for suffix in rule.excluded_suffixes)
    )


def _tree_blob(context: GitContext, commit: str, path: str) -> bytes:
    entry = _tree_entry(context, commit, path)
    if entry is None:
        return b""
    if entry.mode != "100644" or entry.kind != "blob":
        raise CheckError(f"content-trigger path must be a regular blob: {path}")
    size_raw = _git(context, "cat-file", "-s", entry.object_id)
    try:
        size = int(size_raw.decode("ascii").strip())
    except ValueError as error:
        raise CheckError(f"content-trigger blob size is malformed: {path}") from error
    if size > 1024 * 1024:
        raise CheckError(f"content-trigger blob is too large: {path}")
    return _git(context, "cat-file", "blob", entry.object_id)


def _merchant_identity_migration_matches(
    context: GitContext,
    base: str,
    commit: str,
    path: str,
) -> bool:
    if not path.startswith(IDENTITY_MIGRATION_PREFIX) or not path.endswith(".sql"):
        return False
    content = _tree_blob(context, base, path) + b"\n" + _tree_blob(
        context, commit, path
    )
    return any(marker in content for marker in MERCHANT_IDENTITY_MIGRATION_MARKERS)


def validate(
    repository: Path,
    base: str,
    commit: str,
    *,
    context: GitContext | None = None,
) -> list[str]:
    repository = repository.resolve()
    if not repository.is_dir():
        return [f"repository root is not a directory: {repository}"]
    try:
        context = context or _discover_context(repository)
        if context.repository != repository:
            raise CheckError("bound Git context does not match repository root")
        _verify_context(context)
        _require_full_commit(base, "base commit")
        _require_full_commit(commit, "target commit")
        _verify_commit(context, base, "base commit")
        _verify_commit(context, commit, "target commit")
        changed_paths = _changed_paths(context, base, commit)
    except CheckError as error:
        return [str(error)]

    errors: list[str] = []
    for rule in RULES:
        try:
            matched = sorted(
                path
                for path in changed_paths
                if _rule_matches(rule, path)
                or (
                    rule.rule_id == "DOC-SYNC-MERCHANT-LIFECYCLE"
                    and _merchant_identity_migration_matches(
                        context, base, commit, path
                    )
                )
            )
        except CheckError as error:
            errors.append(f"{rule.rule_id}: cannot inspect content trigger: {error}")
            continue
        if not matched:
            continue
        for required_doc in rule.required_docs:
            try:
                base_entry = _tree_entry(context, base, required_doc)
                target_entry = _tree_entry(context, commit, required_doc)
            except CheckError as error:
                errors.append(f"{rule.rule_id}: cannot verify {required_doc}: {error}")
                continue
            if (
                target_entry is None
                or target_entry.mode != "100644"
                or target_entry.kind != "blob"
            ):
                errors.append(
                    f"{rule.rule_id}: required documentation must be a non-executable regular file at target commit: {required_doc}"
                )
                continue
            if base_entry is not None and base_entry.object_id == target_entry.object_id:
                errors.append(
                    f"{rule.rule_id}: changes under {matched[0]} require a content update to {required_doc}"
                )
    return errors


def _required_path(value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        raise argparse.ArgumentTypeError("path must be absolute")
    return path.resolve()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Require code and governance changes to update their owned documentation."
    )
    parser.add_argument("--repository-root", type=_required_path, required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--git-executable", type=_required_path, required=True)
    parser.add_argument("--git-dir", type=_required_path, required=True)
    parser.add_argument("--work-tree", type=_required_path, required=True)
    parser.add_argument("--git-common-dir", type=_required_path, required=True)
    parser.add_argument("--object-directory", type=_required_path, required=True)
    parser.add_argument("--safe-path", required=True)
    parser.add_argument("--safe-home", type=_required_path, required=True)
    arguments = parser.parse_args()

    context = GitContext(
        repository=arguments.repository_root,
        git_dir=arguments.git_dir,
        work_tree=arguments.work_tree,
        common_dir=arguments.git_common_dir,
        object_directory=arguments.object_directory,
        executable=arguments.git_executable,
        safe_path=arguments.safe_path,
        safe_home=arguments.safe_home,
    )
    errors = validate(
        arguments.repository_root,
        arguments.base_commit,
        arguments.commit,
        context=context,
    )
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("PASS: code/documentation synchronization requirements are satisfied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
