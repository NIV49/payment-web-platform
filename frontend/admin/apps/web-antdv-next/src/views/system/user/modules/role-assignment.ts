import type { SystemRoleApi } from '#/api/system/role';

export interface RoleAssignmentOption {
  disabled: boolean;
  label: string;
  value: string;
}

function isAssignable(role: SystemRoleApi.SystemRole) {
  return role.status === 1 && role.assignable && !role.systemRole;
}

function buildRoleAssignmentOptions(
  roles: SystemRoleApi.SystemRole[],
  currentRoleIds: string[],
  currentRoleNames: string[] = [],
): RoleAssignmentOption[] {
  const currentIds = new Set(currentRoleIds);
  const currentLabels = new Map(
    currentRoleIds.map((id, index) => [id, currentRoleNames[index] ?? id]),
  );
  const knownRoleIds = new Set(roles.map((role) => role.id));
  const knownOptions = roles
    .filter((role) => isAssignable(role) || currentIds.has(role.id))
    .map((role) => ({
      disabled: !isAssignable(role),
      label: role.name,
      value: role.id,
    }));
  const missingCurrentOptions = currentRoleIds
    .filter((id) => !knownRoleIds.has(id))
    .map((id) => ({
      disabled: true,
      label: currentLabels.get(id) ?? id,
      value: id,
    }));
  return [...knownOptions, ...missingCurrentOptions];
}

function mergeRoleAssignmentIds(
  selectedRoleIds: string[],
  currentRoleIds: string[],
  roles: SystemRoleApi.SystemRole[],
): string[] {
  const assignableIds = new Set(
    roles.filter((role) => isAssignable(role)).map((role) => role.id),
  );
  const preservedIds = currentRoleIds.filter((id) => !assignableIds.has(id));
  const selectedAssignableIds = selectedRoleIds.filter((id) =>
    assignableIds.has(id),
  );
  return [...new Set([...preservedIds, ...selectedAssignableIds])];
}

export { buildRoleAssignmentOptions, mergeRoleAssignmentIds };
