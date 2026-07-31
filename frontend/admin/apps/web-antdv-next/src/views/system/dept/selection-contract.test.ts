import type { SystemDeptApi } from '#/api/system/dept';

import { describe, expect, it, vi } from 'vitest';

import { filterDeletedDepartmentTree } from '#/api/system/dept';

import {
  canAppendDepartmentChild,
  canManageDepartment,
  filterDepartmentParentOptions,
} from './selection-contract';

vi.mock('#/api/request', () => ({ requestClient: {} }));

const department = (
  id: string,
  overrides: Partial<SystemDeptApi.SystemDept> = {},
): SystemDeptApi.SystemDept => ({
  id,
  name: `department-${id}`,
  pid: '0',
  rowVersion: 0,
  status: 1,
  ...overrides,
});

describe('department selection contract', () => {
  it('hides deleted departments while retaining disabled management rows', () => {
    const result = filterDeletedDepartmentTree([
      department('1', { status: 0 }),
      department('2', { deletedAt: '2026-08-01T00:00:00Z' }),
    ]);

    expect(result).toEqual([department('1', { status: 0 })]);
  });

  it('keeps disabled ordinary departments manageable for recovery', () => {
    expect(canManageDepartment(department('1', { status: 0 }))).toBe(true);
    expect(canManageDepartment(department('2', { systemManaged: true }))).toBe(
      false,
    );
  });

  it('allows only active ordinary departments to own children', () => {
    expect(canAppendDepartmentChild(department('1'))).toBe(true);
    expect(canAppendDepartmentChild(department('2', { status: 0 }))).toBe(
      false,
    );
    expect(
      canAppendDepartmentChild(department('3', { systemManaged: true })),
    ).toBe(false);
    expect(
      canAppendDepartmentChild(
        department('4', { deletedAt: '2026-08-01T00:00:00Z' }),
      ),
    ).toBe(false);
  });

  it('excludes disabled, deleted, protected, self, and descendant parents', () => {
    const result = filterDepartmentParentOptions(
      [
        department('1', {
          children: [department('11', { pid: '1' })],
        }),
        department('2', { status: 0 }),
        department('3', { systemManaged: true }),
        department('4', { deletedAt: '2026-08-01T00:00:00Z' }),
      ],
      '1',
    );

    expect(result).toEqual([]);
  });
});
