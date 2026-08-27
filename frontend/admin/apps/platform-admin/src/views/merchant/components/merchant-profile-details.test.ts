import { createApp, defineComponent, h, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import MerchantProfileDetails from './merchant-profile-details.vue';

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('antdv-next', () => ({
  Descriptions: { template: '<dl><slot /></dl>' },
  DescriptionsItem: {
    props: ['label'],
    template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>',
  },
  Tag: { template: '<span><slot /></span>' },
}));
vi.mock('./merchant-document-preview.vue', () => ({
  default: defineComponent({
    props: ['amendmentId', 'document', 'kind'],
    setup(props) {
      return () =>
        h('span', {
          'data-amendment-id': props.amendmentId,
          'data-document-id': props.document?.documentId,
          'data-kind': props.kind,
        });
    },
  }),
}));

const metadata = (documentId: string, kind: string) => ({
  documentId,
  height: 120,
  kind,
  mediaType: 'image/png',
  sizeBytes: 42,
  width: 160,
});

const profile = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocument: metadata('1', 'BRAND_LOGO'),
  brandName: 'Example Brand',
  businessLicenseDocument: metadata('2', 'BUSINESS_LICENSE'),
  contactEmail: 'merchant@example.test',
  contactPhone: '+551100000000',
  displayName: 'Example Pay',
  industryCode: 'FINANCIAL_SERVICES',
  legalIdBackDocument: metadata('4', 'LEGAL_ID_BACK'),
  legalIdFrontDocument: metadata('3', 'LEGAL_ID_FRONT'),
  legalIdHoldingDocument: metadata('5', 'LEGAL_ID_HOLDING'),
  legalIdNoMasked: '********0123',
  legalIdTypeCode: 'NATIONAL_ID',
  legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
  legalName: 'Example Ltd.',
  legalPersonName: 'Director',
  marketCodes: ['BRA', 'PHL'],
  merchantTypeCode: 'PLATFORM',
  operatingAddress: 'Operating address',
  registeredAddress: 'Registered address',
  registrationCountry: 'BR',
  registrationNumberMasked: '********9988',
  remarks: '',
};

describe('merchant read-only profile details', () => {
  it('renders the complete profile and all five exact amendment documents', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantProfileDetails, {
      amendmentId: '71',
      canPreviewDocuments: true,
      merchantId: '29',
      profile,
    });
    app.mount(root);
    await nextTick();

    for (const value of [
      'Example Pay',
      'Example Brand',
      'Example Ltd.',
      'Director',
      'merchant@example.test',
      '+551100000000',
      '********0123',
      '********9988',
      'Operating address',
      'Registered address',
      'merchant.markets.BRA',
      'merchant.markets.PHL',
    ]) {
      expect(root.textContent).toContain(value);
    }
    expect(root.querySelectorAll('[data-document-id]')).toHaveLength(5);
    expect(
      [...root.querySelectorAll('[data-document-id]')].every(
        (element) => (element as HTMLElement).dataset.amendmentId === '71',
      ),
    ).toBe(true);
    app.unmount();
  });

  it('uses a stable dash for nullable historical fields', async () => {
    const root = document.createElement('div');
    const app = createApp(MerchantProfileDetails, {
      canPreviewDocuments: false,
      merchantId: '29',
      profile: {
        ...profile,
        brandLogoDocument: null,
        brandName: null,
        legalIdNoMasked: null,
        legalIdValidity: null,
        merchantTypeCode: 'DIRECT',
      },
    });
    app.mount(root);
    await nextTick();

    expect(root.textContent).toContain('merchant.merchantTypes.PLATFORM');
    expect(root.textContent).not.toContain('null');
    expect(root.textContent).toContain('-');
    app.unmount();
  });
});
