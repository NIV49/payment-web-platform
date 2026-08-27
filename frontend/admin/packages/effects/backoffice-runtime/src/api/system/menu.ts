import type { Recordable } from '@vben/types';

import type {
  PlatformDirectoryContext,
  PlatformDirectoryTarget,
} from './platform-directory';

import { requestClient } from '../request';
import {
  buildPlatformDirectoryTargetParams,
  hasExactPlatformDirectoryResponseContext,
} from './platform-directory';

export namespace SystemMenuApi {
  export const BadgeVariants = [
    'default',
    'destructive',
    'primary',
    'success',
    'warning',
  ] as const;
  export const BadgeTypes = ['dot', 'normal'] as const;
  export const MenuTypes = [
    'catalog',
    'menu',
    'embedded',
    'link',
    'button',
  ] as const;

  export interface SystemMenu {
    authCode?: string;
    children?: SystemMenu[];
    component?: string;
    deletedAt?: null | string;
    id: string;
    meta?: {
      activeIcon?: string;
      activePath?: string;
      affixTab?: boolean;
      affixTabOrder?: number;
      badge?: string;
      badgeType?: (typeof BadgeTypes)[number];
      badgeVariants?: (typeof BadgeVariants)[number];
      hideChildrenInMenu?: boolean;
      hideInBreadcrumb?: boolean;
      hideInMenu?: boolean;
      hideInTab?: boolean;
      icon?: string;
      iframeSrc?: string;
      keepAlive?: boolean;
      link?: string;
      maxNumOfOpenTab?: number;
      noBasicLayout?: boolean;
      openInNewWindow?: boolean;
      order?: number;
      query?: Recordable<any>;
      title?: string;
    };
    name: string;
    path?: string;
    pid: string;
    redirect?: string;
    rowVersion: number;
    status: 0 | 1;
    systemManaged?: boolean;
    type: (typeof MenuTypes)[number];
  }

  export interface DirectoryMenu
    extends
      Omit<PlatformDirectoryContext, never>,
      Omit<SystemMenu, 'children'> {
    children?: DirectoryMenu[];
  }

  export type MenuSaveParams = Omit<
    SystemMenu,
    'children' | 'deletedAt' | 'id' | 'rowVersion' | 'systemManaged'
  >;

  export type MenuUpdateParams = MenuSaveParams & {
    expectedVersion: number;
  };
}

function filterDeletedMenuTree(
  menus: readonly SystemMenuApi.SystemMenu[],
): SystemMenuApi.SystemMenu[] {
  return menus
    .filter((menu) => !menu.deletedAt)
    .map((menu) => ({
      ...menu,
      ...(menu.children
        ? { children: filterDeletedMenuTree(menu.children) }
        : {}),
    }));
}

async function getMenuList() {
  const menus =
    await requestClient.get<SystemMenuApi.SystemMenu[]>('/system/menu/list');
  return filterDeletedMenuTree(menus);
}

async function getPlatformMenuDirectory(target: PlatformDirectoryTarget) {
  const menus = await requestClient.get<SystemMenuApi.DirectoryMenu[]>(
    '/platform/menu-directory',
    { params: buildPlatformDirectoryTargetParams(target) },
  );
  if (!hasExactPlatformDirectoryResponseContext(menus, target)) return [];
  return filterDeletedMenuTree(menus) as SystemMenuApi.DirectoryMenu[];
}

async function isMenuNameExists(
  name: string,
  id?: SystemMenuApi.SystemMenu['id'],
) {
  return requestClient.get<boolean>('/system/menu/name-exists', {
    params: { id, name },
  });
}

async function isMenuPathExists(
  path: string,
  id?: SystemMenuApi.SystemMenu['id'],
) {
  return requestClient.get<boolean>('/system/menu/path-exists', {
    params: { id, path },
  });
}

async function createMenu(data: SystemMenuApi.MenuSaveParams) {
  return requestClient.post('/system/menu', toMenuRequest(data));
}

async function updateMenu(id: string, data: SystemMenuApi.MenuUpdateParams) {
  return requestClient.put(`/system/menu/${id}`, toMenuRequest(data));
}

function toMenuRequest(
  data: SystemMenuApi.MenuSaveParams | SystemMenuApi.MenuUpdateParams,
) {
  const payload: Recordable<any> = {
    meta: data.meta,
    name: data.name,
    status: data.status,
    type: data.type,
  };
  const pid = data.pid?.trim();
  if (pid) payload.pid = pid;
  if (data.path) payload.path = data.path;
  if (data.component) payload.component = data.component;
  if (data.redirect) payload.redirect = data.redirect;
  if (data.authCode) payload.authCode = data.authCode;
  if ('expectedVersion' in data) {
    payload.expectedVersion = data.expectedVersion;
  }
  return payload;
}

async function deleteMenu(id: string, expectedVersion: number) {
  return requestClient.delete(`/system/menu/${id}`, {
    params: { expectedVersion },
  });
}

export {
  createMenu,
  deleteMenu,
  filterDeletedMenuTree,
  getMenuList,
  getPlatformMenuDirectory,
  isMenuNameExists,
  isMenuPathExists,
  updateMenu,
};
