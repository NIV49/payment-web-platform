import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';

interface GridPage {
  currentPage: number;
  pageSize: number;
}

interface DepartmentTreeSelection {
  value?: unknown;
}

interface UserDepartmentOption extends SystemDeptApi.SystemDept {
  children?: UserDepartmentOption[];
  disabled?: boolean;
}

const USER_LIST_SEARCH_BEHAVIOR = {
  submitOnChange: false,
} as const;

function resolveDepartmentId(selection: unknown) {
  if (!selection || typeof selection !== 'object') return undefined;

  const value = (selection as DepartmentTreeSelection).value;
  if (!value || typeof value !== 'object') return undefined;

  const id = (value as { id?: unknown }).id;
  return typeof id === 'string' && /^[1-9]\d*$/.test(id) ? id : undefined;
}

function buildUserListQuery(
  page: GridPage,
  formValues: Record<string, unknown>,
  selectedDepartmentId?: string,
): SystemUserApi.UserListQuery {
  const query: SystemUserApi.UserListQuery = {
    page: page.currentPage,
    pageSize: page.pageSize,
  };

  const stringFields = [
    'endTime',
    'id',
    'name',
    'startTime',
    'username',
  ] as const;
  for (const field of stringFields) {
    const value = formValues[field];
    if (typeof value === 'string' && value !== '') query[field] = value;
  }

  const status = formValues.status;
  if (status === 0 || status === 1) query.status = status;
  if (selectedDepartmentId) query.deptId = selectedDepartmentId;

  return query;
}

function cloneDepartmentTree(
  departments: readonly SystemDeptApi.SystemDept[],
): SystemDeptApi.SystemDept[] {
  return departments.map((department) => ({
    ...department,
    ...(department.children
      ? { children: cloneDepartmentTree(department.children) }
      : {}),
  }));
}

function filterActiveDepartmentTree(
  departments: readonly SystemDeptApi.SystemDept[],
): SystemDeptApi.SystemDept[] {
  return departments
    .filter((department) => department.status === 1 && !department.deletedAt)
    .map((department) => ({
      ...department,
      ...(department.children
        ? { children: filterActiveDepartmentTree(department.children) }
        : {}),
    }));
}

function buildUserDepartmentOptions(
  departments: readonly SystemDeptApi.SystemDept[],
  currentDepartmentId?: string,
): UserDepartmentOption[] {
  const visit = (
    nodes: readonly SystemDeptApi.SystemDept[],
    ancestorsActive: boolean,
  ): Array<{ containsCurrent: boolean; option: UserDepartmentOption }> =>
    nodes.flatMap((department) => {
      if (department.deletedAt) return [];

      const active = ancestorsActive && department.status === 1;
      const children = visit(department.children ?? [], active);
      const containsCurrent =
        department.id === currentDepartmentId ||
        children.some((child) => child.containsCurrent);
      if (!active && !containsCurrent) return [];

      return [
        {
          containsCurrent,
          option: {
            ...department,
            ...(active ? {} : { disabled: true }),
            ...(department.children
              ? { children: children.map(({ option }) => option) }
              : {}),
          },
        },
      ];
    });

  return visit(departments, true).map(({ option }) => option);
}

function filterDepartmentTree(
  departments: readonly SystemDeptApi.SystemDept[],
  keyword: string,
): SystemDeptApi.SystemDept[] {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase();
  if (!normalizedKeyword) return cloneDepartmentTree(departments);

  return departments.flatMap((department) => {
    if (department.name.toLocaleLowerCase().includes(normalizedKeyword)) {
      return cloneDepartmentTree([department]);
    }

    const children = filterDepartmentTree(
      department.children ?? [],
      normalizedKeyword,
    );
    return children.length > 0 ? [{ ...department, children }] : [];
  });
}

async function loadDepartmentTree(
  loader: () => Promise<SystemDeptApi.SystemDept[]>,
) {
  try {
    return {
      departments: filterActiveDepartmentTree(await loader()),
      error: undefined,
    };
  } catch (error) {
    return { departments: [] as SystemDeptApi.SystemDept[], error };
  }
}

export {
  buildUserDepartmentOptions,
  buildUserListQuery,
  filterDepartmentTree,
  loadDepartmentTree,
  resolveDepartmentId,
  USER_LIST_SEARCH_BEHAVIOR,
};
