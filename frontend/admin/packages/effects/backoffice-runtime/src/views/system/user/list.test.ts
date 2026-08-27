import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import UserList from './list.vue';

const harness = vi.hoisted(() => ({
  // The same component is mounted by PLATFORM, MERCHANT, and AGENT builds.
  getDeptList: vi.fn(),
  getUserList: vi.fn(),
  accountDomainChange: undefined as ((value: unknown) => void) | undefined,
  accountDomainOptions: [
    { color: 'processing', label: 'Platform', value: 'PLATFORM' },
    { color: 'success', label: 'Merchant', value: 'MERCHANT' },
    { color: 'purple', label: 'Agent', value: 'AGENT' },
  ],
  columnAccountDomainOptions: undefined as (() => unknown) | undefined,
  gridControlPlane: false,
  gridApi: {
    grid: { reloadData: vi.fn() },
    query: vi.fn(),
  },
  keyField: '',
  modalOptions: undefined as Record<string, any> | undefined,
  passwordResetModalApi: {
    close: vi.fn(),
    lock: vi.fn(),
    open: vi.fn(),
  },
  query: undefined as ((...args: any[]) => Promise<unknown>) | undefined,
  resetUserPassword: vi.fn(),
  schemaAccountDomainOptions: undefined as (() => unknown) | undefined,
  row: {
    accountDomain: 'PLATFORM',
    credentialVersion: 7,
    deptId: '10',
    id: '51',
    identityStatus: 'ACTIVE',
    identityVersion: 2,
    membershipId: '151',
    name: 'Operator',
    roleIds: [],
    status: 1,
    systemAdministrator: true,
    username: 'operator@example.test',
    userVersion: 3,
  },
  systemAdministrator: true,
  resetCredential: ['Abcd1234', 'Efgh!!!!'].join(''),
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
  useVbenModal: (options: Record<string, any>) => {
    harness.modalOptions = options;
    return [
      defineComponent({
        setup(_props, { slots }) {
          return () =>
            h('div', [slots.default?.(), slots['prepend-footer']?.()]);
        },
      }),
      harness.passwordResetModalApi,
    ];
  },
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: { template: '<span />' },
  Plus: { template: '<span />' },
  RotateCw: { template: '<span />' },
  X: { template: '<span />' },
}));

vi.mock('@vben/stores', () => ({
  useUserStore: () => ({
    userInfo: { systemAdministrator: harness.systemAdministrator },
  }),
}));

vi.mock('../../../deployment', () => ({
  getInstalledBackofficeDeployment: () => ({ accountDomain: 'PLATFORM' }),
}));

vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot name="action" /></div>' },
  Button: {
    emits: ['click'],
    props: ['disabled'],
    template:
      '<button type="button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  Card: { template: '<section><slot /></section>' },
  InputPassword: {
    props: ['disabled', 'value'],
    template: '<input :disabled="disabled" :value="value" />',
  },
  InputSearch: { template: '<input />' },
  Modal: { confirm: ({ onOk }: { onOk: () => void }) => onOk() },
  Spin: { template: '<div><slot /></div>' },
  Tooltip: { template: '<div><slot /></div>' },
  message: { loading: () => vi.fn(), success: vi.fn() },
}));

vi.mock('@payment/backoffice-runtime/adapter/vxe-table', () => ({
  VbenTableAction: defineComponent({
    props: {
      actions: { default: () => [], type: Array },
      dropdownTrigger: { default: 'click', type: String },
      dropdownActions: { default: () => [], type: Array },
    },
    setup(props) {
      return () => {
        const visibleActions = [
          ...(props.actions as Array<Record<string, any>>),
          ...(props.dropdownActions as Array<Record<string, any>>),
        ].filter((action) => action.ifShow?.() !== false);
        const renderAction = (action: Record<string, any>) =>
          h(
            'button',
            {
              'data-icon': action.icon,
              onClick: action.onClick,
              type: 'button',
            },
            action.text,
          );
        return h('div', { 'data-dropdown-trigger': props.dropdownTrigger }, [
          h(
            'div',
            { 'data-action-group': 'primary' },
            visibleActions.slice(0, 3).map((action) => renderAction(action)),
          ),
          h(
            'div',
            { 'data-action-group': 'overflow' },
            visibleActions.slice(3).map((action) => renderAction(action)),
          ),
        ]);
      };
    },
  }),
  useVbenVxeGrid: (options: {
    gridOptions: {
      proxyConfig: { ajax: { query: (...args: any[]) => Promise<unknown> } };
      rowConfig: { keyField: string };
    };
  }) => {
    harness.keyField = options.gridOptions.rowConfig.keyField;
    harness.query = options.gridOptions.proxyConfig.ajax.query;
    return [
      defineComponent({
        setup(_props, { slots }) {
          return () => h('div', slots.action?.({ row: harness.row }));
        },
      }),
      harness.gridApi,
    ];
  },
}));

vi.mock('@payment/backoffice-runtime/api', async () => {
  const { PERMISSION_CODES } =
    await import('@payment/backoffice-runtime/api/permission-codes');
  return {
    PERMISSION_CODES,
    deleteUser: vi.fn(),
    getDeptList: harness.getDeptList,
    getUserList: harness.getUserList,
    resetUserPassword: harness.resetUserPassword,
    updateUserStatus: vi.fn(),
  };
});

vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));
vi.mock(
  '@payment/backoffice-runtime/components/account-domain-dictionary-alert',
  () => ({
    default: { template: '<div />' },
  }),
);
vi.mock(
  '@payment/backoffice-runtime/components/common-status-dictionary-alert',
  () => ({
    default: { template: '<div />' },
  }),
);
vi.mock('@payment/backoffice-runtime/composables', () => ({
  useAccountDomainDictionary: ({ enabled }: { enabled: boolean }) => ({
    enabled,
    error: { value: undefined },
    options: { value: harness.accountDomainOptions },
    reload: vi.fn(),
  }),
  useCommonStatusDictionary: () => ({
    error: { value: undefined },
    getLabel: (value: 0 | 1) =>
      value === 1 ? 'common.enabled' : 'common.disabled',
    options: {
      value: [
        { color: 'success', label: 'common.enabled', value: 1 },
        { color: 'error', label: 'common.disabled', value: 0 },
      ],
    },
    reload: vi.fn(),
  }),
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('./data', () => ({
  useColumns: (
    _status: unknown,
    _canChange: unknown,
    enabled: boolean,
    _getStatusOptions: unknown,
    getAccountDomainOptions?: () => unknown,
  ) => {
    harness.gridControlPlane = enabled;
    harness.columnAccountDomainOptions = getAccountDomainOptions;
    return [];
  },
  useGridFormSchema: (
    enabled: boolean,
    onAccountDomainChange?: (value: unknown) => void,
    _getStatusOptions?: () => unknown,
    getAccountDomainOptions?: () => unknown,
  ) => {
    harness.gridControlPlane = enabled;
    harness.accountDomainChange = onAccountDomainChange;
    harness.schemaAccountDomainOptions = getAccountDomainOptions;
    return [];
  },
}));
vi.mock('./password-policy', () => ({
  generateLocalPassword: () => harness.resetCredential,
  isLocalPasswordResetMode: () => true,
  isValidLocalPassword: (candidate: string) =>
    candidate === harness.resetCredential,
}));

async function flushAsyncWork() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('user list password reset action', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.getDeptList.mockResolvedValue([]);
    harness.getUserList.mockResolvedValue({ items: [], total: 0 });
    harness.resetUserPassword.mockResolvedValue({ credentialVersion: 8 });
    harness.systemAdministrator = true;
    harness.gridControlPlane = false;
    harness.accountDomainChange = undefined;
    harness.columnAccountDomainOptions = undefined;
    harness.modalOptions = undefined;
    harness.query = undefined;
    harness.gridApi.grid.reloadData.mockReset();
    harness.schemaAccountDomainOptions = undefined;
    Object.assign(harness.row, {
      accountDomain: 'PLATFORM',
      systemAdministrator: true,
    });
  });

  it('opens a generated-password modal, submits the bound reset, and refreshes', async () => {
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

    expect(harness.passwordResetModalApi.open).toHaveBeenCalledTimes(1);
    await harness.modalOptions?.onConfirm?.();
    await flushAsyncWork();

    expect(harness.resetUserPassword).toHaveBeenCalledWith('51', {
      credentialVersion: 7,
      password: harness.resetCredential,
    });
    expect(harness.gridApi.query).toHaveBeenCalledTimes(1);
    app.unmount();
  });

  it('freezes the displayed password while a reset request is pending', async () => {
    let resolveReset!: (value: { credentialVersion: number }) => void;
    harness.resetUserPassword.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveReset = resolve;
      }),
    );
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const resetButton = [...root.querySelectorAll('button')].find(
      (element) => element.textContent === 'system.user.resetPassword',
    ) as HTMLButtonElement;
    resetButton.click();
    await flushAsyncWork();

    const confirmation = harness.modalOptions?.onConfirm?.();
    await flushAsyncWork();
    const passwordInput = root.querySelector(
      '#local-password-reset',
    ) as HTMLInputElement;
    const regenerateButton = [...root.querySelectorAll('button')].find(
      (element) =>
        element.textContent?.includes('system.user.regeneratePassword'),
    ) as HTMLButtonElement;

    expect(passwordInput.disabled).toBe(true);
    expect(regenerateButton.disabled).toBe(true);
    await harness.modalOptions?.onConfirm?.();
    expect(harness.resetUserPassword).toHaveBeenCalledTimes(1);

    resolveReset({ credentialVersion: 8 });
    await confirmation;
    await flushAsyncWork();
    expect(passwordInput.disabled).toBe(false);
    app.unmount();
  });

  it('does not submit on regeneration and clears the password after a failed reset', async () => {
    harness.resetUserPassword.mockRejectedValueOnce(new Error('reset failed'));
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const resetButton = [...root.querySelectorAll('button')].find(
      (element) => element.textContent === 'system.user.resetPassword',
    ) as HTMLButtonElement;
    resetButton.click();
    await flushAsyncWork();
    const regenerateButton = [...root.querySelectorAll('button')].find(
      (element) =>
        element.textContent?.includes('system.user.regeneratePassword'),
    ) as HTMLButtonElement;
    regenerateButton.click();
    expect(harness.resetUserPassword).not.toHaveBeenCalled();

    await harness.modalOptions?.onConfirm?.();
    await flushAsyncWork();

    expect(harness.passwordResetModalApi.close).not.toHaveBeenCalled();
    expect(
      (root.querySelector('#local-password-reset') as HTMLInputElement).value,
    ).toBe('');
    app.unmount();
  });

  it('uses membership identity as the platform directory row key', async () => {
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    expect(harness.keyField).toBe('membershipId');
    expect(harness.gridControlPlane).toBe(true);
    expect(harness.schemaAccountDomainOptions?.()).toBe(
      harness.accountDomainOptions,
    );
    expect(harness.columnAccountDomainOptions?.()).toBe(
      harness.accountDomainOptions,
    );
    await harness.query?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { accountDomain: 'PLATFORM' },
    );
    expect(harness.getUserList).toHaveBeenCalledWith(
      expect.objectContaining({ accountDomain: 'PLATFORM' }),
      true,
    );
    app.unmount();
  });

  it('shows PLATFORM departments and clears them for cross-domain searches', async () => {
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    expect(harness.getDeptList).toHaveBeenCalledOnce();
    expect(root.querySelector('section')).not.toBeNull();

    harness.accountDomainChange?.('MERCHANT');
    await flushAsyncWork();
    expect(root.querySelector('section')).toBeNull();
    await harness.query?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { accountDomain: 'MERCHANT' },
    );
    expect(harness.getUserList).toHaveBeenLastCalledWith(
      { accountDomain: 'MERCHANT', page: 1, pageSize: 20 },
      true,
    );

    harness.accountDomainChange?.('PLATFORM');
    await flushAsyncWork();
    expect(root.querySelector('section')).not.toBeNull();
    expect(harness.getDeptList).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('discards and clears an in-flight user response after the account domain changes', async () => {
    const pending = deferred<{
      items: Array<{
        accountDomain: 'PLATFORM';
        id: string;
        tenantId: string;
      }>;
      total: number;
    }>();
    harness.getUserList.mockReturnValueOnce(pending.promise);
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const response = harness.query?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { accountDomain: 'PLATFORM' },
    );
    harness.accountDomainChange?.('MERCHANT');

    expect(harness.gridApi.grid.reloadData).toHaveBeenCalledWith([]);
    pending.resolve({
      items: [{ accountDomain: 'PLATFORM', id: 'stale-user', tenantId: '1' }],
      total: 1,
    });
    await expect(response).resolves.toEqual({ items: [], total: 0 });

    app.unmount();
  });

  it('fails closed when the current user response has a mismatched account domain', async () => {
    harness.getUserList.mockResolvedValueOnce({
      items: [{ accountDomain: 'AGENT', id: 'wrong-user', tenantId: '3001' }],
      total: 1,
    });
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    await expect(
      harness.query?.(
        { page: { currentPage: 1, pageSize: 20 } },
        { accountDomain: 'MERCHANT' },
      ),
    ).resolves.toEqual({ items: [], total: 0 });

    app.unmount();
  });

  it('fails closed when an explicitly targeted tenant does not match the user response', async () => {
    harness.getUserList.mockResolvedValueOnce({
      items: [
        { accountDomain: 'MERCHANT', id: 'wrong-user', tenantId: '2002' },
      ],
      total: 1,
    });
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    await expect(
      harness.query?.(
        { page: { currentPage: 1, pageSize: 20 } },
        { accountDomain: 'MERCHANT', tenantId: '2001' },
      ),
    ).resolves.toEqual({ items: [], total: 0 });
    expect(harness.getUserList).toHaveBeenLastCalledWith(
      {
        accountDomain: 'MERCHANT',
        page: 1,
        pageSize: 20,
        tenantId: '2001',
      },
      true,
    );

    app.unmount();
  });

  it('accepts multiple tenants when every user belongs to the requested account domain', async () => {
    const directory = {
      items: [
        { accountDomain: 'MERCHANT', id: 'user-1', tenantId: '2001' },
        { accountDomain: 'MERCHANT', id: 'user-2', tenantId: '2002' },
      ],
      total: 2,
    };
    harness.getUserList.mockResolvedValueOnce(directory);
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    await expect(
      harness.query?.(
        { page: { currentPage: 1, pageSize: 20 } },
        { accountDomain: 'MERCHANT' },
      ),
    ).resolves.toEqual(directory);

    app.unmount();
  });

  it('keeps a cross-domain ordinary user read-only except for platform password reset', async () => {
    Object.assign(harness.row, {
      accountDomain: 'MERCHANT',
      systemAdministrator: false,
    });
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const actions = [...root.querySelectorAll('button')].map(
      (element) => element.textContent,
    );
    expect(actions).toContain('common.detail');
    expect(actions).not.toContain('common.edit');
    expect(actions).not.toContain('system.user.assignRole');
    expect(actions).toContain('system.user.resetPassword');
    expect(actions).not.toContain('common.delete');
    app.unmount();
  });

  it('allows editing and resetting a cross-domain system administrator without exposing delete', async () => {
    Object.assign(harness.row, {
      accountDomain: 'AGENT',
      systemAdministrator: true,
    });
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const actions = [...root.querySelectorAll('button')].map(
      (element) => element.textContent,
    );
    expect(actions).toContain('common.edit');
    expect(actions).not.toContain('system.user.assignRole');
    expect(actions).toContain('system.user.resetPassword');
    expect(actions).not.toContain('common.delete');
    app.unmount();
  });

  it('offers dedicated role assignment for a same-tenant user', async () => {
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    expect(
      [...root.querySelectorAll('button')].map(
        (element) => element.textContent,
      ),
    ).toContain('system.user.assignRole');
    app.unmount();
  });

  it('keeps three primary actions and exposes the remaining actions through click overflow', async () => {
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    const primaryActions = [
      ...root.querySelectorAll('[data-action-group="primary"] button'),
    ].map((element) => element.textContent);
    const overflowActions = [
      ...root.querySelectorAll('[data-action-group="overflow"] button'),
    ].map((element) => element.textContent);

    expect(primaryActions).toEqual([
      'common.detail',
      'common.edit',
      'system.user.assignRole',
    ]);
    expect(overflowActions).toEqual([
      'system.user.resetPassword',
      'common.delete',
    ]);
    expect(
      root.querySelector('[data-dropdown-trigger="click"]'),
    ).not.toBeNull();
    expect(root.querySelector('[data-icon]')).toBeNull();
    app.unmount();
  });

  it('hides the action from a non-system-administrator session', async () => {
    harness.systemAdministrator = false;
    const root = document.createElement('div');
    const app = createApp(UserList);
    app.directive('access', {});
    app.mount(root);
    await flushAsyncWork();

    expect(harness.keyField).toBe('id');
    expect(harness.gridControlPlane).toBe(false);
    await harness.query?.({ page: { currentPage: 1, pageSize: 20 } }, {});
    expect(harness.getUserList).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, pageSize: 20 }),
      false,
    );

    expect(
      [...root.querySelectorAll('button')].some(
        (element) => element.textContent === 'system.user.resetPassword',
      ),
    ).toBe(false);
    app.unmount();
  });
});
