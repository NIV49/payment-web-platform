import type { SystemRoleApi } from '#/api/system/role';

import { describe, expect, it, vi } from 'vitest';

import {
  buildRoleAssignmentOptions,
  loadRoleAssignmentCatalog,
  mergeRoleAssignmentIds,
  resolveRoleAssignmentIds,
} from './role-assignment';

function role(
  id: string,
  overrides: Partial<SystemRoleApi.SystemRole> = {},
): SystemRoleApi.SystemRole {
  return {
    assignable: true,
    id,
    menuIds: [],
    name: `Role ${id}`,
    rowVersion: 0,
    status: 1,
    systemRole: false,
    ...overrides,
  };
}

describe('user role assignment contract', () => {
  const roles = [
    role('ordinary'),
    role('system', { assignable: false, systemRole: true }),
    role('protected', { assignable: false }),
    role('disabled', { status: 0 }),
  ];

  it('only exposes active assignable ordinary roles when creating a user', () => {
    expect(buildRoleAssignmentOptions(roles, [])).toEqual([
      { disabled: false, label: 'Role ordinary', value: 'ordinary' },
    ]);
  });

  it('keeps an existing disabled ordinary role removable while protected roles stay disabled', () => {
    expect(
      buildRoleAssignmentOptions(
        roles,
        ['system', 'protected', 'disabled', 'unknown'],
        ['System role', 'Protected role', 'Disabled role', 'Historical role'],
        true,
      ),
    ).toEqual([
      { disabled: false, label: 'Role ordinary', value: 'ordinary' },
      { disabled: true, label: 'Role system', value: 'system' },
      { disabled: true, label: 'Role protected', value: 'protected' },
      { disabled: false, label: 'Role disabled', value: 'disabled' },
      { disabled: true, label: 'Historical role', value: 'unknown' },
    ]);
  });

  it('keeps disabled ordinary roles read-only without system administrator cleanup authority', () => {
    expect(buildRoleAssignmentOptions(roles, ['disabled'])).toContainEqual({
      disabled: true,
      label: 'Role disabled',
      value: 'disabled',
    });
    expect(mergeRoleAssignmentIds([], ['disabled'], roles)).toEqual([
      'disabled',
    ]);
  });

  it('preserves protected and unknown ids while existing disabled ordinary roles remain editable', () => {
    expect(
      mergeRoleAssignmentIds(
        ['ordinary', 'disabled'],
        ['system', 'protected', 'disabled', 'unknown'],
        roles,
        true,
      ),
    ).toEqual(['system', 'protected', 'unknown', 'ordinary', 'disabled']);
    expect(
      mergeRoleAssignmentIds(
        ['ordinary'],
        ['system', 'protected', 'disabled', 'unknown'],
        roles,
        true,
      ),
    ).toEqual(['system', 'protected', 'unknown', 'ordinary']);
    expect(mergeRoleAssignmentIds([], ['ordinary'], roles)).toEqual([]);
    expect(mergeRoleAssignmentIds(['disabled'], [], roles)).toEqual([]);
  });

  it('fails closed when pagination ends before the advertised total', async () => {
    await expect(
      loadRoleAssignmentCatalog(async () => ({
        items: [role('1')],
        total: 2,
      })),
    ).rejects.toThrow('Role catalog pagination ended before total was loaded');
  });

  it('loads every role page before classifying current assignments', async () => {
    const pageOne = Array.from({ length: 200 }, (_, index) =>
      role(String(index + 1)),
    );
    const disabledCurrentRole = role('201', { status: 0 });
    const loadPage = vi.fn(async ({ page }: { page: number }) => ({
      items: page === 1 ? pageOne : [disabledCurrentRole],
      total: 201,
    }));

    const catalog = await loadRoleAssignmentCatalog(loadPage);

    expect(loadPage).toHaveBeenCalledTimes(2);
    expect(loadPage).toHaveBeenNthCalledWith(1, { page: 1, pageSize: 200 });
    expect(loadPage).toHaveBeenNthCalledWith(2, { page: 2, pageSize: 200 });
    expect(catalog).toHaveLength(201);
    expect(
      buildRoleAssignmentOptions(catalog, ['201'], [], true),
    ).toContainEqual({
      disabled: false,
      label: 'Role 201',
      value: '201',
    });
  });

  it('submits an explicit empty role list when creation is allowed without role assignment', () => {
    expect(resolveRoleAssignmentIds(false, [], [], roles)).toEqual([]);
  });

  it('preserves existing roles when editing without role assignment capability', () => {
    expect(
      resolveRoleAssignmentIds(false, [], ['system', 'ordinary'], roles),
    ).toEqual(['system', 'ordinary']);
  });
});
