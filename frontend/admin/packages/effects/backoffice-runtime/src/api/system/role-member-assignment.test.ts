import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '../request';
import { getRoleMembers, updateRoleMembers } from './role';

vi.mock('../request', () => ({
  requestClient: {
    get: vi.fn(),
    request: vi.fn(),
  },
}));

describe('role member assignment requests', () => {
  beforeEach(() => vi.clearAllMocks());

  it('queries assigned membership candidates through the role boundary', async () => {
    await getRoleMembers('20', {
      assigned: true,
      page: 1,
      pageSize: 200,
    });

    expect(requestClient.get).toHaveBeenCalledWith('/system/role/20/members', {
      params: { assigned: true, page: 1, pageSize: 200 },
    });
  });

  it('submits an atomic mixed member change set', async () => {
    const members = [
      { assigned: true, userId: '100', userVersion: 2 },
      { assigned: false, userId: '101', userVersion: 4 },
    ];

    await updateRoleMembers('20', { members });

    expect(requestClient.request).toHaveBeenCalledWith(
      '/system/role/20/members',
      { data: { members }, method: 'PATCH' },
    );
  });
});
