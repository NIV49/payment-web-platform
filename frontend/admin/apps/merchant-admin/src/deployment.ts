import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import type { ComponentRecordType } from '@vben/types';

import { COMMON_BACKOFFICE_PAGE_MAP } from '@payment/backoffice-runtime';

const merchantPageMap: ComponentRecordType = import.meta.glob([
  './views/merchant/profile.vue',
]);

export const deployment: BackofficeDeployment = {
  accessRoutes: [],
  accountDomain: 'MERCHANT',
  menuPageComponents: [
    '/dashboard/workspace/index',
    '/merchant/profile',
    '/system/role/list',
    '/system/user/list',
  ],
  pageMap: { ...COMMON_BACKOFFICE_PAGE_MAP, ...merchantPageMap },
  routeNames: [
    'MerchantDashboard',
    'MerchantWorkspace',
    'MerchantProfile',
    'System',
    'SystemRole',
    'SystemUser',
  ],
  routePaths: [
    '/dashboard',
    '/dashboard/workspace',
    '/merchant/profile',
    '/system',
    '/system/role',
    '/system/user',
  ],
};
