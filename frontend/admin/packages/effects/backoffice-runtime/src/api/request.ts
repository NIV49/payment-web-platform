/**
 * 该文件可自行根据业务逻辑进行调整
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import {
  authenticateResponseInterceptor,
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
  RequestClient,
} from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { useAuthStore } from '@payment/backoffice-runtime/store';
import { message } from 'antdv-next';

import { refreshTokenApi } from './core';
import { resolveApiErrorMessage } from './error-contract';
import { COOKIE_SESSION_MARKER, formatSessionAuthorization } from './session';

const REQUEST_PROOF_HEADER = 'X-CSRF-Token';
const MUTATING_METHODS = new Set(['DELETE', 'PATCH', 'POST', 'PUT']);
const PROOF_EXEMPT_PATHS = new Set(['/auth/login', '/auth/oidc/handoff']);
let cachedRequestProof: null | string = null;
let pendingRequestProof: null | Promise<string> = null;
let requestProofGeneration = 0;

const { apiURL } = useAppConfig(
  { VITE_GLOB_API_URL: import.meta.env.VITE_GLOB_API_URL },
  import.meta.env.PROD,
);

function createRequestClient(
  baseURL: string,
  options?: RequestClientOptions,
  enableSessionRecovery = true,
) {
  const client = new RequestClient({
    ...options,
    baseURL,
    withCredentials: true,
  });

  /**
   * 重新认证逻辑
   */
  async function doReAuthenticate() {
    console.warn('Access token or refresh token is invalid or expired. ');
    resetSessionRequestProof();
    const accessStore = useAccessStore();
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);
    if (
      preferences.app.loginExpiredMode === 'modal' &&
      accessStore.isAccessChecked
    ) {
      accessStore.setLoginExpired(true);
    } else {
      await authStore.logout();
    }
  }

  /**
   * 刷新token逻辑
   */
  async function doRefreshToken() {
    const accessStore = useAccessStore();
    const resp = await refreshTokenApi();
    const newToken = resp.data;
    if (newToken !== COOKIE_SESSION_MARKER) {
      throw new Error('Invalid cookie-session refresh response');
    }
    accessStore.setAccessToken(COOKIE_SESSION_MARKER);
    return COOKIE_SESSION_MARKER;
  }

  // 请求头处理
  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();

      if (requiresRequestProof(config.method, config.url)) {
        config.headers[REQUEST_PROOF_HEADER] = await sessionRequestProof();
      } else {
        delete config.headers['X-CSRF-Token'];
      }

      const authorization = formatSessionAuthorization(accessStore.accessToken);
      if (authorization) {
        config.headers.Authorization = authorization;
      } else {
        delete config.headers.Authorization;
      }
      config.headers['Accept-Language'] = preferences.app.locale;
      return config;
    },
  });

  // 处理返回的响应数据格式
  client.addResponseInterceptor(
    defaultResponseInterceptor({
      codeField: 'code',
      dataField: 'data',
      successCode: 0,
    }),
  );

  if (enableSessionRecovery) {
    client.addResponseInterceptor(
      authenticateResponseInterceptor({
        client,
        doReAuthenticate,
        doRefreshToken,
        enableRefreshToken: preferences.app.enableRefreshToken,
        formatToken: formatSessionAuthorization,
      }),
    );
  }

  // 通用的错误处理,如果没有进入上面的错误处理逻辑，就会进入这里
  client.addResponseInterceptor(
    errorMessageResponseInterceptor((msg: string, error) => {
      // 这里可以根据业务进行定制,你可以拿到 error 内的信息进行定制化处理，根据不同的 code 做不同的提示，而不是直接使用 message.error 提示 msg
      const responseData = error?.response?.data;
      // 优先展示服务端的可读消息；error 是供程序分支判断的稳定机器码。
      message.error(resolveApiErrorMessage(responseData, msg));
    }),
  );

  return client;
}

function requiresRequestProof(method?: string, url?: string) {
  if (!method || !MUTATING_METHODS.has(method.toUpperCase())) return false;
  const path = new URL(url ?? '/', 'https://backoffice.invalid').pathname;
  return !PROOF_EXEMPT_PATHS.has(path);
}

async function sessionRequestProof() {
  if (cachedRequestProof) return cachedRequestProof;
  if (pendingRequestProof) return pendingRequestProof;
  const generation = requestProofGeneration;
  const loading = loadSessionRequestProof();
  pendingRequestProof = loading;
  try {
    const proof = await loading;
    if (requestProofGeneration === generation) cachedRequestProof = proof;
    return proof;
  } finally {
    if (pendingRequestProof === loading) pendingRequestProof = null;
  }
}

async function loadSessionRequestProof() {
  const response = (await baseRequestClient.get('/auth/csrf')) as unknown as {
    data?: { data?: { requestProof?: unknown } };
  };
  const proof = response.data?.data?.requestProof;
  if (typeof proof !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(proof)) {
    throw new Error('Invalid session request proof response');
  }
  return proof;
}

export function resetSessionRequestProof() {
  requestProofGeneration += 1;
  cachedRequestProof = null;
  pendingRequestProof = null;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
});

// Login/logout failures are terminal to that mutation and must not recursively
// enter the global expired-session logout flow.
export const authenticationRequestClient = createRequestClient(
  apiURL,
  { responseReturn: 'data' },
  false,
);

export const baseRequestClient = new RequestClient({
  baseURL: apiURL,
  withCredentials: true,
});
