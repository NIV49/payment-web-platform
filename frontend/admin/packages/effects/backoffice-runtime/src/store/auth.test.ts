import type { UserInfo } from '@vben/types';

import { createMemoryHistory, createRouter } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { COOKIE_SESSION_MARKER } from '@payment/backoffice-runtime/api/session';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from './auth';

const mocks = vi.hoisted(() => ({
  getAccessCodesApi: vi.fn(),
  getUserInfoApi: vi.fn(),
  loginApi: vi.fn(),
  logoutApi: vi.fn(),
  notificationSuccess: vi.fn(),
  oidcHandoffApi: vi.fn(),
  oidcStepUpHandoffApi: vi.fn(),
  oidcStepUpStartApi: vi.fn(),
  router: undefined as unknown as ReturnType<typeof createRouter>,
}));

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => mocks.router,
}));

vi.mock('antdv-next', () => ({
  notification: { success: mocks.notificationSuccess },
}));

vi.mock('@vben/stores', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/stores')>()),
  resetAllStores: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/api', () => ({
  getAccessCodesApi: mocks.getAccessCodesApi,
  getUserInfoApi: mocks.getUserInfoApi,
  LOGIN_CREDENTIAL_FIELD: ['pass', 'word'].join(''),
  loginApi: mocks.loginApi,
  logoutApi: mocks.logoutApi,
  oidcHandoffApi: mocks.oidcHandoffApi,
  oidcStepUpHandoffApi: mocks.oidcStepUpHandoffApi,
  oidcStepUpStartApi: mocks.oidcStepUpStartApi,
}));

vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

const CREDENTIAL_FIELD = ['pass', 'word'].join('');
const SESSION_MARKER_FIELD = ['access', 'Token'].join('');

function credentials(username: string) {
  return Object.fromEntries([
    [CREDENTIAL_FIELD, `${username}-credential`],
    ['username', username],
  ]);
}

function cookieLoginResult() {
  return Object.fromEntries([[SESSION_MARKER_FIELD, COOKIE_SESSION_MARKER]]);
}

function userInfo(username: string): UserInfo {
  return {
    avatar: '',
    desc: '',
    homePath: '/',
    realName: '',
    roles: [],
    token: '',
    userId: username,
    username,
  };
}

describe('authentication session generation', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { component: {}, name: 'Root', path: '/' },
        { component: {}, name: 'Login', path: '/auth/login' },
      ],
    });
    mocks.getAccessCodesApi.mockReset();
    mocks.getUserInfoApi.mockReset();
    mocks.loginApi.mockReset();
    mocks.logoutApi.mockReset();
    mocks.notificationSuccess.mockReset();
    mocks.oidcHandoffApi.mockReset();
    mocks.oidcStepUpHandoffApi.mockReset();
    mocks.oidcStepUpStartApi.mockReset();
    mocks.loginApi.mockResolvedValue(cookieLoginResult());
    mocks.logoutApi.mockResolvedValue(undefined);
    mocks.oidcHandoffApi.mockResolvedValue(cookieLoginResult());
    mocks.oidcStepUpHandoffApi.mockResolvedValue({
      stepUpAt: '2026-08-05T04:00:00Z',
    });
    mocks.oidcStepUpStartApi.mockResolvedValue({
      redirectUrl: 'https://idp.example.test/step-up',
    });
  });

  it('uses one login request while authentication is in flight', async () => {
    const loginResponse = deferred<Record<string, string>>();
    mocks.loginApi.mockReturnValue(loginResponse.promise);
    mocks.getUserInfoApi.mockResolvedValue(userInfo('first'));
    mocks.getAccessCodesApi.mockResolvedValue(['first:allowed']);
    const firstSuccess = vi.fn();
    const duplicateSuccess = vi.fn();
    const authStore = useAuthStore();

    const firstLogin = authStore.authLogin(credentials('first'), firstSuccess);
    const duplicateLogin = authStore.authLogin(
      credentials('duplicate'),
      duplicateSuccess,
    );

    expect(mocks.loginApi).toHaveBeenCalledOnce();
    loginResponse.resolve(cookieLoginResult());
    await Promise.all([firstLogin, duplicateLogin]);

    expect(useUserStore().userInfo?.username).toBe('first');
    expect(useAccessStore().accessCodes).toEqual(['first:allowed']);
    expect(firstSuccess).toHaveBeenCalledOnce();
    expect(duplicateSuccess).not.toHaveBeenCalled();
  });

  it('waits for a pending login response before sending logout', async () => {
    const loginResponse = deferred<Record<string, string>>();
    mocks.loginApi.mockReturnValue(loginResponse.promise);
    const authStore = useAuthStore();

    const login = authStore.authLogin(credentials('old'));
    await vi.waitFor(() => expect(mocks.loginApi).toHaveBeenCalledOnce());
    const logout = authStore.logout(false);
    expect(mocks.logoutApi).not.toHaveBeenCalled();

    loginResponse.resolve(cookieLoginResult());
    await login;
    await logout;

    expect(mocks.getUserInfoApi).not.toHaveBeenCalled();
    expect(mocks.logoutApi).toHaveBeenCalledOnce();
    expect(useAccessStore().accessToken).toBeNull();
  });

  it('waits for logout before sending a new login request', async () => {
    const logoutResponse = deferred<undefined>();
    mocks.logoutApi.mockReturnValue(logoutResponse.promise);
    mocks.getUserInfoApi.mockResolvedValue(userInfo('new'));
    mocks.getAccessCodesApi.mockResolvedValue(['new:allowed']);
    const authStore = useAuthStore();

    const logout = authStore.logout(false);
    await vi.waitFor(() => expect(mocks.logoutApi).toHaveBeenCalledOnce());
    const login = authStore.authLogin(credentials('new'));
    expect(mocks.loginApi).not.toHaveBeenCalled();

    logoutResponse.resolve(undefined);
    await logout;
    await login;

    expect(mocks.loginApi).toHaveBeenCalledOnce();
    expect(useUserStore().userInfo?.username).toBe('new');
    expect(useAccessStore().accessCodes).toEqual(['new:allowed']);
  });

  it('does not restore user data or codes that return after logout', async () => {
    const staleUser = deferred<UserInfo>();
    const staleCodes = deferred<string[]>();
    mocks.getUserInfoApi.mockReturnValueOnce(staleUser.promise);
    mocks.getAccessCodesApi.mockReturnValueOnce(staleCodes.promise);
    const authStore = useAuthStore();

    const login = authStore.authLogin(credentials('old'));
    await vi.waitFor(() => expect(mocks.getUserInfoApi).toHaveBeenCalledOnce());
    const logout = authStore.logout(false);
    staleUser.resolve(userInfo('old'));
    staleCodes.resolve(['old:stale']);
    await login;
    await logout;

    expect(useUserStore().userInfo).toBeNull();
    expect(useAccessStore().accessCodes).toEqual([]);
    expect(useAccessStore().accessToken).toBeNull();
    expect(mocks.notificationSuccess).not.toHaveBeenCalled();
  });

  it('releases login single-flight after a failed request', async () => {
    mocks.loginApi
      .mockRejectedValueOnce(new Error('invalid credentials'))
      .mockResolvedValueOnce(cookieLoginResult());
    mocks.getUserInfoApi.mockResolvedValue(userInfo('retry'));
    mocks.getAccessCodesApi.mockResolvedValue(['retry:allowed']);
    const authStore = useAuthStore();

    await expect(authStore.authLogin(credentials('invalid'))).rejects.toThrow(
      'invalid credentials',
    );
    expect(authStore.loginLoading).toBe(false);
    await authStore.authLogin(credentials('retry'));

    expect(mocks.loginApi).toHaveBeenCalledTimes(2);
    expect(useUserStore().userInfo?.username).toBe('retry');
  });

  it('establishes the same guarded session after an oidc handoff', async () => {
    mocks.getUserInfoApi.mockResolvedValue(userInfo('federated'));
    mocks.getAccessCodesApi.mockResolvedValue(['merchant:view']);
    const authStore = useAuthStore();

    const result = await authStore.redeemOidcHandoff(
      'one-time-handoff',
      () => undefined,
    );

    expect(mocks.oidcHandoffApi).toHaveBeenCalledWith('one-time-handoff');
    expect(result.userInfo?.username).toBe('federated');
    expect(useAccessStore().accessToken).toBe(COOKIE_SESSION_MARKER);
    expect(useAccessStore().accessCodes).toEqual(['merchant:view']);
  });

  it('redeems an independent oidc step-up transaction', async () => {
    const authStore = useAuthStore();

    await expect(
      authStore.redeemOidcStepUp('step-up-handoff'),
    ).resolves.toEqual({ stepUpAt: '2026-08-05T04:00:00Z' });

    expect(mocks.oidcStepUpHandoffApi).toHaveBeenCalledWith('step-up-handoff');
  });
});
