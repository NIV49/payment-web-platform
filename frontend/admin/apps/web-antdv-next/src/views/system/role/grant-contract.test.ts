import type { SystemRoleApi } from '#/api/system/role';

import { describe, expect, it } from 'vitest';

import {
  buildTenantRoleGrants,
  canMutateRole,
  findMissingPermissionDependencies,
  hasPermissionDependencies,
  permissionDependencies,
  reconcilePermissionSelection,
  ROLE_LIST_SEARCH_BEHAVIOR,
} from './grant-contract';

const role = (
  overrides: Partial<SystemRoleApi.SystemRole> = {},
): SystemRoleApi.SystemRole => ({
  assignable: true,
  id: '2001',
  menuIds: [],
  name: 'Operator',
  rowVersion: 0,
  status: 1,
  systemRole: false,
  ...overrides,
});

describe('role grant frontend contract', () => {
  it('requires explicit search submission on the role list', () => {
    expect(ROLE_LIST_SEARCH_BEHAVIOR).toEqual({ submitOnChange: false });
  });

  it('keeps system and non-assignable roles read-only', () => {
    expect(canMutateRole(role())).toBe(true);
    expect(canMutateRole(role({ systemRole: true }))).toBe(false);
    expect(canMutateRole(role({ assignable: false }))).toBe(false);
  });

  it('builds only canonical TENANT/TENANT_ALL grant intents', () => {
    expect(
      buildTenantRoleGrants(['user:create', 'user:delete', 'user:create']),
    ).toEqual([
      {
        dimensions: [{ code: 'TENANT', mode: 'TENANT_ALL', targets: [] }],
        grantKey: 'user-create',
        permissionCode: 'user:create',
      },
      {
        dimensions: [{ code: 'TENANT', mode: 'TENANT_ALL', targets: [] }],
        grantKey: 'user-delete',
        permissionCode: 'user:delete',
      },
    ]);
  });

  it('adds the permissions required to make create and edit actions usable', () => {
    expect(reconcilePermissionSelection([], 'user:create', true)).toEqual([
      'user:create',
      'user:view',
      'department:view',
      'role:view',
    ]);
    expect(reconcilePermissionSelection([], 'user:update', true)).toEqual([
      'user:update',
      'user:view',
      'user:disable',
      'user:assign-role',
      'department:view',
      'role:view',
    ]);
    expect(permissionDependencies('role:create')).toEqual([
      'role:view',
      'menu:view',
    ]);
  });

  it('fails closed when an action grant is missing a required view dependency', () => {
    const granted = new Set(['role:create']);
    expect(
      hasPermissionDependencies(['role:create'], (codes) =>
        codes.some((code) => granted.has(code)),
      ),
    ).toBe(false);
    granted.add('role:view');
    granted.add('menu:view');
    expect(
      hasPermissionDependencies(['role:create'], (codes) =>
        codes.some((code) => granted.has(code)),
      ),
    ).toBe(true);
  });

  it('removes actions that become unusable when a dependency is removed', () => {
    expect(
      reconcilePermissionSelection(
        [
          'user:update',
          'user:view',
          'user:disable',
          'user:assign-role',
          'department:view',
          'role:view',
        ],
        'role:view',
        false,
      ),
    ).toEqual(['user:view', 'user:disable', 'department:view']);
  });

  it('reports existing invalid combinations without silently rewriting them', () => {
    expect(findMissingPermissionDependencies(['user:update'])).toEqual([
      {
        missing: [
          'user:view',
          'user:disable',
          'user:assign-role',
          'department:view',
          'role:view',
        ],
        permissionCode: 'user:update',
      },
    ]);
  });
});
