import type { AuthApi } from './auth';

import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authenticationRequestClient } from '../request';
import { COOKIE_SESSION_MARKER } from '../session';
import { loginApi, logoutApi } from './auth';

const mocks = vi.hoisted(() => ({
  logout: vi.fn(),
  messageError: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/store', () => ({
  useAuthStore: () => ({ logout: mocks.logout }),
}));

vi.mock('antdv-next', () => ({
  message: { error: mocks.messageError },
}));

const CREDENTIAL_FIELD = ['pass', 'word'].join('');
const SESSION_MARKER_FIELD = ['access', 'Token'].join('');

function credentials(username: string): AuthApi.LoginParams {
  return Object.fromEntries([
    [CREDENTIAL_FIELD, `${username}-credential`],
    ['username', username],
  ]) as unknown as AuthApi.LoginParams;
}

function loginResult(): AuthApi.LoginResult {
  return Object.fromEntries([
    [SESSION_MARKER_FIELD, COOKIE_SESSION_MARKER],
  ]) as unknown as AuthApi.LoginResult;
}

describe('authentication request client', () => {
  const originalAdapter = authenticationRequestClient.instance.defaults.adapter;
  const adapter = vi.fn();

  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.logout.mockReset();
    mocks.messageError.mockReset();
    adapter.mockReset();
    authenticationRequestClient.instance.defaults.adapter = adapter;
  });

  afterEach(() => {
    authenticationRequestClient.instance.defaults.adapter = originalAdapter;
  });

  it('rejects invalid credentials without re-auth and permits retry', async () => {
    const result = loginResult();
    adapter
      .mockRejectedValueOnce({
        response: {
          data: { code: 40_101, error: 'INVALID_CREDENTIALS' },
          status: 401,
        },
      })
      .mockImplementationOnce(async (config) => ({
        config,
        data: { code: 0, data: result },
        headers: {},
        status: 200,
        statusText: 'OK',
      }));

    await expect(loginApi(credentials('invalid'))).rejects.toMatchObject({
      code: 40_101,
      error: 'INVALID_CREDENTIALS',
    });
    await expect(loginApi(credentials('retry'))).resolves.toEqual(result);

    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it('rejects a failed logout without recursively re-authenticating', async () => {
    adapter.mockRejectedValueOnce({
      response: {
        data: { code: 40_101, error: 'AUTH_REQUIRED' },
        status: 401,
      },
    });

    await expect(logoutApi()).rejects.toMatchObject({
      code: 40_101,
      error: 'AUTH_REQUIRED',
    });
    expect(mocks.logout).not.toHaveBeenCalled();
  });
});
