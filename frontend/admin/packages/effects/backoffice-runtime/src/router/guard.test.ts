import type { UserInfo } from '@vben/types';

import { createMemoryHistory, createRouter } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

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
});
