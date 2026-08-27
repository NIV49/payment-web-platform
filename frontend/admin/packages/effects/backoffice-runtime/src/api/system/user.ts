import type { PageResult } from './types';

import { getInstalledBackofficeDeployment } from '../../deployment';
import { requestClient } from '../request';

export namespace SystemUserApi {
  export type IdentityStatus =
    | 'ACTIVE'
    | 'DISABLED'
    | 'LOCKED'
    | 'PENDING_ACTIVATION';

  export interface SystemUser {
    accountDomain?: 'AGENT' | 'MERCHANT' | 'PLATFORM';
    credentialVersion: number;
    createTime?: string;
    deptId: string;
    deptName?: string;
    id: string;
    identityVersion: number;
    identityStatus: IdentityStatus;
    membershipId?: string;
    name: string;
    remark?: string;
    roleIds: string[];
    roleNames?: string[];
    status: 0 | 1;
    systemAdministrator?: boolean;
    tenantId?: string;
    tenantName?: string;
    username: string;
    userVersion: number;
  }

  export interface UserCreateParams {
    accountDomain?: 'AGENT' | 'MERCHANT' | 'PLATFORM';
    deptId: string;
    name: string;
    remark?: string;
    roleIds: string[];
    status: 0 | 1;
    tenantId?: string;
    username: string;
  }

  export interface MembershipUpdateParams {
    deptId: string;
    roleIds: string[];
    status: 0 | 1;
    userVersion: number;
  }

  export interface SystemAdministratorUserUpdateParams extends MembershipUpdateParams {
    accountDomain?: 'AGENT' | 'MERCHANT' | 'PLATFORM';
    credentialVersion: number;
    identityVersion: number;
    name: string;
    remark?: string;
    username: string;
    tenantId?: string;
  }

  export type UserUpdateParams =
    | MembershipUpdateParams
    | SystemAdministratorUserUpdateParams;

  export interface UserListQuery {
    accountDomain?: 'AGENT' | 'MERCHANT' | 'PLATFORM';
    deptId?: string;
    endTime?: string;
    id?: string;
    name?: string;
    page: number;
    pageSize: number;
    startTime?: string;
    status?: 0 | 1;
    tenantId?: string;
    username?: string;
  }

  export interface TenantOption {
    accountDomain: 'AGENT' | 'MERCHANT';
    code: string;
    id: string;
    name: string;
  }

  export interface UserStatusParams {
    status: 0 | 1;
    userVersion: number;
  }

  export interface UserStatusResult {
    userVersion: number;
  }

  export interface UserRoleAssignmentParams {
    roleIds: string[];
    userVersion: number;
  }

  export interface UserRoleAssignmentResult {
    userVersion: number;
  }

  interface UserPasswordResetBaseParams {
    credentialVersion: number;
    password: string;
  }

  export interface SameTenantUserPasswordResetParams extends UserPasswordResetBaseParams {
    accountDomain?: never;
    tenantId?: never;
  }

  export interface PlatformUserPasswordResetParams extends UserPasswordResetBaseParams {
    accountDomain: 'AGENT' | 'MERCHANT';
    tenantId: string;
  }

  export type UserPasswordResetParams =
    | PlatformUserPasswordResetParams
    | SameTenantUserPasswordResetParams;

  export interface UserPasswordResetResult {
    credentialVersion: number;
    identityVersion: number;
    userVersion: number;
  }
}

async function getUserList(
  params: SystemUserApi.UserListQuery,
  usePlatformControlPlane = false,
) {
  if (
    usePlatformControlPlane &&
    getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM'
  ) {
    return requestClient.get<PageResult<SystemUserApi.SystemUser>>(
      '/platform/user-directory',
      { params: { accountDomain: 'PLATFORM', ...params } },
    );
  }
  return requestClient.get<PageResult<SystemUserApi.SystemUser>>(
    '/system/user/list',
    { params },
  );
}

async function createUser(data: SystemUserApi.UserCreateParams) {
  if (
    getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM' &&
    data.accountDomain &&
    data.accountDomain !== 'PLATFORM'
  ) {
    return requestClient.post('/platform/tenant-administrators', {
      accountDomain: data.accountDomain,
      name: data.name,
      status: data.status,
      tenantId: data.tenantId,
      username: data.username,
    });
  }
  return requestClient.post('/system/user', {
    deptId: data.deptId,
    name: data.name,
    remark: data.remark,
    roleIds: data.roleIds,
    status: data.status,
    username: data.username,
  });
}

async function updateUser(id: string, data: SystemUserApi.UserUpdateParams) {
  if (
    getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM' &&
    'accountDomain' in data &&
    data.accountDomain &&
    data.accountDomain !== 'PLATFORM'
  ) {
    return requestClient.put(`/platform/tenant-administrators/${id}`, {
      accountDomain: data.accountDomain,
      credentialVersion: data.credentialVersion,
      identityVersion: data.identityVersion,
      name: data.name,
      status: data.status,
      tenantId: data.tenantId,
      username: data.username,
      userVersion: data.userVersion,
    });
  }
  const sameTenantPayload: SystemUserApi.UserUpdateParams = {
    deptId: data.deptId,
    roleIds: data.roleIds,
    status: data.status,
    userVersion: data.userVersion,
    ...('identityVersion' in data
      ? {
          credentialVersion: data.credentialVersion,
          identityVersion: data.identityVersion,
          name: data.name,
          remark: data.remark,
          username: data.username,
        }
      : {}),
  };
  return requestClient.put(`/system/user/${id}`, sameTenantPayload);
}

async function getTenantOptions(accountDomain: 'AGENT' | 'MERCHANT') {
  return requestClient.get<SystemUserApi.TenantOption[]>(
    '/platform/tenant-options',
    { params: { accountDomain } },
  );
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

async function replaceUserRoles(
  id: string,
  data: SystemUserApi.UserRoleAssignmentParams,
) {
  return requestClient.put<SystemUserApi.UserRoleAssignmentResult>(
    `/system/user/${id}/roles`,
    data,
  );
}

async function resetUserPassword(
  id: string,
  data: SystemUserApi.UserPasswordResetParams,
) {
  const containsTargetBinding = 'accountDomain' in data || 'tenantId' in data;
  if (containsTargetBinding) {
    const target = data as SystemUserApi.PlatformUserPasswordResetParams;
    const isValidTargetBinding =
      getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM' &&
      (target.accountDomain === 'AGENT' ||
        target.accountDomain === 'MERCHANT') &&
      typeof target.tenantId === 'string' &&
      target.tenantId.trim().length > 0;
    if (!isValidTargetBinding) {
      throw new Error('Invalid cross-domain password reset target binding');
    }
    return requestClient.post<SystemUserApi.UserPasswordResetResult>(
      `/platform/users/${id}/password/reset`,
      {
        accountDomain: target.accountDomain,
        credentialVersion: target.credentialVersion,
        password: target.password,
        tenantId: target.tenantId,
      },
    );
  }
  return requestClient.post<SystemUserApi.UserPasswordResetResult>(
    `/system/user/${id}/password/reset`,
    {
      credentialVersion: data.credentialVersion,
      password: data.password,
    },
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
  getTenantOptions,
  getUserList,
  replaceUserRoles,
  resetUserPassword,
  updateUser,
  updateUserStatus,
};
