import type { SystemRoleApi } from '#/api/system/role';
import type { PageResult } from '#/api/system/types';

const ROLE_CATALOG_PAGE_SIZE = 200;

interface RoleCatalogPageQuery {
  page: number;
  pageSize: number;
}

type RoleCatalogPageLoader = (
  query: RoleCatalogPageQuery,
) => Promise<PageResult<SystemRoleApi.SystemRole>>;

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
  canRemoveDisabledRoles = false,
): RoleAssignmentOption[] {
  const currentIds = new Set(currentRoleIds);
  const currentLabels = new Map(
    currentRoleIds.map((id, index) => [id, currentRoleNames[index] ?? id]),
  );
  const knownRoleIds = new Set(roles.map((role) => role.id));
  const knownOptions = roles
    .filter((role) => isCurrentlyAssignable(role) || currentIds.has(role.id))
    .map((role) => ({
      disabled:
        isProtected(role) || (role.status === 0 && !canRemoveDisabledRoles),
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
  canRemoveDisabledRoles = false,
): string[] {
  const currentIds = new Set(currentRoleIds);
  const rolesById = new Map(roles.map((role) => [role.id, role]));
  const editableIds = new Set(
    roles
      .filter(
        (role) =>
          isCurrentlyAssignable(role) ||
          (currentIds.has(role.id) &&
            !isProtected(role) &&
            role.status === 0 &&
            canRemoveDisabledRoles),
      )
      .map((role) => role.id),
  );
  const preservedIds = currentRoleIds.filter((id) => {
    const role = rolesById.get(id);
    return (
      !role ||
      isProtected(role) ||
      (role.status === 0 && !canRemoveDisabledRoles)
    );
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
  canRemoveDisabledRoles = false,
): string[] {
  return canAssignRoles
    ? mergeRoleAssignmentIds(
        selectedRoleIds,
        currentRoleIds,
        roles,
        canRemoveDisabledRoles,
      )
    : [...currentRoleIds];
}

async function loadRoleAssignmentCatalog(loadPage: RoleCatalogPageLoader) {
  const rolesById = new Map<string, SystemRoleApi.SystemRole>();
  let expectedTotal = 0;
  let page = 1;

  while (true) {
    const result = await loadPage({ page, pageSize: ROLE_CATALOG_PAGE_SIZE });
    expectedTotal = Math.max(expectedTotal, result.total);
    result.items.forEach((role) => rolesById.set(role.id, role));

    if (rolesById.size >= expectedTotal) {
      return [...rolesById.values()];
    }
    if (result.items.length < ROLE_CATALOG_PAGE_SIZE) {
      throw new Error('Role catalog pagination ended before total was loaded');
    }
    page += 1;
  }
}

export {
  buildRoleAssignmentOptions,
  loadRoleAssignmentCatalog,
  mergeRoleAssignmentIds,
  resolveRoleAssignmentIds,
};
