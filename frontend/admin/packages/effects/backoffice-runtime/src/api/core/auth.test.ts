import type { AuthApi } from './auth';

import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  authenticationRequestClient,
  baseRequestClient,
  requestClient,
  resetSessionRequestProof,
} from '../request';
import { COOKIE_SESSION_MARKER } from '../session';
import { loginApi, logoutApi, oidcHandoffApi } from './auth';

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
  const originalBaseAdapter = baseRequestClient.instance.defaults.adapter;
  const originalRequestAdapter = requestClient.instance.defaults.adapter;
  const adapter = vi.fn();
  const baseAdapter = vi.fn();
  const requestAdapter = vi.fn();

  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.logout.mockReset();
    mocks.messageError.mockReset();
    adapter.mockReset();
    baseAdapter.mockReset();
    requestAdapter.mockReset();
    resetSessionRequestProof();
    authenticationRequestClient.instance.defaults.adapter = adapter;
    baseRequestClient.instance.defaults.adapter = baseAdapter;
    requestClient.instance.defaults.adapter = requestAdapter;
  });

  afterEach(() => {
    authenticationRequestClient.instance.defaults.adapter = originalAdapter;
    baseRequestClient.instance.defaults.adapter = originalBaseAdapter;
    requestClient.instance.defaults.adapter = originalRequestAdapter;
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
    baseAdapter.mockResolvedValueOnce({
      config: {},
      data: { code: 0, data: { requestProof: 'a'.repeat(43) } },
      headers: {},
      status: 200,
      statusText: 'OK',
    });
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
    expect(adapter.mock.calls[0]?.[0]?.headers?.['X-CSRF-Token']).toBe(
      'a'.repeat(43),
    );
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it('redeems an opaque oidc handoff without a pre-existing request proof', async () => {
    const result = loginResult();
    adapter.mockImplementationOnce(async (config) => ({
      config,
      data: { code: 0, data: result },
      headers: {},
      status: 200,
      statusText: 'OK',
    }));

    await expect(oidcHandoffApi('handoff-value')).resolves.toEqual(result);

    expect(adapter.mock.calls[0]?.[0]).toMatchObject({
      data: JSON.stringify({ handoff: 'handoff-value' }),
      url: '/auth/oidc/handoff',
    });
    expect(
      adapter.mock.calls[0]?.[0]?.headers?.['X-CSRF-Token'],
    ).toBeUndefined();
  });

  it('single-flights the session proof and attaches it to browser mutations', async () => {
    let releaseProof!: () => void;
    const proofReady = new Promise<void>((resolve) => {
      releaseProof = resolve;
    });
    baseAdapter.mockImplementationOnce(async (config) => {
      await proofReady;
      return {
        config,
        data: { code: 0, data: { requestProof: 'b'.repeat(43) } },
        headers: {},
        status: 200,
        statusText: 'OK',
      };
    });
    requestAdapter.mockImplementation(async (config) => ({
      config,
      data: { code: 0, data: null },
      headers: {},
      status: 200,
      statusText: 'OK',
    }));

    const create = requestClient.post('/system/role', {});
    const update = requestClient.put('/system/role/1', {});
    await vi.waitFor(() => expect(baseAdapter).toHaveBeenCalledOnce());
    releaseProof();
    await Promise.all([create, update]);

    expect(baseAdapter).toHaveBeenCalledOnce();
    for (const [config] of requestAdapter.mock.calls) {
      expect(config.headers['X-CSRF-Token']).toBe('b'.repeat(43));
    }
  });
});
