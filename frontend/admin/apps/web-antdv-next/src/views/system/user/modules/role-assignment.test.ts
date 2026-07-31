import type { SystemRoleApi } from '#/api/system/role';

import { describe, expect, it } from 'vitest';

import {
  buildRoleAssignmentOptions,
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
      ),
    ).toEqual([
      { disabled: false, label: 'Role ordinary', value: 'ordinary' },
      { disabled: true, label: 'Role system', value: 'system' },
      { disabled: true, label: 'Role protected', value: 'protected' },
      { disabled: false, label: 'Role disabled', value: 'disabled' },
      { disabled: true, label: 'Historical role', value: 'unknown' },
    ]);
  });

  it('preserves protected and unknown ids while existing disabled ordinary roles remain editable', () => {
    expect(
      mergeRoleAssignmentIds(
        ['ordinary', 'disabled'],
        ['system', 'protected', 'disabled', 'unknown'],
        roles,
      ),
    ).toEqual(['system', 'protected', 'unknown', 'ordinary', 'disabled']);
    expect(
      mergeRoleAssignmentIds(
        ['ordinary'],
        ['system', 'protected', 'disabled', 'unknown'],
        roles,
      ),
    ).toEqual(['system', 'protected', 'unknown', 'ordinary']);
    expect(mergeRoleAssignmentIds([], ['ordinary'], roles)).toEqual([]);
    expect(mergeRoleAssignmentIds(['disabled'], [], roles)).toEqual([]);
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
