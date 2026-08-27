import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

import { describe, expect, it } from 'vitest';

import { mergeRoleMemberCatalog } from './role-member-assignment';

function user(
  id: string,
  overrides: Partial<SystemUserApi.SystemUser> = {},
): SystemUserApi.SystemUser {
  return {
    accountDomain: 'PLATFORM',
    createTime: '2026-08-11T00:00:00Z',
    credentialVersion: 0,
    deptId: '1',
    id,
    identityStatus: 'ACTIVE',
    identityVersion: 0,
    membershipId: `membership-${id}`,
    name: `User ${id}`,
    roleIds: [],
    roleNames: [],
    status: 1,
    tenantId: '1',
    userVersion: 0,
    username: `user-${id}@example.test`,
    ...overrides,
  };
}

describe('role member assignment catalog', () => {
  it('excludes the operator from unassigned candidates but preserves an existing assignment', () => {
    expect(
      mergeRoleMemberCatalog(
        [user('operator'), user('assigned')],
        [user('operator'), user('candidate')],
        'operator',
      ).map((item) => item.id),
    ).toEqual(['operator', 'assigned', 'candidate']);

    expect(
      mergeRoleMemberCatalog(
        [user('assigned')],
        [user('operator'), user('candidate')],
        'operator',
      ).map((item) => item.id),
    ).toEqual(['assigned', 'candidate']);
  });
});
