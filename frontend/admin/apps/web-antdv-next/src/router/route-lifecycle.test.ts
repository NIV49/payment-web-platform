import type { RouteRecordRaw } from 'vue-router';

import type { RouteRecordStringComponent } from '@vben/types';

import { createMemoryHistory, createRouter } from 'vue-router';

import { generateAccessible } from '@vben/access';

import { describe, expect, it } from 'vitest';

import { assertNoReservedBackendRoutes } from './product-access';
import {
  isProductRouteGenerationCurrent,
  resetProductRoutes,
  startProductRouteGeneration,
} from './route-lifecycle';
import { routes } from './routes';

describe('product route lifecycle', () => {
  it.each([
    { name: 'Login', path: '/remote-login' },
    { name: 'Authentication', path: '/remote-authentication' },
    { name: 'Root', path: '/remote-root' },
    { name: 'RemoteLogin', path: '/auth/login' },
    { name: 'RemoteAuthentication', path: '/auth' },
    { name: 'RemoteRoot', path: '/' },
  ])(
    'rejects backend route $name at $path without mutating the real router',
    (backendRoute) => {
      const router = createRouter({
        history: createMemoryHistory(),
        routes: routes.map((route) => ({ ...route })) as RouteRecordRaw[],
      });

      expect(() => assertNoReservedBackendRoutes([backendRoute])).toThrow(
        'Backend route conflicts with reserved local route',
      );
      expect(router.resolve('/auth/login').name).toBe('Login');
      expect(router.resolve('/').name).toBe('Root');
    },
  );

  it('removes generated user routes without shadowing the core login route', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: routes.map((route) => ({ ...route })) as RouteRecordRaw[],
    });
    const originalLogin = router.resolve('/auth/login');
    expect(originalLogin.name).toBe('Login');

    await generateAccessible('frontend', {
      router,
      routes: [
        {
          component: { render: () => null },
          name: 'PreviousUserRoute',
          path: '/previous-user-route',
        },
      ],
    });
    expect(router.resolve('/previous-user-route').name).toBe(
      'PreviousUserRoute',
    );

    resetProductRoutes(router);

    expect(router.hasRoute('PreviousUserRoute')).toBe(false);
    expect(router.resolve('/previous-user-route').name).toBe(
      'FallbackNotFound',
    );
    expect(router.resolve('/auth/login').name).toBe('Login');
    expect(router.resolve('/auth/login').matched.at(-1)?.name).toBe(
      originalLogin.matched.at(-1)?.name,
    );
  });

  it('does not attach a delayed backend route after logout resets the router', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: routes.map((route) => ({ ...route })) as RouteRecordRaw[],
    });
    let resolveMenus!: (menus: RouteRecordStringComponent[]) => void;
    const delayedMenus = new Promise<RouteRecordStringComponent[]>(
      (resolve) => {
        resolveMenus = resolve;
      },
    );
    const generation = startProductRouteGeneration();

    const pendingGeneration = generateAccessible('backend', {
      canCommitRoutes: () => isProductRouteGenerationCurrent(generation),
      fetchMenuListAsync: () => delayedMenus,
      pageMap: {
        '/previous-user-route.vue': async () => ({ render: () => null }),
      },
      router,
      routes: [],
    });

    resetProductRoutes(router);
    resolveMenus([
      {
        component: '/previous-user-route',
        name: 'PreviousUserRoute',
        path: '/previous-user-route',
      },
    ]);
    await pendingGeneration;

    expect(router.hasRoute('PreviousUserRoute')).toBe(false);
    expect(router.resolve('/previous-user-route').name).toBe(
      'FallbackNotFound',
    );
    expect(router.resolve('/auth/login').name).toBe('Login');
  });
});
