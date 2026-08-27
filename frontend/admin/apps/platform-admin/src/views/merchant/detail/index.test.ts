import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantDetailPage from './index.vue';

const detail = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocument: { documentId: '1', kind: 'BRAND_LOGO' },
  brandName: 'Example',
  businessLicenseDocument: { documentId: '2', kind: 'BUSINESS_LICENSE' },
  contactEmail: 'merchant@example.test',
  contactPhone: '+551100000000',
  createdAt: '2026-08-15T08:00:00Z',
  displayName: 'Example Pay',
  industryCode: 'FINANCIAL_SERVICES',
  lastDecision: null,
  legalIdBackDocument: { documentId: '4', kind: 'LEGAL_ID_BACK' },
  legalIdFrontDocument: { documentId: '3', kind: 'LEGAL_ID_FRONT' },
  legalIdHoldingDocument: { documentId: '5', kind: 'LEGAL_ID_HOLDING' },
  legalIdNoMasked: '********0123',
  legalIdTypeCode: 'NATIONAL_ID',
  legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
  legalName: 'Example Ltd.',
  legalPersonName: 'Director',
  marketCodes: ['BRA'],
  merchantCode: 'MCH_1',
  merchantId: '29',
  merchantTypeCode: 'PLATFORM',
  operatingAddress: 'Operating address',
  registeredAddress: 'Registered address',
  registrationCountry: 'BR',
  registrationNumberMasked: '********0123',
  remarks: '',
  reviewedAt: null,
  rowVersion: 0,
  status: 'PENDING_REVIEW',
  statusReasonCode: 'PLATFORM_APPLICATION_SUBMITTED',
  submittedAt: '2026-08-15T08:00:00Z',
  tenantId: '17',
  updatedAt: '2026-08-15T08:00:00Z',
};

const pending = {
  amendmentId: '71',
  authorMembershipId: '61',
  canCurrentActorReview: true,
  createdAt: '2026-08-15T10:00:00Z',
  decision: null,
  merchantId: '29',
  originMerchantVersion: 7,
  originStatus: 'ACTIVE',
  profile: { ...detail, displayName: 'Pending Display' },
  rowVersion: 0,
  status: 'PENDING_REVIEW',
  updatedAt: '2026-08-15T10:00:00Z',
};

const harness = vi.hoisted(() => ({
  allowed: new Set<string>(),
  getDetail: vi.fn(),
  getPending: vi.fn(),
  messageSuccess: vi.fn(),
  params: { merchantId: '29' },
  push: vi.fn(),
  reviewAmendment: vi.fn(),
  reviewCreation: vi.fn(),
  routeName: 'MerchantDetail',
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: harness.routeName, params: harness.params }),
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.allowed.has(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    props: ['title'],
    setup(_props, { slots }) {
      return () =>
        h('main', [
          h('header', [
            h('div', { 'data-test': 'page-title' }, slots.title?.()),
            h('div', { 'data-test': 'page-extra' }, slots.extra?.()),
          ]),
          h('section', { 'data-test': 'page-content' }, slots.default?.()),
        ]);
    },
  }),
}));
vi.mock('@payment/backoffice-runtime/api/merchant-lifecycle', () => ({
  getPlatformMerchant: harness.getDetail,
  reviewMerchant: harness.reviewCreation,
}));
vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  getPendingMerchantAmendment: harness.getPending,
  reviewMerchantAmendment: harness.reviewAmendment,
}));
vi.mock('@payment/backoffice-runtime/api/error-contract', () => ({
  isOptimisticLockConflict: () => false,
}));
vi.mock('@payment/backoffice-runtime/api/permission-codes', () => ({
  PERMISSION_CODES: {
    merchantDocumentView: 'merchant:document:view',
    merchantReview: 'merchant:review',
    merchantView: 'merchant:view',
  },
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('../components/merchant-profile-details.vue', () => ({
  default: defineComponent({
    props: ['amendmentId', 'canPreviewDocuments', 'profile'],
    setup(props) {
      return () =>
        h('div', {
          'data-amendment-id': props.amendmentId,
          'data-can-preview': String(props.canPreviewDocuments),
          'data-profile-name': props.profile?.displayName,
          'data-test': 'profile-details',
        });
    },
  }),
}));
vi.mock('antdv-next', () => ({
  Alert: { props: ['message'], template: '<div>{{ message }}</div>' },
  Button: defineComponent({
    props: ['disabled'],
    emits: ['click'],
    setup(props, { emit, slots }) {
      return () =>
        h(
          'button',
          { disabled: props.disabled, onClick: () => emit('click') },
          slots.default?.(),
        );
    },
  }),
  Descriptions: { template: '<dl><slot /></dl>' },
  DescriptionsItem: { template: '<dd><slot /></dd>' },
  Modal: { template: '<div data-test="review-modal"><slot /></div>' },
  Radio: { props: ['value'], template: '<label><slot /></label>' },
  RadioGroup: { template: '<div data-test="review-decision"><slot /></div>' },
  Select: defineComponent({
    props: ['options', 'value'],
    template: '<div data-test="review-reason" />',
  }),
  Spin: { template: '<div><slot /></div>' },
  Tag: { template: '<span><slot /></span>' },
  message: { success: harness.messageSuccess },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function mountPage() {
  const root = document.createElement('div');
  const app = createApp(MerchantDetailPage);
  const instance = app.mount(root) as any;
  return { app, instance, root };
}

describe('merchant full-page detail and review', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.allowed = new Set([
      'merchant:document:view',
      'merchant:review',
      'merchant:view',
    ]);
    harness.routeName = 'MerchantDetail';
    harness.getDetail.mockResolvedValue(detail);
    harness.getPending.mockResolvedValue(null);
  });

  it('loads a read-only detail page without querying review state', async () => {
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    await nextTick();

    expect(harness.getDetail).toHaveBeenCalledWith('29');
    expect(harness.getPending).not.toHaveBeenCalled();
    expect(
      root.querySelector('[data-profile-name="Example Pay"]'),
    ).not.toBeNull();
    expect(root.textContent).not.toContain('merchant.detail.reviewAction');
    expect(
      root.querySelector(
        '[data-test="page-title"] [data-test="merchant-return"]',
      ),
    ).not.toBeNull();
    expect(
      root.querySelector(
        '[data-test="page-content"] [data-test="merchant-return"]',
      ),
    ).toBeNull();
    app.unmount();
  });

  it('shows one review action and submits a creation decision from the modal', async () => {
    harness.routeName = 'MerchantReview';
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    await nextTick();

    expect(
      root.textContent?.match(/merchant\.detail\.reviewAction/g),
    ).toHaveLength(1);
    expect(
      root.querySelector(
        '[data-test="page-extra"] [data-test="merchant-review"]',
      ),
    ).not.toBeNull();
    await expect(
      instance.submitReview('APPROVE', 'PROFILE_VERIFIED'),
    ).resolves.toBe(true);
    expect(harness.reviewCreation).toHaveBeenCalledWith(
      '29',
      expect.objectContaining({
        decision: 'APPROVE',
        reasonCode: 'PROFILE_VERIFIED',
      }),
    );
    app.unmount();
  });

  it('does not render a review action after the application was reviewed', async () => {
    harness.routeName = 'MerchantReview';
    harness.getDetail.mockResolvedValue({
      ...detail,
      lastDecision: {
        decidedAt: '2026-08-15T09:00:00Z',
        decidedByMembershipId: '61',
        decision: 'APPROVE',
        reasonCode: 'PROFILE_VERIFIED',
      },
      reviewedAt: '2026-08-15T09:00:00Z',
      rowVersion: 1,
      status: 'ACTIVE',
    });
    harness.getPending.mockResolvedValue(null);
    const { app, instance, root } = mountPage();

    await instance.loadPage();
    await nextTick();

    expect(root.querySelector('[data-test="merchant-review"]')).toBeNull();
    expect(root.textContent).toContain('merchant.detail.reviewUnavailable');
    app.unmount();
  });

  it('renders and reviews the exact pending amendment profile', async () => {
    harness.routeName = 'MerchantReview';
    harness.getDetail.mockResolvedValue({
      ...detail,
      rowVersion: 7,
      status: 'ACTIVE',
    });
    harness.getPending.mockResolvedValue(pending);
    const { app, instance, root } = mountPage();
    await instance.loadPage();
    await nextTick();

    expect(
      root.querySelector('[data-profile-name="Pending Display"]'),
    ).not.toBeNull();
    expect(root.querySelector('[data-amendment-id="71"]')).not.toBeNull();
    await expect(
      instance.submitReview('REJECT', 'DOCUMENT_UNVERIFIED'),
    ).resolves.toBe(true);
    expect(harness.reviewAmendment).toHaveBeenCalledWith(
      '29',
      '71',
      expect.objectContaining({
        decision: 'REJECT',
        reasonCode: 'DOCUMENT_UNVERIFIED',
      }),
    );
    app.unmount();
  });

  it('ignores a review response that arrives after the page unmounts', async () => {
    harness.routeName = 'MerchantReview';
    const response = deferred<unknown>();
    harness.reviewCreation.mockReturnValue(response.promise);
    const { app, instance } = mountPage();
    await instance.loadPage();

    const reviewing = instance.submitReview('APPROVE', 'PROFILE_VERIFIED');
    app.unmount();
    response.resolve({});

    await expect(reviewing).resolves.toBe(false);
    expect(harness.messageSuccess).not.toHaveBeenCalled();
    expect(harness.push).not.toHaveBeenCalled();
  });
});
