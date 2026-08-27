import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import type { ComponentRecordType } from '@vben/types';

import { COMMON_BACKOFFICE_PAGE_MAP } from '@payment/backoffice-runtime';
import { $t } from '@payment/backoffice-runtime/locales';

const platformAccessRoutes = [
  {
    component: () => import('./views/merchant/onboarding/index.vue'),
    meta: {
      activePath: '/merchant/list',
      hideInMenu: true,
      title: $t('merchant.onboarding.title'),
    },
    name: 'MerchantOnboarding',
    path: '/merchant/onboarding/:merchantId?',
  },
  {
    component: () => import('./views/merchant/detail/index.vue'),
    meta: {
      activePath: '/merchant/list',
      hideInMenu: true,
      title: $t('merchant.detail.title'),
    },
    name: 'MerchantDetail',
    path: '/merchant/detail/:merchantId',
  },
  {
    component: () => import('./views/merchant/detail/index.vue'),
    meta: {
      activePath: '/merchant/list',
      hideInMenu: true,
      title: $t('merchant.detail.reviewTitle'),
    },
    name: 'MerchantReview',
    path: '/merchant/review/:merchantId',
  },
];

const platformPageMap: ComponentRecordType = import.meta.glob([
  './views/dashboard/analytics/index.vue',
  './views/demos/antd/index.vue',
  './views/merchant/list.vue',
  './views/system/dept/list.vue',
  './views/system/dict/data/list.vue',
  './views/system/dict/list.vue',
  './views/system/menu/list.vue',
]);

export const deployment: BackofficeDeployment = {
  accessRoutes: platformAccessRoutes,
  accountDomain: 'PLATFORM',
  menuPageComponents: [
    '/dashboard/analytics/index',
    '/dashboard/workspace/index',
    '/demos/antd/index',
    '/merchant/list',
    '/system/dept/list',
    '/system/dict/data/list',
    '/system/dict/list',
    '/system/menu/list',
    '/system/role/list',
    '/system/user/list',
  ],
  pageMap: { ...COMMON_BACKOFFICE_PAGE_MAP, ...platformPageMap },
  routeNames: [
    'MerchantList',
    'MerchantManagement',
    'SystemDictionary',
    'SystemDictionaryDataIndex',
  ],
  routePaths: [
    '/dashboard',
    '/dashboard/analytics',
    '/dashboard/workspace',
    '/demos',
    '/demos/antd',
    '/merchant',
    '/merchant/list',
    '/system',
    '/system/dept',
    '/system/dict',
    '/system/dict/data',
    '/system/menu',
    '/system/role',
    '/system/user',
  ],
};
