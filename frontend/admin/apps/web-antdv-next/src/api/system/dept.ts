import { requestClient } from '#/api/request';

export namespace SystemDeptApi {
  export interface SystemDept {
    children?: SystemDept[];
    createTime?: string;
    deletedAt?: null | string;
    id: string;
    name: string;
    pid?: number | string;
    remark?: string;
    rowVersion: number;
    status: 0 | 1;
    systemManaged?: boolean;
  }

  export type DeptSaveParams = Omit<
    SystemDept,
    | 'children'
    | 'createTime'
    | 'deletedAt'
    | 'id'
    | 'rowVersion'
    | 'systemManaged'
  >;

  export type DeptUpdateParams = DeptSaveParams & {
    expectedVersion: number;
  };
}

function filterDeletedDepartmentTree(
  departments: readonly SystemDeptApi.SystemDept[],
): SystemDeptApi.SystemDept[] {
  return departments
    .filter((department) => !department.deletedAt)
    .map((department) => ({
      ...department,
      ...(department.children
        ? { children: filterDeletedDepartmentTree(department.children) }
        : {}),
    }));
}

async function getDeptList() {
  const departments =
    await requestClient.get<SystemDeptApi.SystemDept[]>('/system/dept/list');
  return filterDeletedDepartmentTree(departments);
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

export {
  createDept,
  deleteDept,
  filterDeletedDepartmentTree,
  getDeptList,
  updateDept,
};
