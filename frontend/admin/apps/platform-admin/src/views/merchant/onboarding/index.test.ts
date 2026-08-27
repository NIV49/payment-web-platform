import { createApp, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantOnboardingPage from './index.vue';

const profileValues = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocumentId: '1',
  brandName: 'Example',
  businessLicenseDocumentId: '2',
  contactEmail: 'merchant@example.test',
  contactPhone: '+551100000000',
  displayName: 'Example Pay',
  industryCode: 'FINANCIAL_SERVICES',
  legalIdBackDocumentId: '4',
  legalIdFrontDocumentId: '3',
  legalIdHoldingDocumentId: '5',
  legalIdNo: 'ID-123',
  legalIdTypeCode: 'NATIONAL_ID',
  legalIdValidity: ['2026-01-01', '2036-01-01'],
  legalName: 'Example Ltd.',
  legalPersonName: 'Director',
  marketCodes: ['BRA'],
  merchantTypeCode: 'PLATFORM',
  operatingAddress: 'Operating address',
  registeredAddress: 'Registered address',
  registrationCountry: 'BR',
  registrationNumber: 'REG-123',
  remarks: '',
};

const detail = {
  ...profileValues,
  brandLogoDocument: { documentId: '1' },
  brandName: 'Example',
  businessLicenseDocument: { documentId: '2' },
  createdAt: '2026-08-15T08:00:00Z',
  lastDecision: null,
  legalIdBackDocument: { documentId: '4' },
  legalIdFrontDocument: { documentId: '3' },
  legalIdHoldingDocument: { documentId: '5' },
  legalIdNoMasked: '********0123',
  legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
  merchantCode: 'MCH_1',
  merchantId: '29',
  registrationNumberMasked: '********0123',
  reviewedAt: null,
  rowVersion: 7,
  status: 'ACTIVE',
  statusReasonCode: 'PROFILE_VERIFIED',
  submittedAt: '2026-08-15T08:00:00Z',
  tenantId: '17',
  updatedAt: '2026-08-15T08:00:00Z',
};

const harness = vi.hoisted(() => ({
  create: vi.fn(),
  eligible: vi.fn(),
  formApis: [] as any[],
  formOptions: [] as any[],
  getDetail: vi.fn(),
  getPending: vi.fn(),
  hasAccess: vi.fn<(codes: string[]) => boolean>(() => true),
  messageSuccess: vi.fn(),
  optimisticConflict: false,
  params: {} as Record<string, string>,
  push: vi.fn(),
  review: vi.fn(),
  submitAmendment: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: harness.params }),
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: harness.hasAccess }),
}));
vi.mock('@vben/common-ui', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  Page: { template: '<main><slot /></main>' },
}));
vi.mock('@payment/backoffice-runtime/adapter/form', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    useVbenForm: (options: any) => {
      const api = {
        getValues: vi.fn().mockResolvedValue(profileValues),
        setState: vi.fn(),
        setValues: vi.fn(),
        updateSchema: vi.fn(),
        validate: vi.fn().mockResolvedValue({ valid: true }),
      };
      harness.formApis.push(api);
      harness.formOptions.push(options);
      return [{ template: '<form />' }, api];
    },
  };
});
vi.mock('@payment/backoffice-runtime/api/merchant-lifecycle', () => ({
  getPlatformMerchant: harness.getDetail,
}));
vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => harness.optimisticConflict,
}));
vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  createPlatformMerchant: harness.create,
  getEligibleMerchantTenants: harness.eligible,
  getPendingMerchantAmendment: harness.getPending,
  reviewMerchantAmendment: harness.review,
  submitMerchantAmendment: harness.submitAmendment,
}));
vi.mock('@payment/backoffice-runtime/api/permission-codes', () => ({
  PERMISSION_CODES: {
    merchantAmend: 'merchant:amend',
    merchantCreate: 'merchant:create',
    merchantDocumentUpload: 'merchant:document:upload',
    merchantDocumentView: 'merchant:document:view',
    merchantReview: 'merchant:review',
    merchantView: 'merchant:view',
  },
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('antdv-next', () => ({
  Alert: { props: ['message'], template: '<div>{{ message }}<slot /></div>' },
  Button: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  Descriptions: { template: '<dl><slot /></dl>' },
  DescriptionsItem: { template: '<dd><slot /></dd>' },
  Modal: { template: '<div><slot /></div>' },
  Select: {
    props: {
      allowClear: { type: Boolean },
      disabled: { type: Boolean },
      options: { type: Array },
      value: { type: String },
    },
    template:
      '<select :data-allow-clear="String(allowClear)" :data-value="value" :disabled="disabled" />',
  },
  Spin: { template: '<div><slot /></div>' },
  Switch: { template: '<button />' },
  message: { success: harness.messageSuccess },
}));
vi.mock('../components/classification-dictionary-alert.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('../components/merchant-document-upload.vue', () => ({
  default: {
    props: ['amendmentId', 'disabled', 'kind', 'modelValue'],
    template:
      '<div :data-amendment-id="amendmentId" :data-disabled="String(disabled)" :data-document-id="modelValue" :data-document-kind="kind" />',
  },
}));
vi.mock('../merchant-classification-dictionary', () => ({
  useMerchantClassificationDictionary: () => ({
    authenticationTypeOptions: ref([]),
    error: ref(),
    industryOptions: ref([]),
    legalIdTypeOptions: ref([]),
    merchantTypeOptions: ref([]),
    reload: vi.fn(),
  }),
}));

function mountPage() {
  const root = document.createElement('div');
  const app = createApp(MerchantOnboardingPage);
  const instance = app.mount(root) as any;
  return { app, instance, root };
}

function deferred<T>() {
  let reject!: (reason?: unknown) => void;
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done, fail) => {
    reject = fail;
    resolve = done;
  });
  return { promise, reject, resolve };
}

function enterFormValues(values = profileValues) {
  const valueRecord = values as unknown as Record<string, unknown>;
  for (const options of harness.formOptions) {
    const fields = options.schema.map(
      ({ fieldName }: { fieldName: string }) => fieldName,
    );
    options.handleValuesChange(
      Object.fromEntries(
        fields.map((field: string) => [field, valueRecord[field]]),
      ),
      fields,
    );
  }
}

describe('pLATFORM merchant onboarding page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.formApis = [];
    harness.formOptions = [];
    harness.params = {};
    harness.hasAccess.mockReturnValue(true);
    harness.optimisticConflict = false;
    harness.eligible.mockResolvedValue({
      items: [
        { tenantCode: 'm-17', tenantId: '17', tenantName: 'Merchant 17' },
      ],
      total: 1,
    });
    harness.create.mockResolvedValue({
      merchantCode: 'MCH_1',
      merchantId: '29',
      rowVersion: 0,
      status: 'PENDING_REVIEW',
    });
    harness.getDetail.mockResolvedValue(detail);
    harness.getPending.mockResolvedValue(null);
  });

  it('creates from three VbenForm sections and returns to the list', async () => {
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues();

    await instance.submitForm();

    expect(harness.formApis).toHaveLength(3);
    expect(harness.create).toHaveBeenCalledWith(
      expect.objectContaining({
        idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
        targetTenantId: '17',
      }),
    );
    expect(harness.push).toHaveBeenCalledWith({ name: 'MerchantList' });
    expect(
      [...root.querySelectorAll('select')].every(
        (select) => select.dataset.allowClear === 'true',
      ),
    ).toBe(true);
    app.unmount();
  });

  it('submits a contract-valid profile without waiting on stalled adapter validation', async () => {
    const { app, instance } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues();
    harness.formApis.forEach((api) => {
      api.getValues.mockImplementation(() => new Promise(() => {}));
      api.validate.mockImplementation(() => new Promise(() => {}));
    });

    await expect(
      Promise.race([
        instance.submitForm(),
        new Promise((resolve) => setTimeout(() => resolve('timed-out'), 100)),
      ]),
    ).resolves.toBe(true);
    expect(harness.create).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('shows a visible error for a contract-invalid profile without waiting on adapter validation', async () => {
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues({ ...profileValues, legalName: '' });
    harness.formApis.forEach((api) => {
      api.getValues.mockImplementation(() => new Promise(() => {}));
      api.validate.mockImplementation(() => new Promise(() => {}));
    });

    await expect(
      Promise.race([
        instance.submitForm(),
        new Promise((resolve) => setTimeout(() => resolve('timed-out'), 100)),
      ]),
    ).resolves.toBe(false);
    expect(harness.create).not.toHaveBeenCalled();
    expect(root.textContent).toContain('merchant.onboarding.submitFailed');
    app.unmount();
  });

  it('ignores a create response that arrives after the page unmounts', async () => {
    const response = deferred<unknown>();
    harness.create.mockReturnValue(response.promise);
    const { app, instance } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues();

    const submitting = instance.submitForm();
    app.unmount();
    response.resolve({});

    await expect(submitting).resolves.toBe(false);
    expect(harness.messageSuccess).not.toHaveBeenCalled();
    expect(harness.push).not.toHaveBeenCalled();
  });

  it('freezes the target tenant and form values while create is submitting', async () => {
    const response = deferred<unknown>();
    harness.create.mockReturnValueOnce(response.promise).mockResolvedValueOnce({
      merchantCode: 'MCH_1',
      merchantId: '29',
      rowVersion: 0,
      status: 'PENDING_REVIEW',
    });
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues();

    const submitting = instance.submitForm();
    instance.setTargetTenant('18');
    enterFormValues({ ...profileValues, displayName: 'Changed While Saving' });
    await Promise.resolve();

    const targetSelect = root.querySelector('select');
    expect(targetSelect?.disabled).toBe(true);
    expect(targetSelect?.dataset.value).toBe('17');
    harness.formApis.forEach((api) =>
      expect(api.setState).toHaveBeenLastCalledWith(
        expect.objectContaining({
          commonConfig: expect.objectContaining({ disabled: true }),
        }),
      ),
    );

    response.resolve({});
    await submitting;
    await instance.submitForm();

    expect(harness.create.mock.calls[1]?.[0]).toEqual(
      expect.objectContaining({
        profile: expect.objectContaining({ displayName: 'Example Pay' }),
        targetTenantId: '17',
      }),
    );
    app.unmount();
  });

  it('ignores an amendment response that arrives after the page unmounts', async () => {
    harness.params = { merchantId: '29' };
    const response = deferred<unknown>();
    harness.submitAmendment.mockReturnValue(response.promise);
    const { app, instance } = mountPage();
    await instance.loadPage();
    enterFormValues();

    const submitting = instance.submitForm();
    app.unmount();
    response.resolve({});

    await expect(submitting).resolves.toBe(false);
    expect(harness.messageSuccess).not.toHaveBeenCalled();
    expect(harness.push).not.toHaveBeenCalled();
  });

  it('retries the same body with one key and rotates after a form edit', async () => {
    harness.create
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({
        merchantCode: 'MCH_1',
        merchantId: '29',
        rowVersion: 0,
        status: 'PENDING_REVIEW',
      });
    const { app, instance } = mountPage();
    await instance.loadPage();
    instance.setTargetTenant('17');
    enterFormValues();

    await instance.submitForm();
    await instance.submitForm();
    const sameKey = harness.create.mock.calls[0]?.[0].idempotencyKey;
    expect(sameKey).toBeTypeOf('string');
    expect(harness.create.mock.calls[1]?.[0].idempotencyKey).toBe(sameKey);

    harness.formOptions[0].handleValuesChange(
      { ...profileValues, displayName: 'Edited Example Pay' },
      ['displayName'],
    );
    await instance.submitForm();
    expect(harness.create.mock.calls[2]?.[0].idempotencyKey).not.toBe(sameKey);
    app.unmount();
  });

  it('initializes edit without plaintext and retains protected values and current documents', async () => {
    harness.params = { merchantId: '29' };
    const { app, instance } = mountPage();
    await instance.loadPage();
    enterFormValues();
    expect(harness.getDetail).toHaveBeenCalledWith('29');
    await vi.waitFor(() =>
      expect(harness.formApis[0].setValues).toHaveBeenCalled(),
    );

    expect(
      JSON.stringify(harness.formApis.map((api) => api.setValues.mock.calls)),
    ).not.toContain('********0123');
    await instance.submitForm();
    expect(harness.submitAmendment).toHaveBeenCalledWith(
      '29',
      expect.objectContaining({
        expectedMerchantVersion: 7,
        profile: expect.objectContaining({
          brandLogoDocumentId: '1',
          businessLicenseDocumentId: '2',
          legalIdBackDocumentId: '4',
          legalIdFrontDocumentId: '3',
          legalIdHoldingDocumentId: '5',
          legalIdNo: { mode: 'RETAIN' },
          registrationNumber: { mode: 'RETAIN' },
        }),
      }),
    );
    app.unmount();
  });

  it('hydrates each form once without repeatedly rebuilding all schemas', async () => {
    harness.params = { merchantId: '29' };
    const { app, instance } = mountPage();
    await instance.loadPage();

    for (const api of harness.formApis) {
      expect(api.setValues).toHaveBeenCalledTimes(1);
      expect(api.setState).toHaveBeenCalledTimes(1);
    }
    app.unmount();
  });

  it.each([
    ['registrationCountry', 'SG', 'registrationNumber', 'NEW-REG'],
    ['legalIdTypeCode', 'PASSPORT', 'legalIdNo', 'NEW-ID'],
  ] as const)(
    'forces protected replacement when %s changes',
    async (changedField, changedValue, protectedField, protectedValue) => {
      harness.params = { merchantId: '29' };
      const { app, instance } = mountPage();
      await instance.loadPage();
      enterFormValues({
        ...profileValues,
        [changedField]: changedValue,
        [protectedField]: protectedValue,
      });

      await instance.submitForm();

      expect(harness.submitAmendment).toHaveBeenCalledWith(
        '29',
        expect.objectContaining({
          profile: expect.objectContaining({
            [protectedField]: { mode: 'REPLACE', value: protectedValue },
          }),
        }),
      );
      app.unmount();
    },
  );

  it('allows RETAIN again after a coupled classification returns to its original value', async () => {
    harness.params = { merchantId: '29' };
    const { app, instance } = mountPage();
    await instance.loadPage();
    enterFormValues({ ...profileValues, registrationCountry: 'SG' });
    enterFormValues(profileValues);
    instance.setReplacement('registrationNumber', false);

    await instance.submitForm();

    expect(harness.submitAmendment).toHaveBeenCalledWith(
      '29',
      expect.objectContaining({
        profile: expect.objectContaining({
          registrationNumber: { mode: 'RETAIN' },
        }),
      }),
    );
    app.unmount();
  });

  it('fails closed before loading or submitting when route permissions are absent', async () => {
    harness.hasAccess.mockReturnValue(false);
    const { app, instance } = mountPage();
    await instance.loadPage();

    expect(harness.eligible).not.toHaveBeenCalled();
    expect(harness.getDetail).not.toHaveBeenCalled();
    await expect(instance.submitForm()).resolves.toBe(false);
    expect(harness.create).not.toHaveBeenCalled();
    app.unmount();
  });

  it('fails closed when create receives only one permission from the required set', async () => {
    const granted = new Set(['merchant:create']);
    harness.hasAccess.mockImplementation((codes: string[]) =>
      codes.some((code) => granted.has(code)),
    );
    const { app, instance } = mountPage();
    await instance.loadPage();

    expect(harness.eligible).not.toHaveBeenCalled();
    instance.setTargetTenant('17');
    await expect(instance.submitForm()).resolves.toBe(false);
    expect(harness.create).not.toHaveBeenCalled();
    app.unmount();
  });

  it('fails closed when edit receives only one permission from the required set', async () => {
    harness.params = { merchantId: '29' };
    const granted = new Set(['merchant:view']);
    harness.hasAccess.mockImplementation((codes: string[]) =>
      codes.some((code) => granted.has(code)),
    );
    const { app, instance } = mountPage();
    await instance.loadPage();

    expect(harness.getDetail).not.toHaveBeenCalled();
    expect(harness.getPending).not.toHaveBeenCalled();
    await expect(instance.submitForm()).resolves.toBe(false);
    expect(harness.submitAmendment).not.toHaveBeenCalled();
    app.unmount();
  });

  it('does not issue edit reads when merchant:view is missing', async () => {
    harness.params = { merchantId: '29' };
    harness.hasAccess.mockImplementation((codes: string[]) =>
      codes.every((code) => code !== 'merchant:view'),
    );
    const { app, instance } = mountPage();
    await instance.loadPage();

    expect(harness.getDetail).not.toHaveBeenCalled();
    expect(harness.getPending).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps the STALE warning visible after reloading effective data', async () => {
    harness.params = { merchantId: '29' };
    harness.optimisticConflict = true;
    harness.submitAmendment.mockRejectedValue(new Error('stale'));
    const { app, instance, root } = mountPage();
    await instance.loadPage();

    await instance.submitForm();

    expect(root.textContent).toContain('merchant.onboarding.stale');
    app.unmount();
  });
});
