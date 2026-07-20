import { requestClient } from '#/api/request';

export namespace SystemDeptApi {
  export interface SystemDept {
    children?: SystemDept[];
    createTime?: string;
    id: string;
    name: string;
    pid?: number | string;
    remark?: string;
    status: 0 | 1;
  }

  export type DeptSaveParams = Omit<
    SystemDept,
    'children' | 'createTime' | 'id'
  >;
}

async function getDeptList() {
  return requestClient.get<SystemDeptApi.SystemDept[]>('/system/dept/list');
}

async function createDept(data: SystemDeptApi.DeptSaveParams) {
  return requestClient.post('/system/dept', data);
}

async function updateDept(id: string, data: SystemDeptApi.DeptSaveParams) {
  return requestClient.put(`/system/dept/${id}`, data);
}

async function deleteDept(id: string) {
  return requestClient.delete(`/system/dept/${id}`);
}

export { createDept, deleteDept, getDeptList, updateDept };
