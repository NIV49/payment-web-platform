import type { PermissionCode } from '#/api/permission-codes';
import type { SystemDeptApi } from '#/api/system/dept';

import { PERMISSION_CODES } from '#/api/permission-codes';

import { hasPermissionDependencies } from '../permission-dependencies';

type AccessCodeChecker = (codes: string[]) => boolean;

interface DepartmentParentOption extends SystemDeptApi.SystemDept {
  children?: DepartmentParentOption[];
  disabled?: boolean;
}

function canManageDepartment(department: SystemDeptApi.SystemDept) {
  return !department.deletedAt && department.systemManaged !== true;
}

function canAppendDepartmentChild(department: SystemDeptApi.SystemDept) {
  return canManageDepartment(department) && department.status === 1;
}

function canPerformDepartmentAction(
  department: SystemDeptApi.SystemDept,
  action: PermissionCode,
  hasAccessByCodes: AccessCodeChecker,
) {
  const stateAllowed =
    action === PERMISSION_CODES.departmentCreate
      ? canAppendDepartmentChild(department)
      : canManageDepartment(department);
  return stateAllowed && hasPermissionDependencies([action], hasAccessByCodes);
}

function filterDepartmentParentOptions(
  departments: readonly SystemDeptApi.SystemDept[],
  excludedDepartmentId?: string,
  currentParentId?: string,
): DepartmentParentOption[] {
  return departments.flatMap((department) => {
    if (department.deletedAt || department.id === excludedDepartmentId) {
      return [];
    }

    const children = filterDepartmentParentOptions(
      department.children ?? [],
      excludedDepartmentId,
      currentParentId,
    );
    const selectable = canAppendDepartmentChild(department);
    const pinned = department.id === currentParentId;
    if (!selectable && !pinned && children.length === 0) return [];

    return [
      {
        ...department,
        ...(!selectable && { disabled: true }),
        ...(department.children ? { children } : {}),
      },
    ];
  });
}

export {
  canAppendDepartmentChild,
  canManageDepartment,
  canPerformDepartmentAction,
  filterDepartmentParentOptions,
};
