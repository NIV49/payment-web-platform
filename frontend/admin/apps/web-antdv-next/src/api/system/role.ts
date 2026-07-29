import type { Recordable } from '@vben/types';

import type { PageResult } from './types';

import { requestClient } from '#/api/request';

export namespace SystemRoleApi {
  export interface SystemRole {
    createTime?: string;
    id: string;
    menuIds: string[];
    name: string;
    remark?: string;
    rowVersion: number;
    status: 0 | 1;
  }

  export type RoleSaveParams = Omit<
    SystemRole,
    'createTime' | 'id' | 'rowVersion'
  >;

  export type RoleUpdateParams = RoleSaveParams & {
    expectedVersion: number;
  };

  export interface RoleStatusParams {
    expectedVersion: number;
    status: 0 | 1;
  }
}

async function getRoleList(params: Recordable<any> = {}) {
  return requestClient.get<PageResult<SystemRoleApi.SystemRole>>(
    '/system/role/list',
    { params },
  );
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

export { createRole, deleteRole, getRoleList, updateRole, updateRoleStatus };
