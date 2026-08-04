import type { ComponentRecordType } from '@vben/types';

export const DEPLOYMENT_ACCOUNT_DOMAIN = 'PLATFORM' as const;
export const DEPLOYMENT_MENU_PAGE_COMPONENTS: readonly string[] = [
  '/dashboard/analytics/index',
  '/dashboard/workspace/index',
  '/demos/antd/index',
  '/system/dept/list',
  '/system/menu/list',
  '/system/role/list',
  '/system/user/list',
] as const;
export const DEPLOYMENT_ROUTE_NAMES: readonly string[] = [];
export const DEPLOYMENT_ROUTE_PATHS: readonly string[] = [
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
] as const;
export const DEPLOYMENT_PAGE_MAP: ComponentRecordType = import.meta.glob([
  '../views/dashboard/analytics/index.vue',
  '../views/dashboard/workspace/index.vue',
  '../views/demos/antd/index.vue',
  '../views/system/dept/list.vue',
  '../views/system/menu/list.vue',
  '../views/system/role/list.vue',
  '../views/system/user/list.vue',
]);
