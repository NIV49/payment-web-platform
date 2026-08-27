import { createApp, defineComponent, onMounted } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantProfile from './profile.vue';

const harness = vi.hoisted(() => ({
  formMounted: false,
  getValues: vi.fn(),
  getApplication: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  optimisticConflict: false,
  resetForm: vi.fn(),
  stateConflict: false,
  submit: vi.fn(),
  validate: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@payment/backoffice-runtime/adapter/form', () => ({
  useVbenForm: () => [
    defineComponent({
      setup() {
        onMounted(() => {
          harness.formMounted = true;
        });
      },
      template: '<form><input /></form>',
    }),
    {
      getValues: harness.getValues,
      reset: harness.resetForm,
      setValues: vi.fn(),
      validate: harness.validate,
    },
  ],
  z: {
    string: () => {
      const chain: any = {
        max: () => chain,
        min: () => chain,
        optional: () => chain,
        regex: () => chain,
        trim: () => chain,
      };
      return chain;
    },
  },
}));
vi.mock('antdv-next', () => ({
  Alert: {
    props: ['message'],
    template: '<div>{{ message }}<slot /><slot name="action" /></div>',
  },
  Button: { template: '<button><slot /></button>' },
  Card: { template: '<section><slot /></section>' },
  Descriptions: { template: '<dl><slot /></dl>' },
  DescriptionsItem: { template: '<div><slot /></div>' },
  Empty: { template: '<div><slot /></div>' },
  Input: { template: '<input />' },
  Select: { template: '<select />' },
  Spin: { template: '<div><slot /></div>' },
  Tag: { template: '<span><slot /></span>' },
  message: { error: harness.messageError, success: harness.messageSuccess },
}));
vi.mock('@payment/backoffice-runtime/api/merchant-lifecycle', () => ({
  getMerchantApplication: harness.getApplication,
  isMerchantStateConflict: () => harness.stateConflict,
  submitMerchantApplication: harness.submit,
}));
vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => harness.optimisticConflict,
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('@payment/backoffice-runtime/views/merchant/contract', async () => {
  const actual = await vi.importActual<any>(
    '@payment/backoffice-runtime/views/merchant/contract',
  );
  return actual;
});

describe('mERCHANT profile page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.formMounted = false;
    harness.getValues.mockResolvedValue({
      displayName: 'Example Pay',
      legalName: 'Example Payments',
      registrationCountry: 'BR',
      registrationNumber: 'REG-123',
    });
    harness.optimisticConflict = false;
    harness.stateConflict = false;
    harness.validate.mockResolvedValue({ valid: true });
    harness.resetForm.mockImplementation(async () => {
      await vi.waitFor(() => {
        if (!harness.formMounted)
          throw new Error('Profile form is not mounted');
      });
    });
  });

  it('loads the application before waiting for the editable form to mount', async () => {
    harness.getApplication.mockResolvedValue({ merchant: null });
    const root = document.createElement('div');
    const app = createApp(MerchantProfile);
    app.mount(root);

    await vi.waitFor(() =>
      expect(harness.getApplication).toHaveBeenCalledOnce(),
    );

    app.unmount();
  });

  it('shows a visible first-submission state when no Merchant exists', async () => {
    harness.getApplication.mockResolvedValue({ merchant: null });
    const root = document.createElement('div');
    const app = createApp(MerchantProfile);
    app.mount(root);
    await vi.waitFor(() =>
      expect(root.textContent).toContain('merchant.profile.notSubmitted'),
    );
    expect(root.textContent).not.toContain('merchant.profile.readOnly');
    app.unmount();
  });

  it('shows a visible error and retry state instead of an empty success page', async () => {
    harness.getApplication.mockRejectedValue(new Error('offline'));
    const root = document.createElement('div');
    const app = createApp(MerchantProfile);
    app.mount(root);
    await vi.waitFor(() =>
      expect(root.textContent).toContain('merchant.profile.loadFailed'),
    );
    expect(root.textContent).toContain('common.retry');
    app.unmount();
  });

  it('keeps ACTIVE Merchant data read-only with no registration input', async () => {
    harness.getApplication.mockResolvedValue({
      merchant: {
        displayName: 'Example Pay',
        legalName: 'Example Payments',
        merchantCode: 'MCH_1',
        merchantId: '1',
        registrationCountry: 'SG',
        registrationNumberMasked: '******6789',
        rowVersion: 2,
        status: 'ACTIVE',
      },
    });
    const root = document.createElement('div');
    const app = createApp(MerchantProfile);
    app.mount(root);
    await vi.waitFor(() =>
      expect(root.textContent).toContain('merchant.profile.readOnly'),
    );
    expect(root.querySelector('input')).toBeNull();
    app.unmount();
  });

  it.each([
    ['success', undefined, false],
    ['optimistic conflict', new Error('conflict'), true],
    ['ordinary failure', new Error('offline'), false],
  ] as const)(
    'ignores a late %s submission response after unmount',
    async (_label, rejection, conflict) => {
      let rejectSubmission!: (reason: unknown) => void;
      let resolveSubmission!: (value: unknown) => void;
      harness.getApplication.mockResolvedValue({ merchant: null });
      harness.optimisticConflict = conflict;
      harness.submit.mockReturnValue(
        new Promise((resolve, reject) => {
          rejectSubmission = reject;
          resolveSubmission = resolve;
        }),
      );
      const root = document.createElement('div');
      const app = createApp(MerchantProfile);
      app.mount(root);
      await vi.waitFor(() =>
        expect(harness.getApplication).toHaveBeenCalledOnce(),
      );
      await vi.waitFor(() =>
        expect(root.querySelector('button')).not.toBeNull(),
      );
      const resetsBeforeSubmit = harness.resetForm.mock.calls.length;

      (root.querySelector('button') as HTMLButtonElement).click();
      await vi.waitFor(() => expect(harness.submit).toHaveBeenCalledOnce());
      app.unmount();
      if (rejection) rejectSubmission(rejection);
      else resolveSubmission({});
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(harness.messageSuccess).not.toHaveBeenCalled();
      expect(harness.messageError).not.toHaveBeenCalled();
      expect(harness.getApplication).toHaveBeenCalledOnce();
      expect(harness.resetForm).toHaveBeenCalledTimes(resetsBeforeSubmit + 1);
    },
  );
});
