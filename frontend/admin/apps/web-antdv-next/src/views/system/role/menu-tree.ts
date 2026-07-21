import type { SystemMenuApi } from '#/api/system/menu';

export function filterNavigableMenuTree(
  menuTree: readonly SystemMenuApi.SystemMenu[],
): SystemMenuApi.SystemMenu[] {
  return menuTree
    .filter(({ type }) => type !== 'button')
    .map((menu) => ({
      ...menu,
      ...(menu.children
        ? { children: filterNavigableMenuTree(menu.children) }
        : {}),
    }));
}
