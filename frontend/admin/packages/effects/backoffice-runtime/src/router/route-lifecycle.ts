import type { Router, RouteRecordName, RouteRecordRaw } from 'vue-router';

import { routes } from './routes';

function collectStaticRouteNames(
  routeRecords: readonly RouteRecordRaw[],
  names = new Set<RouteRecordName>(),
) {
  for (const route of routeRecords) {
    if (route.name) {
      names.add(route.name);
    }
    if (route.children) {
      collectStaticRouteNames(route.children, names);
    }
  }
  return names;
}

const STATIC_ROUTE_NAMES = collectStaticRouteNames(routes);
let productRouteGeneration = 0;
let productSessionGeneration = 0;

export function getProductSessionGeneration() {
  return productSessionGeneration;
}

export function startProductSessionGeneration() {
  productSessionGeneration += 1;
  return productSessionGeneration;
}

export function isProductSessionGenerationCurrent(generation: number) {
  return productSessionGeneration === generation;
}

export function startProductRouteGeneration() {
  productRouteGeneration += 1;
  return productRouteGeneration;
}

export function isProductRouteGenerationCurrent(generation: number) {
  return productRouteGeneration === generation;
}

export function resetProductRoutes(router: Router) {
  productRouteGeneration += 1;
  for (const route of router.getRoutes()) {
    if (
      route.name &&
      !STATIC_ROUTE_NAMES.has(route.name) &&
      router.hasRoute(route.name)
    ) {
      router.removeRoute(route.name);
    }
  }
}
