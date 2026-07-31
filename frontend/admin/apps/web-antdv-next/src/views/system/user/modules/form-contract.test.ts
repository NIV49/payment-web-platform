import type { UserFormValues } from './form-contract';

import { describe, expect, it } from 'vitest';

import { toMembershipUpdateParams, toUserCreateParams } from './form-contract';

const formValues: UserFormValues = {
  credentialVersion: 5,
  deptId: '10',
  identityVersion: 3,
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

  it('includes global identity fields and all versions for a system administrator', () => {
    const payload = toMembershipUpdateParams(formValues, ['2002'], true);

    expect(payload).toEqual({
      credentialVersion: 5,
      deptId: '10',
      identityVersion: 3,
      name: 'Global Display Name',
      remark: 'Global identity note',
      roleIds: ['2002'],
      status: 1,
      username: 'global-login',
      userVersion: 7,
    });
  });

  it('strips residual identity fields for an ordinary administrator', () => {
    const payload = toMembershipUpdateParams(formValues, ['2002'], false);

    expect(payload).toEqual({
      deptId: '10',
      roleIds: ['2002'],
      status: 1,
      userVersion: 7,
    });
    expect(payload).not.toHaveProperty('username');
    expect(payload).not.toHaveProperty('name');
    expect(payload).not.toHaveProperty('remark');
    expect(payload).not.toHaveProperty('identityVersion');
    expect(payload).not.toHaveProperty('credentialVersion');
  });
});
