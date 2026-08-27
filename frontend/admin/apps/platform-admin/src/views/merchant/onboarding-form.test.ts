import { describe, expect, it } from 'vitest';

import {
  buildMerchantOnboardingSections,
  buildMerchantProfileInput,
  effectiveDetailToForm,
  MERCHANT_ONBOARDING_FIELD_NAMES,
  normalizeSensitiveField,
} from './onboarding-form';

const formValues = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocumentId: '1',
  brandName: ' Example ',
  businessLicenseDocumentId: '2',
  contactEmail: ' merchant@example.test ',
  contactPhone: '+551100000000',
  displayName: ' Example Pay ',
  industryCode: 'FINANCIAL_SERVICES',
  legalIdBackDocumentId: '4',
  legalIdFrontDocumentId: '3',
  legalIdHoldingDocumentId: '5',
  legalIdNo: ' ID-123 ',
  legalIdTypeCode: 'NATIONAL_ID',
  legalIdValidity: ['2026-01-01', '2036-01-01'],
  legalName: ' Example Ltd. ',
  legalPersonName: ' Director ',
  marketCodes: ['PHL', 'BRA'],
  merchantTypeCode: 'PLATFORM',
  operatingAddress: ' Operating address ',
  registeredAddress: ' Registered address ',
  registrationCountry: 'BR',
  registrationNumber: ' REG-123 ',
  remarks: ' note ',
} as const;

describe('merchant onboarding form contract', () => {
  it('keeps exactly 23 business inputs in the three accepted full-width sections', () => {
    expect(MERCHANT_ONBOARDING_FIELD_NAMES).toEqual([
      'displayName',
      'brandName',
      'authenticationType',
      'merchantTypeCode',
      'industryCode',
      'brandLogoDocumentId',
      'legalName',
      'registrationCountry',
      'marketCodes',
      'registeredAddress',
      'operatingAddress',
      'businessLicenseDocumentId',
      'legalPersonName',
      'contactEmail',
      'contactPhone',
      'legalIdTypeCode',
      'legalIdNo',
      'legalIdValidity',
      'legalIdFrontDocumentId',
      'legalIdBackDocumentId',
      'legalIdHoldingDocumentId',
      'remarks',
      'registrationNumber',
    ]);

    const sections = buildMerchantOnboardingSections({
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [],
    });
    expect(sections.map(({ key }) => key)).toEqual([
      'merchantBasic',
      'subject',
      'legalRepresentative',
    ]);
    expect(
      sections.flatMap(({ schema }) =>
        schema.map(({ fieldName }) => fieldName),
      ),
    ).toEqual(MERCHANT_ONBOARDING_FIELD_NAMES);
    const labels = Object.fromEntries(
      sections
        .flatMap(({ schema }) => schema)
        .map(({ fieldName, label }) => [fieldName, label]),
    );
    expect(labels).toMatchObject({
      displayName: 'merchant.onboarding.fields.displayName',
      legalName: 'merchant.onboarding.fields.legalName',
      marketCodes: 'merchant.onboarding.fields.marketCodes',
      registrationCountry: 'merchant.onboarding.fields.registrationCountry',
    });
    expect(
      sections
        .flatMap(({ schema }) => schema)
        .some(({ fieldName }) => fieldName === 'businessEmail'),
    ).toBe(false);
  });

  it('keeps width-sensitive controls aligned with full-width text fields', () => {
    const fields = buildMerchantOnboardingSections({
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [],
    }).flatMap(({ schema }) => schema);

    const widthSensitiveFields = fields.filter(
      ({ component }) => component === 'RangePicker' || component === 'Select',
    );

    expect(widthSensitiveFields).not.toHaveLength(0);
    expect(
      widthSensitiveFields.every((field) => {
        const props =
          typeof field.componentProps === 'function'
            ? field.componentProps({} as never)
            : field.componentProps;
        return props?.class === 'w-full';
      }),
    ).toBe(true);
  });

  it('allows only the four MCH-003 merchant types', () => {
    const sections = buildMerchantOnboardingSections({
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [
        { label: 'Direct', value: 'DIRECT' },
        { label: 'Platform', value: 'PLATFORM' },
        { label: 'Indirect', value: 'INDIRECT' },
        { label: 'Commission', value: 'COMMISSION' },
        { label: 'Sales', value: 'SALES' },
      ],
    });
    const merchantType = sections
      .flatMap(({ schema }) => schema)
      .find(({ fieldName }) => fieldName === 'merchantTypeCode');
    const props =
      typeof merchantType?.componentProps === 'function'
        ? merchantType.componentProps({} as never)
        : merchantType?.componentProps;
    expect(props?.options.map(({ value }: { value: string }) => value)).toEqual(
      ['PLATFORM', 'INDIRECT', 'COMMISSION', 'SALES'],
    );
  });

  it('uses assigned ISO country options and rejects an unassigned code', () => {
    const registrationCountry = buildMerchantOnboardingSections({
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [],
    })
      .flatMap(({ schema }) => schema)
      .find(({ fieldName }) => fieldName === 'registrationCountry');
    const props =
      typeof registrationCountry?.componentProps === 'function'
        ? registrationCountry.componentProps({} as never)
        : registrationCountry?.componentProps;
    const rule = registrationCountry?.rules as {
      safeParse: (value: string) => { success: boolean };
    };

    expect(registrationCountry?.component).toBe('Select');
    expect(props?.allowClear).toBe(true);
    expect(props?.options).toContainEqual({ label: 'BR', value: 'BR' });
    expect(rule.safeParse('BR').success).toBe(true);
    expect(rule.safeParse('ZZ').success).toBe(false);
  });

  it('counts text limits by Unicode code point without DOM UTF-16 truncation', () => {
    const fields = buildMerchantOnboardingSections({
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [],
    }).flatMap(({ schema }) => schema);
    expect(
      fields.every((field) => {
        const props =
          typeof field.componentProps === 'function'
            ? field.componentProps({} as never)
            : field.componentProps;
        return !props || !('maxlength' in props);
      }),
    ).toBe(true);

    const displayName = fields.find(
      ({ fieldName }) => fieldName === 'displayName',
    );
    const rule = displayName?.rules as {
      safeParse: (value: string) => { success: boolean };
    };
    expect(rule.safeParse('😀'.repeat(128)).success).toBe(true);
    expect(rule.safeParse('😀'.repeat(129)).success).toBe(false);
  });

  it('requires REPLACE on create and keeps amendment secrets RETAIN by default', () => {
    expect(normalizeSensitiveField('create', false, '  secret  ')).toEqual({
      mode: 'REPLACE',
      value: 'secret',
    });
    expect(normalizeSensitiveField('edit', false, '')).toEqual({
      mode: 'RETAIN',
    });
    expect(normalizeSensitiveField('edit', true, '  new-secret  ')).toEqual({
      mode: 'REPLACE',
      value: 'new-secret',
    });
    expect(() => normalizeSensitiveField('create', false, '   ')).toThrow(
      'Sensitive replacement value is required',
    );
    expect(() => normalizeSensitiveField('edit', true, '   ')).toThrow(
      'Sensitive replacement value is required',
    );
  });

  it('builds the exact normalized profile and canonicalizes market order', () => {
    expect(
      buildMerchantProfileInput(formValues, 'create', {
        legalIdNo: true,
        registrationNumber: true,
      }),
    ).toEqual({
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
      legalIdNo: { mode: 'REPLACE', value: 'ID-123' },
      legalIdTypeCode: 'NATIONAL_ID',
      legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
      legalName: 'Example Ltd.',
      legalPersonName: 'Director',
      marketCodes: ['BRA', 'PHL'],
      merchantTypeCode: 'PLATFORM',
      operatingAddress: 'Operating address',
      registeredAddress: 'Registered address',
      registrationCountry: 'BR',
      registrationNumber: { mode: 'REPLACE', value: 'REG-123' },
      remarks: 'note',
    });
  });

  it('never places masks in sensitive controls and forces replacement for historical nulls', () => {
    const result = effectiveDetailToForm({
      ...formValues,
      brandLogoDocument: null,
      brandName: null,
      businessLicenseDocument: null,
      contactEmail: null,
      contactPhone: null,
      industryCode: null,
      legalIdBackDocument: null,
      legalIdFrontDocument: null,
      legalIdHoldingDocument: null,
      legalIdNoMasked: null,
      legalIdTypeCode: null,
      legalIdValidity: null,
      operatingAddress: null,
      registeredAddress: null,
      registrationNumberMasked: '********0123',
    } as never);

    expect(result.values.legalIdNo).toBe('');
    expect(result.values.registrationNumber).toBe('');
    expect(JSON.stringify(result.values)).not.toContain('********');
    expect(result.replacements).toEqual({
      legalIdNo: true,
      registrationNumber: false,
    });
    expect(result.values.brandLogoDocumentId).toBe('');
  });
});
