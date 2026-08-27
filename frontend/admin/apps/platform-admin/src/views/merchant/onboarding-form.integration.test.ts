import { createApp, defineComponent, h, nextTick } from 'vue';

import { globalShareState } from '@vben/common-ui';

import {
  initSetupVbenForm,
  useVbenForm,
} from '@payment/backoffice-runtime/adapter/form';
import { beforeAll, describe, expect, it } from 'vitest';

import {
  buildMerchantOnboardingSections,
  buildMerchantProfileInput,
  validateMerchantOnboardingFormValues,
} from './onboarding-form';

const completeValues = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocumentId: '101',
  brandName: 'Example Brand',
  businessLicenseDocumentId: '102',
  contactEmail: 'merchant@example.test',
  contactPhone: '+551100000000',
  displayName: 'Example Pay',
  industryCode: 'FINANCIAL_SERVICES',
  legalIdBackDocumentId: '104',
  legalIdFrontDocumentId: '103',
  legalIdHoldingDocumentId: '105',
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
} as const;

const TestField = defineComponent({
  inheritAttrs: false,
  props: ['value'],
  emits: ['update:value'],
  setup(props, { attrs, emit }) {
    return () =>
      h('input', {
        ...attrs,
        onInput: (event: Event) =>
          emit('update:value', (event.target as HTMLInputElement).value),
        value: props.value ?? '',
      });
  },
});

beforeAll(async () => {
  globalShareState.setComponents({
    Input: TestField,
    InputPassword: TestField,
    RangePicker: TestField,
    Select: TestField,
    Textarea: TestField,
  });
  await initSetupVbenForm();
});

describe('merchant onboarding real VbenForm adapter', () => {
  it('validates and collects a complete 23-field create profile', async () => {
    const sections = buildMerchantOnboardingSections({
      authenticationTypeOptions: [{ label: 'Enterprise', value: 'ENTERPRISE' }],
      industryOptions: [
        { label: 'Financial services', value: 'FINANCIAL_SERVICES' },
      ],
      legalIdTypeOptions: [{ label: 'National ID', value: 'NATIONAL_ID' }],
      merchantTypeOptions: [{ label: 'Platform', value: 'PLATFORM' }],
    });
    const forms = sections.map(({ schema }) =>
      useVbenForm({
        layout: 'vertical',
        schema,
        showDefaultActions: false,
      }),
    );
    const Harness = defineComponent(
      () => () =>
        h(
          'div',
          forms.map(([Form]) => h(Form)),
        ),
    );
    const root = document.createElement('div');
    document.body.append(root);
    const app = createApp(Harness);
    app.mount(root);
    await nextTick();
    await Promise.resolve();

    await Promise.all(forms.map(([, api]) => api.setValues(completeValues)));
    await nextTick();

    const validations = await Promise.all(
      forms.map(([, api]) => api.validate()),
    );
    const values = Object.assign(
      {},
      ...(await Promise.all(forms.map(([, api]) => api.getValues()))),
    );

    expect(validations).toEqual([
      { errors: {}, valid: true },
      { errors: {}, valid: true },
      { errors: {}, valid: true },
    ]);
    expect(
      buildMerchantProfileInput(values as never, 'create', {
        legalIdNo: true,
        registrationNumber: true,
      }),
    ).toMatchObject({
      brandLogoDocumentId: '101',
      businessLicenseDocumentId: '102',
      legalIdBackDocumentId: '104',
      legalIdFrontDocumentId: '103',
      legalIdHoldingDocumentId: '105',
      legalIdValidity: {
        validFrom: '2026-01-01',
        validTo: '2036-01-01',
      },
    });
    expect(
      validateMerchantOnboardingFormValues(values, 'create', {
        legalIdNo: true,
        registrationNumber: true,
      }),
    ).toEqual({ invalidFields: [], valid: true });

    app.unmount();
    root.remove();
  });
});
