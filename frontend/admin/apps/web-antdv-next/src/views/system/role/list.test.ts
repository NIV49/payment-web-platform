import type { SystemRoleApi } from '#/api';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import RoleList from './list.vue';

const harness = vi.hoisted(() => ({
  accessCodes: new Set<string>(),
  canEditRole: undefined as
    | ((row: SystemRoleApi.SystemRole) => boolean)
    | undefined,
  systemAdministrator: true,
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.accessCodes.has(code)),
  }),
}));

vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
  useVbenDrawer: () => [
    { template: '<div />' },
    { setData: () => ({ open() {} }) },
  ],
}));

vi.mock('@vben/icons', () => ({
  Plus: { template: '<span />' },
}));

vi.mock('@vben/stores', () => ({
  useUserStore: () => ({
    userInfo: { systemAdministrator: harness.systemAdministrator },
  }),
}));

vi.mock('antdv-next', () => ({
  Button: {
    emits: ['click'],
    template:
      '<button type="button" @click="$emit(\'click\')"><slot /></button>',
  },
  Modal: { confirm: vi.fn() },
  message: { loading: () => vi.fn(), success: vi.fn() },
}));

vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: () => [
    defineComponent({
      setup(_props, { slots }) {
        return () => h('div', slots['toolbar-tools']?.());
      },
    }),
    { query: vi.fn() },
  ],
}));

vi.mock('#/api', async () => {
  const { PERMISSION_CODES } = await import('#/api/permission-codes');
  return {
    PERMISSION_CODES,
    deleteRole: vi.fn(),
    getRoleList: vi.fn(),
    updateRoleStatus: vi.fn(),
  };
});

vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

vi.mock('./data', () => ({
  useColumns: (
    _onActionClick: unknown,
    _onStatusChange: unknown,
    _canChangeRoleStatus: unknown,
    canEditRole: (row: SystemRoleApi.SystemRole) => boolean,
  ) => {
    harness.canEditRole = canEditRole;
    return [];
  },
  useGridFormSchema: () => [],
}));

const ordinaryRole: SystemRoleApi.SystemRole = {
  assignable: true,
  id: '2001',
  menuIds: [],
  name: 'Operator',
  rowVersion: 0,
  status: 1,
  systemRole: false,
};

async function mountRoleList() {
  const root = document.createElement('div');
  const app = createApp(RoleList);
  app.mount(root);
  await nextTick();
  return { app, root };
}

describe('role list configuration entry permissions', () => {
  beforeEach(() => {
    harness.accessCodes = new Set([
      'menu:view',
      'role:create',
      'role:grant-update',
      'role:update',
      'role:view',
    ]);
    harness.canEditRole = undefined;
    harness.systemAdministrator = true;
  });

  it('hides create and edit configuration from a non-system administrator', async () => {
    harness.systemAdministrator = false;
    const { app, root } = await mountRoleList();

    expect(root.querySelector('button')).toBeNull();
    expect(harness.canEditRole?.(ordinaryRole)).toBe(false);
    app.unmount();
  });

  it('hides create and edit configuration without role:grant-update', async () => {
    harness.accessCodes.delete('role:grant-update');
    const { app, root } = await mountRoleList();

    expect(root.querySelector('button')).toBeNull();
    expect(harness.canEditRole?.(ordinaryRole)).toBe(false);
    app.unmount();
  });

  it('shows configuration entries only for a fully authorized system administrator', async () => {
    const { app, root } = await mountRoleList();

    expect(root.querySelector('button')?.textContent).toContain(
      'ui.actionTitle.create',
    );
    expect(harness.canEditRole?.(ordinaryRole)).toBe(true);
    app.unmount();
  });
});
