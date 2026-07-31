import type { SystemRoleApi } from '#/api/system/role';
import type { IamRoleGrantApi } from '#/api/system/role-grant';

import { PERMISSION_CODES } from '#/api/permission-codes';

const ROLE_LIST_SEARCH_BEHAVIOR = {
  submitOnChange: false,
} as const;

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

function mergeRoleNavigationMenuIds(
  navigationMenuIds: readonly string[],
  preservedButtonMenuIds: readonly string[],
) {
  return [...new Set([...navigationMenuIds, ...preservedButtonMenuIds])];
}

export {
  buildTenantRoleGrants,
  canConfigureRoleGrants,
  canMutateRole,
  mergeRoleNavigationMenuIds,
  ROLE_LIST_SEARCH_BEHAVIOR,
};
