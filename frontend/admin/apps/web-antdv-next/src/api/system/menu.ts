import type { Recordable } from '@vben/types';

import { requestClient } from '#/api/request';

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
    type: (typeof MenuTypes)[number];
  }

  export type MenuSaveParams = Omit<
    SystemMenu,
    'children' | 'id' | 'rowVersion'
  >;

  export type MenuUpdateParams = MenuSaveParams & {
    expectedVersion: number;
  };
}

async function getMenuList() {
  return requestClient.get<SystemMenuApi.SystemMenu[]>('/system/menu/list');
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
  return requestClient.post('/system/menu', data);
}

async function updateMenu(id: string, data: SystemMenuApi.MenuUpdateParams) {
  return requestClient.put(`/system/menu/${id}`, data);
}

async function deleteMenu(id: string, expectedVersion: number) {
  return requestClient.delete(`/system/menu/${id}`, {
    params: { expectedVersion },
  });
}

export {
  createMenu,
  deleteMenu,
  getMenuList,
  isMenuNameExists,
  isMenuPathExists,
  updateMenu,
};
