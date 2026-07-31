import type { SystemRoleApi } from '#/api/system/role';

import { describe, expect, it, vi } from 'vitest';

import {
  buildRoleAssignmentOptions,
  loadRoleAssignmentCatalog,
  mergeRoleAssignmentIds,
  mergeRoleSearchResults,
  resolveRoleAssignmentIds,
  tryLoadRoleAssignmentCatalog,
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

  it('converts role catalog failures into a non-ready empty form state', async () => {
    await expect(
      tryLoadRoleAssignmentCatalog(async () => {
        throw new Error('role catalog unavailable');
      }),
    ).resolves.toEqual({ ready: false, roles: [] });
  });

  it('loads one active page even when the tenant has more roles', async () => {
    const pageOne = Array.from({ length: 200 }, (_, index) =>
      role(String(index + 1)),
    );
    let calls = 0;
    const loadPage = vi.fn(async () => {
      calls += 1;
      if (calls > 1) throw new Error('unexpected second page');
      return { items: pageOne, total: 201 };
    });

    const catalog = await loadRoleAssignmentCatalog(loadPage);

    expect(loadPage).toHaveBeenCalledOnce();
    expect(loadPage).toHaveBeenCalledWith({
      page: 1,
      pageSize: 200,
      status: 1,
    });
    expect(catalog).toHaveLength(200);
  });

  it('loads missing current roles by exact id with bounded concurrency', async () => {
    const currentRoleIds = Array.from({ length: 10 }, (_, index) =>
      String(index + 201),
    );
    let active = 0;
    let maximumActive = 0;
    const loadPage = vi.fn(async (query: { id?: string }) => {
      if (!query.id) return { items: [role('1')], total: 500 };
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await new Promise((resolve) => setTimeout(resolve, 1));
      active -= 1;
      return {
        items: [role(query.id, { status: query.id === '201' ? 0 : 1 })],
        total: 1,
      };
    });

    const catalog = await loadRoleAssignmentCatalog(loadPage, currentRoleIds);

    expect(loadPage).toHaveBeenCalledTimes(11);
    expect(maximumActive).toBeLessThanOrEqual(8);
    expect(catalog).toHaveLength(11);
    expect(catalog).toContainEqual(role('201', { status: 0 }));
    expect(loadPage).toHaveBeenCalledWith({ id: '201', page: 1, pageSize: 1 });
  });

  it('replaces search candidates without dropping pinned current roles', () => {
    const current = role('disabled', { status: 0 });
    expect(
      mergeRoleSearchResults(
        [current, role('old-candidate')],
        [role('new-candidate')],
        ['disabled'],
      ),
    ).toEqual([current, role('new-candidate')]);
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
