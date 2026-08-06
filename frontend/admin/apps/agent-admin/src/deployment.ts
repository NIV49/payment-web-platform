import type { BackofficeDeployment } from '@payment/backoffice-runtime';

import {
  COMMON_BACKOFFICE_PAGE_MAP,
  SYSTEM_ADMINISTRATOR_ROUTE_ROLE,
} from '@payment/backoffice-runtime';

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
      name: 'AgentIdentityMembers',
      path: '/identity/members',
    },
  ],
  accountDomain: 'AGENT',
  menuPageComponents: ['/dashboard/workspace/index'],
  pageMap: COMMON_BACKOFFICE_PAGE_MAP,
  routeNames: ['AgentDashboard', 'AgentWorkspace'],
  routePaths: ['/dashboard', '/dashboard/workspace'],
};
