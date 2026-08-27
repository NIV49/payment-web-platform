import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getPlatformMenuDirectory } from './menu';
import { getPlatformRoleDirectory } from './role';

const harness = vi.hoisted(() => ({
  accountDomain: 'PLATFORM' as 'AGENT' | 'MERCHANT' | 'PLATFORM',
  requestClient: { get: vi.fn() },
}));

vi.mock('../../deployment', () => ({
  getInstalledBackofficeDeployment: () => ({
    accountDomain: harness.accountDomain,
  }),
}));
vi.mock('../request', () => ({ requestClient: harness.requestClient }));

describe('platform administration directory request boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.accountDomain = 'PLATFORM';
    harness.requestClient.get.mockResolvedValue([]);
  });

  it('queries the current PLATFORM role directory without a client tenant selector', async () => {
    await getPlatformRoleDirectory(
      { name: 'Risk', page: 1, pageSize: 20 },
      { accountDomain: 'PLATFORM' },
    );

    expect(harness.requestClient.get).toHaveBeenCalledWith(
      '/platform/role-directory',
      {
        params: {
          accountDomain: 'PLATFORM',
          name: 'Risk',
          page: 1,
          pageSize: 20,
        },
      },
    );
  });

  it.each(['MERCHANT', 'AGENT'] as const)(
    'binds a %s role directory query to an exact tenant',
    async (accountDomain) => {
      await getPlatformRoleDirectory(
        { page: 2, pageSize: 10 },
        { accountDomain, tenantId: '9007199254740993' },
      );

      expect(harness.requestClient.get).toHaveBeenCalledWith(
        '/platform/role-directory',
        {
          params: {
            accountDomain,
            page: 2,
            pageSize: 10,
            tenantId: '9007199254740993',
          },
        },
      );
    },
  );

  it('binds a merchant menu tree to exactly one tenant', async () => {
    await getPlatformMenuDirectory({
      accountDomain: 'MERCHANT',
      tenantId: '2001',
    });

    expect(harness.requestClient.get).toHaveBeenCalledWith(
      '/platform/menu-directory',
      { params: { accountDomain: 'MERCHANT', tenantId: '2001' } },
    );
  });

  it.each([
    [
      'role',
      () => getPlatformRoleDirectory({}, { accountDomain: 'MERCHANT' } as any),
    ],
    ['menu', () => getPlatformMenuDirectory({ accountDomain: 'AGENT' } as any)],
  ])(
    'fails closed before an unbound cross-domain %s request',
    async (_name, request) => {
      await expect(request()).rejects.toThrow(
        'Cross-domain administration directory requires an exact tenant',
      );
      expect(harness.requestClient.get).not.toHaveBeenCalled();
    },
  );

  it('does not expose platform directory calls from another deployment', async () => {
    harness.accountDomain = 'MERCHANT';

    await expect(
      getPlatformRoleDirectory({}, { accountDomain: 'PLATFORM' }),
    ).rejects.toThrow(
      'Platform administration directory is unavailable in this deployment',
    );
    expect(harness.requestClient.get).not.toHaveBeenCalled();
  });

  it('accepts only an exact cross-domain role response context', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          accountDomain: 'MERCHANT',
          managementMode: 'READ_ONLY',
          tenantId: '2001',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformRoleDirectory(
        { page: 1, pageSize: 20 },
        { accountDomain: 'MERCHANT', tenantId: '2001' },
      ),
    ).resolves.toMatchObject({ total: 1 });

    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          accountDomain: 'MERCHANT',
          managementMode: 'READ_ONLY',
          tenantId: '2002',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformRoleDirectory(
        { page: 1, pageSize: 20 },
        { accountDomain: 'MERCHANT', tenantId: '2001' },
      ),
    ).resolves.toEqual({ items: [], total: 0 });
  });

  it('fails closed when any nested menu has a mismatched context', async () => {
    harness.requestClient.get.mockResolvedValueOnce([
      {
        accountDomain: 'AGENT',
        children: [
          {
            accountDomain: 'MERCHANT',
            children: [],
            managementMode: 'READ_ONLY',
            tenantId: '3001',
          },
        ],
        managementMode: 'READ_ONLY',
        tenantId: '3001',
      },
    ]);

    await expect(
      getPlatformMenuDirectory({
        accountDomain: 'AGENT',
        tenantId: '3001',
      }),
    ).resolves.toEqual([]);
  });

  it('requires a consistent nonempty PLATFORM tenant and SAME_TENANT mode', async () => {
    harness.requestClient.get.mockResolvedValueOnce([
      {
        accountDomain: 'PLATFORM',
        children: [
          {
            accountDomain: 'PLATFORM',
            children: [],
            managementMode: 'SAME_TENANT',
            tenantId: '1002',
          },
        ],
        managementMode: 'SAME_TENANT',
        tenantId: '1001',
      },
    ]);

    await expect(
      getPlatformMenuDirectory({ accountDomain: 'PLATFORM' }),
    ).resolves.toEqual([]);

    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          accountDomain: 'PLATFORM',
          managementMode: 'READ_ONLY',
          tenantId: '1001',
        },
      ],
      total: 1,
    });
    await expect(
      getPlatformRoleDirectory({}, { accountDomain: 'PLATFORM' }),
    ).resolves.toEqual({ items: [], total: 0 });
  });
});
