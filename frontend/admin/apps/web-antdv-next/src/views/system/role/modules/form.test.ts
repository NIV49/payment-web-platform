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
  getMenuList: vi.fn(),
  updateRole: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  Tree: { template: '<div />' },
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
  updateRole: harness.updateRole,
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
  return { app, onSuccess };
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

const menu = (id: string) => ({
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
    harness.updateRole.mockReset();
    harness.formApi.getValues.mockReset();
    harness.formApi.setValues.mockReset().mockResolvedValue(undefined);
    harness.formApi.validate.mockReset().mockResolvedValue({ valid: true });
  });

  it('never writes role B with role A form state and ignores a save after close', async () => {
    const menusA = Promise.withResolvers<any[]>();
    const menusB = Promise.withResolvers<any[]>();
    const saveB = Promise.withResolvers<any>();
    harness.getMenuList
      .mockReturnValueOnce(menusA.promise)
      .mockReturnValueOnce(menusB.promise);
    harness.drawerApi.getData
      .mockReturnValueOnce(role('A', 1))
      .mockReturnValueOnce(role('B', 2));
    harness.formApi.getValues.mockResolvedValue({
      menuIds: ['menu-B'],
      name: 'Role B edited',
      status: 1,
    });
    harness.updateRole.mockReturnValueOnce(saveB.promise);

    const { app, onSuccess } = mountDrawer();
    harness.drawerOptions.onOpenChange(true);
    harness.drawerOptions.onOpenChange(false);
    harness.drawerOptions.onOpenChange(true);

    menusB.resolve([menu('menu-B')]);
    await flushAsyncWork();
    expect(harness.formApi.setValues).toHaveBeenCalledTimes(1);
    expect(harness.formApi.setValues).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'B', menuIds: ['menu-B'] }),
    );

    const saving = harness.drawerOptions.onConfirm();
    await flushAsyncWork();
    expect(harness.updateRole).toHaveBeenCalledWith('B', {
      expectedVersion: 2,
      menuIds: ['menu-B'],
      name: 'Role B edited',
      status: 1,
    });

    harness.drawerOptions.onOpenChange(false);
    saveB.resolve(undefined);
    await saving;
    menusA.resolve([menu('menu-A')]);
    await flushAsyncWork();

    expect(harness.formApi.setValues).toHaveBeenCalledTimes(1);
    expect(harness.drawerApi.close).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    app.unmount();
  });
});
