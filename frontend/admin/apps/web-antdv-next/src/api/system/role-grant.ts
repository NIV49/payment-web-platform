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

export { getGrantablePermissions, getRoleGrants, replaceRoleGrants };
