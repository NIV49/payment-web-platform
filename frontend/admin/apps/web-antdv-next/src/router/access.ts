import type {
  ComponentRecordType,
  GenerateMenuAndRoutesOptions,
} from '@vben/types';

import { generateAccessible } from '@vben/access';

import { message } from 'antdv-next';

import { getAllMenusApi } from '#/api';
import { DEPLOYMENT_PAGE_MAP } from '#/deployment-policy';
import { BasicLayout, IFrameView } from '#/layouts';
import { $t } from '#/locales';

import {
  assertValidBackendRoutes,
  PRODUCT_ACCESS_MODE,
} from './product-access';

const forbiddenComponent = () => import('#/views/_core/fallback/forbidden.vue');

async function generateAccess(options: GenerateMenuAndRoutesOptions) {
  const layoutMap: ComponentRecordType = {
    BasicLayout,
    IFrameView,
  };

  return await generateAccessible(PRODUCT_ACCESS_MODE, {
    ...options,
    fetchMenuListAsync: async () => {
      message.loading({
        content: `${$t('common.loadingMenu')}...`,
        duration: 1.5,
      });
      const menus = await getAllMenusApi();
      assertValidBackendRoutes(menus);
      return menus;
    },
    // 可以指定没有权限跳转403页面
    forbiddenComponent,
    // 如果 route.meta.menuVisibleWithForbidden = true
    layoutMap,
    pageMap: DEPLOYMENT_PAGE_MAP,
  });
}

export { generateAccess };
