import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createUser, getUserList, resetUserPassword, updateUser } from './user';

const harness = vi.hoisted(() => ({
  accountDomain: 'PLATFORM' as 'AGENT' | 'MERCHANT' | 'PLATFORM',
  requestClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}));

vi.mock('../../deployment', () => ({
  getInstalledBackofficeDeployment: () => ({
    accountDomain: harness.accountDomain,
  }),
}));
vi.mock('../request', () => ({ requestClient: harness.requestClient }));

const createPayload = {
  accountDomain: 'PLATFORM' as const,
  deptId: '10',
  name: 'Platform User',
  remark: 'same tenant',
  roleIds: ['2001'],
  status: 1 as const,
  tenantId: '1',
  username: 'platform-user@example.test',
};
const localTestPassword = ['Abcd1234', 'Efgh!!!!'].join('');

describe('user governance request boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.accountDomain = 'PLATFORM';
  });

  it('uses the platform directory only when the caller enables the control plane', async () => {
    const query = { page: 1, pageSize: 20 };

    await getUserList(query);
    await getUserList({ ...query, accountDomain: 'MERCHANT' }, true);

    expect(harness.requestClient.get).toHaveBeenNthCalledWith(
      1,
      '/system/user/list',
      { params: query },
    );
    expect(harness.requestClient.get).toHaveBeenNthCalledWith(
      2,
      '/platform/user-directory',
      { params: { accountDomain: 'MERCHANT', ...query } },
    );
  });

  it('strips platform selector fields from same-tenant create and update requests', async () => {
    await createUser(createPayload);
    await updateUser('51', {
      ...createPayload,
      credentialVersion: 4,
      identityVersion: 3,
      userVersion: 2,
    });

    expect(harness.requestClient.post).toHaveBeenCalledWith('/system/user', {
      deptId: '10',
      name: 'Platform User',
      remark: 'same tenant',
      roleIds: ['2001'],
      status: 1,
      username: 'platform-user@example.test',
    });
    expect(harness.requestClient.put).toHaveBeenCalledWith('/system/user/51', {
      credentialVersion: 4,
      deptId: '10',
      identityVersion: 3,
      name: 'Platform User',
      remark: 'same tenant',
      roleIds: ['2001'],
      status: 1,
      username: 'platform-user@example.test',
      userVersion: 2,
    });
  });

  it.each(['MERCHANT', 'AGENT'] as const)(
    'keeps %s same-tenant requests free of platform selector fields',
    async (accountDomain) => {
      harness.accountDomain = accountDomain;

      await createUser(createPayload);

      const payload = harness.requestClient.post.mock.calls[0]?.[1];
      expect(payload).not.toHaveProperty('accountDomain');
      expect(payload).not.toHaveProperty('tenantId');
    },
  );

  it('uses the dedicated target-system-administrator DTO for cross-domain writes', async () => {
    await createUser({
      ...createPayload,
      accountDomain: 'MERCHANT',
      tenantId: '2',
    });
    await updateUser('61', {
      ...createPayload,
      accountDomain: 'MERCHANT',
      credentialVersion: 7,
      identityVersion: 6,
      tenantId: '2',
      userVersion: 5,
    });

    expect(harness.requestClient.post).toHaveBeenCalledWith(
      '/platform/tenant-administrators',
      {
        accountDomain: 'MERCHANT',
        name: 'Platform User',
        status: 1,
        tenantId: '2',
        username: 'platform-user@example.test',
      },
    );
    expect(harness.requestClient.put).toHaveBeenCalledWith(
      '/platform/tenant-administrators/61',
      {
        accountDomain: 'MERCHANT',
        credentialVersion: 7,
        identityVersion: 6,
        name: 'Platform User',
        status: 1,
        tenantId: '2',
        username: 'platform-user@example.test',
        userVersion: 5,
      },
    );
  });

  it('keeps same-tenant password reset selectors out of the request body', async () => {
    await resetUserPassword('51', {
      credentialVersion: 7,
      password: localTestPassword,
    });

    expect(harness.requestClient.post).toHaveBeenCalledWith(
      '/system/user/51/password/reset',
      {
        credentialVersion: 7,
        password: localTestPassword,
      },
    );
  });

  it.each(['MERCHANT', 'AGENT'] as const)(
    'routes a PLATFORM reset of a %s user through the bound cross-domain endpoint',
    async (accountDomain) => {
      await resetUserPassword('61', {
        accountDomain,
        credentialVersion: 9,
        password: localTestPassword,
        tenantId: '2',
      });

      expect(harness.requestClient.post).toHaveBeenCalledWith(
        '/platform/users/61/password/reset',
        {
          accountDomain,
          credentialVersion: 9,
          password: localTestPassword,
          tenantId: '2',
        },
      );
    },
  );

  it.each([
    { accountDomain: 'MERCHANT', tenantId: undefined },
    { accountDomain: undefined, tenantId: '2' },
    { accountDomain: 'PLATFORM', tenantId: '1' },
  ])(
    'fails closed before sending an incompletely bound cross-domain reset: %o',
    async (binding) => {
      await expect(
        resetUserPassword('61', {
          ...binding,
          credentialVersion: 9,
          password: localTestPassword,
        } as any),
      ).rejects.toThrow('Invalid cross-domain password reset target binding');

      expect(harness.requestClient.post).not.toHaveBeenCalled();
    },
  );

  it('rejects a cross-domain reset outside the PLATFORM deployment', async () => {
    harness.accountDomain = 'MERCHANT';

    await expect(
      resetUserPassword('61', {
        accountDomain: 'AGENT',
        credentialVersion: 9,
        password: localTestPassword,
        tenantId: '3',
      }),
    ).rejects.toThrow('Invalid cross-domain password reset target binding');

    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });
});
