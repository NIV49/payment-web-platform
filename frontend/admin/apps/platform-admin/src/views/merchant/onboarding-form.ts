import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import type { VbenFormSchema } from '#/adapter/form';

import { z } from '@payment/backoffice-runtime/adapter/form';
import {
  ASSIGNED_ISO_COUNTRY_CODES,
  isAssignedIsoCountryCode,
} from '@payment/backoffice-runtime/api/merchant-country';

import { $t } from '#/locales';

const MCH003_MERCHANT_TYPES = [
  'PLATFORM',
  'INDIRECT',
  'COMMISSION',
  'SALES',
] as const;

const MERCHANT_ONBOARDING_FIELD_NAMES = [
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
] as const;

type MerchantOnboardingFieldName =
  (typeof MERCHANT_ONBOARDING_FIELD_NAMES)[number];
type MerchantFormMode = 'create' | 'edit';
type MerchantFormOption = {
  color?: string;
  label: string;
  value: string;
};
type SensitiveField = { mode: 'REPLACE'; value: string } | { mode: 'RETAIN' };

interface MerchantOnboardingOptions {
  authenticationTypeOptions: readonly MerchantFormOption[];
  industryOptions: readonly MerchantFormOption[];
  legalIdTypeOptions: readonly MerchantFormOption[];
  merchantTypeOptions: readonly MerchantFormOption[];
}

interface MerchantOnboardingSection {
  key: 'legalRepresentative' | 'merchantBasic' | 'subject';
  schema: VbenFormSchema[];
  titleKey: string;
}

interface MerchantOnboardingFormValues {
  authenticationType: '' | MerchantOnboardingApi.AuthenticationType;
  brandLogoDocumentId: string;
  brandName: string;
  businessLicenseDocumentId: string;
  contactEmail: string;
  contactPhone: string;
  displayName: string;
  industryCode: '' | MerchantOnboardingApi.IndustryCode;
  legalIdBackDocumentId: string;
  legalIdFrontDocumentId: string;
  legalIdHoldingDocumentId: string;
  legalIdNo: string;
  legalIdTypeCode: '' | MerchantOnboardingApi.LegalIdTypeCode;
  legalIdValidity: readonly [string, string];
  legalName: string;
  legalPersonName: string;
  marketCodes: readonly ('BRA' | 'PHL')[];
  merchantTypeCode: '' | MerchantOnboardingApi.MerchantTypeCode;
  operatingAddress: string;
  registeredAddress: string;
  registrationCountry: string;
  registrationNumber: string;
  remarks: string;
}

interface SensitiveReplacementState {
  legalIdNo: boolean;
  registrationNumber: boolean;
}

const DEFAULT_SENSITIVE_REPLACEMENTS: Readonly<SensitiveReplacementState> = {
  legalIdNo: true,
  registrationNumber: true,
};

const requiredText = (maximum: number) =>
  z
    .string()
    .trim()
    .min(1)
    .refine((value) => [...value].length <= maximum);

function selectProps(options: readonly MerchantFormOption[]) {
  return { allowClear: true, class: 'w-full', options: [...options] };
}

function hiddenDocumentField(fieldName: MerchantOnboardingFieldName) {
  return {
    component: 'Input' as const,
    fieldName,
    formItemClass: 'hidden',
    label: $t(`merchant.onboarding.fields.${fieldName}`),
    rules: requiredText(19).regex(/^[1-9][0-9]{0,18}$/),
  };
}

function buildMerchantOnboardingSections(
  options: MerchantOnboardingOptions,
  mode: MerchantFormMode = 'create',
  replacements: Readonly<SensitiveReplacementState> = DEFAULT_SENSITIVE_REPLACEMENTS,
): MerchantOnboardingSection[] {
  const merchantTypes = new Map(
    options.merchantTypeOptions.map((option) => [option.value, option]),
  );
  const merchantTypeOptions = MCH003_MERCHANT_TYPES.map(
    (value) =>
      merchantTypes.get(value) ?? {
        label: $t(`merchant.merchantTypes.${value}`),
        value,
      },
  );
  const marketOptions = ['BRA', 'PHL'].map((value) => ({
    label: $t(`merchant.markets.${value}`),
    value,
  }));

  return [
    {
      key: 'merchantBasic',
      titleKey: 'merchant.onboarding.sections.merchantBasic',
      schema: [
        {
          component: 'Input',
          fieldName: 'displayName',
          label: $t('merchant.onboarding.fields.displayName'),
          rules: requiredText(128),
        },
        {
          component: 'Input',
          fieldName: 'brandName',
          label: $t('merchant.onboarding.fields.brandName'),
          rules: requiredText(128),
        },
        {
          component: 'Select',
          componentProps: selectProps(options.authenticationTypeOptions),
          fieldName: 'authenticationType',
          label: $t('merchant.onboarding.fields.authenticationType'),
          rules: 'selectRequired',
        },
        {
          component: 'Select',
          componentProps: selectProps(merchantTypeOptions),
          fieldName: 'merchantTypeCode',
          label: $t('merchant.onboarding.fields.merchantTypeCode'),
          rules: 'selectRequired',
        },
        {
          component: 'Select',
          componentProps: selectProps(options.industryOptions),
          fieldName: 'industryCode',
          label: $t('merchant.onboarding.fields.industryCode'),
          rules: 'selectRequired',
        },
        hiddenDocumentField('brandLogoDocumentId'),
      ],
    },
    {
      key: 'subject',
      titleKey: 'merchant.onboarding.sections.subject',
      schema: [
        {
          component: 'Input',
          fieldName: 'legalName',
          label: $t('merchant.onboarding.fields.legalName'),
          rules: requiredText(200),
        },
        {
          component: 'Select',
          componentProps: selectProps(
            ASSIGNED_ISO_COUNTRY_CODES.map((code) => ({
              label: code,
              value: code,
            })),
          ),
          fieldName: 'registrationCountry',
          label: $t('merchant.onboarding.fields.registrationCountry'),
          rules: z.string().refine(isAssignedIsoCountryCode),
        },
        {
          component: 'Select',
          componentProps: {
            ...selectProps(marketOptions),
            maxCount: 2,
            mode: 'multiple',
          },
          fieldName: 'marketCodes',
          label: $t('merchant.onboarding.fields.marketCodes'),
          rules: z
            .array(z.enum(['BRA', 'PHL']))
            .min(1)
            .max(2)
            .refine((values) => new Set(values).size === values.length),
        },
        {
          component: 'Textarea',
          componentProps: { rows: 3 },
          fieldName: 'registeredAddress',
          label: $t('merchant.onboarding.fields.registeredAddress'),
          rules: requiredText(300),
        },
        {
          component: 'Textarea',
          componentProps: { rows: 3 },
          fieldName: 'operatingAddress',
          label: $t('merchant.onboarding.fields.operatingAddress'),
          rules: requiredText(300),
        },
        hiddenDocumentField('businessLicenseDocumentId'),
      ],
    },
    {
      key: 'legalRepresentative',
      titleKey: 'merchant.onboarding.sections.legalRepresentative',
      schema: [
        {
          component: 'Input',
          fieldName: 'legalPersonName',
          label: $t('merchant.onboarding.fields.legalPersonName'),
          rules: requiredText(200),
        },
        {
          component: 'Input',
          fieldName: 'contactEmail',
          label: $t('merchant.onboarding.fields.contactEmail'),
          rules: z.string().trim().email().min(3).max(254),
        },
        {
          component: 'Input',
          fieldName: 'contactPhone',
          label: $t('merchant.onboarding.fields.contactPhone'),
          rules: z.string().regex(/^\+[1-9][0-9]{1,14}$/),
        },
        {
          component: 'Select',
          componentProps: selectProps(options.legalIdTypeOptions),
          fieldName: 'legalIdTypeCode',
          label: $t('merchant.onboarding.fields.legalIdTypeCode'),
          rules: 'selectRequired',
        },
        {
          component: 'InputPassword',
          componentProps: {
            autocomplete: 'off',
            disabled: mode === 'edit' && !replacements.legalIdNo,
          },
          fieldName: 'legalIdNo',
          label: $t('merchant.onboarding.fields.legalIdNo'),
          rules:
            mode === 'create' || replacements.legalIdNo
              ? requiredText(128)
              : z.string(),
        },
        {
          component: 'RangePicker',
          componentProps: { class: 'w-full', valueFormat: 'YYYY-MM-DD' },
          fieldName: 'legalIdValidity',
          label: $t('merchant.onboarding.fields.legalIdValidity'),
          rules: 'selectRequired',
        },
        hiddenDocumentField('legalIdFrontDocumentId'),
        hiddenDocumentField('legalIdBackDocumentId'),
        hiddenDocumentField('legalIdHoldingDocumentId'),
        {
          component: 'Textarea',
          componentProps: { rows: 3 },
          fieldName: 'remarks',
          label: $t('merchant.onboarding.fields.remarks'),
          rules: z
            .string()
            .trim()
            .refine((value) => [...value].length <= 300),
        },
        {
          component: 'InputPassword',
          componentProps: {
            autocomplete: 'off',
            disabled: mode === 'edit' && !replacements.registrationNumber,
          },
          fieldName: 'registrationNumber',
          label: $t('merchant.onboarding.fields.registrationNumber'),
          rules:
            mode === 'create' || replacements.registrationNumber
              ? requiredText(128)
              : z.string(),
        },
      ],
    },
  ];
}

function normalizeSensitiveField(
  mode: MerchantFormMode,
  replace: boolean,
  value: string,
): SensitiveField {
  if (mode === 'edit' && !replace) return { mode: 'RETAIN' };
  const normalized = value.trim();
  if (!normalized) throw new Error('Sensitive replacement value is required');
  return { mode: 'REPLACE', value: normalized };
}

function buildMerchantProfileInput(
  values: Readonly<MerchantOnboardingFormValues>,
  mode: MerchantFormMode,
  replacements: Readonly<SensitiveReplacementState>,
): MerchantOnboardingApi.ProfileInput {
  if (
    !values.authenticationType ||
    !values.industryCode ||
    !values.legalIdTypeCode ||
    !values.merchantTypeCode
  ) {
    throw new Error('Merchant classification is required');
  }
  const [validFrom, validTo] = values.legalIdValidity;
  if (!validFrom || !validTo) {
    throw new Error('Legal ID validity is required');
  }
  const marketOrder = new Map([
    ['BRA', 0],
    ['PHL', 1],
  ]);
  return {
    authenticationType: values.authenticationType,
    brandLogoDocumentId: values.brandLogoDocumentId,
    brandName: values.brandName.trim(),
    businessLicenseDocumentId: values.businessLicenseDocumentId,
    contactEmail: values.contactEmail.trim(),
    contactPhone: values.contactPhone.trim(),
    displayName: values.displayName.trim(),
    industryCode: values.industryCode,
    legalIdBackDocumentId: values.legalIdBackDocumentId,
    legalIdFrontDocumentId: values.legalIdFrontDocumentId,
    legalIdHoldingDocumentId: values.legalIdHoldingDocumentId,
    legalIdNo: normalizeSensitiveField(
      mode,
      replacements.legalIdNo,
      values.legalIdNo,
    ),
    legalIdTypeCode: values.legalIdTypeCode,
    legalIdValidity: { validFrom, validTo },
    legalName: values.legalName.trim(),
    legalPersonName: values.legalPersonName.trim(),
    marketCodes: [...values.marketCodes].toSorted(
      (left, right) =>
        (marketOrder.get(left) ?? Number.MAX_SAFE_INTEGER) -
        (marketOrder.get(right) ?? Number.MAX_SAFE_INTEGER),
    ),
    merchantTypeCode: values.merchantTypeCode,
    operatingAddress: values.operatingAddress.trim(),
    registeredAddress: values.registeredAddress.trim(),
    registrationCountry: values.registrationCountry.trim(),
    registrationNumber: normalizeSensitiveField(
      mode,
      replacements.registrationNumber,
      values.registrationNumber,
    ),
    remarks: values.remarks.trim(),
  };
}

function validateMerchantOnboardingFormValues(
  values: Readonly<Partial<Record<MerchantOnboardingFieldName, unknown>>>,
  mode: MerchantFormMode,
  replacements: Readonly<SensitiveReplacementState>,
) {
  const invalidFields: MerchantOnboardingFieldName[] = [];
  const schemas = buildMerchantOnboardingSections(
    {
      authenticationTypeOptions: [],
      industryOptions: [],
      legalIdTypeOptions: [],
      merchantTypeOptions: [],
    },
    mode,
    replacements,
  ).flatMap(({ schema }) => schema);

  for (const { fieldName, rules } of schemas) {
    const merchantFieldName = fieldName as MerchantOnboardingFieldName;
    const value = values[merchantFieldName];
    let valid = true;
    if (rules === 'required') {
      valid = value !== undefined && value !== null && value !== '';
    } else if (rules === 'selectRequired') {
      valid = value !== undefined && value !== null;
    } else if (rules && typeof rules !== 'string') {
      valid = rules.safeParse(value).success;
    }
    if (!valid) invalidFields.push(merchantFieldName);
  }

  if (invalidFields.length === 0) {
    try {
      buildMerchantProfileInput(
        values as unknown as MerchantOnboardingFormValues,
        mode,
        replacements,
      );
    } catch {
      return {
        invalidFields: [...MERCHANT_ONBOARDING_FIELD_NAMES],
        valid: false,
      };
    }
  }
  return { invalidFields, valid: invalidFields.length === 0 };
}

function effectiveDetailToForm(
  detail: MerchantLifecycleApi.PlatformMerchantDetail,
): {
  replacements: SensitiveReplacementState;
  values: MerchantOnboardingFormValues;
} {
  const merchantTypeCode =
    detail.merchantTypeCode === 'DIRECT'
      ? 'PLATFORM'
      : (detail.merchantTypeCode ?? '');
  return {
    replacements: {
      legalIdNo: detail.legalIdNoMasked === null,
      registrationNumber: !detail.registrationNumberMasked,
    },
    values: {
      authenticationType: detail.authenticationType ?? '',
      brandLogoDocumentId: detail.brandLogoDocument?.documentId ?? '',
      brandName: detail.brandName ?? '',
      businessLicenseDocumentId:
        detail.businessLicenseDocument?.documentId ?? '',
      contactEmail: detail.contactEmail ?? '',
      contactPhone: detail.contactPhone ?? '',
      displayName: detail.displayName,
      industryCode:
        (detail.industryCode as MerchantOnboardingApi.IndustryCode | null) ??
        '',
      legalIdBackDocumentId: detail.legalIdBackDocument?.documentId ?? '',
      legalIdFrontDocumentId: detail.legalIdFrontDocument?.documentId ?? '',
      legalIdHoldingDocumentId: detail.legalIdHoldingDocument?.documentId ?? '',
      legalIdNo: '',
      legalIdTypeCode:
        (detail.legalIdTypeCode as MerchantOnboardingApi.LegalIdTypeCode | null) ??
        '',
      legalIdValidity: detail.legalIdValidity
        ? [detail.legalIdValidity.validFrom, detail.legalIdValidity.validTo]
        : ['', ''],
      legalName: detail.legalName,
      legalPersonName: detail.legalPersonName ?? '',
      marketCodes: detail.marketCodes as Array<'BRA' | 'PHL'>,
      merchantTypeCode,
      operatingAddress: detail.operatingAddress ?? '',
      registeredAddress: detail.registeredAddress ?? '',
      registrationCountry: detail.registrationCountry,
      registrationNumber: '',
      remarks: detail.remarks,
    },
  };
}

export {
  buildMerchantOnboardingSections,
  buildMerchantProfileInput,
  effectiveDetailToForm,
  MCH003_MERCHANT_TYPES,
  MERCHANT_ONBOARDING_FIELD_NAMES,
  normalizeSensitiveField,
  validateMerchantOnboardingFormValues,
};
export type {
  MerchantFormMode,
  MerchantFormOption,
  MerchantOnboardingFormValues,
  MerchantOnboardingOptions,
  MerchantOnboardingSection,
  SensitiveField,
  SensitiveReplacementState,
};
