import type { COOKIE_SESSION_MARKER } from '../session';

import {
  authenticationRequestClient,
  baseRequestClient,
  requestClient,
  resetSessionRequestProof,
} from '@payment/backoffice-runtime/api/request';

export const LOGIN_CREDENTIAL_FIELD = 'password' as const;

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password: string;
    username: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: typeof COOKIE_SESSION_MARKER;
  }

  export interface LogoutResult {
    logoutUrl: string;
  }

  export interface StepUpStartResult {
    redirectUrl: string;
  }

  export interface StepUpResult {
    stepUpAt: string;
  }

  export interface RefreshTokenResult {
    data: string;
    status: number;
  }
}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  resetSessionRequestProof();
  return authenticationRequestClient.post<AuthApi.LoginResult>('/auth/login', {
    [LOGIN_CREDENTIAL_FIELD]: data.password,
    username: data.username,
  });
}

export async function oidcHandoffApi(handoff: string) {
  resetSessionRequestProof();
  return authenticationRequestClient.post<AuthApi.LoginResult>(
    '/auth/oidc/handoff',
    { handoff },
  );
}

export async function oidcStepUpStartApi() {
  return requestClient.post<AuthApi.StepUpStartResult>(
    '/auth/oidc/step-up/start',
    null,
  );
}

export async function oidcStepUpHandoffApi(handoff: string) {
  return requestClient.post<AuthApi.StepUpResult>(
    '/auth/oidc/step-up/handoff',
    { handoff },
  );
}

/**
 * 刷新accessToken
 */
export async function refreshTokenApi() {
  return baseRequestClient.post<AuthApi.RefreshTokenResult>(
    '/auth/refresh',
    null,
  );
}

/**
 * 退出登录
 */
export async function logoutApi() {
  try {
    return await authenticationRequestClient.post<AuthApi.LogoutResult | null>(
      '/auth/logout',
      null,
    );
  } finally {
    resetSessionRequestProof();
  }
}

/**
 * 获取用户权限码
 */
export async function getAccessCodesApi() {
  return requestClient.get<string[]>('/auth/codes');
}
