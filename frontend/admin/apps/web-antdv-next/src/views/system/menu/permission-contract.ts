import type { PermissionCode } from '#/api/permission-codes';
import type { SystemMenuApi } from '#/api/system/menu';

import { PERMISSION_CODES } from '#/api/permission-codes';

import { hasPermissionDependencies } from '../permission-dependencies';

type AccessCodeChecker = (codes: string[]) => boolean;

interface MenuParentOption extends SystemMenuApi.SystemMenu {
  children?: MenuParentOption[];
  disabled?: boolean;
}

function canAppendMenuChild(menu: SystemMenuApi.SystemMenu) {
  return canManageMenu(menu) && menu.status === 1 && menu.type !== 'button';
}

function canManageMenu(menu: SystemMenuApi.SystemMenu) {
  return !menu.deletedAt && menu.systemManaged !== true;
}

function canPerformMenuAction(
  menu: SystemMenuApi.SystemMenu,
  action: PermissionCode,
  hasAccessByCodes: AccessCodeChecker,
) {
  const stateAllowed =
    action === PERMISSION_CODES.menuCreate
      ? canAppendMenuChild(menu)
      : canManageMenu(menu);
  return stateAllowed && hasPermissionDependencies([action], hasAccessByCodes);
}

function filterMenuParentOptions(
  menus: readonly SystemMenuApi.SystemMenu[],
  excludedMenuId?: string,
  currentParentId?: string,
): MenuParentOption[] {
  return menus.flatMap((menu) => {
    if (
      menu.deletedAt ||
      menu.id === excludedMenuId ||
      menu.type === 'button'
    ) {
      return [];
    }

    const children = filterMenuParentOptions(
      menu.children ?? [],
      excludedMenuId,
      currentParentId,
    );
    const selectable = canAppendMenuChild(menu);
    const pinned = menu.id === currentParentId;
    if (!selectable && !pinned && children.length === 0) return [];

    return [
      {
        ...menu,
        ...(!selectable && { disabled: true }),
        ...(menu.children ? { children } : {}),
      },
    ];
  });
}

export {
  canAppendMenuChild,
  canManageMenu,
  canPerformMenuAction,
  filterMenuParentOptions,
};
