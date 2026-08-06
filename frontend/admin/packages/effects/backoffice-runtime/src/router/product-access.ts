import type { AccessModeType } from '@vben/types';

import { getBackofficeDeployment } from '@payment/backoffice-runtime/deployment-internal';

import { accessRoutes, routes as staticRoutes } from './routes';

type BackendRouteIdentity = {
  children?: BackendRouteIdentity[];
  component?: unknown;
  meta?: {
    iframeSrc?: unknown;
    link?: unknown;
  };
  name?: unknown;
  path?: unknown;
  type?: unknown;
};

export type DeploymentRoutePolicy = {
  accountDomain: 'AGENT' | 'MERCHANT' | 'PLATFORM';
  menuPageComponents: readonly string[];
  routeNames: readonly string[];
  routePaths: readonly string[];
};

const ROUTE_CONFLICT_ERROR =
  'Backend route conflicts with reserved local route';
const ROUTE_BOUNDARY_ERROR =
  'Backend route is outside the current account-domain boundary';

export const PRODUCT_ACCESS_MODE: AccessModeType = 'mixed';

function canonicalRoutePath(path: string, parentPath: string) {
  const absolutePath = path.startsWith('/')
    ? path
    : `${parentPath.replace(/\/$/, '')}/${path}`;

  const normalized = absolutePath.replaceAll(/\/+/g, '/').toLowerCase();
  return normalized.length > 1 ? normalized.replace(/\/$/, '') : normalized;
}

function collectLocalRouteIdentities(
  routes: readonly BackendRouteIdentity[],
  parentPath = '/',
  names = new Set<string>(),
  paths = new Set<string>(),
) {
  for (const route of routes) {
    if (typeof route.name === 'string') {
      names.add(route.name.toLowerCase());
    }
    const routePath =
      typeof route.path === 'string'
        ? canonicalRoutePath(route.path, parentPath)
        : parentPath;
    paths.add(routePath);
    if (route.children) {
      collectLocalRouteIdentities(route.children, routePath, names, paths);
    }
  }
  return { names, paths };
}

const RESERVED_LOCAL_ROUTES = collectLocalRouteIdentities([
  ...staticRoutes,
  ...accessRoutes,
]);

export function assertValidBackendRoutesForPolicy(
  routes: readonly BackendRouteIdentity[],
  policy: DeploymentRoutePolicy,
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
      RESERVED_LOCAL_ROUTES.names.has(routeName) ||
      RESERVED_LOCAL_ROUTES.paths.has(routePath)
    ) {
      throw new Error(ROUTE_CONFLICT_ERROR);
    }

    const component =
      typeof route.component === 'string' ? route.component : '';
    const isCatalog = route.type === 'catalog';
    const isPage = route.type === 'menu';
    const isExternal = route.type === 'embedded' || route.type === 'link';
    const isLimitedDeployment = policy.accountDomain !== 'PLATFORM';
    const invalidComponent = isPage
      ? !policy.menuPageComponents.includes(component)
      : !isCatalog && !(isExternal && component === 'IFrameView');
    const invalidExternalNavigation =
      isLimitedDeployment &&
      (isExternal || Boolean(route.meta?.iframeSrc || route.meta?.link));
    const invalidPath =
      isLimitedDeployment && !policy.routePaths.includes(routePath);
    const invalidLimitedName =
      isLimitedDeployment &&
      !policy.routeNames.some((name) => name.toLowerCase() === routeName);

    if (
      invalidComponent ||
      invalidExternalNavigation ||
      invalidPath ||
      invalidLimitedName
    ) {
      throw new Error(ROUTE_BOUNDARY_ERROR);
    }

    if (route.children) {
      assertValidBackendRoutesForPolicy(route.children, policy, routePath);
    }
  }
}

export function assertValidBackendRoutes(
  routes: readonly BackendRouteIdentity[],
  parentPath = '/',
) {
  assertValidBackendRoutesForPolicy(
    routes,
    getBackofficeDeployment(),
    parentPath,
  );
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
      RESERVED_LOCAL_ROUTES.names.has(routeName) ||
      RESERVED_LOCAL_ROUTES.paths.has(routePath)
    ) {
      throw new Error(ROUTE_CONFLICT_ERROR);
    }

    if (route.children) {
      assertNoReservedBackendRoutes(route.children, routePath);
    }
  }
}
