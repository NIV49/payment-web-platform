import type { SystemDeptApi } from '#/api/system/dept';

import { describe, expect, it } from 'vitest';

import {
  buildUserListQuery,
  filterDepartmentTree,
  loadDepartmentTree,
  resolveDepartmentId,
  USER_LIST_SEARCH_BEHAVIOR,
} from './query-contract';

const departments: SystemDeptApi.SystemDept[] = [
  {
    children: [
      {
        id: '11',
        name: 'Platform Engineering',
        pid: '10',
        rowVersion: 0,
        status: 1,
      },
      {
        id: '12',
        name: 'Information Security',
        pid: '10',
        rowVersion: 0,
        status: 1,
      },
    ],
    id: '10',
    name: 'Technology',
    pid: '0',
    rowVersion: 0,
    status: 1,
  },
  {
    id: '20',
    name: 'Finance',
    pid: '0',
    rowVersion: 0,
    status: 1,
  },
];

describe('system user query contract', () => {
  it('extracts only the scalar department id from a flattened tree item', () => {
    expect(
      resolveDepartmentId({
        _id: 'tree-10',
        level: 0,
        value: departments[0],
      }),
    ).toBe('10');
    expect(
      resolveDepartmentId({ value: { id: { nested: '10' } } }),
    ).toBeUndefined();
  });

  it('omits deptId when no department is selected', () => {
    expect(
      buildUserListQuery(
        { currentPage: 2, pageSize: 50 },
        { status: 1, username: 'alice' },
        undefined,
      ),
    ).toEqual({
      page: 2,
      pageSize: 50,
      status: 1,
      username: 'alice',
    });
  });

  it('uses the selected scalar department id and ignores a form deptId value', () => {
    expect(
      buildUserListQuery(
        { currentPage: 1, pageSize: 20 },
        { deptId: { value: { id: 'wrong' } }, name: 'Ada' },
        '11',
      ),
    ).toEqual({
      deptId: '11',
      name: 'Ada',
      page: 1,
      pageSize: 20,
    });
  });

  it('filters recursively, preserves ancestors, and does not mutate source data', () => {
    const before = structuredClone(departments);

    const result = filterDepartmentTree(departments, 'security');

    expect(result).toEqual([
      {
        children: [
          {
            id: '12',
            name: 'Information Security',
            pid: '10',
            rowVersion: 0,
            status: 1,
          },
        ],
        id: '10',
        name: 'Technology',
        pid: '0',
        rowVersion: 0,
        status: 1,
      },
    ]);
    expect(departments).toEqual(before);
    expect(result).not.toBe(departments);
    expect(result[0]).not.toBe(departments[0]);
  });

  it('returns a fresh complete tree for a blank search', () => {
    const result = filterDepartmentTree(departments, '  ');

    expect(result).toEqual(departments);
    expect(result).not.toBe(departments);
    expect(result[0]?.children).not.toBe(departments[0]?.children);
  });

  it('requires an explicit search action for form value changes', () => {
    expect(USER_LIST_SEARCH_BEHAVIOR).toEqual({ submitOnChange: false });
  });

  it('exposes a failed department load and allows a successful retry', async () => {
    const expectedError = new Error('department service unavailable');
    const loader = async () => {
      if (loaderCalls++ === 0) throw expectedError;
      return departments;
    };
    let loaderCalls = 0;

    await expect(loadDepartmentTree(loader)).resolves.toEqual({
      departments: [],
      error: expectedError,
    });
    await expect(loadDepartmentTree(loader)).resolves.toEqual({
      departments,
      error: undefined,
    });
  });
});
