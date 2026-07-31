import { requestClient } from '#/api/request';

export namespace IamRoleGrantApi {
  export interface GrantDimension {
    code: 'TENANT';
    mode: 'TENANT_ALL';
    targets: string[];
  }

  export interface GrantIntent {
    dimensions: GrantDimension[];
    grantKey: string;
    permissionCode: string;
  }

  export interface GrantablePermission {
    actionCode: string;
    permissionCode: string;
    requiredDimensions: Array<{
      allowedModes: Array<'TENANT_ALL'>;
      code: 'TENANT';
    }>;
    resourceCode: string;
    riskLevel: 'NORMAL';
  }

  export interface RoleGrantDetail {
    editable: boolean;
    grants: GrantIntent[];
    roleId: string;
    roleVersion: number;
  }

  export interface ReplaceRoleGrantsParams {
    expectedVersion: number;
    grants: GrantIntent[];
    reason: string;
  }

  export interface ReplaceRoleConfigurationParams {
    expectedVersion: number;
    grants: GrantIntent[];
    menuIds: string[];
    name: string;
    reason: string;
    remark?: string;
    status: 0 | 1;
  }

  export interface RoleConfiguration {
    editable: boolean;
    grants: GrantIntent[];
    menuIds: string[];
    roleId: string;
    roleVersion: number;
  }
}

async function getGrantablePermissions() {
  return requestClient.get<IamRoleGrantApi.GrantablePermission[]>(
    '/v1/iam/permissions/grantable',
  );
}

async function getRoleGrants(roleId: string) {
  return requestClient.get<IamRoleGrantApi.RoleGrantDetail>(
    `/v1/iam/roles/${roleId}/grants`,
  );
}

async function replaceRoleGrants(
  roleId: string,
  data: IamRoleGrantApi.ReplaceRoleGrantsParams,
) {
  return requestClient.put<IamRoleGrantApi.RoleGrantDetail>(
    `/v1/iam/roles/${roleId}/grants`,
    data,
  );
}

async function replaceRoleConfiguration(
  roleId: string,
  data: IamRoleGrantApi.ReplaceRoleConfigurationParams,
) {
  return requestClient.put<IamRoleGrantApi.RoleConfiguration>(
    `/v1/iam/roles/${roleId}/configuration`,
    data,
  );
}

export {
  getGrantablePermissions,
  getRoleGrants,
  replaceRoleConfiguration,
  replaceRoleGrants,
};
