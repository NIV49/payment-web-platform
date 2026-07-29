import type { UserFormValues } from './form-contract';

import { describe, expect, it } from 'vitest';

import { toMembershipUpdateParams, toUserCreateParams } from './form-contract';

const formValues: UserFormValues = {
  deptId: '10',
  name: 'Global Display Name',
  remark: 'Global identity note',
  roleIds: ['2001'],
  status: 1,
  username: 'global-login',
  userVersion: 7,
};

describe('system user form contract', () => {
  it('creates a global identity without sending membership version', () => {
    const payload = toUserCreateParams(formValues, ['2002']);

    expect(payload).toEqual({
      deptId: '10',
      name: 'Global Display Name',
      remark: 'Global identity note',
      roleIds: ['2002'],
      status: 1,
      username: 'global-login',
    });
    expect(payload).not.toHaveProperty('userVersion');
  });

  it('updates only the current membership fields', () => {
    const payload = toMembershipUpdateParams(formValues, ['2002']);

    expect(payload).toEqual({
      deptId: '10',
      roleIds: ['2002'],
      status: 1,
      userVersion: 7,
    });
    expect(payload).not.toHaveProperty('username');
    expect(payload).not.toHaveProperty('name');
    expect(payload).not.toHaveProperty('remark');
  });
});
