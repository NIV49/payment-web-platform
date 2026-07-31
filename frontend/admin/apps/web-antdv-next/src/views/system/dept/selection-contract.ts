import type { SystemDeptApi } from '#/api/system/dept';

function canManageDepartment(department: SystemDeptApi.SystemDept) {
  return !department.deletedAt && department.systemManaged !== true;
}

function canAppendDepartmentChild(department: SystemDeptApi.SystemDept) {
  return canManageDepartment(department) && department.status === 1;
}

function filterDepartmentParentOptions(
  departments: readonly SystemDeptApi.SystemDept[],
  excludedDepartmentId?: string,
): SystemDeptApi.SystemDept[] {
  return departments
    .filter(
      (department) =>
        department.id !== excludedDepartmentId &&
        canAppendDepartmentChild(department),
    )
    .map((department) => ({
      ...department,
      ...(department.children
        ? {
            children: filterDepartmentParentOptions(
              department.children,
              excludedDepartmentId,
            ),
          }
        : {}),
    }));
}

export {
  canAppendDepartmentChild,
  canManageDepartment,
  filterDepartmentParentOptions,
};
