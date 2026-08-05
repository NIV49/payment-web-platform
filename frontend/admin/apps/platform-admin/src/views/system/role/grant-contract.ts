import type { SystemRoleApi } from '#/api/system/role';
import type { IamRoleGrantApi } from '#/api/system/role-grant';

import { PERMISSION_CODES } from '#/api/permission-codes';

import {
  expandPermissionDependencies,
  hasPermissionDependencies,
  permissionDependencies,
} from '../permission-dependencies';

const ROLE_LIST_SEARCH_BEHAVIOR = {
  submitOnChange: false,
} as const;

function canMutateRole(role: SystemRoleApi.SystemRole) {
  return role.assignable && !role.systemRole;
}

function canConfigureRole(
  systemAdministrator: boolean,
  actionPermission: string,
  hasAccessByCodes: (codes: string[]) => boolean,
) {
  return (
    systemAdministrator &&
    hasPermissionDependencies(
      [actionPermission, PERMISSION_CODES.roleGrantUpdate],
      hasAccessByCodes,
    )
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
  canConfigureRole,
  canMutateRole,
  findMissingPermissionDependencies,
  hasPermissionDependencies,
  permissionDependencies,
  reconcilePermissionSelection,
  ROLE_LIST_SEARCH_BEHAVIOR,
};
