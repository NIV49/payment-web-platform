import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantList from './list.vue';

const harness = vi.hoisted(() => ({
  allowedCodes: new Set<string>(),
  busy: false,
  disable: vi.fn(),
  gridQuery: undefined as
    | ((params: any, values: Record<string, unknown>) => Promise<unknown>)
    | undefined,
  gridApi: {
    grid: { reloadData: vi.fn() },
    query: vi.fn(),
    reload: undefined as unknown as (
      values: Record<string, unknown>,
    ) => Promise<void>,
  },
  gridOptions: undefined as any,
  list: vi.fn(),
  modalClose: vi.fn(),
  modalOpen: vi.fn(),
  modalOptions: undefined as any,
  messageSuccess: vi.fn(),
  routerPush: vi.fn(),
  rawReload: vi.fn(),
  reloadPromises: [] as Array<Promise<void>>,
  renderedItems: [] as Array<Record<string, unknown>>,
  row: {
    marketCodes: ['BRA'],
    merchantId: '9007199254740993',
    reviewPending: false,
    rowVersion: 3,
    status: 'ACTIVE',
  } as Record<string, any>,
  stateConflict: false,
  statusFormOptions: undefined as any,
  statusFormValues: vi.fn(),
  statusFormValidate: vi.fn(),
  enable: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.routerPush }),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.allowedCodes.has(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
  useVbenModal: (options: any) => {
    harness.modalOptions = options;
    return [
      { template: '<div><slot /></div>' },
      {
        close: harness.modalClose,
        lock: vi.fn(),
        open: harness.modalOpen,
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot /></div>' },
  Button: defineComponent({
    emits: ['click'],
    setup(_props, { emit, slots }) {
      return () =>
        h(
          'button',
          { 'data-test': 'merchant-add', onClick: () => emit('click') },
          slots.default?.(),
        );
    },
  }),
  Popover: { template: '<div><slot /><slot name="content" /></div>' },
  Switch: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['change'],
    setup(props, { emit }) {
      return () =>
        h('button', {
          'data-test': 'merchant-switch',
          disabled: props.disabled,
          onClick: () => emit('change', !props.checked),
        });
    },
  }),
  Tag: { template: '<span data-test="merchant-tag"><slot /></span>' },
  message: { success: harness.messageSuccess },
}));
vi.mock('@payment/backoffice-runtime/adapter/form', () => ({
  useVbenForm: (options: any) => {
    harness.statusFormOptions = options;
    return [
      { template: '<form />' },
      {
        getValues: harness.statusFormValues,
        reset: vi.fn(),
        validate: harness.statusFormValidate,
      },
    ];
  },
  z: { string: () => ({ min: () => ({}) }) },
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: any) => {
    harness.gridOptions = options.gridOptions;
    harness.gridQuery = options.gridOptions.proxyConfig.ajax.query;
    return [
      defineComponent({
        setup(_props, { slots }) {
          const row = harness.row;
          const search = (status: string) => {
            harness.reloadPromises.push(harness.gridApi.reload({ status }));
          };
          return () =>
            h('div', [
              ...(slots['toolbar-tools']?.() ?? []),
              ...(slots.action?.({ row }) ?? []),
              ...(slots.merchantTypeCode?.({ row }) ?? []),
              ...(slots.authenticationType?.({ row }) ?? []),
              ...(slots.status?.({ row }) ?? []),
              h(
                'button',
                {
                  'data-test': 'search-active',
                  onClick: () => search('ACTIVE'),
                },
                'search active',
              ),
              h(
                'button',
                {
                  'data-test': 'search-disabled',
                  onClick: () => search('DISABLED'),
                },
                'search disabled',
              ),
            ]);
        },
      }),
      harness.gridApi,
    ];
  },
  VbenTableAction: defineComponent({
    props: ['actions', 'dropdownActions'],
    setup(props) {
      return () =>
        h('div', [
          ...(props.actions ?? []).map((action: any) =>
            h(
              'button',
              {
                'data-test': action.text,
                disabled: action.disabled,
                onClick: action.onClick,
              },
              action.text,
            ),
          ),
          ...(props.dropdownActions ?? []).map((action: any) =>
            h('button', { onClick: action.onClick }, action.text),
          ),
        ]);
    },
  }),
}));
vi.mock('@payment/backoffice-runtime/api', () => ({
  PERMISSION_CODES: {
    merchantAmend: 'merchant:amend',
    merchantCreate: 'merchant:create',
    merchantDisable: 'merchant:disable',
    merchantDocumentView: 'merchant:document:view',
    merchantEnable: 'merchant:enable',
    merchantReview: 'merchant:review',
    merchantView: 'merchant:view',
  },
  disableMerchant: harness.disable,
  enableMerchant: harness.enable,
  getPlatformMerchants: harness.list,
  isMerchantStateConflict: () => harness.stateConflict,
}));
vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => harness.stateConflict,
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({
  marketLabel: (code: string) => code,
  merchantColumns: () => [],
  merchantSearchSchema: () => [],
  statusOption: (status: string) => ({ label: status }),
}));
vi.mock('./merchant-classification-dictionary', () => ({
  useMerchantClassificationDictionary: () => ({
    authenticationTypeOptions: { value: [] },
    error: { value: undefined },
    merchantTypeOptions: { value: [] },
    reload: vi.fn(),
  }),
}));
vi.mock('./components/classification-dictionary-alert.vue', () => ({
  default: { template: '<div />' },
}));

describe('pLATFORM merchant list page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.busy = false;
    harness.allowedCodes = new Set([
      'merchant:amend',
      'merchant:create',
      'merchant:disable',
      'merchant:document:view',
      'merchant:enable',
      'merchant:review',
      'merchant:view',
    ]);
    harness.row = {
      marketCodes: ['BRA'],
      merchantId: '9007199254740993',
      reviewPending: false,
      rowVersion: 3,
      status: 'ACTIVE',
    };
    harness.stateConflict = false;
    harness.reloadPromises = [];
    harness.renderedItems = [];
    harness.list.mockResolvedValue({ items: [], total: 0 });
    harness.statusFormValues.mockResolvedValue({ reasonCode: 'RISK_CONTROL' });
    harness.statusFormValidate.mockResolvedValue({ valid: true });
    harness.rawReload.mockImplementation(async (values) => {
      if (harness.busy) return;
      harness.busy = true;
      try {
        const response = (await harness.gridQuery?.(
          { page: { currentPage: 1, pageSize: 20 } },
          values,
        )) as undefined | { items: Array<Record<string, unknown>> };
        harness.renderedItems = response?.items ?? [];
      } finally {
        harness.busy = false;
      }
    });
    harness.gridApi.reload = harness.rawReload;
  });

  it('queries the exact control-plane list without tenant selectors', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    await harness.gridQuery?.(
      { page: { currentPage: 2, pageSize: 20 } },
      {
        authenticationType: 'ENTERPRISE',
        merchantCode: 'MCH_1',
        merchantTypeCode: 'DIRECT',
        status: 'ACTIVE',
      },
    );

    expect(harness.list).toHaveBeenCalledWith({
      authenticationType: 'ENTERPRISE',
      merchantCode: 'MCH_1',
      merchantTypeCode: 'DIRECT',
      page: 2,
      pageSize: 20,
      status: 'ACTIVE',
    });
    expect(harness.list.mock.calls[0]?.[0]).not.toHaveProperty('tenantId');
    app.unmount();
  });

  it('opens the full detail page for the string Merchant ID', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    (
      root.querySelector('[data-test="common.detail"]') as HTMLButtonElement
    ).click();

    expect(harness.routerPush).toHaveBeenCalledWith({
      name: 'MerchantDetail',
      params: { merchantId: '9007199254740993' },
    });
    app.unmount();
  });

  it('exposes edit and opens the reason modal before a controlled status change', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    expect(root.querySelector('[data-test="common.edit"]')).not.toBeNull();
    (
      root.querySelector('[data-test="common.edit"]') as HTMLButtonElement
    ).click();
    expect(harness.routerPush).toHaveBeenCalledWith({
      name: 'MerchantOnboarding',
      params: { merchantId: '9007199254740993' },
    });
    (
      root.querySelector('[data-test="merchant-switch"]') as HTMLButtonElement
    ).click();

    expect(harness.modalOpen).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('opens the same full page without an ID for assisted creation', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    (
      root.querySelector('[data-test="merchant-add"]') as HTMLButtonElement
    ).click();

    expect(harness.routerPush).toHaveBeenCalledWith({
      name: 'MerchantOnboarding',
    });
    app.unmount();
  });

  it('offers a discoverable review route to a least-privilege reviewer', async () => {
    harness.allowedCodes = new Set([
      'merchant:document:view',
      'merchant:review',
      'merchant:view',
    ]);
    harness.row.reviewPending = true;
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    const review = root.querySelector(
      '[data-test="merchant.detail.reviewAction"]',
    ) as HTMLButtonElement;
    expect(review).not.toBeNull();
    expect(review.disabled).toBe(false);
    review.click();
    expect(harness.routerPush).toHaveBeenCalledWith({
      name: 'MerchantReview',
      params: { merchantId: '9007199254740993' },
    });
    app.unmount();
  });

  it('does not open review without the exact review permission', async () => {
    harness.allowedCodes = new Set(['merchant:document:view', 'merchant:view']);
    harness.row.reviewPending = true;
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    const review = root.querySelector(
      '[data-test="merchant.detail.reviewAction"]',
    ) as HTMLButtonElement;
    expect(review).not.toBeNull();
    expect(review.disabled).toBe(true);
    review.click();
    expect(harness.routerPush).not.toHaveBeenCalled();
    app.unmount();
  });

  it('does not render review after the row has no pending review work', async () => {
    harness.row.status = 'ACTIVE';
    harness.row.reviewPending = false;
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    expect(
      root.querySelector('[data-test="merchant.detail.reviewAction"]'),
    ).toBeNull();
    app.unmount();
  });

  it('cancels a status change without mutating the row or calling the API', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    clickStatusSwitch(root);
    harness.modalOptions.onOpenChange(false);
    await nextTick();

    expect(harness.row.status).toBe('ACTIVE');
    expect(harness.disable).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps the row unchanged after an ordinary status API failure', async () => {
    harness.disable.mockRejectedValue(new Error('network failed'));
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    clickStatusSwitch(root);
    await harness.modalOptions.onConfirm();

    expect(harness.row.status).toBe('ACTIVE');
    expect(harness.gridApi.query).not.toHaveBeenCalled();
    app.unmount();
  });

  it('refreshes after a successful status command without optimistic row mutation', async () => {
    harness.disable.mockResolvedValue({ status: 'DISABLED' });
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    clickStatusSwitch(root);
    await harness.modalOptions.onConfirm();

    expect(harness.disable).toHaveBeenCalledWith('9007199254740993', {
      expectedVersion: 3,
      idempotencyKey: expect.any(String),
      reasonCode: 'RISK_CONTROL',
    });
    expect(harness.row.status).toBe('ACTIVE');
    expect(harness.gridApi.query).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('refreshes a stale row after a 409 status conflict', async () => {
    harness.stateConflict = true;
    harness.disable.mockRejectedValue(new Error('conflict'));
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    clickStatusSwitch(root);
    await harness.modalOptions.onConfirm();

    expect(harness.row.status).toBe('ACTIVE');
    expect(harness.gridApi.query).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('renders non-binary lifecycle states as tags rather than disabled switches', async () => {
    harness.row.status = 'PENDING_REVIEW';
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    expect(root.querySelector('[data-test="merchant-switch"]')).toBeNull();
    expect(root.querySelector('[data-test="merchant-tag"]')?.textContent).toBe(
      'PENDING_REVIEW',
    );
    app.unmount();
  });

  it('renders historical null classification values as dashes, not empty tags', async () => {
    harness.row.merchantTypeCode = null;
    harness.row.authenticationType = null;
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    expect(
      root.querySelector('[data-test="merchant-type-fallback"]')?.textContent,
    ).toBe('-');
    expect(
      root.querySelector('[data-test="authentication-type-fallback"]')
        ?.textContent,
    ).toBe('-');
    expect(root.querySelectorAll('[data-test="merchant-tag"]')).toHaveLength(0);
    app.unmount();
  });

  it('disables status and edit controls without their exact permissions', async () => {
    harness.allowedCodes = new Set(['merchant:view']);
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    expect(
      (root.querySelector('[data-test="merchant-switch"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(
      (root.querySelector('[data-test="common.edit"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    app.unmount();
  });

  it('queues the latest real grid reload and never renders the older response', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    harness.list
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveFirst = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveSecond = resolve;
        }),
      );
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);

    (
      root.querySelector('[data-test="search-active"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(harness.list).toHaveBeenCalledTimes(1));
    (
      root.querySelector('[data-test="search-disabled"]') as HTMLButtonElement
    ).click();

    expect(harness.rawReload).toHaveBeenCalledTimes(1);
    expect(harness.list).toHaveBeenCalledTimes(1);
    resolveFirst({ items: [{ merchantId: '1' }], total: 1 });
    await vi.waitFor(() => expect(harness.list).toHaveBeenCalledTimes(2));
    resolveSecond({ items: [{ merchantId: '2' }], total: 1 });
    await Promise.all(harness.reloadPromises);
    expect(harness.renderedItems).toEqual([{ merchantId: '2' }]);
    app.unmount();
  });

  it('runs the latest search after an initial raw query finishes', async () => {
    let resolveInitial!: (value: unknown) => void;
    let resolveSearch!: (value: unknown) => void;
    harness.list
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveInitial = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveSearch = resolve;
        }),
      );
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);

    const initial = harness.rawReload({});
    await vi.waitFor(() => expect(harness.list).toHaveBeenCalledTimes(1));
    (
      root.querySelector('[data-test="search-disabled"]') as HTMLButtonElement
    ).click();

    expect(harness.list).toHaveBeenCalledTimes(1);
    resolveInitial({ items: [{ merchantId: 'initial' }], total: 1 });
    await initial;
    await vi.waitFor(() => expect(harness.list).toHaveBeenCalledTimes(2));
    resolveSearch({ items: [{ merchantId: 'search' }], total: 1 });
    await Promise.all(harness.reloadPromises);

    expect(harness.renderedItems).toEqual([{ merchantId: 'search' }]);
    app.unmount();
  });

  it('settles a queued reload without starting another query after unmount', async () => {
    let resolveInitial!: (value: unknown) => void;
    harness.list
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveInitial = resolve;
        }),
      )
      .mockResolvedValueOnce({ items: [], total: 0 });
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);

    (
      root.querySelector('[data-test="search-active"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(harness.list).toHaveBeenCalledOnce());
    (
      root.querySelector('[data-test="search-disabled"]') as HTMLButtonElement
    ).click();
    app.unmount();
    resolveInitial({ items: [], total: 0 });

    await expect(Promise.all(harness.reloadPromises)).resolves.toBeDefined();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(harness.list).toHaveBeenCalledOnce();
  });

  it('ignores a status response that arrives after unmount', async () => {
    let resolveDisable!: (value: unknown) => void;
    harness.disable.mockReturnValue(
      new Promise((resolve) => {
        resolveDisable = resolve;
      }),
    );
    const root = document.createElement('div');
    const app = createApp(MerchantList);
    app.mount(root);
    await nextTick();

    clickStatusSwitch(root);
    const confirming = harness.modalOptions.onConfirm();
    await vi.waitFor(() => expect(harness.disable).toHaveBeenCalledOnce());
    app.unmount();
    resolveDisable({ status: 'DISABLED' });
    await confirming;

    expect(harness.messageSuccess).not.toHaveBeenCalled();
    expect(harness.gridApi.query).not.toHaveBeenCalled();
    expect(harness.modalClose).not.toHaveBeenCalled();
  });
});

it('offers only page sizes accepted by the Merchant list contract', () => {
  const root = document.createElement('div');
  const app = createApp(MerchantList);
  app.mount(root);

  expect(harness.gridOptions.pagerConfig.pageSizes).toEqual([
    10, 20, 30, 50, 100,
  ]);
  expect(harness.gridOptions.pagerConfig.pageSizes).not.toContain(200);
  app.unmount();
});

function clickStatusSwitch(root: HTMLElement) {
  (
    root.querySelector('[data-test="merchant-switch"]') as HTMLButtonElement
  ).click();
}
