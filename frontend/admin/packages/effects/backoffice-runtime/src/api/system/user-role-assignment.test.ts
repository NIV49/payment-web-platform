import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '../request';
import { replaceUserRoles } from './user';

vi.mock('../request', () => ({
  requestClient: {
    put: vi.fn(),
  },
}));

describe('user role assignment requests', () => {
  beforeEach(() => vi.clearAllMocks());

  it('submits only role ids and the membership version', async () => {
    await replaceUserRoles('100', {
      roleIds: ['21', '22'],
      userVersion: 7,
    });

    expect(requestClient.put).toHaveBeenCalledWith('/system/user/100/roles', {
      roleIds: ['21', '22'],
      userVersion: 7,
    });
  });
});
