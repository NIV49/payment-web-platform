import { createApp, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import GrantsDrawer from './grants.vue';

const harness = vi.hoisted(() => ({
  drawerApi: {
    close: vi.fn(),
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  drawerOptions: undefined as any,
  getGrantablePermissions: vi.fn(),
  getRoleGrants: vi.fn(),
  replaceRoleGrants: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: any) => {
    harness.drawerOptions = options;
    return [{ template: '<div><slot /></div>' }, harness.drawerApi];
  },
}));

vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot name="action" /></div>' },
  Button: {
    emits: ['click'],
    template: '<button @click="$emit(\'click\')"><slot /></button>',
  },
  Checkbox: { template: '<div><slot /></div>' },
  Empty: { template: '<div />' },
  Input: {
    TextArea: {
      emits: ['update:value'],
      props: ['value'],
      template:
        '<textarea :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
    },
  },
  message: { success: vi.fn(), warning: vi.fn() },
  Modal: { confirm: vi.fn() },
  Spin: { template: '<div><slot /></div>' },
  Tag: { template: '<span><slot /></span>' },
}));

vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));

vi.mock('#/api/system/role-grant', () => ({
  getGrantablePermissions: harness.getGrantablePermissions,
  getRoleGrants: harness.getRoleGrants,
  replaceRoleGrants: harness.replaceRoleGrants,
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

async function flushAsyncWork() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

function mountDrawer(onSuccess = vi.fn()) {
  const root = document.createElement('div');
  const app = createApp(GrantsDrawer, { onSuccess });
  app.mount(root);
  return { app, onSuccess, root };
}

async function setReason(root: HTMLElement, value: string) {
  const textarea = root.querySelector('textarea');
  if (!(textarea instanceof HTMLTextAreaElement)) {
    throw new TypeError('Expected the grant reason textarea to be rendered');
  }
  textarea.value = value;
  textarea.dispatchEvent(new Event('input'));
  await nextTick();
}

const role = (id: string) => ({
  assignable: true,
  id,
  menuIds: [],
  name: `Role ${id}`,
  rowVersion: 0,
  status: 1 as const,
  systemRole: false,
});

const permission = (permissionCode: string) => ({
  actionCode: permissionCode.split(':')[1],
  permissionCode,
  requiredDimensions: [{ allowedModes: ['TENANT_ALL'], code: 'TENANT' }],
  resourceCode: permissionCode.split(':')[0],
  riskLevel: 'NORMAL',
});

const detail = (roleId: string, permissionCode: string) => ({
  editable: true,
  grants: [
    {
      dimensions: [{ code: 'TENANT', mode: 'TENANT_ALL', targets: [] }],
      grantKey: permissionCode.replace(':', '-'),
      permissionCode,
    },
  ],
  roleId,
  roleVersion: 3,
});

describe('role grant drawer request isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.drawerApi.getData.mockReset();
    harness.getGrantablePermissions.mockReset();
    harness.getRoleGrants.mockReset();
    harness.replaceRoleGrants.mockReset();
  });

  it('never combines role B with role A grants and ignores a save after close', async () => {
    const permissionsA = Promise.withResolvers<any[]>();
    const permissionsB = Promise.withResolvers<any[]>();
    const grantsA = Promise.withResolvers<any>();
    const grantsB = Promise.withResolvers<any>();
    const saveB = Promise.withResolvers<any>();
    harness.getGrantablePermissions
      .mockReturnValueOnce(permissionsA.promise)
      .mockReturnValueOnce(permissionsB.promise);
    harness.getRoleGrants
      .mockReturnValueOnce(grantsA.promise)
      .mockReturnValueOnce(grantsB.promise);
    harness.replaceRoleGrants.mockReturnValueOnce(saveB.promise);
    harness.drawerApi.getData
      .mockReturnValueOnce(role('A'))
      .mockReturnValueOnce(role('B'));

    const { app, onSuccess, root } = mountDrawer();
    harness.drawerOptions.onOpenChange(true);
    harness.drawerOptions.onOpenChange(false);
    harness.drawerOptions.onOpenChange(true);

    permissionsB.resolve([permission('user:view')]);
    grantsB.resolve(detail('B', 'user:view'));
    await flushAsyncWork();
    await setReason(root, 'bounded change');

    const saving = harness.drawerOptions.onConfirm();
    expect(harness.replaceRoleGrants).toHaveBeenCalledWith(
      'B',
      expect.objectContaining({
        grants: [expect.objectContaining({ permissionCode: 'user:view' })],
        reason: 'bounded change',
      }),
    );

    harness.drawerOptions.onOpenChange(false);
    saveB.resolve(detail('B', 'user:view'));
    await saving;
    permissionsA.resolve([permission('menu:view')]);
    grantsA.resolve(detail('A', 'menu:view'));
    await flushAsyncWork();

    expect(harness.drawerApi.close).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(harness.replaceRoleGrants).toHaveBeenCalledTimes(1);
    app.unmount();
  });

  it('keeps only the latest retry result for the same role', async () => {
    const failedPermissions = Promise.withResolvers<any[]>();
    const retryOnePermissions = Promise.withResolvers<any[]>();
    const retryTwoPermissions = Promise.withResolvers<any[]>();
    const retryOneGrants = Promise.withResolvers<any>();
    const retryTwoGrants = Promise.withResolvers<any>();
    harness.getGrantablePermissions
      .mockReturnValueOnce(failedPermissions.promise)
      .mockReturnValueOnce(retryOnePermissions.promise)
      .mockReturnValueOnce(retryTwoPermissions.promise);
    harness.getRoleGrants
      .mockRejectedValueOnce(new Error('initial failure'))
      .mockReturnValueOnce(retryOneGrants.promise)
      .mockReturnValueOnce(retryTwoGrants.promise);
    harness.replaceRoleGrants.mockResolvedValue(undefined);
    harness.drawerApi.getData.mockReturnValue(role('C'));

    const { app, root } = mountDrawer();
    harness.drawerOptions.onOpenChange(true);
    failedPermissions.resolve([]);
    await flushAsyncWork();

    const retryButton = root.querySelector('button');
    if (!(retryButton instanceof HTMLButtonElement)) {
      throw new TypeError('Expected the retry button to be rendered');
    }
    retryButton.click();
    retryButton.click();
    retryTwoPermissions.resolve([permission('user:view')]);
    retryTwoGrants.resolve(detail('C', 'user:view'));
    await flushAsyncWork();
    retryOnePermissions.resolve([permission('menu:view')]);
    retryOneGrants.resolve(detail('C', 'menu:view'));
    await flushAsyncWork();

    await setReason(root, 'latest retry only');
    await harness.drawerOptions.onConfirm();

    expect(harness.replaceRoleGrants).toHaveBeenCalledWith(
      'C',
      expect.objectContaining({
        grants: [expect.objectContaining({ permissionCode: 'user:view' })],
      }),
    );
    app.unmount();
  });
});
