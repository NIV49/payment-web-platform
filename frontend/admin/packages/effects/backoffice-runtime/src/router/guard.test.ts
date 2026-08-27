import type { UserInfo } from '@vben/types';

import {
  createMemoryHistory,
  createRouter,
  isNavigationFailure,
  NavigationFailureType,
} from 'vue-router';

import { useAccessStore, useTabbarStore, useUserStore } from '@vben/stores';

import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createRouterGuard } from './guard';
import {
  resetProductRoutes,
  startProductSessionGeneration,
} from './route-lifecycle';
import { routes } from './routes';

const mocks = vi.hoisted(() => ({
  fetchUserInfo: vi.fn(),
  generateAccess: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/store', () => ({
  useAuthStore: () => ({ fetchUserInfo: mocks.fetchUserInfo }),
}));

vi.mock('./access', () => ({
  generateAccess: mocks.generateAccess,
}));

describe('access guard route generation', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.fetchUserInfo.mockReset();
    mocks.generateAccess.mockReset();
  });

  it('does not commit stale access state after route reset', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    const accessStore = useAccessStore();
    const userStore = useUserStore();
    accessStore.setAccessToken('cookie-session');
    const userInfo: UserInfo = {
      avatar: '',
      desc: '',
      homePath: '/dashboard',
      realName: 'Previous User',
      roles: [],
      token: '',
      userId: 'previous-user',
      username: 'previous-user',
    };
    userStore.setUserInfo(userInfo);

    let resolveGeneration!: (value: {
      accessibleMenus: [];
      accessibleRoutes: [];
    }) => void;
    mocks.generateAccess.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveGeneration = resolve;
        }),
    );
    createRouterGuard(router);

    const navigation = router.push('/previous-user-route');
    await vi.waitFor(() => expect(mocks.generateAccess).toHaveBeenCalledOnce());
    resetProductRoutes(router);
    resolveGeneration({ accessibleMenus: [], accessibleRoutes: [] });
    await navigation;

    expect(accessStore.isAccessChecked).toBe(false);
    expect(accessStore.accessMenus).toEqual([]);
    expect(accessStore.accessRoutes).toEqual([]);
  });

  it('removes persisted tabs that are unavailable after access routes are generated', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        ...routes,
        {
          component: { render: () => null },
          name: 'GuardTrigger',
          path: '/guard-trigger',
        },
      ],
    });
    const accessStore = useAccessStore();
    const tabbarStore = useTabbarStore();
    const userStore = useUserStore();
    accessStore.setAccessToken('cookie-session');
    userStore.setUserInfo({
      avatar: '',
      desc: '',
      homePath: '/system/user',
      realName: 'Current User',
      roles: [],
      token: '',
      userId: 'current-user',
      username: 'current-user',
    });
    tabbarStore.tabs = [
      {
        fullPath: '/system/user?page=2',
        hash: '',
        key: '/system/user?page=2',
        matched: [],
        meta: { title: 'system.user.title' },
        name: 'SystemUser',
        params: {},
        path: '/system/user',
        query: { page: '2' },
        redirectedFrom: undefined,
      },
      {
        fullPath: '/system/dict/data?dictType=SYS_COMMON_STATUS',
        hash: '',
        key: '/system/dict/data?dictType=SYS_COMMON_STATUS',
        matched: [],
        meta: { affixTab: true, title: 'system.dict.data.title' },
        name: 'SystemDictionaryDataIndex',
        params: {},
        path: '/system/dict/data',
        query: { dictType: 'SYS_COMMON_STATUS' },
        redirectedFrom: undefined,
      },
      {
        fullPath: '/retired-feature/report',
        hash: '',
        key: '/retired-feature/report',
        matched: [],
        meta: { title: 'retired.feature.title' },
        name: 'RetiredFeatureReport',
        params: {},
        path: '/retired-feature/report',
        query: {},
        redirectedFrom: undefined,
      },
    ];
    mocks.generateAccess.mockImplementation(async () => {
      const systemUserRoute = {
        component: { render: () => null },
        meta: { title: 'system.user.title' },
        name: 'SystemUser',
        path: '/system/user',
      };
      router.addRoute('Root', systemUserRoute);
      return {
        accessibleMenus: [],
        accessibleRoutes: [systemUserRoute],
      };
    });
    createRouterGuard(router);

    await router.push('/guard-trigger');

    expect(tabbarStore.tabs).toHaveLength(1);
    expect(tabbarStore.tabs[0]).toMatchObject({
      fullPath: '/system/user?page=2',
      name: 'SystemUser',
      query: { page: '2' },
    });
  });

  it('does not request menus after delayed user info belongs to an old session', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    const accessStore = useAccessStore();
    accessStore.setAccessToken('cookie-session');
    let resolveUserInfo!: (value: UserInfo) => void;
    mocks.fetchUserInfo.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveUserInfo = resolve;
        }),
    );
    mocks.generateAccess.mockResolvedValue({
      accessibleMenus: [],
      accessibleRoutes: [],
    });
    createRouterGuard(router);

    const navigation = router.push('/previous-user-route');
    await vi.waitFor(() => expect(mocks.fetchUserInfo).toHaveBeenCalledOnce());
    startProductSessionGeneration();
    resolveUserInfo({
      avatar: '',
      desc: '',
      homePath: '/dashboard',
      realName: 'Previous User',
      roles: [],
      token: '',
      userId: 'previous-user',
      username: 'previous-user',
    });
    await navigation;

    expect(mocks.generateAccess).not.toHaveBeenCalled();
    expect(accessStore.isAccessChecked).toBe(false);
  });

  it('cancels stale navigation after session recovery clears the login marker', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    const accessStore = useAccessStore();
    accessStore.setAccessToken('cookie-session');
    mocks.fetchUserInfo.mockImplementation(async () => {
      accessStore.setAccessToken(null);
      startProductSessionGeneration();
      throw new Error('Session is invalid or expired');
    });
    createRouterGuard(router);

    const navigation = await router.push('/revoked-session-route');

    expect(isNavigationFailure(navigation, NavigationFailureType.aborted)).toBe(
      true,
    );
    expect(mocks.generateAccess).not.toHaveBeenCalled();
    expect(accessStore.isAccessChecked).toBe(false);
  });
});
