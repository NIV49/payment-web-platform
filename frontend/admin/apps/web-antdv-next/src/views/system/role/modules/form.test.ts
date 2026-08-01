import type { SystemMenuApi } from '#/api/system/menu';

import { createApp, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import RoleFormDrawer from './form.vue';

const harness = vi.hoisted(() => ({
  createRole: vi.fn(),
  drawerApi: {
    close: vi.fn(),
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  drawerOptions: undefined as any,
  formApi: {
    getValues: vi.fn(),
    reset: vi.fn(),
    setValues: vi.fn(),
    validate: vi.fn(),
  },
  getGrantablePermissions: vi.fn(),
  getMenuList: vi.fn(),
  getRoleGrants: vi.fn(),
  replaceRoleConfiguration: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  Tree: {
    emits: ['select'],
    props: ['treeData'],
    template:
      '<button data-testid="tree-select" type="button" @click="$emit(\'select\', { value: treeData[0] })">select tree node</button>',
  },
  useVbenDrawer: (options: any) => {
    harness.drawerOptions = options;
    return [{ template: '<div><slot /></div>' }, harness.drawerApi];
  },
}));

vi.mock('@vben/icons', () => ({ IconifyIcon: { template: '<span />' } }));

vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot name="action" /></div>' },
  Button: {
    emits: ['click'],
    template: '<button @click="$emit(\'click\')"><slot /></button>',
  },
  Input: {
    TextArea: {
      emits: ['update:value'],
      props: ['value'],
      template:
        '<textarea :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
    },
  },
  Spin: { template: '<div><slot /></div>' },
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    { template: '<form><slot name="menuIds" /></form>' },
    harness.formApi,
  ],
}));

vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));

vi.mock('#/api/system/menu', () => ({ getMenuList: harness.getMenuList }));
vi.mock('#/api/system/role', () => ({
  createRole: harness.createRole,
}));
vi.mock('#/api/system/role-grant', () => ({
  getGrantablePermissions: harness.getGrantablePermissions,
  getRoleGrants: harness.getRoleGrants,
  replaceRoleConfiguration: harness.replaceRoleConfiguration,
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('../data', () => ({ useFormSchema: () => [] }));

async function flushAsyncWork() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

function mountDrawer(onSuccess = vi.fn()) {
  const root = document.createElement('div');
  const app = createApp(RoleFormDrawer, { onSuccess });
  app.mount(root);
  return { app, onSuccess, root };
}

async function setReason(root: HTMLElement, value: string) {
  const textarea = root.querySelector('textarea');
  if (!(textarea instanceof HTMLTextAreaElement)) {
    throw new TypeError(
      'Expected the configuration reason textarea to be rendered',
    );
  }
  textarea.value = value;
  textarea.dispatchEvent(new Event('input'));
  await nextTick();
}

const role = (id: string, rowVersion: number) => ({
  assignable: true,
  id,
  menuIds: [`menu-${id}`],
  name: `Role ${id}`,
  rowVersion,
  status: 1 as const,
  systemRole: false,
});

const menu = (
  id: string,
  children?: SystemMenuApi.SystemMenu[],
): SystemMenuApi.SystemMenu => ({
  children,
  id,
  meta: { title: `menu.${id}` },
  name: id,
  pid: '0',
  rowVersion: 0,
  status: 1 as const,
  type: 'menu' as const,
});

describe('role form drawer request isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.drawerApi.getData.mockReset();
    harness.getMenuList.mockReset();
    harness.getGrantablePermissions.mockReset();
    harness.getRoleGrants.mockReset();
    harness.replaceRoleConfiguration.mockReset();
    harness.formApi.getValues.mockReset();
    harness.formApi.setValues.mockReset().mockResolvedValue(undefined);
    harness.formApi.validate.mockReset().mockResolvedValue({ valid: true });
  });

  it('never writes role B with role A form state and ignores a save after close', async () => {
    const menusA = Promise.withResolvers<any[]>();
    const menusB = Promise.withResolvers<any[]>();
    const permissionsA = Promise.withResolvers<any[]>();
    const permissionsB = Promise.withResolvers<any[]>();
    const grantsA = Promise.withResolvers<any>();
    const grantsB = Promise.withResolvers<any>();
    const saveB = Promise.withResolvers<any>();
    harness.getMenuList
      .mockReturnValueOnce(menusA.promise)
      .mockReturnValueOnce(menusB.promise);
    harness.getGrantablePermissions
      .mockReturnValueOnce(permissionsA.promise)
      .mockReturnValueOnce(permissionsB.promise);
    harness.getRoleGrants
      .mockReturnValueOnce(grantsA.promise)
      .mockReturnValueOnce(grantsB.promise);
    harness.drawerApi.getData
      .mockReturnValueOnce(role('A', 1))
      .mockReturnValueOnce(role('B', 2));
    harness.formApi.getValues.mockResolvedValue({
      menuIds: ['menu-B'],
      name: 'Role B edited',
      status: 1,
    });
    harness.replaceRoleConfiguration.mockReturnValueOnce(saveB.promise);

    const { app, onSuccess, root } = mountDrawer();
    harness.drawerOptions.onOpenChange(true);
    harness.drawerOptions.onOpenChange(false);
    harness.drawerOptions.onOpenChange(true);

    menusB.resolve([menu('menu-B')]);
    permissionsB.resolve([]);
    grantsB.resolve({
      editable: true,
      grants: [],
      roleId: 'B',
      roleVersion: 2,
    });
    await flushAsyncWork();
    expect(harness.formApi.setValues).toHaveBeenCalledTimes(1);
    expect(harness.formApi.setValues).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'B', menuIds: ['menu-B'] }),
    );
    await setReason(root, 'bounded role configuration');

    const saving = harness.drawerOptions.onConfirm();
    await flushAsyncWork();
    expect(harness.replaceRoleConfiguration).toHaveBeenCalledWith('B', {
      expectedVersion: 2,
      grants: [],
      menuIds: ['menu-B'],
      name: 'Role B edited',
      reason: 'bounded role configuration',
      status: 1,
    });

    harness.drawerOptions.onOpenChange(false);
    saveB.resolve(undefined);
    await saving;
    menusA.resolve([menu('menu-A')]);
    permissionsA.resolve([]);
    grantsA.resolve({
      editable: true,
      grants: [],
      roleId: 'A',
      roleVersion: 1,
    });
    await flushAsyncWork();

    expect(harness.formApi.setValues).toHaveBeenCalledTimes(1);
    expect(harness.drawerApi.close).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    app.unmount();
  });

  it('applies navigation cascading on the new-role tree interaction path', async () => {
    harness.drawerApi.getData.mockReturnValue(undefined);
    harness.getMenuList.mockResolvedValue([menu('parent', [menu('child')])]);
    harness.formApi.getValues.mockResolvedValue({
      menuIds: ['parent'],
      name: 'New role',
      status: 1,
    });

    const { app, root } = mountDrawer();
    harness.drawerOptions.onOpenChange(true);
    await flushAsyncWork();
    harness.formApi.setValues.mockClear();

    const treeSelect = root.querySelector('[data-testid="tree-select"]');
    if (!(treeSelect instanceof HTMLButtonElement)) {
      throw new TypeError('Expected the role tree interaction control');
    }
    treeSelect.click();
    await flushAsyncWork();

    expect(harness.formApi.setValues).toHaveBeenCalledWith({
      menuIds: ['parent', 'child'],
    });
    app.unmount();
  });
});
