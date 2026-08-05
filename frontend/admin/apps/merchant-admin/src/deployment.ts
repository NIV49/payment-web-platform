import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import { COMMON_BACKOFFICE_PAGE_MAP } from '@payment/backoffice-runtime';

export const deployment: BackofficeDeployment = {
  accountDomain: 'MERCHANT',
  menuPageComponents: ['/dashboard/workspace/index'],
  pageMap: COMMON_BACKOFFICE_PAGE_MAP,
  routeNames: ['MerchantDashboard', 'MerchantWorkspace'],
  routePaths: ['/dashboard', '/dashboard/workspace'],
};
