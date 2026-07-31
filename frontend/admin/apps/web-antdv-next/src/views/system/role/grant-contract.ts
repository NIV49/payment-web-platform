import type { PermissionCode } from '#/api/permission-codes';
import type { SystemRoleApi } from '#/api/system/role';
import type { IamRoleGrantApi } from '#/api/system/role-grant';

import { PERMISSION_CODES } from '#/api/permission-codes';

const ROLE_LIST_SEARCH_BEHAVIOR = {
  submitOnChange: false,
} as const;

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

function canMutateRole(role: SystemRoleApi.SystemRole) {
  return role.assignable && !role.systemRole;
}

function canConfigureRoleGrants(
  role: SystemRoleApi.SystemRole,
  hasAccessByCodes: (codes: string[]) => boolean,
) {
  return (
    canMutateRole(role) && hasAccessByCodes([PERMISSION_CODES.roleGrantUpdate])
  );
}

function buildTenantRoleGrants(
  permissionCodes: readonly string[],
): IamRoleGrantApi.GrantIntent[] {
  return [...new Set(permissionCodes)].map((permissionCode) => ({
    dimensions: [{ code: 'TENANT', mode: 'TENANT_ALL', targets: [] }],
    grantKey: permissionCode.replace(':', '-'),
    permissionCode,
  }));
}

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

function reconcilePermissionSelection(
  permissionCodes: readonly string[],
  changedPermissionCode: string,
  checked: boolean,
) {
  if (checked) {
    return expandPermissionDependencies([
      ...permissionCodes,
      changedPermissionCode,
    ]);
  }

  const next = new Set(permissionCodes);
  next.delete(changedPermissionCode);
  let removedDependent = true;
  while (removedDependent) {
    removedDependent = false;
    for (const permissionCode of next) {
      if (
        permissionDependencies(permissionCode).some(
          (dependency) => !next.has(dependency),
        )
      ) {
        next.delete(permissionCode);
        removedDependent = true;
      }
    }
  }
  return [...next];
}

function findMissingPermissionDependencies(permissionCodes: readonly string[]) {
  const selected = new Set(permissionCodes);
  return permissionCodes.flatMap((permissionCode) => {
    const missing = permissionDependencies(permissionCode).filter(
      (dependency) => !selected.has(dependency),
    );
    return missing.length > 0 ? [{ missing, permissionCode }] : [];
  });
}

export {
  buildTenantRoleGrants,
  canConfigureRoleGrants,
  canMutateRole,
  findMissingPermissionDependencies,
  permissionDependencies,
  reconcilePermissionSelection,
  ROLE_LIST_SEARCH_BEHAVIOR,
};
