import type { AccessModeType } from '@vben/types';

type BackendRouteIdentity = {
  children?: BackendRouteIdentity[];
  name?: unknown;
  path?: unknown;
};

const RESERVED_LOCAL_ROUTE_NAME = 'profile';
const RESERVED_LOCAL_ROUTE_PATH = '/profile';
const ROUTE_CONFLICT_ERROR =
  'Backend route conflicts with reserved local route';

export const PRODUCT_ACCESS_MODE: AccessModeType = 'mixed';

function canonicalRoutePath(path: string, parentPath: string) {
  const absolutePath = path.startsWith('/')
    ? path
    : `${parentPath.replace(/\/$/, '')}/${path}`;

  const normalized = absolutePath.replaceAll(/\/+/g, '/').toLowerCase();
  return normalized.length > 1 ? normalized.replace(/\/$/, '') : normalized;
}

export function assertNoReservedBackendRoutes(
  routes: readonly BackendRouteIdentity[],
  parentPath = '/',
): void {
  for (const route of routes) {
    const routeName =
      typeof route.name === 'string' ? route.name.toLowerCase() : '';
    const routePath =
      typeof route.path === 'string'
        ? canonicalRoutePath(route.path, parentPath)
        : parentPath;

    if (
      routeName === RESERVED_LOCAL_ROUTE_NAME ||
      routePath === RESERVED_LOCAL_ROUTE_PATH
    ) {
      throw new Error(ROUTE_CONFLICT_ERROR);
    }

    if (route.children) {
      assertNoReservedBackendRoutes(route.children, routePath);
    }
  }
}
