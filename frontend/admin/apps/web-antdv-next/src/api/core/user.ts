import type { CurrentUserInfo } from './user-contract';

import { requestClient } from '#/api/request';

import { mapCurrentUserResponse } from './user-contract';

/**
 * 获取用户信息
 */
export async function getUserInfoApi(): Promise<CurrentUserInfo> {
  const response = await requestClient.get<unknown>('/user/info');
  return mapCurrentUserResponse(response);
}
