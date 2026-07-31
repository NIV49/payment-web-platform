import type { SystemMenuApi } from '#/api/system/menu';

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
