import type { SystemRoleApi } from '#/api/system/role';
import type { PageResult } from '#/api/system/types';

const ROLE_CATALOG_PAGE_SIZE = 200;
const ROLE_LOOKUP_CONCURRENCY = 8;

interface RoleCatalogPageQuery {
  id?: string;
  name?: string;
  page: number;
  pageSize: number;
  status?: 0 | 1;
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

async function loadRoleAssignmentCatalog(
  loadPage: RoleCatalogPageLoader,
  currentRoleIds: string[] = [],
) {
  const rolesById = new Map<string, SystemRoleApi.SystemRole>();
  const firstPage = await loadPage({
    page: 1,
    pageSize: ROLE_CATALOG_PAGE_SIZE,
    status: 1,
  });
  firstPage.items.forEach((role) => rolesById.set(role.id, role));

  const missingCurrentIds = [...new Set(currentRoleIds)].filter(
    (roleId) => !rolesById.has(roleId),
  );
  const exactRoles = await mapWithConcurrency(
    missingCurrentIds,
    ROLE_LOOKUP_CONCURRENCY,
    async (roleId) => {
      const result = await loadPage({ id: roleId, page: 1, pageSize: 1 });
      return result.items.find((role) => role.id === roleId);
    },
  );
  exactRoles.forEach((role) => {
    if (role) rolesById.set(role.id, role);
  });
  return [...rolesById.values()];
}

async function tryLoadRoleAssignmentCatalog(
  loadPage: RoleCatalogPageLoader,
  currentRoleIds: string[] = [],
) {
  try {
    return {
      ready: true as const,
      roles: await loadRoleAssignmentCatalog(loadPage, currentRoleIds),
    };
  } catch {
    return { ready: false as const, roles: [] };
  }
}

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T) => Promise<R>,
) {
  const results: Array<undefined | { value: R }> = Array.from({
    length: items.length,
  });
  let nextIndex = 0;
  const workers = Array.from(
    { length: Math.min(concurrency, items.length) },
    async () => {
      while (nextIndex < items.length) {
        const index = nextIndex;
        nextIndex += 1;
        const item = items[index];
        if (item === undefined) {
          throw new Error('Role lookup worker exceeded the input boundary');
        }
        results[index] = { value: await mapper(item) };
      }
    },
  );
  await Promise.all(workers);
  return results.map((result) => {
    if (!result) throw new Error('Role lookup worker did not return a result');
    return result.value;
  });
}

function mergeRoleSearchResults(
  existingRoles: SystemRoleApi.SystemRole[],
  searchRoles: SystemRoleApi.SystemRole[],
  pinnedRoleIds: string[],
) {
  const pinnedIds = new Set(pinnedRoleIds);
  const merged = new Map<string, SystemRoleApi.SystemRole>();
  existingRoles
    .filter((role) => pinnedIds.has(role.id))
    .forEach((role) => merged.set(role.id, role));
  searchRoles.forEach((role) => merged.set(role.id, role));
  return [...merged.values()];
}

export {
  buildRoleAssignmentOptions,
  loadRoleAssignmentCatalog,
  mergeRoleAssignmentIds,
  mergeRoleSearchResults,
  resolveRoleAssignmentIds,
  tryLoadRoleAssignmentCatalog,
};
