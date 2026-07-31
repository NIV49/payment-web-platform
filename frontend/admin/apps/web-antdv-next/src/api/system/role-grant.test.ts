import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getGrantablePermissions,
  getRoleGrants,
  replaceRoleGrants,
} from './role-grant';

const requestClient = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('role grant administration requests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('uses the dedicated grantable and role grant endpoints', async () => {
    await getGrantablePermissions();
    await getRoleGrants('2001');

    expect(requestClient.get).toHaveBeenNthCalledWith(
      1,
      '/v1/iam/permissions/grantable',
    );
    expect(requestClient.get).toHaveBeenNthCalledWith(
      2,
      '/v1/iam/roles/2001/grants',
    );
  });

  it('submits only version, reason, and grant intent fields', async () => {
    const payload = {
      expectedVersion: 7,
      grants: [
        {
          dimensions: [
            {
              code: 'TENANT' as const,
              mode: 'TENANT_ALL' as const,
              targets: [],
            },
          ],
          grantKey: 'user-create',
          permissionCode: 'user:create',
        },
      ],
      reason: 'Operator onboarding',
    };

    await replaceRoleGrants('2001', payload);

    expect(requestClient.put).toHaveBeenCalledWith(
      '/v1/iam/roles/2001/grants',
      payload,
    );
    expect(payload).not.toHaveProperty('riskLevel');
    expect(payload).not.toHaveProperty('approval');
  });
});
