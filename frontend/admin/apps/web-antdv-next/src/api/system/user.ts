import type { Recordable } from '@vben/types';

import type { PageResult } from './types';

import { requestClient } from '#/api/request';

export namespace SystemUserApi {
  export interface SystemUser {
    createTime?: string;
    deptId: string;
    deptName?: string;
    id: string;
    name: string;
    remark?: string;
    roleIds: string[];
    roleNames?: string[];
    status: 0 | 1;
    username: string;
    userVersion: number;
  }

  export interface UserSaveParams {
    deptId: string;
    name: string;
    remark?: string;
    roleIds: string[];
    status: 0 | 1;
    username: string;
    userVersion: number;
  }

  export interface UserStatusParams {
    status: 0 | 1;
    userVersion: number;
  }

  export interface UserStatusResult {
    userVersion: number;
  }
}

async function getUserList(params: Recordable<any>) {
  return requestClient.get<PageResult<SystemUserApi.SystemUser>>(
    '/system/user/list',
    { params },
  );
}

async function createUser(data: SystemUserApi.UserSaveParams) {
  return requestClient.post('/system/user', data);
}

async function updateUser(id: string, data: SystemUserApi.UserSaveParams) {
  return requestClient.put(`/system/user/${id}`, data);
}

async function updateUserStatus(
  id: string,
  data: SystemUserApi.UserStatusParams,
) {
  return requestClient.request<SystemUserApi.UserStatusResult>(
    `/system/user/${id}/status`,
    {
      data,
      method: 'PATCH',
    },
  );
}

async function deleteUser(id: string) {
  return requestClient.delete(`/system/user/${id}`);
}

export { createUser, deleteUser, getUserList, updateUser, updateUserStatus };
