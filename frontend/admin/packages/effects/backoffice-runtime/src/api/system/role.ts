import type { Recordable } from '@vben/types';

import type {
  PlatformDirectoryContext,
  PlatformDirectoryTarget,
} from './platform-directory';
import type { PageResult } from './types';
import type { SystemUserApi } from './user';

import { requestClient } from '../request';
import {
  buildPlatformDirectoryTargetParams,
  hasExactPlatformDirectoryResponseContext,
} from './platform-directory';

export namespace SystemRoleApi {
  export interface SystemRole {
    assignable: boolean;
    createTime?: string;
    id: string;
    menuIds: string[];
    name: string;
    remark?: string;
    rowVersion: number;
    status: 0 | 1;
    systemRole: boolean;
  }

  export interface DirectoryRole extends PlatformDirectoryContext, SystemRole {}

  export type RoleSaveParams = Omit<
    SystemRole,
    'assignable' | 'createTime' | 'id' | 'rowVersion' | 'systemRole'
  >;

  export interface RoleListQuery {
    endTime?: string;
    id?: string;
    name?: string;
    page: number;
    pageSize: number;
    remark?: string;
    startTime?: string;
    status?: 0 | 1;
  }

  export type RoleUpdateParams = RoleSaveParams & {
    expectedVersion: number;
  };

  export interface RoleStatusParams {
    expectedVersion: number;
    status: 0 | 1;
  }

  export interface RoleMemberQuery {
    assigned: boolean;
    name?: string;
    page: number;
    pageSize: number;
    status?: 0 | 1;
    userId?: string;
    username?: string;
  }

  export interface RoleMemberChange {
    assigned: boolean;
    userId: string;
    userVersion: number;
  }

  export interface RoleMemberUpdateParams {
    members: RoleMemberChange[];
  }
}

async function getRoleList(params: Recordable<any> = {}) {
  return requestClient.get<PageResult<SystemRoleApi.SystemRole>>(
    '/system/role/list',
    { params },
  );
}

async function getPlatformRoleDirectory(
  params: Partial<SystemRoleApi.RoleListQuery>,
  target: PlatformDirectoryTarget,
) {
  const result = await requestClient.get<
    PageResult<SystemRoleApi.DirectoryRole>
  >('/platform/role-directory', {
    params: {
      ...params,
      ...buildPlatformDirectoryTargetParams(target),
    },
  });
  if (
    !result ||
    !Array.isArray(result.items) ||
    !hasExactPlatformDirectoryResponseContext(result.items, target)
  ) {
    return { items: [], total: 0 };
  }
  return result;
}

async function createRole(data: SystemRoleApi.RoleSaveParams) {
  return requestClient.post('/system/role', data);
}

async function updateRole(id: string, data: SystemRoleApi.RoleUpdateParams) {
  return requestClient.put(`/system/role/${id}`, data);
}

async function updateRoleStatus(
  id: string,
  data: SystemRoleApi.RoleStatusParams,
) {
  return requestClient.request(`/system/role/${id}/status`, {
    data,
    method: 'PATCH',
  });
}

async function deleteRole(id: string, expectedVersion: number) {
  return requestClient.delete(`/system/role/${id}`, {
    params: { expectedVersion },
  });
}

async function getRoleMembers(
  id: string,
  params: SystemRoleApi.RoleMemberQuery,
) {
  return requestClient.get<PageResult<SystemUserApi.SystemUser>>(
    `/system/role/${id}/members`,
    { params },
  );
}

async function updateRoleMembers(
  id: string,
  data: SystemRoleApi.RoleMemberUpdateParams,
) {
  return requestClient.request(`/system/role/${id}/members`, {
    data,
    method: 'PATCH',
  });
}

export {
  createRole,
  deleteRole,
  getPlatformRoleDirectory,
  getRoleList,
  getRoleMembers,
  updateRole,
  updateRoleMembers,
  updateRoleStatus,
};
