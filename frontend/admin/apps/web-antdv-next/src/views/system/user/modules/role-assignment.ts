import type { SystemRoleApi } from '#/api/system/role';

export interface RoleAssignmentOption {
  disabled: boolean;
  label: string;
  value: string;
}

function isProtected(role: SystemRoleApi.SystemRole) {
  return role.systemRole || !role.assignable;
}

function isCurrentlyAssignable(role: SystemRoleApi.SystemRole) {
  return role.status === 1 && !isProtected(role);
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
    .filter((role) => isCurrentlyAssignable(role) || currentIds.has(role.id))
    .map((role) => ({
      disabled: isProtected(role),
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
  const currentIds = new Set(currentRoleIds);
  const rolesById = new Map(roles.map((role) => [role.id, role]));
  const editableIds = new Set(
    roles
      .filter(
        (role) =>
          isCurrentlyAssignable(role) ||
          (currentIds.has(role.id) && !isProtected(role)),
      )
      .map((role) => role.id),
  );
  const preservedIds = currentRoleIds.filter((id) => {
    const role = rolesById.get(id);
    return !role || isProtected(role);
  });
  const selectedEditableIds = selectedRoleIds.filter((id) =>
    editableIds.has(id),
  );
  return [...new Set([...preservedIds, ...selectedEditableIds])];
}

function resolveRoleAssignmentIds(
  canAssignRoles: boolean,
  selectedRoleIds: string[],
  currentRoleIds: string[],
  roles: SystemRoleApi.SystemRole[],
): string[] {
  return canAssignRoles
    ? mergeRoleAssignmentIds(selectedRoleIds, currentRoleIds, roles)
    : [...currentRoleIds];
}

export {
  buildRoleAssignmentOptions,
  mergeRoleAssignmentIds,
  resolveRoleAssignmentIds,
};
