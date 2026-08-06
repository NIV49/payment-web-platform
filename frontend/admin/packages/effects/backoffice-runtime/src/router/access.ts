import type {
  ComponentRecordType,
  GenerateMenuAndRoutesOptions,
} from '@vben/types';

import { generateAccessible } from '@vben/access';

import { getAllMenusApi } from '@payment/backoffice-runtime/api';
import { getBackofficeDeployment } from '@payment/backoffice-runtime/deployment-internal';
import { BasicLayout, IFrameView } from '@payment/backoffice-runtime/layouts';
import { $t } from '@payment/backoffice-runtime/locales';
import { message } from 'antdv-next';

import {
  assertValidBackendRoutes,
  PRODUCT_ACCESS_MODE,
} from './product-access';

const forbiddenComponent = () =>
  import('../views/_core/fallback/forbidden.vue');

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
    pageMap: getBackofficeDeployment().pageMap,
  });
}

export { generateAccess };
