/* eslint-disable vue/one-component-per-file */
import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import DictionaryDataList from './list.vue';

const harness = vi.hoisted(() => ({
  actionClick: undefined as ((event: any) => void) | undefined,
  formOpen: vi.fn(),
  formSetData: vi.fn(),
  getDictionaryData: vi.fn(),
  gridQuery: undefined as
    | ((
        params: { page: { currentPage: number; pageSize: number } },
        formValues: Record<string, unknown>,
      ) => Promise<unknown>)
    | undefined,
  query: vi.fn(),
  reloadData: vi.fn(),
  route: undefined as any,
}));

vi.mock('vue-router', () => {
  harness.route = reactive({ query: { dictType: 'TYPE_A' } });
  return {
    useRoute: () => harness.route,
    useRouter: () => ({ push: vi.fn() }),
  };
});

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
  useVbenModal: () => [
    { template: '<div />' },
    {
      setData: harness.formSetData.mockImplementation(() => ({
        open: harness.formOpen,
      })),
    },
  ],
}));
vi.mock('@vben/icons', () => ({ Plus: { template: '<span />' } }));
vi.mock(
  '@payment/backoffice-runtime/components/dictionary-type-selector',
  () => ({ default: { template: '<div />' } }),
);
vi.mock('@payment/backoffice-runtime/views/system/dictionary-data', () => ({
  dictionaryDataRouteLocation: vi.fn(),
  resolveDictionaryTypeQuery: (value: unknown) =>
    typeof value === 'string' ? value : undefined,
  useColumns: (onActionClick: (event: any) => void) => {
    harness.actionClick = onActionClick;
    return [];
  },
  useGridFormSchema: () => [],
}));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    setup(_props, { slots }) {
      return () => h('button', slots.default?.());
    },
  }),
  message: { loading: () => vi.fn(), success: vi.fn() },
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
        grid: { reloadData: harness.reloadData },
        query: harness.query,
      },
    ];
  },
}));
vi.mock('#/api', () => ({
  getDictionaryData: harness.getDictionaryData,
  PERMISSION_CODES: { dictionaryUpdate: 'dictionary:update' },
}));
vi.mock('#/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));
vi.mock('#/api/system/dictionary', () => ({
  deleteDictionaryData: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./modules/form.vue', () => ({ default: { template: '<div />' } }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function mountList() {
  const root = document.createElement('div');
  const app = createApp(DictionaryDataList);
  app.mount(root);
  await nextTick();
  return app;
}

describe('dictionary data list scope boundary', () => {
  beforeEach(() => {
    harness.actionClick = undefined;
    harness.formOpen.mockReset();
    harness.formSetData.mockClear();
    harness.getDictionaryData.mockReset();
    harness.gridQuery = undefined;
    harness.query.mockReset();
    harness.reloadData.mockReset();
    harness.route.query.dictType = 'TYPE_A';
  });

  it('clears the grid and discards an old dictionary response after the type changes', async () => {
    const pending = deferred<{
      items: Array<{ dictType: string }>;
      total: number;
    }>();
    harness.getDictionaryData.mockReturnValueOnce(pending.promise);
    const app = await mountList();

    const response = harness.gridQuery?.(
      { page: { currentPage: 1, pageSize: 20 } },
      {},
    );
    harness.route.query.dictType = 'TYPE_B';
    await nextTick();

    expect(harness.reloadData).toHaveBeenCalledWith([]);
    expect(harness.query).not.toHaveBeenCalled();
    pending.resolve({ items: [{ dictType: 'TYPE_A' }], total: 1 });
    await expect(response).resolves.toEqual({ items: [], total: 0 });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(harness.query).toHaveBeenCalledOnce();

    app.unmount();
  });

  it('fails closed when any current response row has another dictionary type', async () => {
    harness.getDictionaryData.mockResolvedValueOnce({
      items: [{ dictType: 'TYPE_A' }, { dictType: 'TYPE_B' }],
      total: 2,
    });
    const app = await mountList();

    await expect(
      harness.gridQuery?.({ page: { currentPage: 1, pageSize: 20 } }, {}),
    ).resolves.toEqual({ items: [], total: 0 });

    app.unmount();
  });

  it('preserves a current response whose rows all match the dictionary type', async () => {
    const currentPage = {
      items: [{ dictCode: '1', dictType: 'TYPE_A' }],
      total: 1,
    };
    harness.getDictionaryData.mockResolvedValueOnce(currentPage);
    const app = await mountList();

    await expect(
      harness.gridQuery?.({ page: { currentPage: 1, pageSize: 20 } }, {}),
    ).resolves.toBe(currentPage);

    app.unmount();
  });

  it('opens edit only for a row in the current dictionary type', async () => {
    const app = await mountList();

    harness.actionClick?.({
      code: 'edit',
      row: { dictCode: '1', dictType: 'TYPE_B' },
    });
    expect(harness.formSetData).not.toHaveBeenCalled();

    const currentRow = { dictCode: '2', dictType: 'TYPE_A' };
    harness.actionClick?.({ code: 'edit', row: currentRow });
    expect(harness.formSetData).toHaveBeenCalledWith({
      dictType: 'TYPE_A',
      row: currentRow,
    });
    expect(harness.formOpen).toHaveBeenCalledOnce();

    app.unmount();
  });
});
