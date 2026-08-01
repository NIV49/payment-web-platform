import type { PageResult } from './types';

import { requestClient } from '#/api/request';

export namespace SystemUserApi {
  export type IdentityStatus =
    | 'ACTIVE'
    | 'DISABLED'
    | 'LOCKED'
    | 'PENDING_ACTIVATION';

  export interface SystemUser {
    credentialVersion: number;
    createTime?: string;
    deptId: string;
    deptName?: string;
    id: string;
    identityVersion: number;
    identityStatus: IdentityStatus;
    name: string;
    remark?: string;
    roleIds: string[];
    roleNames?: string[];
    status: 0 | 1;
    username: string;
    userVersion: number;
  }

  export interface UserCreateParams {
    deptId: string;
    name: string;
    remark?: string;
    roleIds: string[];
    status: 0 | 1;
    username: string;
  }

  export interface MembershipUpdateParams {
    deptId: string;
    roleIds: string[];
    status: 0 | 1;
    userVersion: number;
  }

  export interface SystemAdministratorUserUpdateParams extends MembershipUpdateParams {
    credentialVersion: number;
    identityVersion: number;
    name: string;
    remark?: string;
    username: string;
  }

  export type UserUpdateParams =
    | MembershipUpdateParams
    | SystemAdministratorUserUpdateParams;

  export interface UserListQuery {
    deptId?: string;
    endTime?: string;
    id?: string;
    name?: string;
    page: number;
    pageSize: number;
    startTime?: string;
    status?: 0 | 1;
    username?: string;
  }

  export interface UserStatusParams {
    status: 0 | 1;
    userVersion: number;
  }

  export interface UserStatusResult {
    userVersion: number;
  }

  export interface UserPasswordResetParams {
    credentialVersion: number;
  }

  export interface UserPasswordResetResult {
    credentialVersion: number;
  }
}

async function getUserList(params: SystemUserApi.UserListQuery) {
  return requestClient.get<PageResult<SystemUserApi.SystemUser>>(
    '/system/user/list',
    { params },
  );
}

async function createUser(data: SystemUserApi.UserCreateParams) {
  return requestClient.post('/system/user', data);
}

async function updateUser(id: string, data: SystemUserApi.UserUpdateParams) {
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

async function resetUserPassword(
  id: string,
  data: SystemUserApi.UserPasswordResetParams,
) {
  return requestClient.post<SystemUserApi.UserPasswordResetResult>(
    `/system/user/${id}/password/reset`,
    data,
  );
}

async function deleteUser(id: string, expectedVersion: number) {
  return requestClient.delete(`/system/user/${id}`, {
    params: { expectedVersion },
  });
}

export {
  createUser,
  deleteUser,
  getUserList,
  resetUserPassword,
  updateUser,
  updateUserStatus,
};
