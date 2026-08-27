import type { SystemMenuApi } from '@payment/backoffice-runtime/api/system/menu';

import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';

export interface RoleConfigurationTree {
  buttonIdByPermission: Record<string, string>;
  linkedNavigationIds: Record<string, string[]>;
  navigationIds: string[];
  parentById: Record<string, string | undefined>;
  permissionByButtonId: Record<string, string>;
  tree: SystemMenuApi.SystemMenu[];
}

export interface RoleConfigurationSelectionChange {
  checked: boolean;
  id: string;
}

export function filterNavigableMenuTree(
  menuTree: readonly SystemMenuApi.SystemMenu[],
): SystemMenuApi.SystemMenu[] {
  return menuTree
    .filter(({ status, type }) => status === 1 && type !== 'button')
    .map((menu) => ({
      ...menu,
      ...(menu.children
        ? { children: filterNavigableMenuTree(menu.children) }
        : {}),
    }));
}

export function filterAvailableNavigationMenuIds(
  menuIds: readonly string[],
  menuTree: readonly SystemMenuApi.SystemMenu[],
) {
  const availableIds = new Set<string>();
  const collect = (menus: readonly SystemMenuApi.SystemMenu[]) => {
    for (const menu of menus) {
      availableIds.add(menu.id);
      if (menu.children) collect(menu.children);
    }
  };
  collect(menuTree);
  return menuIds.filter((menuId) => availableIds.has(menuId));
}

export function buildRoleConfigurationTree(
  menuTree: readonly SystemMenuApi.SystemMenu[],
  grantablePermissionCodes: readonly string[],
): RoleConfigurationTree {
  const grantable = new Set(grantablePermissionCodes);
  grantable.delete(PERMISSION_CODES.roleGrantUpdate);
  const buttonIdByPermission: Record<string, string> = {};
  const navigationIds: string[] = [];
  const parentById: Record<string, string | undefined> = {};
  const sourceParentById: Record<string, string | undefined> = {};
  const hiddenNavigationIds = new Set<string>();
  const linkedHiddenNavigationIds = new Map<string, string[]>();
  const visibleNavigationIdByPath = new Map<string, string>();

  const indexNavigation = (
    menus: readonly SystemMenuApi.SystemMenu[],
    parentId?: string,
  ) => {
    for (const menu of menus) {
      if (menu.status !== 1 || menu.type === 'button') continue;
      sourceParentById[menu.id] = parentId;
      if (menu.meta?.hideInMenu === true) {
        hiddenNavigationIds.add(menu.id);
      } else if (menu.path) {
        visibleNavigationIdByPath.set(menu.path, menu.id);
      }
      if (menu.children) indexNavigation(menu.children, menu.id);
    }
  };
  indexNavigation(menuTree);

  const nearestVisibleParent = (parentId?: string) => {
    let candidate = parentId;
    while (candidate && hiddenNavigationIds.has(candidate)) {
      candidate = sourceParentById[candidate];
    }
    return candidate;
  };

  const promotionTarget = (
    menu: SystemMenuApi.SystemMenu,
    parentId?: string,
  ) => {
    const activePath = menu.meta?.activePath;
    const activeTarget = activePath
      ? visibleNavigationIdByPath.get(activePath)
      : undefined;
    if (activeTarget && !isSourceDescendant(activeTarget, menu.id)) {
      return activeTarget;
    }
    return nearestVisibleParent(parentId);
  };

  const isSourceDescendant = (id: string, ancestorId: string) => {
    let parentId = sourceParentById[id];
    while (parentId) {
      if (parentId === ancestorId) return true;
      parentId = sourceParentById[parentId];
    }
    return false;
  };

  const visit = (
    menus: readonly SystemMenuApi.SystemMenu[],
    parentId?: string,
  ): SystemMenuApi.SystemMenu[] =>
    menus.flatMap((menu) => {
      if (menu.status !== 1) return [];
      if (menu.type === 'button') {
        const authCode = menu.authCode?.trim();
        if (
          !parentId ||
          !authCode ||
          !grantable.has(authCode) ||
          buttonIdByPermission[authCode]
        ) {
          return [];
        }
        buttonIdByPermission[authCode] = menu.id;
        parentById[menu.id] = parentId;
        return [{ ...menu, children: undefined }];
      }

      navigationIds.push(menu.id);
      const hidden = menu.meta?.hideInMenu === true;
      const logicalParentId = hidden
        ? promotionTarget(menu, parentId)
        : parentId;
      parentById[menu.id] = logicalParentId;
      const children = menu.children ? visit(menu.children, menu.id) : [];
      if (hidden) {
        if (logicalParentId) {
          const linked = linkedHiddenNavigationIds.get(logicalParentId) ?? [];
          linkedHiddenNavigationIds.set(logicalParentId, [...linked, menu.id]);
        }
        return [];
      }
      return [
        {
          ...menu,
          ...(menu.children ? { children } : {}),
        },
      ];
    });

  const tree = visit(menuTree);
  const availableButtonIdByPermission: Record<string, string> = {};
  const availablePermissionByButtonId: Record<string, string> = {};
  for (const permission of Object.keys(buttonIdByPermission)) {
    const buttonId = buttonIdByPermission[permission];
    if (!buttonId) continue;
    availableButtonIdByPermission[permission] = buttonId;
    availablePermissionByButtonId[buttonId] = permission;
  }
  const visibleButtons = new Set(Object.values(availableButtonIdByPermission));
  const removeUnavailableButtons = (
    menus: readonly SystemMenuApi.SystemMenu[],
  ): SystemMenuApi.SystemMenu[] =>
    menus.flatMap((menu) => {
      if (menu.type === 'button') {
        return visibleButtons.has(menu.id) ? [menu] : [];
      }
      return [
        {
          ...menu,
          ...(menu.children
            ? { children: removeUnavailableButtons(menu.children) }
            : {}),
        },
      ];
    });

  return {
    buttonIdByPermission: availableButtonIdByPermission,
    linkedNavigationIds: Object.fromEntries(linkedHiddenNavigationIds),
    navigationIds,
    parentById,
    permissionByButtonId: availablePermissionByButtonId,
    tree: removeUnavailableButtons(tree),
  };
}

export function normalizeRoleConfigurationSelection(
  selectedIds: readonly string[],
  configuration: RoleConfigurationTree,
  change?: RoleConfigurationSelectionChange,
) {
  const navigationSet = new Set(configuration.navigationIds);
  const requestedIds = new Set(selectedIds);
  if (change && navigationSet.has(change.id)) {
    for (const id of [
      ...configuration.navigationIds,
      ...Object.keys(configuration.permissionByButtonId),
    ]) {
      if (id !== change.id && !isDescendantOf(id, change.id, configuration)) {
        continue;
      }
      if (change.checked) requestedIds.add(id);
      if (!change.checked) requestedIds.delete(id);
    }
  }

  const availablePermissions = Object.entries(
    configuration.buttonIdByPermission,
  ).flatMap(([permission, buttonId]) =>
    requestedIds.has(buttonId) ? [permission] : [],
  );
  const nextIds = new Set(
    [...requestedIds].filter((id) => navigationSet.has(id)),
  );
  for (const navigationId of [...nextIds]) {
    let parentId = configuration.parentById[navigationId];
    while (parentId) {
      if (navigationSet.has(parentId)) nextIds.add(parentId);
      parentId = configuration.parentById[parentId];
    }
  }
  for (const navigationId of [...nextIds]) {
    for (const linkedId of configuration.linkedNavigationIds[navigationId] ??
      []) {
      nextIds.add(linkedId);
    }
  }
  for (const permission of availablePermissions) {
    let currentId: string | undefined =
      configuration.buttonIdByPermission[permission];
    while (currentId) {
      nextIds.add(currentId);
      currentId = configuration.parentById[currentId];
    }
  }
  const order = [
    ...configuration.navigationIds,
    ...Object.values(configuration.buttonIdByPermission),
  ];
  const normalizedIds = order.filter((id) => nextIds.has(id));
  return {
    menuIds: configuration.navigationIds.filter((id) => nextIds.has(id)),
    permissionCodes: [...availablePermissions].toSorted(),
    selectedIds: normalizedIds,
  };
}

function isDescendantOf(
  id: string,
  ancestorId: string,
  configuration: RoleConfigurationTree,
) {
  let parentId = configuration.parentById[id];
  while (parentId) {
    if (parentId === ancestorId) return true;
    parentId = configuration.parentById[parentId];
  }
  return false;
}
