import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';

interface GridPage {
  currentPage: number;
  pageSize: number;
}

interface DepartmentTreeSelection {
  value?: unknown;
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
    return { departments: await loader(), error: undefined };
  } catch (error) {
    return { departments: [] as SystemDeptApi.SystemDept[], error };
  }
}

export {
  buildUserListQuery,
  filterDepartmentTree,
  loadDepartmentTree,
  resolveDepartmentId,
  USER_LIST_SEARCH_BEHAVIOR,
};
