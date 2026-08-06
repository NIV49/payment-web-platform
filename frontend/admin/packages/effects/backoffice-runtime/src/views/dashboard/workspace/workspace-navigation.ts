import type { WorkbenchQuickNavItem } from '@vben/common-ui';

interface WorkspaceQuickNavItem extends WorkbenchQuickNavItem {
  routeName: string;
}

export const WORKSPACE_QUICK_NAV_ITEMS: WorkspaceQuickNavItem[] = [
  {
    color: '#1fdaca',
    icon: 'ion:home-outline',
    routeName: 'Dashboard',
    title: '首页',
    url: '/',
  },
  {
    color: '#bf0c2c',
    icon: 'ion:grid-outline',
    routeName: 'Workspace',
    title: '仪表盘',
    url: '/dashboard/workspace',
  },
  {
    color: '#3fb27f',
    icon: 'ion:settings-outline',
    routeName: 'SystemMenu',
    title: '系统管理',
    url: '/system/menu',
  },
  {
    color: '#4daf1bc9',
    icon: 'ion:key-outline',
    routeName: 'SystemRole',
    title: '权限管理',
    url: '/system/role',
  },
  {
    color: '#00d8ff',
    icon: 'ion:bar-chart-outline',
    routeName: 'Analytics',
    title: '图表',
    url: '/dashboard/analytics',
  },
];

export function getAccessibleWorkspaceQuickNavItems(
  hasRoute: (routeName: string) => boolean,
): WorkbenchQuickNavItem[] {
  return WORKSPACE_QUICK_NAV_ITEMS.filter(({ routeName }) =>
    hasRoute(routeName),
  ).map(({ routeName: _routeName, ...item }) => item);
}
