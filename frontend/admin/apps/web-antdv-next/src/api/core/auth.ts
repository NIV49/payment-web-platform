import type { COOKIE_SESSION_MARKER } from '../session';

import {
  authenticationRequestClient,
  baseRequestClient,
  requestClient,
} from '#/api/request';

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

  export interface RefreshTokenResult {
    data: string;
    status: number;
  }
}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  return authenticationRequestClient.post<AuthApi.LoginResult>('/auth/login', {
    [LOGIN_CREDENTIAL_FIELD]: data.password,
    username: data.username,
  });
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
  return authenticationRequestClient.post('/auth/logout', null);
}

/**
 * 获取用户权限码
 */
export async function getAccessCodesApi() {
  return requestClient.get<string[]>('/auth/codes');
}
