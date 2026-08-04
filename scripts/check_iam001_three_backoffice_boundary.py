#!/usr/bin/env python3
"""Run IAM-001 checks against an immutable Git snapshot and emit an unsigned v2 diagnostic."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence


SLICE_ID = "IAM-001-three-backoffice-session-isolation"
TURN_ID = "019f7fea-28fb-7da3-808e-4e5f9c74533e"
TARGET_BASE_SHA = "1ca8a89b7884cca5b2e7ed929f447a73b8a3ef18"
PROCESS_CHECK_ID = "IAM-001-PROCESS-BOUNDARY"
MUTATION_CHECK_ID = "IAM-001-MUTATION-SENSITIVITY"
FULL_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
MANDATORY_PERMISSION_CONTEXT = "docs/ai-context/permission/06-database-design.md"
REQUIRED_WORKSPACE_CONTEXT = (
    "登录 API 不接受 `tenantId` 或等价工作区选择字段",
    "授权工作区只能由服务端可信入口或上下文解析",
    "无法唯一解析一个 ACTIVE Membership 时，认证以不泄露 Membership 是否存在的通用 401 失败",
)
FORBIDDEN_WORKSPACE_CONTEXT = (
    "多个活动 Membership 必须显式选择 tenantId",
)
CLIENT_WORKSPACE_ACTORS = ("客户端", "浏览器")
CLIENT_WORKSPACE_SELECTORS = ("tenantId", "workspaceId", "工作区")
CLIENT_WORKSPACE_SELECTION_VERBS = ("选择", "指定", "切换")
CLIENT_WORKSPACE_NEGATIONS = ("不接受", "不允许", "不应", "不得", "不能", "拒绝", "禁止")
FORBIDDEN_GIT_ENVIRONMENT = frozenset({
    "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_ATTR_NOSYSTEM", "GIT_ATTR_SOURCE",
    "GIT_CEILING_DIRECTORIES", "GIT_COMMON_DIR", "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_SYSTEM", "GIT_DIR", "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_EXEC_PATH", "GIT_GRAFT_FILE", "GIT_INDEX_FILE",
    "GIT_INTERNAL_SUPER_PREFIX", "GIT_NAMESPACE", "GIT_OBJECT_DIRECTORY",
    "GIT_PREFIX", "GIT_QUARANTINE_PATH", "GIT_REPLACE_REF_BASE",
    "GIT_SHALLOW_FILE", "GIT_WORK_TREE",
})


@dataclass(frozen=True)
class Command:
    cwd: str
    argv: tuple[str, ...]


@dataclass(frozen=True)
class Mutation:
    mutation_id: str
    path: str
    before: str
    after: str
    command: Command
    report_glob: str
    test_class: str
    test_name: str


OWNED_PATHS = (
    ".agents/payment-modernization-judge-registry.json",
    ".agents/payment-modernization-policy.json",
    ".agents/payment-modernization/rules/IAM-001.json",
    ".github/workflows/frontend.yml",
    "README.md",
    "backend",
    "docs",
    "frontend/admin/apps/web-antdv-next",
    "frontend/admin/packages/@core/base/typings",
    "frontend/admin/packages/effects/access",
    "frontend/admin/packages/effects/common-ui",
    "frontend/admin/scripts/deploy",
    "scripts",
)


MAVEN = ("./mvnw", "-s", "maven-settings.xml")
MUTATIONS = (
    Mutation(
        "PLATFORM_MUTATION_ORIGIN_GUARD",
        "backend/applications/admin-api/src/main/java/com/niv/payment/adminapi/config/SecurityConfiguration.java",
        "            requireTrustedOriginForMutation(request);\n",
        "",
        Command("backend", MAVEN + (
            "-pl", "applications/admin-api,applications/merchant-admin-api,applications/agent-admin-api,tests/iam001-blackbox",
            "-am",
            "-Dit.test=ThreeBackofficeBoundaryIntegrationTest#untrustedOriginsCannotCreateOrTerminateSessions",
            "-Dfailsafe.failIfNoSpecifiedTests=false", "verify",
        )),
        "backend/tests/iam001-blackbox/target/failsafe-reports/TEST-*.xml",
        "com.niv.payment.iam001.ThreeBackofficeBoundaryIntegrationTest",
        "untrustedOriginsCannotCreateOrTerminateSessions",
    ),
    Mutation(
        "SHARED_BACKOFFICE_MUTATION_ORIGIN_GUARD",
        "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeSecurityConfiguration.java",
        "            if (!Set.of(\"GET\", \"HEAD\").contains(request.getMethod())\n"
        "                && !allowedOrigin.equals(request.getHeader(\"Origin\"))) {\n"
        "                throw new BackofficeAccessDeniedException();\n"
        "            }\n",
        "",
        Command("backend", MAVEN + (
            "-pl", "applications/admin-api,applications/merchant-admin-api,applications/agent-admin-api,tests/iam001-blackbox",
            "-am",
            "-Dit.test=ThreeBackofficeBoundaryIntegrationTest#untrustedOriginsCannotCreateOrTerminateSessions",
            "-Dfailsafe.failIfNoSpecifiedTests=false", "verify",
        )),
        "backend/tests/iam001-blackbox/target/failsafe-reports/TEST-*.xml",
        "com.niv.payment.iam001.ThreeBackofficeBoundaryIntegrationTest",
        "untrustedOriginsCannotCreateOrTerminateSessions",
    ),
    Mutation(
        "CROSS_DOMAIN_CREDENTIAL_RESULT",
        "backend/modules/identity/core/src/main/java/com/niv/payment/permission/service/AuthenticationService.java",
        "credentials.findActiveByUsername(username, accountDomain)\n            .filter(account -> account.accountDomain() == accountDomain);",
        "credentials.findActiveByUsername(username, accountDomain);",
        Command("backend", MAVEN + (
            "-pl", "modules/identity/core",
            "-Dtest=AuthenticationServiceTest#adapterCannotReturnAnAccountFromAnotherDomain",
            "test",
        )),
        "backend/modules/identity/core/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.AuthenticationServiceTest",
        "adapterCannotReturnAnAccountFromAnotherDomain",
    ),
    Mutation(
        "EXPLICIT_PORTAL_GRANT",
        "backend/modules/identity/persistence-postgres/src/main/java/com/niv/payment/permission/persistence/repository/JooqCredentialRepository.java",
        ".and(membershipScope)\n                .and(explicitPortalAccess))",
        ".and(membershipScope))",
        Command("backend", MAVEN + (
            "-pl", "applications/admin-api", "-am",
            "-Dit.test=LocalIdentityFixtureBootstrapIntegrationTest#activeMembershipWithoutAnExplicitPortalGrantCannotAuthenticate",
            "-Dfailsafe.failIfNoSpecifiedTests=false", "verify",
        )),
        "backend/applications/admin-api/target/failsafe-reports/TEST-*.xml",
        "com.niv.payment.adminapi.config.LocalIdentityFixtureBootstrapIntegrationTest",
        "activeMembershipWithoutAnExplicitPortalGrantCannotAuthenticate",
    ),
    Mutation(
        "SESSION_ACCOUNT_DOMAIN",
        "backend/modules/identity/session-satoken/src/main/java/com/niv/payment/permission/security/SaTokenSessionBridge.java",
        "if (sessionDomain != accountDomain) {",
        "if (false) {",
        Command("backend", MAVEN + (
            "-pl", "modules/identity/session-satoken", "-am",
            "-Dtest=SaTokenSessionBridgeTest#rejectsCrossRealmAndMissingDomainSessions",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/session-satoken/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.SaTokenSessionBridgeTest",
        "rejectsCrossRealmAndMissingDomainSessions",
    ),
    Mutation(
        "SESSION_PERMISSION_VERSION",
        "backend/modules/identity/session-satoken/src/main/java/com/niv/payment/permission/security/SaTokenSessionBridge.java",
        "if (permissionVersion != currentVersions.permissionVersion()) {",
        "if (false) {",
        Command("backend", MAVEN + (
            "-pl", "modules/identity/session-satoken", "-am",
            "-Dtest=SaTokenSessionBridgeTest#rejectsARevokedPermissionVersion",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/session-satoken/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.SaTokenSessionBridgeTest",
        "rejectsARevokedPermissionVersion",
    ),
    Mutation(
        "SESSION_REVOCATION_VERSION",
        "backend/modules/identity/session-satoken/src/main/java/com/niv/payment/permission/security/SaTokenSessionBridge.java",
        "if (sessionVersion != currentVersions.sessionVersion()) {",
        "if (false) {",
        Command("backend", MAVEN + (
            "-pl", "modules/identity/session-satoken", "-am",
            "-Dtest=SaTokenSessionBridgeTest#rejectsARevokedSessionVersion",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/session-satoken/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.SaTokenSessionBridgeTest",
        "rejectsARevokedSessionVersion",
    ),
    Mutation(
        "ACTIVE_MEMBERSHIP_REQUIRED",
        "backend/modules/identity/session-satoken/src/main/java/com/niv/payment/permission/security/SaTokenSessionBridge.java",
        ".orElseThrow(() -> new InvalidSessionException(\n                \"No active tenant, user, credential, and membership tuple found for session validation\"));",
        ".orElse(new MembershipSessionVersionRepository.MembershipVersions(\n                permissionVersion, sessionVersion));",
        Command("backend", MAVEN + (
            "-pl", "modules/identity/session-satoken", "-am",
            "-Dtest=SaTokenSessionBridgeTest#rejectsASessionWhoseMembershipIsNoLongerActive",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/session-satoken/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.SaTokenSessionBridgeTest",
        "rejectsASessionWhoseMembershipIsNoLongerActive",
    ),
    Mutation(
        "CACHE_KEY_ACCOUNT_DOMAIN",
        "backend/modules/identity/cache-redis/src/main/java/com/niv/payment/permission/cache/PermissionCacheKey.java",
        'return "iam:%s:grant:%d:%d:v%d".formatted(\n            accountDomain.cacheNamespace(), tenantId, membershipId, permissionVersion);',
        'return "iam:grant:%d:%d:v%d".formatted(tenantId, membershipId, permissionVersion);',
        Command("backend", MAVEN + (
            "-pl", "modules/identity/cache-redis", "-am",
            "-Dtest=RedisPermissionGrantCacheTest#usesDistinctKeysForTheSameIdentityInEveryAccountDomain",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/cache-redis/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.RedisPermissionGrantCacheTest",
        "usesDistinctKeysForTheSameIdentityInEveryAccountDomain",
    ),
    Mutation(
        "CACHE_PAYLOAD_ACCOUNT_DOMAIN",
        "backend/modules/identity/cache-redis/src/main/java/com/niv/payment/permission/cache/RedisPermissionGrantCache.java",
        'return ("iam-grant:" + accountDomain.name() + ":").getBytes(StandardCharsets.US_ASCII);',
        'return "iam-grant:".getBytes(StandardCharsets.US_ASCII);',
        Command("backend", MAVEN + (
            "-pl", "modules/identity/cache-redis", "-am",
            "-Dtest=RedisPermissionGrantCacheTest#rejectsAPlatformSnapshotCopiedIntoTheMerchantNamespace",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/modules/identity/cache-redis/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.permission.RedisPermissionGrantCacheTest",
        "rejectsAPlatformSnapshotCopiedIntoTheMerchantNamespace",
    ),
    Mutation(
        "CLIENT_TENANT_SELECTION",
        "backend/modules/identity/backoffice-web/src/main/java/com/niv/payment/permission/backoffice/BackofficeAuthController.java",
        "record LoginRequest(@NotBlank @Size(max = 100) String username,\n                        @NotBlank @Size(max = 256) String password) { }",
        "record LoginRequest(@NotBlank @Size(max = 100) String username,\n                        @NotBlank @Size(max = 256) String password, Long tenantId) { }",
        Command("backend", MAVEN + (
            "-pl", "applications/admin-api,applications/merchant-admin-api,applications/agent-admin-api,tests/iam001-blackbox",
            "-am",
            "-Dit.test=ThreeBackofficeBoundaryIntegrationTest#tenantIdCannotSelectAWorkspace",
            "-Dfailsafe.failIfNoSpecifiedTests=false", "verify",
        )),
        "backend/tests/iam001-blackbox/target/failsafe-reports/TEST-*.xml",
        "com.niv.payment.iam001.ThreeBackofficeBoundaryIntegrationTest",
        "tenantIdCannotSelectAWorkspace",
    ),
    Mutation(
        "UNKNOWN_API_DEFAULT_DENY",
        "backend/applications/admin-api/src/main/java/com/niv/payment/adminapi/config/AdminApiPermissionPolicy.java",
        "        throw new AccessDeniedException();\n    }\n\n    private static boolean sessionOnly",
        "        return List.of();\n    }\n\n    private static boolean sessionOnly",
        Command("backend", MAVEN + (
            "-pl", "applications/admin-api", "-am",
            "-Dtest=AdminApiPermissionPolicyTest#deniesUnknownRoutesMethodsAndPrefixLookalikes",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        )),
        "backend/applications/admin-api/target/surefire-reports/TEST-*.xml",
        "com.niv.payment.adminapi.config.AdminApiPermissionPolicyTest",
        "deniesUnknownRoutesMethodsAndPrefixLookalikes",
    ),
)


PROCESS_COMMANDS = (
    Command("backend", MAVEN + ("clean", "verify")),
    Command("frontend/admin", ("pnpm", "install", "--offline", "--frozen-lockfile")),
    Command("frontend/admin", ("pnpm", "lint")),
    Command("frontend/admin", ("pnpm", "-F", "@vben/web-antdv-next", "typecheck")),
    Command("frontend/admin", ("pnpm", "test:unit")),
    Command("frontend/admin", ("pnpm", "test:production-safety")),
    Command("frontend/admin", ("pnpm", "-F", "@vben/web-antdv-next", "build:all")),
    Command("frontend/admin", ("node", "scripts/deploy/verify-three-artifacts.mjs")),
    Command(".", ("python3", "-B", "-I", "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py")),
    Command(".", ("python3", "-B", "-I", "scripts/check_project_skills.py")),
    Command(".", ("python3", "-B", "-I", "scripts/check-doc-decisions.py")),
)


def _load_artifact_contracts(repository: Path):
    path = repository.joinpath("scripts/check_modernization_artifacts.py")
    spec = importlib.util.spec_from_file_location("payment_modernization_artifacts", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _isolated_git_environment(inherited: dict[str, str]) -> dict[str, str]:
    forbidden = sorted(
        key for key in inherited
        if key in FORBIDDEN_GIT_ENVIRONMENT
        or key.startswith(("GIT_CONFIG_KEY_", "GIT_CONFIG_VALUE_"))
    )
    if forbidden:
        raise RuntimeError("Git environment overrides are not allowed")
    return {
        "PATH": inherited.get("PATH", os.defpath),
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


def _git_command(repository: Path, *args: str) -> tuple[str, ...]:
    resolved = repository.resolve()
    return (
        "git", "-C", str(resolved),
        "-c", "safe.directory=", "-c", f"safe.directory={resolved}",
        "-c", "core.fsmonitor=", "-c", "core.hooksPath=/dev/null",
        "-c", "core.commitGraph=false", "-c", "core.useReplaceRefs=false",
        "-c", "submodule.recurse=false", "--no-replace-objects", "--literal-pathspecs",
        *args,
    )


def _git(repository: Path, *args: str, text: bool = True) -> str | bytes:
    completed = subprocess.run(
        _git_command(repository, *args),
        cwd=repository.resolve(),
        env=_isolated_git_environment(dict(os.environ)),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() if text else "Git command failed")
    return completed.stdout


def _raw_object_id(object_type: str, content: bytes, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    digest.update(f"{object_type} {len(content)}\0".encode("ascii"))
    digest.update(content)
    return digest.hexdigest()


def _verify_raw_commit(repository: Path, target: str) -> None:
    algorithm = str(_git(repository, "rev-parse", "--show-object-format")).strip()
    if algorithm not in hashlib.algorithms_available:
        raise RuntimeError("unsupported Git object format")
    raw_commit = bytes(_git(repository, "cat-file", "commit", target, text=False))
    if _raw_object_id("commit", raw_commit, algorithm) != target:
        raise RuntimeError("target commit does not match its raw Git object")
    tree_header = next(
        (line for line in raw_commit.splitlines() if line.startswith(b"tree ")),
        None,
    )
    if tree_header is None:
        raise RuntimeError("target commit has no tree")
    tree = tree_header.removeprefix(b"tree ").decode("ascii")
    if FULL_SHA_PATTERN.fullmatch(tree) is None:
        raise RuntimeError("target commit has an invalid tree object ID")
    raw_tree = bytes(_git(repository, "cat-file", "tree", tree, text=False))
    if _raw_object_id("tree", raw_tree, algorithm) != tree:
        raise RuntimeError("target tree does not match its raw Git object")


def require_clean_exact_commit(repository: Path, requested: str | None) -> str:
    target = str(_git(repository, "rev-parse", "--verify", f"{requested or 'HEAD'}^{{commit}}")).strip()
    if FULL_SHA_PATTERN.fullmatch(target) is None:
        raise RuntimeError("target commit must resolve to a full SHA")
    _verify_raw_commit(repository, target)
    status = str(_git(repository, "status", "--porcelain", "--untracked-files=all"))
    if status:
        raise RuntimeError("IAM-001 Judge requires a clean exact target commit")
    return target


def _resolve_queue_output(repository: Path, requested: Path) -> Path:
    if requested.is_absolute():
        raise RuntimeError("queue output must be repository-relative")
    repository = repository.resolve()
    output = repository.joinpath(requested).resolve()
    try:
        output.relative_to(repository)
    except ValueError as exception:
        raise RuntimeError("queue output must be repository-relative") from exception
    return output


def _archive(repository: Path, target: str, destination: Path) -> None:
    archive = subprocess.Popen(
        _git_command(repository, "archive", "--format=tar", target),
        cwd=repository.resolve(),
        env=_isolated_git_environment(dict(os.environ)),
        stdout=subprocess.PIPE,
    )
    assert archive.stdout is not None
    extracted = subprocess.run(["tar", "-x", "-C", str(destination)], stdin=archive.stdout, check=False)
    archive.stdout.close()
    archive_result = archive.wait()
    if archive_result != 0 or extracted.returncode != 0:
        raise RuntimeError("could not materialize the immutable target archive")


def _reintroduces_client_workspace_selection(line: str) -> bool:
    actor_positions = [line.find(value) for value in CLIENT_WORKSPACE_ACTORS if value in line]
    selector_positions = [line.find(value) for value in CLIENT_WORKSPACE_SELECTORS if value in line]
    verb_positions = [line.find(value) for value in CLIENT_WORKSPACE_SELECTION_VERBS if value in line]
    if not actor_positions or not selector_positions or not verb_positions:
        return False
    selection_start = min(actor_positions + selector_positions + verb_positions)
    selection_end = max(actor_positions + selector_positions + verb_positions)
    is_denied = any(
        max(0, selection_start - 4) <= line.find(negation) <= selection_end
        for negation in CLIENT_WORKSPACE_NEGATIONS
        if negation in line
    )
    return not is_denied


def validate_mandatory_permission_context(snapshot: Path) -> None:
    context_path = snapshot.joinpath(MANDATORY_PERMISSION_CONTEXT)
    try:
        context = context_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exception:
        raise RuntimeError("mandatory permission context is unreadable") from exception
    missing = [snippet for snippet in REQUIRED_WORKSPACE_CONTEXT if snippet not in context]
    forbidden = [snippet for snippet in FORBIDDEN_WORKSPACE_CONTEXT if snippet in context]
    forbidden.extend(
        line
        for line in context.splitlines()
        if _reintroduces_client_workspace_selection(line)
    )
    if missing or forbidden:
        raise RuntimeError(
            "mandatory permission context conflicts with server-trusted workspace resolution"
        )


def _java_major_version(java: Path) -> int | None:
    if not java.is_file():
        return None
    completed = subprocess.run(
        [str(java), "-version"], capture_output=True, text=True, check=False
    )
    output = completed.stdout + completed.stderr
    match = re.search(r'version "(?:1\.)?(\d+)', output)
    return int(match.group(1)) if completed.returncode == 0 and match else None


def _runtime_environment() -> dict[str, str]:
    environment = dict(os.environ)
    candidates = []
    configured_home = environment.get("JAVA_HOME")
    if configured_home:
        candidates.append(Path(configured_home))
    brew = shutil.which("brew")
    if brew:
        discovered = subprocess.run(
            [brew, "--prefix", "openjdk@25"], capture_output=True, text=True, check=False
        )
        if discovered.returncode == 0:
            candidates.append(
                Path(discovered.stdout.strip(), "libexec", "openjdk.jdk", "Contents", "Home")
            )
    candidates.extend((
        Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"),
        Path("/usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"),
    ))
    java_home = next(
        (candidate for candidate in candidates if _java_major_version(candidate / "bin/java") == 25),
        None,
    )
    if java_home is None:
        raise RuntimeError("IAM-001 Judge requires Java 25")
    node_directory = Path.home().joinpath(".nvm/versions/node/v24.16.0/bin")
    path_entries = []
    environment["JAVA_HOME"] = str(java_home)
    environment["CI"] = "true"
    environment["COREPACK_ENABLE_DOWNLOAD_PROMPT"] = "0"
    path_entries.append(str(java_home / "bin"))
    if node_directory.is_dir():
        path_entries.append(str(node_directory))
    environment["PATH"] = os.pathsep.join(path_entries + [environment.get("PATH", "")])
    return environment


def _run(command: Command, snapshot: Path, environment: dict[str, str], log) -> int:
    log.write(("\n$ " + " ".join(command.argv) + "\n").encode())
    log.flush()
    completed = subprocess.run(
        list(command.argv), cwd=snapshot.joinpath(command.cwd), env=environment,
        stdout=log, stderr=subprocess.STDOUT, check=False,
    )
    return completed.returncode


def _failed_junit_report(
    snapshot: Path,
    pattern: str,
    test_class: str,
    test_name: str,
) -> bool:
    reports = sorted(snapshot.glob(pattern))
    if not reports:
        return False

    mapped_testcases = 0
    mapped_failures = 0
    for report in reports:
        try:
            root = ElementTree.parse(report).getroot()
        except (ElementTree.ParseError, OSError):
            return False
        elements = list(root.iter())
        root_tag = root.tag.rsplit("}", 1)[-1]
        if root_tag not in {"testsuite", "testsuites"}:
            return False
        tags = [element.tag.rsplit("}", 1)[-1] for element in elements]
        if "error" in tags or "skipped" in tags:
            return False
        suites = [
            element for element in elements
            if element.tag.rsplit("}", 1)[-1] in {"testsuite", "testsuites"}
        ]
        for suite in suites:
            for count_name in ("errors", "skipped"):
                count = suite.get(count_name)
                if count is not None:
                    try:
                        if int(count) != 0:
                            return False
                    except ValueError:
                        return False
            declared_failures = suite.get("failures")
            if declared_failures is not None:
                try:
                    expected_failures = int(declared_failures)
                except ValueError:
                    return False
                actual_failures = sum(
                    1
                    for element in suite.iter()
                    if element.tag.rsplit("}", 1)[-1] == "failure"
                )
                if expected_failures < 0 or expected_failures != actual_failures:
                    return False
        document_failures = tags.count("failure")
        for testcase in (
            element for element in elements
            if element.tag.rsplit("}", 1)[-1] == "testcase"
        ):
            is_mapped = (
                testcase.get("classname") == test_class
                and testcase.get("name") == test_name
            )
            children = [child.tag.rsplit("}", 1)[-1] for child in testcase]
            if "error" in children or "skipped" in children:
                return False
            failures = children.count("failure")
            if failures and (not is_mapped or failures != 1):
                return False
            if is_mapped:
                mapped_testcases += 1
                mapped_failures += failures
        if document_failures != 1:
            return False
    return mapped_testcases == 1 and mapped_failures == 1


def apply_mutation(snapshot: Path, mutation: Mutation) -> bytes:
    path = snapshot.joinpath(mutation.path)
    original = path.read_bytes()
    source = original.decode("utf-8")
    if source.count(mutation.before) != 1:
        raise RuntimeError(f"{mutation.mutation_id}: expected one exact production preimage")
    path.write_text(source.replace(mutation.before, mutation.after), encoding="utf-8")
    return original


def _clear_mutation_build_outputs(snapshot: Path) -> None:
    snapshot = snapshot.resolve()
    backend = snapshot.joinpath("backend")
    targets: list[Path] = []
    for parent, children, files in os.walk(backend, topdown=True, followlinks=False):
        if "target" in files:
            target = Path(parent, "target")
            if target.is_symlink():
                raise RuntimeError("mutation build output must not be a symbolic link")
            raise RuntimeError("mutation build output must be a directory")
        for child in tuple(children):
            if child == "target":
                targets.append(Path(parent, child))
                children.remove(child)
    for target in targets:
        if target.is_symlink():
            raise RuntimeError("mutation build output must not be a symbolic link")
        if not target.is_dir():
            raise RuntimeError("mutation build output must be a directory")
        try:
            target.resolve().relative_to(snapshot)
        except ValueError as exception:
            raise RuntimeError("mutation build output escaped the snapshot") from exception
        shutil.rmtree(target)


def run_mutations(snapshot: Path, environment: dict[str, str], log) -> list[str]:
    failures: list[str] = []
    for mutation in MUTATIONS:
        log.write((f"\n# mutation {mutation.mutation_id}\n").encode())
        log.flush()
        _clear_mutation_build_outputs(snapshot)
        path = snapshot.joinpath(mutation.path)
        original = apply_mutation(snapshot, mutation)
        try:
            for report in snapshot.glob(mutation.report_glob):
                report.unlink()
            exit_code = _run(mutation.command, snapshot, environment, log)
            if exit_code == 0:
                failures.append(f"{mutation.mutation_id}: mapped test passed with the defect present")
            elif not _failed_junit_report(
                snapshot,
                mutation.report_glob,
                mutation.test_class,
                mutation.test_name,
            ):
                failures.append(f"{mutation.mutation_id}: failed without the expected test failure report")
        finally:
            path.write_bytes(original)
    return failures


def deduplicate_queue_items(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    by_fingerprint: dict[str, dict[str, Any]] = {}
    for item in items:
        fingerprint = str(item["fingerprint"])
        previous = by_fingerprint.get(fingerprint)
        if previous is not None and previous != item:
            raise RuntimeError("conflicting queue items share one fingerprint")
        by_fingerprint[fingerprint] = item
    return [by_fingerprint[key] for key in sorted(by_fingerprint)]


def _git_blob(repository: Path, commit: str, path: str) -> bytes:
    return bytes(_git(repository, "show", f"{commit}:{path}", text=False))


def _path_is_owned(path: str, owned_paths: Sequence[str]) -> bool:
    return any(path == owned or path.startswith(f"{owned}/") for owned in owned_paths)


def _unowned_changed_paths(
    repository: Path,
    base: str,
    target: str,
    owned_paths: Sequence[str],
) -> list[str]:
    changed = bytes(_git(
        repository,
        "diff",
        "--name-only",
        "--no-renames",
        "-z",
        f"{base}..{target}",
        "--",
        text=False,
    ))
    paths = [entry.decode("utf-8") for entry in changed.split(b"\0") if entry]
    return sorted(path for path in paths if not _path_is_owned(path, owned_paths))


def _manifests(repository: Path, commit: str, artifacts) -> tuple[dict[str, Any], dict[str, Any]]:
    policy = json.loads(_git_blob(repository, commit, ".agents/payment-modernization-policy.json"))
    manifests = []
    for field, digest_name in (("rulebookPaths", "rulebookDigest"), ("judgePaths", "judgeDigest")):
        paths = policy[field]
        contents = {path: _git_blob(repository, commit, path) for path in paths}
        manifests.append({"paths": paths, digest_name: artifacts.content_bundle_digest(contents)})
    return manifests[0], manifests[1]


def _identity(repository: Path, target: str, artifacts):
    baseline_rulebook, baseline_judge = _manifests(repository, TARGET_BASE_SHA, artifacts)
    evaluated_rulebook, evaluated_judge = _manifests(repository, target, artifacts)
    identity_inputs = {
        "turnId": TURN_ID,
        "sliceId": SLICE_ID,
        "targetBaseSha": TARGET_BASE_SHA,
        "targetRepositoryId": "payment-web-platform",
        "path": "reimagine",
        "actors": ["platform operator", "merchant administrator", "agent administrator"],
        "inputs": ["username/password login", "trusted server account domain", "active membership and RoleGrant"],
        "outputs": ["three isolated backoffice sessions", "dynamic menus and permission codes"],
        "ruleIds": ["IAM-001"],
        "dependencies": [],
        "ownedPaths": list(OWNED_PATHS),
        "forbiddenChanges": ["payment business capabilities", "frontend/portal", "client-selected tenantId"],
        "entryCriteria": ["accepted ADR-0008", "immutable target base"],
        "exitCriteria": ["process boundary passes", "all declared semantic mutants are detected"],
        "judgeCommands": [PROCESS_CHECK_ID, MUTATION_CHECK_ID],
    }
    unowned_paths = _unowned_changed_paths(
        repository, TARGET_BASE_SHA, target, identity_inputs["ownedPaths"]
    )
    if unowned_paths:
        raise RuntimeError(
            "IAM-001 task identity does not own changed paths: "
            + ", ".join(unowned_paths)
        )
    task_key = artifacts.task_identity_key(
        turn_id=TURN_ID,
        slice_id=SLICE_ID,
        target_base_sha=TARGET_BASE_SHA,
        source_snapshots=(),
        rulebook_digest=baseline_rulebook["rulebookDigest"],
        judge_digest=baseline_judge["judgeDigest"],
        target_repository_id="payment-web-platform",
        modernization_path="reimagine",
        rulebook_manifest=baseline_rulebook,
        judge_manifest=baseline_judge,
        **{
            "actors": identity_inputs["actors"],
            "inputs": identity_inputs["inputs"],
            "outputs": identity_inputs["outputs"],
            "rule_ids": identity_inputs["ruleIds"],
            "dependencies": identity_inputs["dependencies"],
            "owned_paths": identity_inputs["ownedPaths"],
            "forbidden_changes": identity_inputs["forbiddenChanges"],
            "entry_criteria": identity_inputs["entryCriteria"],
            "exit_criteria": identity_inputs["exitCriteria"],
            "judge_commands": identity_inputs["judgeCommands"],
        },
    )
    evaluated_key = artifacts.evaluated_version_key(
        task_key, target, evaluated_rulebook["rulebookDigest"], evaluated_judge["judgeDigest"]
    )
    return (
        identity_inputs, task_key, evaluated_key,
        baseline_rulebook, baseline_judge, evaluated_rulebook, evaluated_judge,
    )


def _queue_item(check_id: str, evaluated_key: str, reasons: Sequence[str], log_path: str) -> dict[str, Any]:
    fingerprint = "sha256:" + hashlib.sha256(f"{SLICE_ID}:{check_id}".encode()).hexdigest()
    return {
        "queueItemSchemaVersion": 2,
        "fingerprint": fingerprint,
        "sliceId": SLICE_ID,
        "evaluatedVersionKey": evaluated_key,
        "failureSource": {"type": "judge", "checkId": check_id},
        "severity": "BLOCKER",
        "trigger": "run the registered immutable IAM-001 check",
        "controlFlow": ["archive exact target SHA", "run registered check", *reasons],
        "evidence": [log_path],
        "impact": "the three-backoffice access and session boundary lacks required executable evidence",
        "verification": f"rerun registered check {check_id} against the same exact target SHA",
        "status": "open",
        "resolution": "unresolved",
        "failedReviewRounds": 0,
        "initialStateHistory": [{
            "status": "open", "evaluatedVersionKey": evaluated_key, "failedReviewRounds": 0,
        }],
        "dependencies": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--queue-output", type=Path, required=True)
    parser.add_argument("--check", choices=("process", "mutations"), required=True)
    parser.add_argument("--target-commit")
    args = parser.parse_args()
    repository = args.repository_root.resolve()
    target = require_clean_exact_commit(repository, args.target_commit)
    artifacts = _load_artifact_contracts(repository)
    (
        identity_inputs, task_key, evaluated_key,
        baseline_rulebook, baseline_judge, rulebook, judge,
    ) = _identity(
        repository, target, artifacts
    )
    check_id = PROCESS_CHECK_ID if args.check == "process" else MUTATION_CHECK_ID
    output = _resolve_queue_output(repository, args.queue_output)
    output.parent.mkdir(parents=True, exist_ok=True)
    log_path = output.with_suffix(".log")
    failures: list[str] = []
    environment = _runtime_environment()

    with tempfile.TemporaryDirectory(prefix="iam001-judge-") as directory:
        snapshot = Path(directory, "snapshot")
        snapshot.mkdir()
        _archive(repository, target, snapshot)
        with log_path.open("wb") as log:
            log.write(b"\n$ validate mandatory permission workspace context\n")
            try:
                validate_mandatory_permission_context(snapshot)
            except RuntimeError as exception:
                failures.append(str(exception))
            if not failures:
                if args.check == "process":
                    for command in PROCESS_COMMANDS:
                        exit_code = _run(command, snapshot, environment, log)
                        if exit_code != 0:
                            failures.append(
                                f"command exited with {exit_code}: {' '.join(command.argv)}"
                            )
                            break
                else:
                    failures.extend(run_mutations(snapshot, environment, log))

    queue_items = deduplicate_queue_items(
        [_queue_item(check_id, evaluated_key, failures, str(log_path.relative_to(repository)))]
        if failures else []
    )
    for item in queue_items:
        validation = artifacts.validate_queue_item(item)
        if validation:
            raise RuntimeError("invalid candidate queue item: " + "; ".join(validation))
    payload = {
        "schemaVersion": 2,
        "authority": "UNSIGNED_LOCAL_DIAGNOSTIC",
        "formalStatus": "NEEDS_TRUSTED_REVIEW",
        "formalClosureEligible": False,
        "technicalStatus": "PASS" if not failures else "FAIL",
        "sliceId": SLICE_ID,
        "checkId": check_id,
        "targetCommitSha": target,
        "taskIdentityInputs": identity_inputs,
        "taskIdentityKey": task_key,
        "baselineRulebookManifest": baseline_rulebook,
        "baselineJudgeManifest": baseline_judge,
        "evaluatedRulebookManifest": rulebook,
        "evaluatedJudgeManifest": judge,
        "evaluatedVersionKey": evaluated_key,
        "checkExecutions": [{
            "checkId": check_id,
            "targetCommitSha": target,
            "exitCode": 0 if not failures else 1,
            "logPath": str(log_path.relative_to(repository)),
        }],
        "queueItems": queue_items,
        "queueDigest": artifacts.queue_items_digest(queue_items),
        "limitations": [
            "This is an unsigned local diagnostic and is not a canonical Judge bundle.",
            "The trusted policy has no configured trusted reviewer keys.",
        ],
    }
    output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
