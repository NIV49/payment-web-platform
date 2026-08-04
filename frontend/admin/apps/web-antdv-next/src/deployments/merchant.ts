import type { ComponentRecordType } from '@vben/types';

export const DEPLOYMENT_ACCOUNT_DOMAIN = 'MERCHANT' as const;
export const DEPLOYMENT_MENU_PAGE_COMPONENTS: readonly string[] = [
  '/dashboard/workspace/index',
] as const;
export const DEPLOYMENT_ROUTE_NAMES: readonly string[] = [
  'MerchantDashboard',
  'MerchantWorkspace',
] as const;
export const DEPLOYMENT_ROUTE_PATHS: readonly string[] = [
  '/dashboard',
  '/dashboard/workspace',
] as const;
export const DEPLOYMENT_PAGE_MAP: ComponentRecordType = import.meta.glob(
  '../views/dashboard/workspace/index.vue',
);
