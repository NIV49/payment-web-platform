import type { SystemRoleApi } from '@payment/backoffice-runtime/api/system/role';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import RoleList from './list.vue';

const harness = vi.hoisted(() => ({
  accessCodes: new Set<string>(),
  canEditRole: undefined as
    | ((row: SystemRoleApi.SystemRole) => boolean)
    | undefined,
  canAssignUsers: undefined as
    | ((row: SystemRoleApi.SystemRole) => boolean)
    | undefined,
  accountDomainChange: undefined as ((value: unknown) => void) | undefined,
  tenantChange: undefined as ((value: unknown) => void) | undefined,
  getPlatformRoleDirectory: vi.fn(),
  getRoleList: vi.fn(),
  hasExactResponseContext: vi.fn(),
  operationColumnVisibility: [] as boolean[],
  gridQuery: undefined as
    | ((
        params: { page: { currentPage: number; pageSize: number } },
        formValues: Record<string, unknown>,
      ) => Promise<unknown>)
    | undefined,
  setFieldValue: vi.fn(),
  setGridOptions: vi.fn(),
  reloadData: vi.fn(),
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

vi.mock('@payment/backoffice-runtime/deployment-internal', () => ({
  getInstalledBackofficeDeployment: () => ({ accountDomain: 'PLATFORM' }),
}));

vi.mock('@payment/backoffice-runtime/views/system/platform-directory', () => ({
  createPlatformDirectoryRequestGuard: () => {
    let generation = 0;
    return {
      begin: () => ({ generation: ++generation }),
      invalidate: () => {
        generation += 1;
      },
      isCurrent: (token: { generation: number }) =>
        token.generation === generation,
    };
  },
  hasExactPlatformDirectoryResponseContext: harness.hasExactResponseContext,
  splitPlatformDirectoryQuery: (values: Record<string, unknown>) => {
    const { accountDomain, tenantId, ...query } = values;
    let target;
    if (accountDomain === 'MERCHANT' && typeof tenantId === 'string') {
      target = { accountDomain, tenantId };
    } else if (accountDomain === 'PLATFORM') {
      target = { accountDomain };
    }
    return {
      query,
      target,
    };
  },
  usePlatformDirectoryFilterSchema: (
    _getOptions: unknown,
    onAccountDomainChange: (value: unknown) => void,
    onTenantChange: (value: unknown) => void,
  ) => {
    harness.accountDomainChange = onAccountDomainChange;
    harness.tenantChange = onTenantChange;
    return [];
  },
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

vi.mock('@payment/backoffice-runtime/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: any) => {
    harness.gridQuery = options.gridOptions.proxyConfig.ajax.query;
    return [
      defineComponent({
        setup(_props, { slots }) {
          return () => h('div', slots['toolbar-tools']?.());
        },
      }),
      {
        formApi: { setFieldValue: harness.setFieldValue },
        grid: { reloadData: harness.reloadData },
        query: vi.fn(),
        setGridOptions: harness.setGridOptions,
      },
    ];
  },
}));

vi.mock('@payment/backoffice-runtime/api', async () => {
  const { PERMISSION_CODES } =
    await import('@payment/backoffice-runtime/api/permission-codes');
  return {
    PERMISSION_CODES,
    deleteRole: vi.fn(),
    getPlatformRoleDirectory: harness.getPlatformRoleDirectory,
    getRoleList: harness.getRoleList,
    updateRoleStatus: vi.fn(),
  };
});

vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));

vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('./data', () => ({
  useColumns: (
    _onActionClick: unknown,
    _onStatusChange: unknown,
    _canChangeRoleStatus: unknown,
    canEditRole: (row: SystemRoleApi.SystemRole) => boolean,
    canAssignUsers: (row: SystemRoleApi.SystemRole) => boolean,
    _canDeleteRole: unknown,
    _getStatusOptions: unknown,
    _showDirectoryContext: unknown,
    _getAccountDomainOptions: unknown,
    showOperationColumn: boolean,
  ) => {
    harness.canEditRole = canEditRole;
    harness.canAssignUsers = canAssignUsers;
    harness.operationColumnVisibility.push(showOperationColumn);
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
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
    harness.canAssignUsers = undefined;
    harness.accountDomainChange = undefined;
    harness.tenantChange = undefined;
    harness.getPlatformRoleDirectory.mockReset();
    harness.getRoleList.mockReset();
    harness.hasExactResponseContext.mockReset();
    harness.hasExactResponseContext.mockReturnValue(true);
    harness.gridQuery = undefined;
    harness.operationColumnVisibility = [];
    harness.setFieldValue.mockReset();
    harness.setGridOptions.mockReset();
    harness.reloadData.mockReset();
    harness.systemAdministrator = true;
  });

  it('offers member assignment only for an ordinary role with assignment permissions', async () => {
    harness.accessCodes.add('user:assign-role');
    harness.accessCodes.add('user:view');
    const { app } = await mountRoleList();

    expect(harness.canAssignUsers?.(ordinaryRole)).toBe(true);
    expect(
      harness.canAssignUsers?.({
        ...ordinaryRole,
        assignable: false,
        systemRole: true,
      }),
    ).toBe(false);
    app.unmount();
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

  it('uses an exact tenant-bound directory and makes cross-domain rows read-only', async () => {
    const { app, root } = await mountRoleList();
    const crossDomainRole = {
      ...ordinaryRole,
      accountDomain: 'MERCHANT' as const,
      managementMode: 'READ_ONLY' as const,
      tenantId: '2001',
      tenantName: 'Merchant A',
    };

    harness.accountDomainChange?.('MERCHANT');
    await nextTick();
    await harness.gridQuery?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { accountDomain: 'MERCHANT', name: 'Risk', tenantId: '2001' },
    );

    expect(harness.setFieldValue).toHaveBeenCalledWith('tenantId', undefined);
    expect(harness.getPlatformRoleDirectory).toHaveBeenCalledWith(
      { name: 'Risk', page: 1, pageSize: 20 },
      { accountDomain: 'MERCHANT', tenantId: '2001' },
    );
    expect(root.querySelector('button')).toBeNull();
    expect(harness.canEditRole?.(crossDomainRole)).toBe(false);
    expect(harness.canAssignUsers?.(crossDomainRole)).toBe(false);
    app.unmount();
  });

  it('removes the operation column only while browsing a cross-domain directory', async () => {
    const { app } = await mountRoleList();

    expect(harness.operationColumnVisibility.at(-1)).toBe(true);

    harness.accountDomainChange?.('AGENT');
    await nextTick();
    expect(harness.operationColumnVisibility.at(-1)).toBe(false);
    expect(harness.setGridOptions).toHaveBeenCalledWith({ columns: [] });

    harness.accountDomainChange?.('PLATFORM');
    await nextTick();
    expect(harness.operationColumnVisibility.at(-1)).toBe(true);

    app.unmount();
  });

  it('discards an in-flight role response after the tenant selection changes', async () => {
    const pending = deferred<{ items: Array<{ id: string }>; total: number }>();
    harness.getPlatformRoleDirectory.mockReturnValueOnce(pending.promise);
    const { app } = await mountRoleList();

    const response = harness.gridQuery?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { accountDomain: 'MERCHANT', tenantId: '2001' },
    );
    harness.tenantChange?.('2002');

    expect(harness.reloadData).toHaveBeenCalledWith([]);
    pending.resolve({ items: [{ id: 'stale-role' }], total: 1 });
    await expect(response).resolves.toEqual({ items: [], total: 0 });

    app.unmount();
  });

  it('fails closed when the current role response has a mismatched context', async () => {
    const mismatched = {
      items: [{ accountDomain: 'AGENT', id: 'wrong-role' }],
      total: 1,
    };
    harness.getPlatformRoleDirectory.mockResolvedValueOnce(mismatched);
    harness.hasExactResponseContext.mockReturnValueOnce(false);
    const { app } = await mountRoleList();

    const target = { accountDomain: 'MERCHANT', tenantId: '2001' } as const;
    await expect(
      harness.gridQuery?.({ page: { currentPage: 1, pageSize: 20 } }, target),
    ).resolves.toEqual({ items: [], total: 0 });
    expect(harness.hasExactResponseContext).toHaveBeenCalledWith(
      mismatched.items,
      target,
    );

    app.unmount();
  });
});
