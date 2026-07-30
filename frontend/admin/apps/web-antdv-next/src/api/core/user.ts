import type { UserInfo } from '@vben/types';

import { requestClient } from '#/api/request';

import { mapCurrentUserResponse } from './user-contract';

/**
 * 获取用户信息
 */
export async function getUserInfoApi(): Promise<UserInfo> {
  const response = await requestClient.get<unknown>('/user/info');
  return mapCurrentUserResponse(response);
}
