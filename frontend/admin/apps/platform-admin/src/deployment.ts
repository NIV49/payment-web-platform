import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import type { ComponentRecordType } from '@vben/types';

import {
  COMMON_BACKOFFICE_PAGE_MAP,
  SYSTEM_ADMINISTRATOR_ROUTE_ROLE,
} from '@payment/backoffice-runtime';

const platformPageMap: ComponentRecordType = import.meta.glob([
  './views/dashboard/analytics/index.vue',
  './views/demos/antd/index.vue',
  './views/system/dept/list.vue',
  './views/system/menu/list.vue',
  './views/system/role/list.vue',
  './views/system/user/list.vue',
]);

export const deployment: BackofficeDeployment = {
  accessRoutes: [
    {
      component: () => import('./views/identity/members/index.vue'),
      meta: {
        authority: [SYSTEM_ADMINISTRATOR_ROUTE_ROLE],
        icon: 'lucide:users',
        order: 900,
        title: 'identity.members.title',
      },
      name: 'PlatformIdentityMembers',
      path: '/identity/members',
    },
    {
      component: () => import('./views/identity/tenant-bootstrap/index.vue'),
      meta: {
        authority: [SYSTEM_ADMINISTRATOR_ROUTE_ROLE],
        icon: 'lucide:building-2',
        order: 910,
        title: 'identity.bootstrap.title',
      },
      name: 'PlatformTenantBootstrap',
      path: '/identity/tenant-bootstrap',
    },
  ],
  accountDomain: 'PLATFORM',
  menuPageComponents: [
    '/dashboard/analytics/index',
    '/dashboard/workspace/index',
    '/demos/antd/index',
    '/system/dept/list',
    '/system/menu/list',
    '/system/role/list',
    '/system/user/list',
  ],
  pageMap: { ...COMMON_BACKOFFICE_PAGE_MAP, ...platformPageMap },
  routeNames: [],
  routePaths: [
    '/dashboard',
    '/dashboard/analytics',
    '/dashboard/workspace',
    '/demos',
    '/demos/antd',
    '/system',
    '/system/dept',
    '/system/menu',
    '/system/role',
    '/system/user',
  ],
};
