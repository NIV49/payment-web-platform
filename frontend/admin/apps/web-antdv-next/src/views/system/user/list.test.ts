import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import UserList from './list.vue';

const harness = vi.hoisted(() => ({
  getDeptList: vi.fn(),
  getUserList: vi.fn(),
  gridApi: { query: vi.fn() },
  resetUserPassword: vi.fn(),
  systemAdministrator: true,
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));

vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
  Tree: { template: '<div />' },
  useVbenDrawer: () => [
    { template: '<div />' },
    { setData: () => ({ open() {} }) },
  ],
}));

vi.mock('@vben/icons', () => ({
  Plus: { template: '<span />' },
  RotateCw: { template: '<span />' },
  X: { template: '<span />' },
}));

vi.mock('@vben/stores', () => ({
  useUserStore: () => ({
    userInfo: { systemAdministrator: harness.systemAdministrator },
  }),
}));

vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot name="action" /></div>' },
  Button: {
    emits: ['click'],
    template:
      '<button type="button" @click="$emit(\'click\')"><slot /></button>',
  },
  Card: { template: '<section><slot /></section>' },
  InputSearch: { template: '<input />' },
  Modal: { confirm: ({ onOk }: { onOk: () => void }) => onOk() },
  Spin: { template: '<div><slot /></div>' },
  Tooltip: { template: '<div><slot /></div>' },
  message: { loading: () => vi.fn(), success: vi.fn() },
}));

vi.mock('#/adapter/vxe-table', () => ({
  VbenTableAction: defineComponent({
    props: { actions: { default: () => [], type: Array } },
    setup(props) {
      return () =>
        h(
          'div',
          (props.actions as Array<Record<string, any>>)
            .filter((action) => action.ifShow?.() !== false)
            .map((action) =>
              h(
                'button',
                { onClick: action.onClick, type: 'button' },
                action.text,
              ),
            ),
        );
    },
  }),
  useVbenVxeGrid: () => [
    defineComponent({
      setup(_props, { slots }) {
        const row = {
          credentialVersion: 7,
          deptId: '10',
          id: '51',
          identityStatus: 'ACTIVE',
          identityVersion: 2,
          name: 'Operator',
          roleIds: [],
          status: 1,
          username: 'operator',
          userVersion: 3,
        };
        return () => h('div', slots.action?.({ row }));
      },
    }),
    harness.gridApi,
  ],
}));

vi.mock('#/api', async () => {
  const { PERMISSION_CODES } = await import('#/api/permission-codes');
  return {
    PERMISSION_CODES,
    deleteUser: vi.fn(),
    getDeptList: harness.getDeptList,
    getUserList: harness.getUserList,
    resetUserPassword: harness.resetUserPassword,
    updateUserStatus: vi.fn(),
  };
});

vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({
  useColumns: () => [],
  useGridFormSchema: () => [],
}));

async function flushAsyncWork() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

describe('user list password reset action', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.getDeptList.mockResolvedValue([]);
    harness.getUserList.mockResolvedValue({ items: [], total: 0 });
    harness.resetUserPassword.mockResolvedValue({ credentialVersion: 8 });
    harness.systemAdministrator = true;
  });

  it('confirms the visible action, sends the credential version, and refreshes', async () => {
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const resetButton = [...root.querySelectorAll('button')].find(
      (element) => element.textContent === 'system.user.resetPassword',
    );
    expect(resetButton).toBeInstanceOf(HTMLButtonElement);

    (resetButton as HTMLButtonElement).click();
    await flushAsyncWork();

    expect(harness.resetUserPassword).toHaveBeenCalledWith('51', {
      credentialVersion: 7,
    });
    expect(harness.gridApi.query).toHaveBeenCalledTimes(1);
    app.unmount();
  });

  it('hides the action from a non-system-administrator session', async () => {
    harness.systemAdministrator = false;
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    expect(
      [...root.querySelectorAll('button')].some(
        (element) => element.textContent === 'system.user.resetPassword',
      ),
    ).toBe(false);
    app.unmount();
  });
});
