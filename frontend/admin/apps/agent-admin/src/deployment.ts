import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import { COMMON_BACKOFFICE_PAGE_MAP } from '@payment/backoffice-runtime';

export const deployment: BackofficeDeployment = {
  accessRoutes: [],
  accountDomain: 'AGENT',
  menuPageComponents: [
    '/dashboard/workspace/index',
    '/system/role/list',
    '/system/user/list',
  ],
  pageMap: { ...COMMON_BACKOFFICE_PAGE_MAP },
  routeNames: [
    'AgentDashboard',
    'AgentWorkspace',
    'System',
    'SystemRole',
    'SystemUser',
  ],
  routePaths: [
    '/dashboard',
    '/dashboard/workspace',
    '/system',
    '/system/role',
    '/system/user',
  ],
};
