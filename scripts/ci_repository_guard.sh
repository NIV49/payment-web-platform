#!/usr/bin/env bash

# Source this file from a shell started with an explicit `env -i`. The captured
# values remain in the parent shell while repository-controlled commands run.

CI_SAFE_PATH="${CI_SAFE_PATH:-${PATH:-/usr/local/bin:/usr/bin:/bin}}"
CI_SAFE_HOME="${CI_SAFE_HOME:-${HOME:-/tmp/documentation-ci-home}}"
CI_TRUSTED_NATIVE_PATH="${CI_TRUSTED_NATIVE_PATH:-/usr/bin:/bin}"
CI_PYTHON_DEPENDENCY_ROOT="${CI_PYTHON_DEPENDENCY_ROOT:-/tmp/documentation-python-dependencies}"

ci_guard_fail() {
  printf 'CI repository guard failed: %s\n' "$1" >&2
  return 1
}

ci_validate_safe_path() {
  local directory
  local -a directories

  if [[ -z "$CI_SAFE_PATH" || "$CI_SAFE_PATH" == :* ||
    "$CI_SAFE_PATH" == *: || "$CI_SAFE_PATH" == *::* ]]; then
    ci_guard_fail "safe PATH must contain only non-empty absolute directories"
    return 1
  fi
  IFS=: read -r -a directories <<<"$CI_SAFE_PATH"
  for directory in "${directories[@]}"; do
    if [[ "$directory" != /* || ! -d "$directory" ]]; then
      ci_guard_fail "safe PATH entry is not an absolute directory: $directory"
      return 1
    fi
  done
}

ci_validate_trusted_native_path() {
  local directory
  local -a directories

  if [[ -z "$CI_TRUSTED_NATIVE_PATH" ||
    "$CI_TRUSTED_NATIVE_PATH" == :* ||
    "$CI_TRUSTED_NATIVE_PATH" == *: ||
    "$CI_TRUSTED_NATIVE_PATH" == *::* ]]; then
    ci_guard_fail "trusted native PATH must contain only non-empty absolute directories"
    return 1
  fi
  IFS=: read -r -a directories <<<"$CI_TRUSTED_NATIVE_PATH"
  for directory in "${directories[@]}"; do
    if [[ "$directory" != /* || ! -d "$directory" ]]; then
      ci_guard_fail "trusted native PATH entry is not an absolute directory"
      return 1
    fi
  done
}

ci_validate_python_dependency_root() {
  local parent

  if [[ "$CI_PYTHON_DEPENDENCY_ROOT" != /* ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == */ ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == *//* ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == */./* ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == */. ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == */../* ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == */.. ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == *$'\n'* ||
    "$CI_PYTHON_DEPENDENCY_ROOT" == *$'\r'* ]]; then
    ci_guard_fail "Python dependency root must be an absolute canonical path"
    return 1
  fi
  parent="${CI_PYTHON_DEPENDENCY_ROOT%/*}"
  [[ -n "$parent" ]] || parent=/
  if ! (
    builtin cd -P -- "$parent" 2>/dev/null &&
      builtin pwd -P >/dev/null
  ); then
    ci_guard_fail "Python dependency root parent is unavailable"
    return 1
  fi
  if [[ -L "$CI_PYTHON_DEPENDENCY_ROOT" ]] ||
    [[ -e "$CI_PYTHON_DEPENDENCY_ROOT" &&
      ! -d "$CI_PYTHON_DEPENDENCY_ROOT" ]]; then
    ci_guard_fail "Python dependency root must be a real directory"
    return 1
  fi
}

ci_python_dependency_canonical_root() {
  local base="${CI_PYTHON_DEPENDENCY_ROOT##*/}"
  local parent="${CI_PYTHON_DEPENDENCY_ROOT%/*}"
  local physical_parent

  [[ -n "$parent" ]] || parent=/
  if ! physical_parent="$(
    builtin cd -P -- "$parent" 2>/dev/null &&
      builtin pwd -P
  )"; then
    ci_guard_fail "Python dependency root parent is unavailable"
    return 1
  fi
  printf '%s/%s\n' "$physical_parent" "$base"
}

ci_resolve_tool_path() {
  local executable="$1"
  local resolved

  if ! resolved="$(PATH="$CI_SAFE_PATH" type -P -- "$executable")"; then
    ci_guard_fail "critical executable is unavailable: $executable"
    return 1
  fi
  if [[ "$resolved" != /* || "$resolved" == *$'\n'* ||
    ! -f "$resolved" || ! -x "$resolved" ]]; then
    ci_guard_fail "critical executable is unavailable: $executable"
    return 1
  fi
  printf '%s\n' "$resolved"
}

ci_resolve_native_tool_path() {
  local executable="$1"
  local resolved

  if ! resolved="$(
    PATH="$CI_TRUSTED_NATIVE_PATH" type -P -- "$executable"
  )"; then
    ci_guard_fail "trusted native executable is unavailable: $executable"
    return 1
  fi
  if [[ "$resolved" != /* || "$resolved" == *$'\n'* ||
    ! -f "$resolved" || ! -x "$resolved" ]]; then
    ci_guard_fail "trusted native executable is unavailable: $executable"
    return 1
  fi
  printf '%s\n' "$resolved"
}

ci_native_stat_identity() {
  local path="$1"

  case "$CI_NATIVE_STAT_STYLE" in
    gnu)
      "$CI_TOOL_STAT" -c \
        '%d:%i:%f:%u:%g:%s:%Y:%Z:%y:%z' \
        -- "$path"
      ;;
    bsd)
      "$CI_TOOL_STAT" -f \
        '%d:%i:%p:%u:%g:%z:%m:%c:%Sm:%Sc' \
        "$path"
      ;;
    *)
      ci_guard_fail "trusted native stat style is unavailable"
      return 1
      ;;
  esac
}

ci_native_sha256_file() {
  local digest
  local output
  local path="$1"

  case "$CI_NATIVE_SHA256_STYLE" in
    sha256sum)
      output="$("$CI_TOOL_SHA256" -- "$path")" || return 1
      ;;
    shasum)
      output="$("$CI_TOOL_SHA256" -a 256 -- "$path")" || return 1
      ;;
    *)
      ci_guard_fail "trusted native SHA-256 style is unavailable"
      return 1
      ;;
  esac
  digest="${output%% *}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    ci_guard_fail "trusted native SHA-256 output is invalid"
    return 1
  fi
  printf '%s\n' "$digest"
}

ci_native_physical_path() {
  local base
  local parent
  local path="$1"
  local physical_parent

  if [[ "$path" != /* || "$path" == */ ||
    "$path" == *$'\n'* || "$path" == *$'\r'* ]]; then
    ci_guard_fail "native identity path is invalid"
    return 1
  fi
  parent="${path%/*}"
  base="${path##*/}"
  [[ -n "$parent" ]] || parent=/
  if ! physical_parent="$(
    builtin cd -P -- "$parent" 2>/dev/null &&
      builtin pwd -P
  )"; then
    ci_guard_fail "native identity path parent is unavailable"
    return 1
  fi
  printf '%s/%s\n' "$physical_parent" "$base"
}

ci_native_file_identity() {
  local canonical
  local content_digest
  local current="$2"
  local depth
  local link_target
  local logical_name="$1"
  local metadata
  local mode="$3"
  local original="$2"
  local visited=$'\n'

  printf 'name=%s\noriginal=%s\n' "$logical_name" "$original"
  for ((depth = 0; depth < 41; depth++)); do
    canonical="$(ci_native_physical_path "$current")" || return 1
    if [[ "$visited" == *$'\n'"$canonical"$'\n'* ]]; then
      ci_guard_fail "native identity symbolic-link cycle detected"
      return 1
    fi
    visited+="$canonical"$'\n'
    metadata="$(ci_native_stat_identity "$canonical")" || return 1
    printf 'path=%s\nmetadata=%s\n' "$canonical" "$metadata"
    if [[ -L "$canonical" ]]; then
      link_target="$("$CI_TOOL_READLINK" "$canonical")" || return 1
      if [[ -z "$link_target" ||
        "$link_target" == *$'\n'* ||
        "$link_target" == *$'\r'* ]]; then
        ci_guard_fail "native identity symbolic-link target is invalid"
        return 1
      fi
      printf 'link=%s\n' "$link_target"
      if [[ "$link_target" == /* ]]; then
        current="$link_target"
      else
        current="${canonical%/*}/$link_target"
      fi
      continue
    fi
    if [[ ! -f "$canonical" ]] ||
      [[ "$mode" == executable && ! -x "$canonical" ]]; then
      ci_guard_fail "native identity target is not an allowed regular file"
      return 1
    fi
    content_digest="$(ci_native_sha256_file "$canonical")" || return 1
    printf 'canonical=%s\nsha256=%s\n' "$canonical" "$content_digest"
    return
  done
  ci_guard_fail "native identity symbolic-link chain is too deep"
  return 1
}

ci_native_python_identity() {
  if [[ -z "$CI_TOOL_STAT" ||
    -z "$CI_TOOL_READLINK" ||
    -z "$CI_TOOL_SHA256" ]]; then
    ci_guard_fail "trusted native identity primitives are unavailable"
    return 1
  fi
  ci_native_file_identity python3 "$1" executable
}

ci_python_runtime_boundary() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import json
import os
import sys

root = os.path.realpath(sys.argv[1])
sys.path.insert(0, root)
paths = [os.path.realpath(path) if path else path for path in sys.path]
flags = {
    "dont_write_bytecode": sys.flags.dont_write_bytecode,
    "ignore_environment": sys.flags.ignore_environment,
    "isolated": sys.flags.isolated,
    "no_site": sys.flags.no_site,
    "no_user_site": sys.flags.no_user_site,
    "safe_path": sys.flags.safe_path,
}
expected = {
    "dont_write_bytecode": 1,
    "ignore_environment": 1,
    "isolated": 1,
    "no_site": 1,
    "no_user_site": 1,
    "safe_path": True,
}
if flags != expected:
    raise SystemExit("Python isolation flags do not match the required boundary")
if not paths or paths[0] != root or "" in paths:
    raise SystemExit("Python dependency root is not the only explicit import root")
for path in paths[1:]:
    leaf = os.path.basename(path.rstrip(os.sep))
    if leaf in {"site-packages", "dist-packages"}:
        raise SystemExit("system or user site-packages entered the import boundary")
report = {
    "dependencyRoot": root,
    "flags": flags,
    "siteImported": "site" in sys.modules,
    "sitecustomizeImported": "sitecustomize" in sys.modules,
    "sysPath": paths,
    "usercustomizeImported": "usercustomize" in sys.modules,
}
if any(
    report[name]
    for name in (
        "siteImported",
        "sitecustomizeImported",
        "usercustomizeImported",
    )
):
    raise SystemExit("Python startup hook entered the import boundary")
print(json.dumps(report, sort_keys=True, separators=(",", ":")))
' "$CI_PYTHON_DEPENDENCY_ROOT"
}

ci_verify_python_runtime_immutability() {
  case "${CI_REQUIRE_IMMUTABLE_PYTHON_RUNTIME:-0}" in
    0)
      return
      ;;
    1) ;;
    *)
      ci_guard_fail "immutable Python runtime requirement is invalid"
      return 1
      ;;
  esac

  if ! "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import errno
import os
import sys

version = f"python{sys.version_info.major}.{sys.version_info.minor}"
base_prefix = os.path.realpath(sys.base_prefix)
runtime_root = os.path.join(base_prefix, "lib", version)
stdlib_probe = os.path.join(runtime_root, "tarfile.py")
site_root = os.path.join(runtime_root, "site-packages")

if not os.path.isfile(stdlib_probe):
    raise SystemExit("pinned Python stdlib probe is unavailable")
if not os.path.isdir(site_root):
    raise SystemExit("pinned Python system site root is unavailable")

denied = {errno.EACCES, errno.EPERM, errno.EROFS}
probe_directories = {}
for import_path in sys.path:
    if not import_path or not os.path.isabs(import_path):
        raise SystemExit("pinned Python import path is not absolute")
    resolved = os.path.realpath(import_path)
    if os.path.isdir(resolved):
        directory = resolved
    elif os.path.exists(resolved):
        if not os.path.isfile(resolved):
            raise SystemExit("pinned Python import path has an unsupported type")
        directory = os.path.dirname(resolved)
    else:
        directory = os.path.dirname(resolved)
        while not os.path.exists(directory):
            parent = os.path.dirname(directory)
            if parent == directory:
                raise SystemExit("pinned Python import path parent is unavailable")
            directory = parent
        directory = os.path.realpath(directory)
        if not os.path.isdir(directory):
            raise SystemExit("pinned Python import path parent is not a directory")
    try:
        contained = os.path.commonpath((base_prefix, directory)) == base_prefix
    except ValueError:
        contained = False
    if not contained:
        raise SystemExit("pinned Python import path escapes the runtime prefix")
    probe_directories[directory] = "pinned Python import root"
probe_directories[os.path.realpath(site_root)] = "pinned Python system site"

descriptor = None
try:
    descriptor = os.open(
        stdlib_probe,
        os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0),
    )
except OSError as error:
    if error.errno not in denied:
        raise
else:
    raise SystemExit("pinned Python stdlib is writable")
finally:
    if descriptor is not None:
        os.close(descriptor)

for index, (path, label) in enumerate(sorted(probe_directories.items())):
    directory = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    probe_name = f".ci-runtime-write-probe-{os.getpid()}-{index}"
    created = False
    try:
        try:
            os.mkdir(probe_name, mode=0o700, dir_fd=directory)
            created = True
        except OSError as error:
            if error.errno not in denied:
                raise
        if created:
            raise SystemExit(f"{label} is writable")
    finally:
        if created:
            os.rmdir(probe_name, dir_fd=directory)
        os.close(directory)
'; then
    ci_guard_fail "Python runtime import roots are writable"
    return 1
  fi
}

ci_toolchain_fingerprint() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import hashlib
import os
import stat
import sys

arguments = sys.argv[1:]
if len(arguments) % 2:
    raise SystemExit("tool fingerprint arguments must be name/path pairs")

digest = hashlib.sha256()


def add(value):
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


for offset in range(0, len(arguments), 2):
    name = os.fsencode(arguments[offset])
    original = os.fsencode(arguments[offset + 1])
    add(name)
    add(original)
    current = original
    visited = set()
    for _ in range(41):
        normalized = os.path.normpath(current)
        if normalized in visited:
            raise SystemExit(
                f"critical executable has a symbolic-link cycle: {arguments[offset]}"
            )
        visited.add(normalized)
        metadata = os.lstat(normalized)
        add(normalized)
        for value in (
            metadata.st_mode,
            metadata.st_uid,
            metadata.st_gid,
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_size,
            metadata.st_mtime_ns,
        ):
            digest.update(value.to_bytes(16, "big", signed=False))
        if stat.S_ISLNK(metadata.st_mode):
            target = os.readlink(normalized)
            target_bytes = os.fsencode(target)
            add(target_bytes)
            current = (
                target_bytes
                if os.path.isabs(target_bytes)
                else os.path.join(os.path.dirname(normalized), target_bytes)
            )
            continue
        if not stat.S_ISREG(metadata.st_mode) or not metadata.st_mode & 0o111:
            raise SystemExit(
                f"critical executable is not an executable regular file: "
                f"{arguments[offset]}"
            )
        descriptor = os.open(
            normalized,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            opened = os.fstat(descriptor)
            if (
                opened.st_dev,
                opened.st_ino,
                opened.st_mode,
                opened.st_size,
                opened.st_mtime_ns,
            ) != (
                metadata.st_dev,
                metadata.st_ino,
                metadata.st_mode,
                metadata.st_size,
                metadata.st_mtime_ns,
            ):
                raise SystemExit(
                    f"critical executable changed while opening: {arguments[offset]}"
                )
            while True:
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                add(chunk)
        finally:
            os.close(descriptor)
        break
    else:
        raise SystemExit(
            f"critical executable symbolic-link chain is too deep: "
            f"{arguments[offset]}"
        )

print(digest.hexdigest())
' \
    env "$CI_TOOL_ENV" \
    git "$CI_TOOL_GIT" \
    python3 "$CI_TOOL_PYTHON3" \
    find "$CI_TOOL_FIND" \
    grep "$CI_TOOL_GREP" \
    tar "$CI_TOOL_TAR" \
    mktemp "$CI_TOOL_MKTEMP" \
    mkdir "$CI_TOOL_MKDIR" \
    rm "$CI_TOOL_RM" \
    stat "$CI_TOOL_STAT" \
    readlink "$CI_TOOL_READLINK" \
    sha256 "$CI_TOOL_SHA256"
}

ci_capture_toolchain() {
  local fingerprint
  local native_identity
  local runtime_boundary
  local sha256_path
  local dependency_canonical_root

  ci_validate_safe_path || return 1
  ci_validate_trusted_native_path || return 1
  ci_validate_python_dependency_root || return 1
  CI_TOOL_STAT="$(ci_resolve_native_tool_path stat)" || return 1
  CI_TOOL_READLINK="$(ci_resolve_native_tool_path readlink)" || return 1
  if sha256_path="$(
    PATH="$CI_TRUSTED_NATIVE_PATH" type -P -- sha256sum 2>/dev/null
  )" && [[ "$sha256_path" == /* && -f "$sha256_path" &&
    -x "$sha256_path" ]]; then
    CI_TOOL_SHA256="$sha256_path"
    CI_NATIVE_SHA256_STYLE=sha256sum
  else
    CI_TOOL_SHA256="$(ci_resolve_native_tool_path shasum)" || return 1
    CI_NATIVE_SHA256_STYLE=shasum
  fi
  if "$CI_TOOL_STAT" -c '%d' -- "$CI_TOOL_STAT" >/dev/null 2>&1; then
    CI_NATIVE_STAT_STYLE=gnu
  elif "$CI_TOOL_STAT" -f '%d' "$CI_TOOL_STAT" >/dev/null 2>&1; then
    CI_NATIVE_STAT_STYLE=bsd
  else
    ci_guard_fail "trusted native stat implementation is unsupported"
    return 1
  fi
  dependency_canonical_root="$(ci_python_dependency_canonical_root)" ||
    return 1
  CI_EXPECTED_PYTHON_DEPENDENCY_CANONICAL_ROOT="$dependency_canonical_root"
  CI_TOOL_ENV="$(ci_resolve_tool_path env)" || return 1
  CI_TOOL_GIT="$(ci_resolve_tool_path git)" || return 1
  CI_TOOL_PYTHON3="$(ci_resolve_tool_path python3)" || return 1
  CI_TOOL_FIND="$(ci_resolve_tool_path find)" || return 1
  CI_TOOL_GREP="$(ci_resolve_tool_path grep)" || return 1
  CI_TOOL_TAR="$(ci_resolve_tool_path tar)" || return 1
  CI_TOOL_MKTEMP="$(ci_resolve_tool_path mktemp)" || return 1
  CI_TOOL_MKDIR="$(ci_resolve_tool_path mkdir)" || return 1
  CI_TOOL_RM="$(ci_resolve_tool_path rm)" || return 1
  if ! native_identity="$(ci_native_python_identity "$CI_TOOL_PYTHON3")"; then
    ci_guard_fail "Python native identity capture failed"
    return 1
  fi
  CI_EXPECTED_PYTHON_NATIVE_IDENTITY="$native_identity"
  if ! fingerprint="$(ci_toolchain_fingerprint)"; then
    ci_guard_fail "critical executable identity capture failed"
    return 1
  fi
  CI_EXPECTED_TOOLCHAIN_FINGERPRINT="$fingerprint"
  if ! runtime_boundary="$(ci_python_runtime_boundary)"; then
    ci_guard_fail "Python runtime boundary capture failed"
    return 1
  fi
  CI_EXPECTED_PYTHON_RUNTIME_BOUNDARY="$runtime_boundary"
}

ci_verify_toolchain() {
  local actual
  local executable
  local expected
  local fingerprint
  local native_identity
  local runtime_boundary
  local dependency_canonical_root

  ci_validate_safe_path || return 1
  ci_validate_trusted_native_path || return 1
  ci_validate_python_dependency_root || return 1
  dependency_canonical_root="$(ci_python_dependency_canonical_root)" ||
    return 1
  if [[ "$dependency_canonical_root" != "$CI_EXPECTED_PYTHON_DEPENDENCY_CANONICAL_ROOT" ]]; then
    ci_guard_fail "Python dependency root canonical path changed"
    return 1
  fi
  actual="$(ci_resolve_native_tool_path stat)" || return 1
  if [[ "$actual" != "$CI_TOOL_STAT" ]]; then
    ci_guard_fail "trusted native executable identity changed: stat"
    return 1
  fi
  actual="$(ci_resolve_native_tool_path readlink)" || return 1
  if [[ "$actual" != "$CI_TOOL_READLINK" ]]; then
    ci_guard_fail "trusted native executable identity changed: readlink"
    return 1
  fi
  if [[ "$CI_NATIVE_SHA256_STYLE" == sha256sum ]]; then
    actual="$(ci_resolve_native_tool_path sha256sum)" || return 1
  else
    actual="$(ci_resolve_native_tool_path shasum)" || return 1
  fi
  if [[ "$actual" != "$CI_TOOL_SHA256" ]]; then
    ci_guard_fail "trusted native executable identity changed: SHA-256"
    return 1
  fi
  actual="$(ci_resolve_tool_path python3)" || return 1
  if [[ "$actual" != "$CI_TOOL_PYTHON3" ]]; then
    ci_guard_fail "critical executable identity changed: python3"
    return 1
  fi
  if ! native_identity="$(ci_native_python_identity "$CI_TOOL_PYTHON3")"; then
    ci_guard_fail "Python native identity verification failed"
    return 1
  fi
  if [[ "$native_identity" != "$CI_EXPECTED_PYTHON_NATIVE_IDENTITY" ]]; then
    ci_guard_fail "Python path, content, or metadata changed"
    return 1
  fi

  while read -r executable expected; do
    actual="$(ci_resolve_tool_path "$executable")" || return 1
    if [[ "$actual" != "$expected" ]]; then
      ci_guard_fail "critical executable identity changed: $executable"
      return 1
    fi
  done <<EOF
env $CI_TOOL_ENV
git $CI_TOOL_GIT
python3 $CI_TOOL_PYTHON3
find $CI_TOOL_FIND
grep $CI_TOOL_GREP
tar $CI_TOOL_TAR
mktemp $CI_TOOL_MKTEMP
mkdir $CI_TOOL_MKDIR
rm $CI_TOOL_RM
EOF
  if ! fingerprint="$(ci_toolchain_fingerprint)"; then
    ci_guard_fail "critical executable identity verification failed"
    return 1
  fi
  if [[ "$fingerprint" != "$CI_EXPECTED_TOOLCHAIN_FINGERPRINT" ]]; then
    ci_guard_fail "critical executable content or metadata changed"
    return 1
  fi
  if ! runtime_boundary="$(ci_python_runtime_boundary)"; then
    ci_guard_fail "Python runtime boundary verification failed"
    return 1
  fi
  if [[ "$runtime_boundary" != "$CI_EXPECTED_PYTHON_RUNTIME_BOUNDARY" ]]; then
    ci_guard_fail "Python runtime or import boundary changed"
    return 1
  fi
  ci_verify_python_runtime_immutability || return 1
}

ci_python_dependency_fingerprint() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import hashlib
import os
import stat
import sys

root = os.fsencode(sys.argv[1])
digest = hashlib.sha256()


def add(value):
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


def add_integer(value):
    digest.update(value.to_bytes(16, "big", signed=False))


def visit(path, relative):
    metadata = os.lstat(path)
    add(relative)
    for value in (
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    ):
        add_integer(value)
    if stat.S_ISLNK(metadata.st_mode):
        raise SystemExit("Python dependency root contains a symbolic link")
    if stat.S_ISDIR(metadata.st_mode):
        entries = sorted(os.scandir(path), key=lambda entry: entry.name)
        for entry in entries:
            name = os.fsencode(entry.name)
            child_relative = name if not relative else relative + b"/" + name
            visit(os.path.join(path, name), child_relative)
        return
    if not stat.S_ISREG(metadata.st_mode):
        raise SystemExit("Python dependency root contains an unsupported file")
    basename = os.path.basename(path).lower()
    if basename.endswith(b".pth") or basename in {
        b"sitecustomize.py",
        b"usercustomize.py",
    }:
        raise SystemExit("Python dependency root contains a startup hook")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        identity = (
            opened.st_dev,
            opened.st_ino,
            opened.st_mode,
            opened.st_size,
            opened.st_mtime_ns,
            opened.st_ctime_ns,
        )
        if not stat.S_ISREG(opened.st_mode) or identity != (
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_mode,
            metadata.st_size,
            metadata.st_mtime_ns,
            metadata.st_ctime_ns,
        ):
            raise SystemExit("Python dependency changed while opening")
        content = hashlib.sha256()
        size = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            content.update(chunk)
        final = os.fstat(descriptor)
        if size != opened.st_size or identity != (
            final.st_dev,
            final.st_ino,
            final.st_mode,
            final.st_size,
            final.st_mtime_ns,
            final.st_ctime_ns,
        ):
            raise SystemExit("Python dependency changed while reading")
    finally:
        os.close(descriptor)
    add_integer(size)
    add(content.digest())


root_metadata = os.lstat(root)
if stat.S_ISLNK(root_metadata.st_mode) or not stat.S_ISDIR(root_metadata.st_mode):
    raise SystemExit("Python dependency root is not a real directory")
add(os.path.realpath(root))
visit(root, b"")
print(digest.hexdigest())
' "$CI_PYTHON_DEPENDENCY_ROOT"
}

ci_capture_python_dependency_state() {
  local fingerprint

  if declare -p CI_EXPECTED_PYTHON_DEPENDENCY_FINGERPRINT \
    >/dev/null 2>&1; then
    ci_verify_python_dependency_state
    return
  fi
  ci_verify_toolchain || return 1
  if [[ ! -d "$CI_PYTHON_DEPENDENCY_ROOT" ||
    -L "$CI_PYTHON_DEPENDENCY_ROOT" ]]; then
    ci_guard_fail "Python dependency root is unavailable"
    return 1
  fi
  if [[ -n "${CI_EXPECTED_WORKSPACE:-}" ]]; then
    if [[ "$CI_EXPECTED_PYTHON_DEPENDENCY_CANONICAL_ROOT" == "$CI_EXPECTED_WORKSPACE" ]] ||
      [[ "$CI_EXPECTED_PYTHON_DEPENDENCY_CANONICAL_ROOT" == "$CI_EXPECTED_WORKSPACE"/* ]]; then
      ci_guard_fail "Python dependency root must be outside the repository"
      return 1
    fi
  fi
  if ! fingerprint="$(ci_python_dependency_fingerprint)"; then
    ci_guard_fail "Python dependency root capture failed"
    return 1
  fi
  CI_EXPECTED_PYTHON_DEPENDENCY_FINGERPRINT="$fingerprint"
  readonly CI_EXPECTED_PYTHON_DEPENDENCY_FINGERPRINT
}

ci_verify_python_dependency_state() {
  local fingerprint

  if ! declare -p CI_EXPECTED_PYTHON_DEPENDENCY_FINGERPRINT \
    >/dev/null 2>&1; then
    ci_guard_fail "Python dependency root is not sealed"
    return 1
  fi
  if ! fingerprint="$(ci_python_dependency_fingerprint)"; then
    ci_guard_fail "Python dependency root verification failed"
    return 1
  fi
  if [[ "$fingerprint" != "$CI_EXPECTED_PYTHON_DEPENDENCY_FINGERPRINT" ]]; then
    ci_guard_fail "Python dependency root content or metadata changed"
    return 1
  fi
}

ci_verify_python_execution_environment() {
  ci_verify_toolchain || return 1
  ci_verify_python_dependency_state || return 1
}

ci_prepare_python_dependencies() {
  local lock_file

  if (( $# != 2 )) || [[ "$1" != --require-hashes ]]; then
    ci_guard_fail "dependency preparation requires the locked hash contract"
    return 1
  fi
  lock_file="$2"
  ci_verify_toolchain || return 1
  if [[ "$lock_file" != "$CI_EXPECTED_WORKSPACE"/* ||
    ! -f "$lock_file" || -L "$lock_file" ]]; then
    ci_guard_fail "dependency lock file is outside the captured repository"
    return 1
  fi
  if [[ -e "$CI_PYTHON_DEPENDENCY_ROOT" ||
    -L "$CI_PYTHON_DEPENDENCY_ROOT" ]]; then
    ci_guard_fail "Python dependency root must not pre-exist preparation"
    return 1
  fi
  if ! "$CI_TOOL_MKDIR" -m 0700 -- "$CI_PYTHON_DEPENDENCY_ROOT"; then
    ci_guard_fail "Python dependency root could not be created"
    return 1
  fi
  if ! "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    PIP_CONFIG_FILE=/dev/null \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    "$CI_TOOL_PYTHON3" -B -I -m pip install \
      --disable-pip-version-check \
      --require-hashes \
      --no-deps \
      --only-binary=:all: \
      --no-compile \
      --target "$CI_PYTHON_DEPENDENCY_ROOT" \
      -r "$lock_file"; then
    ci_guard_fail "locked Python dependency preparation failed"
    return 1
  fi
  ci_verify_toolchain || return 1
  ci_capture_python_dependency_state || return 1
}

ci_python() {
  if (( $# == 0 )); then
    ci_guard_fail "controlled Python requires a script or module"
    return 1
  fi
  ci_verify_python_execution_environment || return 1
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import os
import runpy
import stat
import sys

root = os.path.realpath(sys.argv[1])
arguments = sys.argv[2:]
expected_flags = (1, 1, 1, 1, 1, True)
actual_flags = (
    sys.flags.dont_write_bytecode,
    sys.flags.ignore_environment,
    sys.flags.isolated,
    sys.flags.no_site,
    sys.flags.no_user_site,
    sys.flags.safe_path,
)
if actual_flags != expected_flags:
    raise SystemExit("controlled Python isolation flags changed")
sys.path.insert(0, root)
if not sys.path or os.path.realpath(sys.path[0]) != root or "" in sys.path:
    raise SystemExit("controlled Python import root changed")
for path in sys.path[1:]:
    leaf = os.path.basename(os.path.realpath(path).rstrip(os.sep))
    if leaf in {"site-packages", "dist-packages"}:
        raise SystemExit("controlled Python loaded an implicit site directory")
if any(
    name in sys.modules
    for name in ("site", "sitecustomize", "usercustomize")
):
    raise SystemExit("controlled Python loaded a startup hook")
if not arguments:
    raise SystemExit("controlled Python requires a script or module")
if arguments[0] == "-m":
    if len(arguments) < 2:
        raise SystemExit("controlled Python module name is missing")
    module = arguments[1]
    sys.argv = arguments[1:]
    runpy.run_module(module, run_name="__main__", alter_sys=True)
else:
    script = os.path.realpath(arguments[0])
    metadata = os.lstat(script)
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit("controlled Python script is not a regular file")
    sys.argv = [script, *arguments[1:]]
    runpy.run_path(script, run_name="__main__")
' "$CI_PYTHON_DEPENDENCY_ROOT" "$@"
}

ci_assert_clean_environment() {
  local name
  local forbidden=(
    BASH_ENV
    CDPATH
    ENV
    GLOBIGNORE
    GIT_ALTERNATE_OBJECT_DIRECTORIES
    GIT_ATTR_NOSYSTEM
    GIT_ATTR_SOURCE
    GIT_CEILING_DIRECTORIES
    GIT_COMMON_DIR
    GIT_CONFIG_COUNT
    GIT_CONFIG_GLOBAL
    GIT_CONFIG_NOSYSTEM
    GIT_CONFIG_PARAMETERS
    GIT_CONFIG_SYSTEM
    GIT_DIR
    GIT_DISCOVERY_ACROSS_FILESYSTEM
    GIT_EXEC_PATH
    GIT_GRAFT_FILE
    GIT_INDEX_FILE
    GIT_INTERNAL_SUPER_PREFIX
    GIT_NAMESPACE
    GIT_OBJECT_DIRECTORY
    GIT_PREFIX
    GIT_QUARANTINE_PATH
    GIT_REPLACE_REF_BASE
    GIT_SHALLOW_FILE
    GIT_WORK_TREE
  )
  for name in "${forbidden[@]}"; do
    if declare -p "$name" >/dev/null 2>&1; then
      ci_guard_fail "forbidden shell or Git environment variable is set: $name"
      return 1
    fi
  done
  while IFS= read -r name; do
    case "$name" in
      GIT_CONFIG_KEY_* | GIT_CONFIG_VALUE_*)
        ci_guard_fail "forbidden Git configuration environment variable is set: $name"
        return 1
        ;;
    esac
  done < <(compgen -v)
}

ci_discovery_git() {
  local workspace="$1"
  shift
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL=/dev/null \
    "$CI_TOOL_GIT" \
      -C "$workspace" \
      -c core.commitGraph=false \
      -c core.useReplaceRefs=false \
      --no-replace-objects \
      --literal-pathspecs \
      "$@"
}

ci_bound_git() {
  if [[ "${1:-}" == "archive" ]]; then
    ci_guard_fail "archive must use captured-tree isolation"
    return 1
  fi
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL=/dev/null \
    "$CI_TOOL_GIT" \
      -C "$CI_EXPECTED_WORKSPACE" \
      --git-dir="$CI_EXPECTED_GIT_DIR" \
      --work-tree="$CI_EXPECTED_WORK_TREE" \
      -c core.commitGraph=false \
      -c core.useReplaceRefs=false \
      --no-replace-objects \
      --literal-pathspecs \
      "$@"
}

ci_archive_captured_tree() {
  local archive_git_dir
  local archive_status

  ci_verify_python_execution_environment || return 1
  if (( $# != 2 )) ||
    [[
      "$1" != "archive" ||
        "$2" != "${CI_EXPECTED_COMMIT:-}" ||
        ! "${CI_EXPECTED_COMMIT:-}" =~ ^[0-9a-f]{40}$ ||
        ! -d "${CI_EXPECTED_OBJECT_DIRECTORY:-}"
    ]]; then
    ci_guard_fail "archive must target only the captured commit"
    return 1
  fi
  if ! archive_git_dir="$(
    "$CI_TOOL_MKTEMP" -d /tmp/documentation-archive-git.XXXXXX
  )"; then
    ci_guard_fail "temporary archive metadata could not be created"
    return 1
  fi
  if ! "$CI_TOOL_MKDIR" -p "$archive_git_dir/refs" ||
    ! printf '%s\n' 'ref: refs/heads/isolated' >"$archive_git_dir/HEAD"; then
    "$CI_TOOL_RM" -rf -- "$archive_git_dir"
    ci_guard_fail "temporary archive metadata could not be initialized"
    return 1
  fi
  if "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_ATTR_NOSYSTEM=1 \
    GIT_DIR="$archive_git_dir" \
    GIT_OBJECT_DIRECTORY="$CI_EXPECTED_OBJECT_DIRECTORY" \
    "$CI_TOOL_GIT" \
      -c core.attributesFile=/dev/null \
      -c core.commitGraph=false \
      -c core.useReplaceRefs=false \
      --no-replace-objects \
      --literal-pathspecs \
      archive "$CI_EXPECTED_COMMIT"; then
    archive_status=0
  else
    archive_status=$?
  fi
  if ! "$CI_TOOL_RM" -rf -- "$archive_git_dir"; then
    ci_guard_fail "temporary archive metadata could not be removed"
    return 1
  fi
  return "$archive_status"
}

ci_git() {
  if [[ "${1:-}" == "archive" ]]; then
    ci_archive_captured_tree "$@"
    return
  fi
  ci_bound_git "$@"
}

ci_verify_archive_manifest() {
  local archive_path
  local archive_status
  local cleanup_status
  local manifest_diagnostic
  local manifest_status
  local snapshot_root
  local tree_manifest_path
  local tree_status

  if (( $# != 1 )) ||
    [[ "$1" != /* || ! -d "$1" || -L "$1" ]]; then
    ci_guard_fail "archive manifest requires one absolute snapshot directory"
    return 1
  fi
  snapshot_root="$1"
  if ! archive_path="$(
    "$CI_TOOL_MKTEMP" "${TMPDIR:-/tmp}/documentation-archive.XXXXXX"
  )"; then
    ci_guard_fail "temporary archive manifest input could not be allocated"
    return 1
  fi
  if ! tree_manifest_path="$(
    "$CI_TOOL_MKTEMP" "${TMPDIR:-/tmp}/documentation-tree.XXXXXX"
  )"; then
    "$CI_TOOL_RM" -f -- "$archive_path"
    ci_guard_fail "temporary commit-tree manifest could not be allocated"
    return 1
  fi

  if ci_git archive "$CI_EXPECTED_COMMIT" >"$archive_path"; then
    archive_status=0
  else
    archive_status=$?
  fi
  if (( archive_status != 0 )); then
    "$CI_TOOL_RM" -f -- "$archive_path" "$tree_manifest_path"
    ci_guard_fail "captured archive manifest could not be generated"
    return "$archive_status"
  fi
  if ci_git ls-tree -r -z --full-tree \
    "$CI_EXPECTED_COMMIT" -- >"$tree_manifest_path"; then
    tree_status=0
  else
    tree_status=$?
  fi
  if (( tree_status != 0 )); then
    "$CI_TOOL_RM" -f -- "$archive_path" "$tree_manifest_path"
    ci_guard_fail "captured commit-tree manifest could not be generated"
    return "$tree_status"
  fi

  if manifest_diagnostic="$(
    "$CI_TOOL_ENV" -i \
      PATH="$CI_SAFE_PATH" \
      HOME="$CI_SAFE_HOME" \
      LANG=C \
      LC_ALL=C \
      "$CI_TOOL_PYTHON3" -B -I -S -c '
import json
import os
import stat
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath


POLICY_PATH = ".agents/payment-modernization-policy.json"
REQUIRED_RUNTIME_PATHS = {
    POLICY_PATH,
    ".github/CODEOWNERS",
    ".github/workflows/documentation.yml",
    "scripts/check-doc-decisions.py",
    "scripts/check_modernization_artifacts.py",
    "scripts/check_project_skills.py",
    "scripts/check_sensitive_artifacts.py",
    "scripts/ci_repository_guard.sh",
    "scripts/requirements-documentation.txt",
}


def fail(message):
    print(message)
    raise SystemExit(1)


def safe_archive_name(raw_name):
    if raw_name.startswith("./"):
        raw_name = raw_name[2:]
    path = PurePosixPath(raw_name)
    if (
        not raw_name
        or raw_name == "."
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        fail("archive contains an unsafe member name")
    return path.as_posix()


snapshot_root = Path(sys.argv[1])
archive_path = Path(sys.argv[2])
tree_manifest_path = Path(sys.argv[3])

raw_tree_manifest = tree_manifest_path.read_bytes()
if not raw_tree_manifest.endswith(b"\0"):
    fail("captured commit-tree manifest is not NUL terminated")
raw_tree_entries = raw_tree_manifest[:-1].split(b"\0")
tree_entries = {}
for raw_entry in raw_tree_entries:
    raw_metadata, separator, raw_path = raw_entry.partition(b"\t")
    metadata = raw_metadata.split(b" ")
    if not separator or len(metadata) != 3:
        fail("captured commit-tree manifest has an invalid entry")
    raw_mode, raw_type, raw_oid = metadata
    try:
        path = raw_path.decode("utf-8", "strict")
        mode = raw_mode.decode("ascii", "strict")
        object_type = raw_type.decode("ascii", "strict")
        oid = raw_oid.decode("ascii", "strict")
    except UnicodeDecodeError:
        fail("captured commit-tree manifest contains invalid encoding")
    if (
        path in tree_entries
        or mode not in {"100644", "100755", "120000", "160000"}
        or object_type not in {"blob", "commit"}
        or len(oid) not in {40, 64}
        or any(character not in "0123456789abcdef" for character in oid)
    ):
        fail("captured commit-tree manifest has an invalid entry")
    tree_entries[path] = (mode, object_type, oid)
tree_path_set = set(tree_entries)

with tarfile.open(archive_path, mode="r:") as archive:
    archive_members = {}
    archive_paths = set()
    archive_non_directories = set()
    for member in archive.getmembers():
        name = safe_archive_name(member.name)
        if name in archive_members:
            fail("archive manifest contains duplicate paths")
        if not (member.isdir() or member.isreg() or member.issym()):
            fail("archive manifest contains an unsupported member type")
        archive_members[name] = member
        archive_paths.add(name)
        pure_path = PurePosixPath(name)
        archive_paths.update(
            parent.as_posix()
            for parent in pure_path.parents
            if parent.as_posix() != "."
        )
        if not member.isdir():
            archive_non_directories.add(name)
            if name not in tree_path_set:
                fail("archive manifest contains a path outside the captured tree")

    policy_member = archive_members.get(POLICY_PATH)
    if policy_member is None or not policy_member.isreg():
        fail("archive is missing the governance policy")
    policy_stream = archive.extractfile(policy_member)
    if policy_stream is None:
        fail("archive governance policy cannot be read")
    try:
        policy = json.loads(policy_stream.read().decode("utf-8", "strict"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("archive governance policy is not valid UTF-8 JSON")
    judge_paths = policy.get("judgePaths") if isinstance(policy, dict) else None
    if (
        not isinstance(judge_paths, list)
        or not judge_paths
        or any(
            not isinstance(path, str)
            or not path
            or PurePosixPath(path).is_absolute()
            or ".." in PurePosixPath(path).parts
            for path in judge_paths
        )
        or len(judge_paths) != len(set(judge_paths))
    ):
        fail("archive governance policy has an invalid judgePaths manifest")

    captured_tests = {
        path
        for path in tree_path_set
        if path.startswith("scripts/tests/")
        and PurePosixPath(path).name.startswith("test_")
        and PurePosixPath(path).suffix == ".py"
    }
    archived_tests = {
        path
        for path in archive_non_directories
        if path.startswith("scripts/tests/")
        and PurePosixPath(path).name.startswith("test_")
        and PurePosixPath(path).suffix == ".py"
    }
    if not captured_tests or archived_tests != captured_tests:
        fail(
            "archive test manifest differs from captured tree: "
            f"captured={len(captured_tests)} archived={len(archived_tests)}"
        )

    required_paths = REQUIRED_RUNTIME_PATHS.union(judge_paths)
    missing_required = sorted(required_paths.difference(archive_non_directories))
    if missing_required:
        fail(
            "archive is missing required governance paths: "
            + ", ".join(missing_required)
        )
    non_regular_governance = sorted(
        path
        for path in required_paths.union(captured_tests)
        if path in archive_members and not archive_members[path].isreg()
    )
    if non_regular_governance:
        fail("archive governance and test paths must be regular files")

    git_path = sys.argv[4]
    git_dir = sys.argv[5]
    work_tree = sys.argv[6]
    safe_path = sys.argv[7]
    safe_home = sys.argv[8]
    git_environment = {
        "PATH": safe_path,
        "HOME": safe_home,
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_OPTIONAL_LOCKS": "0",
    }
    blob_reader = subprocess.Popen(
        [
            git_path,
            f"--git-dir={git_dir}",
            f"--work-tree={work_tree}",
            "-c",
            "core.commitGraph=false",
            "-c",
            "core.useReplaceRefs=false",
            "--no-replace-objects",
            "cat-file",
            "--batch",
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=git_environment,
    )
    if blob_reader.stdin is None or blob_reader.stdout is None:
        blob_reader.kill()
        blob_reader.wait()
        fail("captured Git blob reader could not be opened")

    def read_git_blob(oid):
        blob_reader.stdin.write(oid.encode("ascii") + b"\n")
        blob_reader.stdin.flush()
        header = blob_reader.stdout.readline()
        fields = header.rstrip(b"\n").split(b" ")
        if len(fields) != 3 or fields[0] != oid.encode("ascii") or fields[1] != b"blob":
            fail("captured Git blob metadata cannot be verified")
        try:
            size = int(fields[2].decode("ascii", "strict"))
        except (UnicodeDecodeError, ValueError):
            fail("captured Git blob metadata cannot be verified")
        content = blob_reader.stdout.read(size)
        if len(content) != size or blob_reader.stdout.read(1) != b"\n":
            fail("captured Git blob content cannot be read")
        return content

    snapshot_paths = set()
    for current_root, directory_names, file_names in os.walk(
        snapshot_root,
        followlinks=False,
    ):
        current = Path(current_root)
        for directory_name in list(directory_names):
            candidate = current / directory_name
            relative = candidate.relative_to(snapshot_root).as_posix()
            snapshot_paths.add(relative)
            if candidate.is_symlink():
                directory_names.remove(directory_name)
        for file_name in file_names:
            candidate = current / file_name
            snapshot_paths.add(candidate.relative_to(snapshot_root).as_posix())
    if snapshot_paths != archive_paths:
        fail("extracted snapshot paths differ from the captured archive manifest")

    try:
        for name, member in archive_members.items():
            extracted = snapshot_root.joinpath(*PurePosixPath(name).parts)
            metadata = os.lstat(extracted)
            if member.isdir():
                if not stat.S_ISDIR(metadata.st_mode):
                    fail("extracted snapshot member type differs from archive manifest")
            elif member.issym():
                if not stat.S_ISLNK(metadata.st_mode):
                    fail("extracted snapshot member type differs from archive manifest")
                if os.readlink(extracted) != member.linkname:
                    fail("extracted snapshot symlink differs from archive manifest")
                tree_mode, tree_type, tree_oid = tree_entries[name]
                if tree_mode != "120000" or tree_type != "blob":
                    fail("archive symlink type differs from captured Git tree")
                if os.fsencode(member.linkname) != read_git_blob(tree_oid):
                    fail("archive symlink target differs from captured Git blob")
            else:
                if not stat.S_ISREG(metadata.st_mode):
                    fail("extracted snapshot member type differs from archive manifest")
                tree_mode, tree_type, tree_oid = tree_entries[name]
                if tree_mode not in {"100644", "100755"} or tree_type != "blob":
                    fail("archive regular-file type differs from captured Git tree")
                archived_stream = archive.extractfile(member)
                if archived_stream is None:
                    fail("archive regular-file content cannot be read")
                archived_content = archived_stream.read()
                if archived_content != read_git_blob(tree_oid):
                    fail(
                        "archive regular-file content differs from captured Git blob"
                    )
                if extracted.read_bytes() != archived_content:
                    fail("extracted snapshot content differs from archive manifest")
    finally:
        blob_reader.stdin.close()
        blob_status = blob_reader.wait()
    if blob_status != 0:
        fail("captured Git blob reader failed")
' "$snapshot_root" "$archive_path" "$tree_manifest_path" \
    "$CI_TOOL_GIT" "$CI_EXPECTED_GIT_DIR" "$CI_EXPECTED_WORK_TREE" \
    "$CI_SAFE_PATH" "$CI_SAFE_HOME"
  )"; then
    manifest_status=0
  else
    manifest_status=$?
  fi

  cleanup_status=0
  if "$CI_TOOL_RM" -f -- "$archive_path" "$tree_manifest_path"; then
    cleanup_status=0
  else
    cleanup_status=$?
  fi
  if (( manifest_status != 0 )); then
    ci_guard_fail "${manifest_diagnostic:-captured archive manifest validation failed}"
    return "$manifest_status"
  fi
  if (( cleanup_status != 0 )); then
    ci_guard_fail "temporary archive manifest inputs could not be removed"
    return "$cleanup_status"
  fi
}

ci_git_with_index() {
  local index_file="$1"
  shift
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_INDEX_FILE="$index_file" \
    "$CI_TOOL_GIT" \
      -C "$CI_EXPECTED_WORKSPACE" \
      --git-dir="$CI_EXPECTED_GIT_DIR" \
      --work-tree="$CI_EXPECTED_WORK_TREE" \
      -c core.commitGraph=false \
      -c core.useReplaceRefs=false \
      --no-replace-objects \
      --literal-pathspecs \
      "$@"
}

ci_sha256_stream() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c \
      'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
}

ci_config_files_fingerprint() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import hashlib
import os
import stat
import sys

digest = hashlib.sha256()
for raw_path in sys.argv[1:]:
    path = os.fsencode(raw_path)
    digest.update(len(path).to_bytes(8, "big"))
    digest.update(path)
    try:
        metadata = os.lstat(path)
    except FileNotFoundError:
        digest.update(b"A")
        continue
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f"local Git configuration is not a regular file: {raw_path}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (
            opened.st_dev,
            opened.st_ino,
        ) != (metadata.st_dev, metadata.st_ino):
            raise SystemExit(f"local Git configuration changed while opening: {raw_path}")
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
    finally:
        os.close(descriptor)
    content = b"".join(chunks)
    digest.update(b"F")
    digest.update(len(content).to_bytes(8, "big"))
    digest.update(content)
print(digest.hexdigest())
' "$@"
}

ci_repository_control_fingerprint() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import hashlib
import os
import stat
import sys

digest = hashlib.sha256()
for raw_path in sys.argv[1:]:
    path = os.fsencode(raw_path)
    digest.update(len(path).to_bytes(8, "big"))
    digest.update(path)
    try:
        metadata = os.lstat(path)
    except FileNotFoundError:
        digest.update(b"A")
        continue
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit("repository exclude control is not a regular file")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        identity = (
            opened.st_dev,
            opened.st_ino,
            opened.st_size,
            opened.st_mtime_ns,
            opened.st_ctime_ns,
        )
        if not stat.S_ISREG(opened.st_mode) or identity[:2] != (
            metadata.st_dev,
            metadata.st_ino,
        ):
            raise SystemExit("repository exclude control changed while opening")
        content_digest = hashlib.sha256()
        size = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            content_digest.update(chunk)
        if size != opened.st_size:
            raise SystemExit("repository exclude control changed while reading")
        final = os.fstat(descriptor)
        if identity != (
            final.st_dev,
            final.st_ino,
            final.st_size,
            final.st_mtime_ns,
            final.st_ctime_ns,
        ):
            raise SystemExit("repository exclude control changed while reading")
    finally:
        os.close(descriptor)
    digest.update(b"F")
    digest.update(size.to_bytes(8, "big"))
    digest.update(content_digest.digest())
print(digest.hexdigest())
' "$@"
}

ci_effective_core_excludes_file() {
  local configured
  local status

  if configured="$(
    ci_git config --path --get core.excludesFile 2>/dev/null
  )"; then
    if [[ -z "$configured" || "$configured" == *$'\n'* ]]; then
      ci_guard_fail "core excludes file path is invalid"
      return 1
    fi
    if [[ "$configured" != /* ]]; then
      configured="$CI_EXPECTED_WORKSPACE/$configured"
    fi
    printf '%s\n' "$configured"
    return
  else
    status=$?
  fi
  if (( status != 1 )); then
    ci_guard_fail "core excludes file could not be resolved"
    return 1
  fi
  printf '%s\n' "$CI_SAFE_HOME/.config/git/ignore"
}

ci_dot_git_fingerprint() {
  "$CI_TOOL_ENV" -i \
    PATH="$CI_SAFE_PATH" \
    HOME="$CI_SAFE_HOME" \
    LANG=C \
    LC_ALL=C \
    "$CI_TOOL_PYTHON3" -B -I -S -c '
import hashlib
import os
import stat
import sys

raw_path = sys.argv[1]
expected_git_dir = os.path.realpath(sys.argv[2])
metadata = os.lstat(raw_path)
if stat.S_ISLNK(metadata.st_mode):
    raise SystemExit("workspace .git entry must not be a symbolic link")
digest = hashlib.sha256()
if stat.S_ISDIR(metadata.st_mode):
    resolved = os.path.realpath(raw_path)
    if resolved != expected_git_dir:
        raise SystemExit("workspace .git directory does not match the captured git-dir")
    digest.update(b"D")
    digest.update(os.fsencode(resolved))
elif stat.S_ISREG(metadata.st_mode):
    descriptor = os.open(
        raw_path,
        os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (
            opened.st_dev,
            opened.st_ino,
        ) != (metadata.st_dev, metadata.st_ino):
            raise SystemExit("workspace .git file changed while opening")
        content = os.read(descriptor, 1024 * 1024 + 1)
        if len(content) > 1024 * 1024:
            raise SystemExit("workspace .git file is unexpectedly large")
    finally:
        os.close(descriptor)
    digest.update(b"F")
    digest.update(content)
else:
    raise SystemExit("workspace .git entry has an unsupported type")
print(digest.hexdigest())
' "$1" "$2"
}

ci_local_config_fingerprint() {
  ci_git config --includes --null --show-origin --show-scope --list |
    ci_sha256_stream
}

ci_capture_repository_state() {
  local declared_sha="$1"
  local workspace
  local top_level
  local git_dir
  local common_dir
  local object_directory
  local info_exclude
  local core_excludes_file
  local core_excludes_file_after
  local config_files_before
  local config_files_after
  local local_config_before
  local local_config_after
  local exclude_controls_before
  local exclude_controls_after

  ci_assert_clean_environment || return 1
  if [[ ! "$declared_sha" =~ ^[0-9a-f]{40}$ ]]; then
    ci_guard_fail "declared commit must be a full lowercase SHA"
    return 1
  fi
  ci_capture_toolchain || return 1
  workspace="$(pwd -P)"
  top_level="$(ci_discovery_git "$workspace" rev-parse --show-toplevel)"
  git_dir="$(ci_discovery_git "$workspace" rev-parse --absolute-git-dir)"
  common_dir="$(
    ci_discovery_git "$workspace" rev-parse --path-format=absolute --git-common-dir
  )"
  object_directory="$(
    ci_discovery_git "$workspace" rev-parse --path-format=absolute --git-path objects
  )"
  if [[ "$top_level" != "$workspace" ]]; then
    ci_guard_fail "workspace is not the repository top level"
    return 1
  fi
  if [[ ! -d "$git_dir" || ! -d "$common_dir" || ! -d "$object_directory" ]]; then
    ci_guard_fail "captured Git metadata directory is invalid"
    return 1
  fi

  CI_EXPECTED_WORKSPACE="$workspace"
  CI_EXPECTED_WORK_TREE="$workspace"
  CI_EXPECTED_GIT_DIR="$git_dir"
  CI_EXPECTED_GIT_COMMON_DIR="$common_dir"
  CI_EXPECTED_OBJECT_DIRECTORY="$object_directory"
  CI_EXPECTED_COMMON_CONFIG="$common_dir/config"
  CI_EXPECTED_WORKTREE_CONFIG="$git_dir/config.worktree"
  info_exclude="$(
    ci_git rev-parse --path-format=absolute --git-path info/exclude
  )"
  CI_EXPECTED_INFO_EXCLUDE="$info_exclude"
  CI_EXPECTED_DOT_GIT_FINGERPRINT="$(
    ci_dot_git_fingerprint "$workspace/.git" "$git_dir"
  )"
  config_files_before="$(
    ci_config_files_fingerprint \
      "$CI_EXPECTED_COMMON_CONFIG" \
      "$CI_EXPECTED_WORKTREE_CONFIG"
  )"
  local_config_before="$(ci_local_config_fingerprint)"
  core_excludes_file="$(ci_effective_core_excludes_file)"
  CI_EXPECTED_CORE_EXCLUDES_FILE="$core_excludes_file"
  exclude_controls_before="$(
    ci_repository_control_fingerprint \
      "$CI_EXPECTED_INFO_EXCLUDE" \
      "$CI_EXPECTED_CORE_EXCLUDES_FILE"
  )"
  config_files_after="$(
    ci_config_files_fingerprint \
      "$CI_EXPECTED_COMMON_CONFIG" \
      "$CI_EXPECTED_WORKTREE_CONFIG"
  )"
  local_config_after="$(ci_local_config_fingerprint)"
  core_excludes_file_after="$(ci_effective_core_excludes_file)"
  if [[ "$config_files_before" != "$config_files_after" ]]; then
    ci_guard_fail "local Git configuration changed during startup capture"
    return 1
  fi
  if [[ "$local_config_before" != "$local_config_after" ]] ||
    [[ "$core_excludes_file" != "$core_excludes_file_after" ]]; then
    ci_guard_fail "effective local Git configuration changed during startup capture"
    return 1
  fi
  exclude_controls_after="$(
    ci_repository_control_fingerprint \
      "$CI_EXPECTED_INFO_EXCLUDE" \
      "$CI_EXPECTED_CORE_EXCLUDES_FILE"
  )"
  if [[ "$exclude_controls_before" != "$exclude_controls_after" ]]; then
    ci_guard_fail "exclude controls changed during startup capture"
    return 1
  fi
  CI_EXPECTED_CONFIG_FILES_FINGERPRINT="$config_files_before"
  CI_EXPECTED_LOCAL_CONFIG_FINGERPRINT="$local_config_before"
  CI_EXPECTED_EXCLUDE_CONTROLS_FINGERPRINT="$exclude_controls_before"
  CI_EXPECTED_COMMIT="$(
    ci_git rev-parse --verify 'HEAD^{commit}'
  )"
  CI_EXPECTED_TREE="$(
    ci_git rev-parse --verify "${CI_EXPECTED_COMMIT}^{tree}"
  )"
  if [[ "$CI_EXPECTED_COMMIT" != "$declared_sha" ]]; then
    ci_guard_fail "checked-out commit does not match the declared commit"
    return 1
  fi
  if [[ -d "$CI_PYTHON_DEPENDENCY_ROOT" &&
    ! -L "$CI_PYTHON_DEPENDENCY_ROOT" ]]; then
    ci_capture_python_dependency_state || return 1
  fi

  readonly \
    CI_SAFE_PATH \
    CI_SAFE_HOME \
    CI_TRUSTED_NATIVE_PATH \
    CI_PYTHON_DEPENDENCY_ROOT \
    CI_TOOL_ENV \
    CI_TOOL_GIT \
    CI_TOOL_PYTHON3 \
    CI_TOOL_FIND \
    CI_TOOL_GREP \
    CI_TOOL_TAR \
    CI_TOOL_MKTEMP \
    CI_TOOL_MKDIR \
    CI_TOOL_RM \
    CI_TOOL_STAT \
    CI_TOOL_READLINK \
    CI_TOOL_SHA256 \
    CI_NATIVE_STAT_STYLE \
    CI_NATIVE_SHA256_STYLE \
    CI_EXPECTED_TOOLCHAIN_FINGERPRINT \
    CI_EXPECTED_PYTHON_NATIVE_IDENTITY \
    CI_EXPECTED_PYTHON_RUNTIME_BOUNDARY \
    CI_EXPECTED_PYTHON_DEPENDENCY_CANONICAL_ROOT \
    CI_EXPECTED_WORKSPACE \
    CI_EXPECTED_WORK_TREE \
    CI_EXPECTED_GIT_DIR \
    CI_EXPECTED_GIT_COMMON_DIR \
    CI_EXPECTED_OBJECT_DIRECTORY \
    CI_EXPECTED_COMMON_CONFIG \
    CI_EXPECTED_WORKTREE_CONFIG \
    CI_EXPECTED_INFO_EXCLUDE \
    CI_EXPECTED_CORE_EXCLUDES_FILE \
    CI_EXPECTED_DOT_GIT_FINGERPRINT \
    CI_EXPECTED_LOCAL_CONFIG_FINGERPRINT \
    CI_EXPECTED_CONFIG_FILES_FINGERPRINT \
    CI_EXPECTED_EXCLUDE_CONTROLS_FINGERPRINT \
    CI_EXPECTED_COMMIT \
    CI_EXPECTED_TREE
}

ci_verify_repository_identity_and_config() {
  local config_files_before
  local config_files_after
  local exclude_controls_before
  local exclude_controls_after
  local actual

  ci_assert_clean_environment || return 1
  if ! test "$(pwd -P)" = "$CI_EXPECTED_WORKSPACE"; then
    ci_guard_fail "workspace top level changed after controlled scripts"
    return 1
  fi
  actual="$(
    ci_dot_git_fingerprint \
      "$CI_EXPECTED_WORKSPACE/.git" \
      "$CI_EXPECTED_GIT_DIR"
  )"
  if [[ "$actual" != "$CI_EXPECTED_DOT_GIT_FINGERPRINT" ]]; then
    ci_guard_fail "workspace .git identity changed after controlled scripts"
    return 1
  fi
  config_files_before="$(
    ci_config_files_fingerprint \
      "$CI_EXPECTED_COMMON_CONFIG" \
      "$CI_EXPECTED_WORKTREE_CONFIG"
  )"
  if [[ "$config_files_before" != "$CI_EXPECTED_CONFIG_FILES_FINGERPRINT" ]]; then
    ci_guard_fail "local Git configuration changed after controlled scripts"
    return 1
  fi
  exclude_controls_before="$(
    ci_repository_control_fingerprint \
      "$CI_EXPECTED_INFO_EXCLUDE" \
      "$CI_EXPECTED_CORE_EXCLUDES_FILE"
  )"
  if [[
    "$exclude_controls_before" != "$CI_EXPECTED_EXCLUDE_CONTROLS_FINGERPRINT"
  ]]; then
    ci_guard_fail "exclude controls changed after controlled scripts"
    return 1
  fi

  actual="$(ci_git rev-parse --show-toplevel)"
  if [[ "$actual" != "$CI_EXPECTED_WORKSPACE" ]]; then
    ci_guard_fail "bound repository top level changed"
    return 1
  fi
  actual="$(ci_git rev-parse --absolute-git-dir)"
  if [[ "$actual" != "$CI_EXPECTED_GIT_DIR" ]]; then
    ci_guard_fail "bound git-dir changed"
    return 1
  fi
  actual="$(ci_git rev-parse --path-format=absolute --git-common-dir)"
  if [[ "$actual" != "$CI_EXPECTED_GIT_COMMON_DIR" ]]; then
    ci_guard_fail "bound common-dir changed"
    return 1
  fi
  actual="$(ci_git rev-parse --path-format=absolute --git-path objects)"
  if [[ "$actual" != "$CI_EXPECTED_OBJECT_DIRECTORY" ]]; then
    ci_guard_fail "bound object directory changed"
    return 1
  fi
  actual="$(ci_local_config_fingerprint)"
  if [[ "$actual" != "$CI_EXPECTED_LOCAL_CONFIG_FINGERPRINT" ]]; then
    ci_guard_fail "effective local Git configuration changed after controlled scripts"
    return 1
  fi
  config_files_after="$(
    ci_config_files_fingerprint \
      "$CI_EXPECTED_COMMON_CONFIG" \
      "$CI_EXPECTED_WORKTREE_CONFIG"
  )"
  if [[ "$config_files_after" != "$CI_EXPECTED_CONFIG_FILES_FINGERPRINT" ]]; then
    ci_guard_fail "local Git configuration changed during verification"
    return 1
  fi
  exclude_controls_after="$(
    ci_repository_control_fingerprint \
      "$CI_EXPECTED_INFO_EXCLUDE" \
      "$CI_EXPECTED_CORE_EXCLUDES_FILE"
  )"
  if [[
    "$exclude_controls_after" != "$CI_EXPECTED_EXCLUDE_CONTROLS_FINGERPRINT"
  ]]; then
    ci_guard_fail "exclude controls changed during verification"
    return 1
  fi
}

ci_verify_index_and_worktree() {
  local tracked_flags
  local snapshot_index

  if [[ "$(ci_git write-tree)" != "$CI_EXPECTED_TREE" ]]; then
    ci_guard_fail "index tree differs from the captured commit tree"
    return 1
  fi
  tracked_flags="$(ci_git ls-files -v)"
  if "$CI_TOOL_GREP" -E '^[a-zS] ' <<<"$tracked_flags" >/dev/null; then
    ci_guard_fail "index contains hidden tracked-file flags"
    return 1
  fi
  if ! snapshot_index="$(
    "$CI_TOOL_MKTEMP" "${TMPDIR:-/tmp}/documentation-index.XXXXXX"
  )"; then
    ci_guard_fail "temporary index path could not be allocated"
    return 1
  fi
  "$CI_TOOL_RM" -f "$snapshot_index"
  if ! ci_git_with_index "$snapshot_index" read-tree "$CI_EXPECTED_TREE"; then
    "$CI_TOOL_RM" -f "$snapshot_index"
    ci_guard_fail "temporary index could not read the captured tree"
    return 1
  fi
  if ! ci_git_with_index "$snapshot_index" update-index --refresh; then
    "$CI_TOOL_RM" -f "$snapshot_index"
    ci_guard_fail "worktree content could not refresh against the captured tree"
    return 1
  fi
  if ! ci_git_with_index "$snapshot_index" diff-files \
    --quiet --no-ext-diff --ignore-submodules=none; then
    "$CI_TOOL_RM" -f "$snapshot_index"
    ci_guard_fail "worktree content differs from the captured tree"
    return 1
  fi
  "$CI_TOOL_RM" -f "$snapshot_index"
}

ci_verify_repository_state() {
  local grafts_path
  local replace_ref_dir
  local replace_refs
  local untracked

  ci_verify_python_execution_environment || return 1
  ci_verify_repository_identity_and_config || return 1
  replace_ref_dir="$(ci_git rev-parse --git-path refs/replace)"
  grafts_path="$(ci_git rev-parse --git-path info/grafts)"
  if [[ -e "$grafts_path" || -L "$grafts_path" ]]; then
    ci_guard_fail "Git graft metadata is forbidden"
    return 1
  fi
  if [[ -L "$replace_ref_dir" ]]; then
    ci_guard_fail "replace-ref directory must not be a symbolic link"
    return 1
  fi
  if [[ "$(ci_git rev-parse --is-shallow-repository)" != "false" ]]; then
    ci_guard_fail "shallow repository state is forbidden"
    return 1
  fi
  if [[ -e "$replace_ref_dir" ]]; then
    if [[ ! -d "$replace_ref_dir" ]]; then
      ci_guard_fail "replace-ref path is not a directory"
      return 1
    fi
    if [[ -n "$(
      "$CI_TOOL_FIND" "$replace_ref_dir" -mindepth 1 -print -quit
    )" ]]; then
      ci_guard_fail "replace-ref directory is not empty"
      return 1
    fi
  fi
  replace_refs="$(
    ci_git for-each-ref --format='%(refname)' refs/replace/
  )"
  if [[ -n "$replace_refs" ]]; then
    ci_guard_fail "replace refs are forbidden"
    return 1
  fi
  if [[ "$(ci_git rev-parse --verify 'HEAD^{commit}')" != "$CI_EXPECTED_COMMIT" ]]; then
    ci_guard_fail "HEAD changed after controlled scripts"
    return 1
  fi
  if [[ "$(ci_git rev-parse --verify 'HEAD^{tree}')" != "$CI_EXPECTED_TREE" ]]; then
    ci_guard_fail "HEAD tree changed after controlled scripts"
    return 1
  fi
  if [[ "$(
    ci_git rev-parse --verify "${CI_EXPECTED_COMMIT}^{tree}"
  )" != "$CI_EXPECTED_TREE" ]]; then
    ci_guard_fail "captured commit tree no longer resolves identically"
    return 1
  fi
  ci_verify_index_and_worktree || return 1
  if ! ci_git diff --no-ext-diff --exit-code "$CI_EXPECTED_COMMIT" --; then
    ci_guard_fail "tracked worktree content changed after controlled scripts"
    return 1
  fi
  untracked="$(ci_git ls-files --others --)"
  if [[ -n "$untracked" ]]; then
    ci_guard_fail "worktree has untracked or modified files after controlled scripts"
    return 1
  fi
}
