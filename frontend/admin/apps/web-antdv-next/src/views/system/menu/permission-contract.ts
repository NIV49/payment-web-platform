import type { SystemMenuApi } from '#/api/system/menu';

function canAppendMenuChild(menu: SystemMenuApi.SystemMenu) {
  return menu.type !== 'button';
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

export { canAppendMenuChild, filterMenuParentOptions };
