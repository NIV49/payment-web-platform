import type { SystemDeptApi } from '#/api/system/dept';

import { describe, expect, it, vi } from 'vitest';

import { PERMISSION_CODES } from '#/api/permission-codes';
import { filterDeletedDepartmentTree } from '#/api/system/dept';

import {
  canAppendDepartmentChild,
  canManageDepartment,
  canPerformDepartmentAction,
  filterDepartmentParentOptions,
} from './selection-contract';

vi.mock('#/api/request', () => ({ requestClient: {} }));

const department = (
  id: string,
  overrides: Partial<SystemDeptApi.SystemDept & { disabled?: boolean }> = {},
): SystemDeptApi.SystemDept & { disabled?: boolean } => ({
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

  it('keeps disabled and system-managed departments manageable for recovery', () => {
    expect(canManageDepartment(department('1', { status: 0 }))).toBe(true);
    expect(canManageDepartment(department('2', { systemManaged: true }))).toBe(
      true,
    );
  });

  it('allows every active non-deleted department to own children', () => {
    expect(canAppendDepartmentChild(department('1'))).toBe(true);
    expect(canAppendDepartmentChild(department('2', { status: 0 }))).toBe(
      false,
    );
    expect(
      canAppendDepartmentChild(department('3', { systemManaged: true })),
    ).toBe(true);
    expect(
      canAppendDepartmentChild(
        department('4', { deletedAt: '2026-08-01T00:00:00Z' }),
      ),
    ).toBe(false);
  });

  it('excludes disabled, deleted, self, and descendant parents', () => {
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

    expect(result).toEqual([department('3', { systemManaged: true })]);
  });

  it('pins the current non-selectable parent and its ancestors as read-only', () => {
    const result = filterDepartmentParentOptions(
      [
        department('1', {
          systemManaged: true,
          children: [
            department('2', {
              pid: '1',
              status: 0,
              children: [department('3', { pid: '2' })],
            }),
          ],
        }),
      ],
      '3',
      '2',
    );

    expect(result).toEqual([
      department('1', {
        systemManaged: true,
        children: [
          department('2', {
            children: [],
            disabled: true,
            pid: '1',
            status: 0,
          }),
        ],
      }),
    ]);
  });

  it('requires department view together with the action permission', () => {
    const granted = new Set<string>([PERMISSION_CODES.departmentDelete]);
    const hasAccess = (codes: string[]) =>
      codes.some((code) => granted.has(code));
    expect(
      canPerformDepartmentAction(
        department('1'),
        PERMISSION_CODES.departmentDelete,
        hasAccess,
      ),
    ).toBe(false);
    granted.add(PERMISSION_CODES.departmentView);
    expect(
      canPerformDepartmentAction(
        department('1'),
        PERMISSION_CODES.departmentDelete,
        hasAccess,
      ),
    ).toBe(true);
  });
});
