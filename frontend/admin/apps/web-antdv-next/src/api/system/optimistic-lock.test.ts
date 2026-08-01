import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createDept, deleteDept, updateDept } from './dept';
import { createMenu, deleteMenu, updateMenu } from './menu';
import { createRole, deleteRole, updateRole, updateRoleStatus } from './role';
import {
  deleteUser,
  resetUserPassword,
  updateUser,
  updateUserStatus,
} from './user';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  request: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('administration optimistic-lock requests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not send expectedVersion when creating roles, departments, or menus', async () => {
    await createRole({
      menuIds: [],
      name: 'Operator',
      status: 1,
    });
    await createDept({
      name: 'Settlement',
      pid: '10',
      status: 1,
    });
    await createMenu({
      meta: { title: 'system.user.title' },
      name: 'SystemUser',
      path: '/system/user',
      pid: '6000',
      status: 1,
      type: 'menu',
    });

    expect(requestClient.post).toHaveBeenNthCalledWith(1, '/system/role', {
      menuIds: [],
      name: 'Operator',
      status: 1,
    });
    expect(requestClient.post).toHaveBeenNthCalledWith(2, '/system/dept', {
      name: 'Settlement',
      pid: '10',
      status: 1,
    });
    expect(requestClient.post).toHaveBeenNthCalledWith(3, '/system/menu', {
      meta: { title: 'system.user.title' },
      name: 'SystemUser',
      path: '/system/user',
      pid: '6000',
      status: 1,
      type: 'menu',
    });
    for (const [, payload] of requestClient.post.mock.calls) {
      expect(payload).not.toHaveProperty('expectedVersion');
    }
  });

  it('sends the role row version on update, status change, and delete', async () => {
    await updateRole('21', {
      expectedVersion: 4,
      menuIds: [],
      name: 'Operator',
      status: 1,
    });
    await updateRoleStatus('21', { expectedVersion: 5, status: 0 });
    await deleteRole('21', 6);

    expect(requestClient.put).toHaveBeenCalledWith('/system/role/21', {
      expectedVersion: 4,
      menuIds: [],
      name: 'Operator',
      status: 1,
    });
    expect(requestClient.request).toHaveBeenCalledWith(
      '/system/role/21/status',
      {
        data: { expectedVersion: 5, status: 0 },
        method: 'PATCH',
      },
    );
    expect(requestClient.delete).toHaveBeenCalledWith('/system/role/21', {
      params: { expectedVersion: 6 },
    });
  });

  it('sends the department and menu row versions on update and delete', async () => {
    await updateDept('31', {
      expectedVersion: 2,
      name: 'Settlement',
      pid: '10',
      status: 1,
    });
    await deleteDept('31', 3);
    await updateMenu('41', {
      expectedVersion: 7,
      meta: { title: 'system.user.title' },
      name: 'SystemUser',
      path: '/system/user',
      pid: '6000',
      status: 1,
      type: 'menu',
    });
    await deleteMenu('41', 8);

    expect(requestClient.put).toHaveBeenCalledWith('/system/dept/31', {
      expectedVersion: 2,
      name: 'Settlement',
      pid: '10',
      status: 1,
    });
    expect(requestClient.delete).toHaveBeenCalledWith('/system/dept/31', {
      params: { expectedVersion: 3 },
    });
    expect(requestClient.put).toHaveBeenCalledWith('/system/menu/41', {
      expectedVersion: 7,
      meta: { title: 'system.user.title' },
      name: 'SystemUser',
      path: '/system/user',
      pid: '6000',
      status: 1,
      type: 'menu',
    });
    expect(requestClient.delete).toHaveBeenCalledWith('/system/menu/41', {
      params: { expectedVersion: 8 },
    });
  });

  it('sends the membership version on user update, status change, and delete', async () => {
    await updateUser('51', {
      deptId: '31',
      roleIds: ['21'],
      status: 1,
      userVersion: 7,
    });
    await updateUserStatus('51', { status: 0, userVersion: 8 });
    await deleteUser('51', 9);

    expect(requestClient.put).toHaveBeenCalledWith('/system/user/51', {
      deptId: '31',
      roleIds: ['21'],
      status: 1,
      userVersion: 7,
    });
    expect(requestClient.request).toHaveBeenCalledWith(
      '/system/user/51/status',
      {
        data: { status: 0, userVersion: 8 },
        method: 'PATCH',
      },
    );
    expect(requestClient.delete).toHaveBeenCalledWith('/system/user/51', {
      params: { expectedVersion: 9 },
    });
  });

  it('sends only the credential version when resetting a user password', async () => {
    await resetUserPassword('51', { credentialVersion: 4 });

    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/user/51/password/reset',
      { credentialVersion: 4 },
    );
  });
});
