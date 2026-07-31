import type { SystemMenuApi } from '#/api/system/menu';

import { PERMISSION_CODES } from '#/api/permission-codes';

import {
  permissionDependencies,
  reconcilePermissionSelection,
} from './grant-contract';

export interface RoleConfigurationTree {
  buttonIdByPermission: Record<string, string>;
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
      parentById[menu.id] = parentId;
      return [
        {
          ...menu,
          ...(menu.children ? { children: visit(menu.children, menu.id) } : {}),
        },
      ];
    });

  const tree = visit(menuTree);
  const availablePermissions = new Set(Object.keys(buttonIdByPermission));
  let removedDependency = true;
  while (removedDependency) {
    removedDependency = false;
    for (const permission of availablePermissions) {
      if (
        permissionDependencies(permission).some(
          (dependency) => !availablePermissions.has(dependency),
        )
      ) {
        availablePermissions.delete(permission);
        removedDependency = true;
      }
    }
  }
  const availableButtonIdByPermission: Record<string, string> = {};
  const availablePermissionByButtonId: Record<string, string> = {};
  for (const permission of availablePermissions) {
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
  const requestedPermissions = selectedIds.flatMap((id) => {
    const permission = configuration.permissionByButtonId[id];
    return permission ? [permission] : [];
  });
  const changedPermission = change
    ? configuration.permissionByButtonId[change.id]
    : undefined;
  let permissions: string[];
  if (change && changedPermission) {
    permissions = reconcilePermissionSelection(
      requestedPermissions,
      changedPermission,
      change.checked,
    );
  } else {
    permissions = [];
    for (const permission of requestedPermissions) {
      permissions = reconcilePermissionSelection(permissions, permission, true);
    }
  }
  const availablePermissions = permissions.filter(
    (permission) => configuration.buttonIdByPermission[permission],
  );
  const nextIds = new Set(selectedIds.filter((id) => navigationSet.has(id)));
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
