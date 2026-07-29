import { requestClient } from '#/api/request';

export namespace SystemDeptApi {
  export interface SystemDept {
    children?: SystemDept[];
    createTime?: string;
    id: string;
    name: string;
    pid?: number | string;
    remark?: string;
    rowVersion: number;
    status: 0 | 1;
  }

  export type DeptSaveParams = Omit<
    SystemDept,
    'children' | 'createTime' | 'id' | 'rowVersion'
  >;

  export type DeptUpdateParams = DeptSaveParams & {
    expectedVersion: number;
  };
}

async function getDeptList() {
  return requestClient.get<SystemDeptApi.SystemDept[]>('/system/dept/list');
}

async function createDept(data: SystemDeptApi.DeptSaveParams) {
  return requestClient.post('/system/dept', data);
}

async function updateDept(id: string, data: SystemDeptApi.DeptUpdateParams) {
  return requestClient.put(`/system/dept/${id}`, data);
}

async function deleteDept(id: string, expectedVersion: number) {
  return requestClient.delete(`/system/dept/${id}`, {
    params: { expectedVersion },
  });
}

export { createDept, deleteDept, getDeptList, updateDept };
