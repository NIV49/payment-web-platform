import type { PermissionCode } from '#/api/permission-codes';

import { PERMISSION_CODES } from '#/api/permission-codes';

type AccessCodeChecker = (codes: string[]) => boolean;

const GRANT_PERMISSION_DEPENDENCIES = {
  [PERMISSION_CODES.departmentCreate]: [PERMISSION_CODES.departmentView],
  [PERMISSION_CODES.departmentDelete]: [PERMISSION_CODES.departmentView],
  [PERMISSION_CODES.departmentUpdate]: [PERMISSION_CODES.departmentView],
  [PERMISSION_CODES.menuCreate]: [PERMISSION_CODES.menuView],
  [PERMISSION_CODES.menuDelete]: [PERMISSION_CODES.menuView],
  [PERMISSION_CODES.menuUpdate]: [PERMISSION_CODES.menuView],
  [PERMISSION_CODES.roleCreate]: [
    PERMISSION_CODES.roleView,
    PERMISSION_CODES.menuView,
  ],
  [PERMISSION_CODES.roleDelete]: [PERMISSION_CODES.roleView],
  [PERMISSION_CODES.roleUpdate]: [
    PERMISSION_CODES.roleView,
    PERMISSION_CODES.menuView,
  ],
  [PERMISSION_CODES.userAssignRole]: [
    PERMISSION_CODES.userView,
    PERMISSION_CODES.userUpdate,
    PERMISSION_CODES.userDisable,
    PERMISSION_CODES.departmentView,
    PERMISSION_CODES.roleView,
  ],
  [PERMISSION_CODES.userCreate]: [
    PERMISSION_CODES.userView,
    PERMISSION_CODES.departmentView,
    PERMISSION_CODES.roleView,
  ],
  [PERMISSION_CODES.userDelete]: [PERMISSION_CODES.userView],
  [PERMISSION_CODES.userDisable]: [PERMISSION_CODES.userView],
  [PERMISSION_CODES.userUpdate]: [
    PERMISSION_CODES.userView,
    PERMISSION_CODES.userDisable,
    PERMISSION_CODES.userAssignRole,
    PERMISSION_CODES.departmentView,
    PERMISSION_CODES.roleView,
  ],
} satisfies Partial<Record<PermissionCode, readonly PermissionCode[]>>;

function permissionDependencies(permissionCode: string): readonly string[] {
  return (
    GRANT_PERMISSION_DEPENDENCIES[
      permissionCode as keyof typeof GRANT_PERMISSION_DEPENDENCIES
    ] ?? []
  );
}

function expandPermissionDependencies(permissionCodes: readonly string[]) {
  const expanded = new Set<string>();
  const pending = [...permissionCodes];
  while (pending.length > 0) {
    const permissionCode = pending.shift();
    if (!permissionCode || expanded.has(permissionCode)) continue;
    expanded.add(permissionCode);
    pending.push(...permissionDependencies(permissionCode));
  }
  return [...expanded];
}

function hasPermissionDependencies(
  permissionCodes: readonly string[],
  hasAccessByCodes: AccessCodeChecker,
) {
  const requiredCodes = expandPermissionDependencies(permissionCodes);
  return (
    requiredCodes.length > 0 &&
    requiredCodes.every((code) => hasAccessByCodes([code]))
  );
}

export {
  expandPermissionDependencies,
  GRANT_PERMISSION_DEPENDENCIES,
  hasPermissionDependencies,
  permissionDependencies,
};
