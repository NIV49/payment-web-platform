import type { SystemMenuApi } from '#/api/system/menu';

function canAppendMenuChild(menu: SystemMenuApi.SystemMenu) {
  return canManageMenu(menu) && menu.status === 1 && menu.type !== 'button';
}

function canManageMenu(menu: SystemMenuApi.SystemMenu) {
  return !menu.deletedAt && menu.systemManaged !== true;
}

function filterMenuParentOptions(
  menus: readonly SystemMenuApi.SystemMenu[],
  excludedMenuId?: string,
): SystemMenuApi.SystemMenu[] {
  return menus
    .filter((menu) => canAppendMenuChild(menu) && menu.id !== excludedMenuId)
    .map((menu) => ({
      ...menu,
      ...(menu.children
        ? { children: filterMenuParentOptions(menu.children, excludedMenuId) }
        : {}),
    }));
}

export { canAppendMenuChild, canManageMenu, filterMenuParentOptions };
