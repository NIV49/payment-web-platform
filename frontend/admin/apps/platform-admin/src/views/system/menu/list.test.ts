import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MenuList from './list.vue';

const harness = vi.hoisted(() => ({
  accountDomainChange: undefined as ((value: unknown) => void) | undefined,
  tenantChange: undefined as ((value: unknown) => void) | undefined,
  getMenuList: vi.fn(),
  getPlatformMenuDirectory: vi.fn(),
  hasExactResponseContext: vi.fn(),
  operationColumnVisibility: [] as boolean[],
  gridQuery: undefined as
    | ((params: unknown, values: Record<string, unknown>) => Promise<unknown>)
    | undefined,
  setFieldValue: vi.fn(),
  setGridOptions: vi.fn(),
  reloadData: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
  useVbenDrawer: () => [
    { template: '<div />' },
    { setData: () => ({ open() {} }) },
  ],
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: { template: '<span />' },
  Plus: { template: '<span />' },
}));
vi.mock('@vben/stores', () => ({
  useUserStore: () => ({ userInfo: { systemAdministrator: true } }),
}));
vi.mock('@vben-core/menu-ui', () => ({ MenuBadge: { template: '<span />' } }));
vi.mock('antdv-next', () => ({
  Button: {
    emits: ['click'],
    template:
      '<button type="button" @click="$emit(\'click\')"><slot /></button>',
  },
  message: { loading: () => vi.fn(), success: vi.fn() },
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
  useAccountDomainDictionary: () => ({
    error: { value: undefined },
    options: { value: [] },
    reload: vi.fn(),
  }),
  useCommonStatusDictionary: () => ({
    error: { value: undefined },
    options: { value: [] },
    reload: vi.fn(),
  }),
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
    let target;
    if (
      values.accountDomain === 'MERCHANT' &&
      typeof values.tenantId === 'string'
    ) {
      target = { accountDomain: 'MERCHANT', tenantId: values.tenantId };
    } else if (values.accountDomain === 'PLATFORM') {
      target = { accountDomain: 'PLATFORM' };
    }
    return { query: {}, target };
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
vi.mock('#/adapter/vxe-table', () => ({
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
vi.mock('#/api', () => ({
  PERMISSION_CODES: {
    menuCreate: 'menu:create',
    menuDelete: 'menu:delete',
    menuUpdate: 'menu:update',
    menuView: 'menu:view',
  },
}));
vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));
vi.mock('#/api/system/menu', () => ({
  deleteMenu: vi.fn(),
  getMenuList: harness.getMenuList,
  getPlatformMenuDirectory: harness.getPlatformMenuDirectory,
  SystemMenuApi: {},
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({
  useColumns: (...args: unknown[]) => {
    harness.operationColumnVisibility.push(args[5] as boolean);
    return [];
  },
}));
vi.mock('./modules/form.vue', () => ({ default: { template: '<div />' } }));
vi.mock('./permission-contract', () => ({
  canPerformMenuAction: () => false,
}));

async function mountMenuList() {
  const root = document.createElement('div');
  const app = createApp(MenuList);
  app.directive('access', {});
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

describe('platform menu directory UI boundary', () => {
  beforeEach(() => {
    harness.accountDomainChange = undefined;
    harness.tenantChange = undefined;
    harness.getMenuList.mockReset();
    harness.getPlatformMenuDirectory.mockReset();
    harness.hasExactResponseContext.mockReset();
    harness.hasExactResponseContext.mockReturnValue(true);
    harness.gridQuery = undefined;
    harness.operationColumnVisibility = [];
    harness.setFieldValue.mockReset();
    harness.setGridOptions.mockReset();
    harness.reloadData.mockReset();
  });

  it('queries an exact merchant tenant and hides create in read-only mode', async () => {
    const { app, root } = await mountMenuList();

    harness.accountDomainChange?.('MERCHANT');
    await nextTick();
    await harness.gridQuery?.(undefined, {
      accountDomain: 'MERCHANT',
      tenantId: '2001',
    });

    expect(harness.setFieldValue).toHaveBeenCalledWith('tenantId', undefined);
    expect(harness.getPlatformMenuDirectory).toHaveBeenCalledWith({
      accountDomain: 'MERCHANT',
      tenantId: '2001',
    });
    expect(harness.getMenuList).not.toHaveBeenCalled();
    expect(root.querySelector('button')).toBeNull();
    app.unmount();
  });

  it('removes the operation column only while browsing a cross-domain directory', async () => {
    const { app } = await mountMenuList();

    expect(harness.operationColumnVisibility.at(-1)).toBe(true);

    harness.accountDomainChange?.('MERCHANT');
    await nextTick();
    expect(harness.operationColumnVisibility.at(-1)).toBe(false);
    expect(harness.setGridOptions).toHaveBeenCalledWith({ columns: [] });

    harness.accountDomainChange?.('PLATFORM');
    await nextTick();
    expect(harness.operationColumnVisibility.at(-1)).toBe(true);

    app.unmount();
  });

  it('discards an in-flight menu response after the tenant selection changes', async () => {
    const pending = deferred<Array<{ id: string }>>();
    harness.getPlatformMenuDirectory.mockReturnValueOnce(pending.promise);
    const { app } = await mountMenuList();

    const response = harness.gridQuery?.(undefined, {
      accountDomain: 'MERCHANT',
      tenantId: '2001',
    });
    harness.tenantChange?.('2002');

    expect(harness.reloadData).toHaveBeenCalledWith([]);
    pending.resolve([{ id: 'stale-menu' }]);
    await expect(response).resolves.toEqual([]);

    app.unmount();
  });

  it('fails closed when the current menu response has a mismatched context', async () => {
    const mismatched = [{ accountDomain: 'AGENT', id: 'wrong-menu' }];
    harness.getPlatformMenuDirectory.mockResolvedValueOnce(mismatched);
    harness.hasExactResponseContext.mockReturnValueOnce(false);
    const { app } = await mountMenuList();

    const target = { accountDomain: 'MERCHANT', tenantId: '2001' } as const;
    await expect(harness.gridQuery?.(undefined, target)).resolves.toEqual([]);
    expect(harness.hasExactResponseContext).toHaveBeenCalledWith(
      mismatched,
      target,
    );

    app.unmount();
  });
});
