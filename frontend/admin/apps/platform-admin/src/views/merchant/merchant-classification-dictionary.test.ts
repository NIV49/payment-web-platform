import { effectScope } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildAuthenticationTypeOptions,
  buildIndustryOptions,
  buildLegalIdTypeOptions,
  buildMerchantTypeOptions,
  useMerchantClassificationDictionary,
} from './merchant-classification-dictionary';

const harness = vi.hoisted(() => ({
  queryBatch: vi.fn(),
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

vi.mock('@payment/backoffice-runtime/api/system/dictionary-data', () => ({
  queryDictionaryDataBatch: harness.queryBatch,
}));
vi.mock('@payment/backoffice-runtime/api/merchant-lifecycle', () => ({
  MERCHANT_AUTHENTICATION_TYPES: [
    'ENTERPRISE',
    'NON_PROFIT_ORGANIZATIONS',
    'CLIQUE',
    'INDIVIDUAL',
    'INDIVIDUAL_HOUSEHOLD',
  ],
  MERCHANT_TYPE_CODES: [
    'DIRECT',
    'INDIRECT',
    'COMMISSION',
    'SALES',
    'PLATFORM',
  ],
}));
vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  INDUSTRY_CODES: [
    'FINANCIAL_SERVICES',
    'ECOMMERCE',
    'RETAIL',
    'TRAVEL',
    'EDUCATION',
    'OTHER',
  ],
  LEGAL_ID_TYPE_CODES: ['NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE'],
  MCH003_MERCHANT_TYPES: ['PLATFORM', 'INDIRECT', 'COMMISSION', 'SALES'],
}));
vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

describe('merchant classification dictionaries', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.queryBatch.mockResolvedValue({
      MERCHANT_AUTH_TYPE: [],
      MERCHANT_INDUSTRY_CODE: [],
      MERCHANT_LEGAL_ID_TYPE: [],
      MERCHANT_TYPE_CODE: [],
    });
  });

  it('uses dictionary order and color without accepting extra or duplicate values', () => {
    expect(
      buildMerchantTypeOptions([
        { color: 'warning', label: 'ignored', value: 'INDIRECT' },
        { color: 'error', label: 'ignored', value: 'DIRECT' },
        { color: 'success', label: 'duplicate', value: 'INDIRECT' },
        { color: 'purple', label: 'illegal', value: 'UNKNOWN' },
      ] as never),
    ).toEqual([
      {
        color: 'warning',
        label: 'merchant.merchantTypes.INDIRECT',
        value: 'INDIRECT',
      },
      {
        color: 'success',
        label: 'merchant.merchantTypes.PLATFORM',
        value: 'PLATFORM',
      },
      {
        color: 'default',
        label: 'merchant.merchantTypes.COMMISSION',
        value: 'COMMISSION',
      },
      {
        color: 'processing',
        label: 'merchant.merchantTypes.SALES',
        value: 'SALES',
      },
    ]);
  });

  it('loads all four types in one batch and keeps i18n labels with fallback options', async () => {
    harness.queryBatch.mockResolvedValue({
      MERCHANT_AUTH_TYPE: [
        { color: 'success', label: 'ignored', value: 'INDIVIDUAL' },
      ],
      MERCHANT_INDUSTRY_CODE: [
        { color: 'warning', label: 'ignored', value: 'RETAIL' },
      ],
      MERCHANT_LEGAL_ID_TYPE: [
        { color: 'purple', label: 'ignored', value: 'PASSPORT' },
      ],
      MERCHANT_TYPE_CODE: [],
    });
    const scope = effectScope();
    const result = scope.run(() => useMerchantClassificationDictionary());
    if (!result) throw new Error('Dictionary scope did not start');

    expect(harness.queryBatch).toHaveBeenCalledWith([
      'MERCHANT_TYPE_CODE',
      'MERCHANT_AUTH_TYPE',
      'MERCHANT_INDUSTRY_CODE',
      'MERCHANT_LEGAL_ID_TYPE',
    ]);
    await vi.waitFor(() =>
      expect(result.authenticationTypeOptions.value[0]?.value).toBe(
        'INDIVIDUAL',
      ),
    );
    expect(result.authenticationTypeOptions.value[0]).toEqual({
      color: 'success',
      label: 'merchant.authenticationTypes.INDIVIDUAL',
      value: 'INDIVIDUAL',
    });
    expect(result.merchantTypeOptions.value).toHaveLength(4);
    expect(result.industryOptions.value[0]).toEqual({
      color: 'warning',
      label: 'merchant.industries.RETAIL',
      value: 'RETAIL',
    });
    expect(result.legalIdTypeOptions.value[0]).toEqual({
      color: 'purple',
      label: 'merchant.legalIdTypes.PASSPORT',
      value: 'PASSPORT',
    });
    scope.stop();
  });

  it('builds a complete authentication fallback from the fixed allowlist', () => {
    expect(
      buildAuthenticationTypeOptions([]).map(({ value }) => value),
    ).toEqual([
      'ENTERPRISE',
      'NON_PROFIT_ORGANIZATIONS',
      'CLIQUE',
      'INDIVIDUAL',
      'INDIVIDUAL_HOUSEHOLD',
    ]);
  });

  it('uses fixed industry and legal ID allowlists when dictionaries are unavailable', () => {
    expect(buildIndustryOptions([]).map(({ value }) => value)).toEqual([
      'FINANCIAL_SERVICES',
      'ECOMMERCE',
      'RETAIL',
      'TRAVEL',
      'EDUCATION',
      'OTHER',
    ]);
    expect(buildLegalIdTypeOptions([]).map(({ value }) => value)).toEqual([
      'NATIONAL_ID',
      'PASSPORT',
      'DRIVER_LICENSE',
    ]);
  });

  it('keeps a visible error and static fallback until retry succeeds', async () => {
    harness.queryBatch
      .mockRejectedValueOnce(new Error('failed'))
      .mockResolvedValueOnce({
        MERCHANT_AUTH_TYPE: [],
        MERCHANT_INDUSTRY_CODE: [],
        MERCHANT_LEGAL_ID_TYPE: [],
        MERCHANT_TYPE_CODE: [
          { color: 'warning', label: 'ignored', value: 'INDIRECT' },
        ],
      });
    const scope = effectScope();
    const result = scope.run(() => useMerchantClassificationDictionary());
    if (!result) throw new Error('Dictionary scope did not start');

    await vi.waitFor(() => expect(result.error.value).toBeInstanceOf(Error));
    expect(result.merchantTypeOptions.value).toHaveLength(4);

    await expect(result.reload()).resolves.toBe(true);
    expect(result.error.value).toBeUndefined();
    expect(result.merchantTypeOptions.value[0]).toMatchObject({
      color: 'warning',
      value: 'INDIRECT',
    });
    scope.stop();
  });

  it('ignores an older batch response after a newer reload completes', async () => {
    const older = deferred<any>();
    const newer = deferred<any>();
    harness.queryBatch
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);
    const scope = effectScope();
    const result = scope.run(() => useMerchantClassificationDictionary());
    if (!result) throw new Error('Dictionary scope did not start');

    const reload = result.reload();
    newer.resolve({
      MERCHANT_AUTH_TYPE: [],
      MERCHANT_INDUSTRY_CODE: [],
      MERCHANT_LEGAL_ID_TYPE: [],
      MERCHANT_TYPE_CODE: [
        { color: 'warning', label: 'newer', value: 'PLATFORM' },
      ],
    });
    await reload;
    older.resolve({
      MERCHANT_AUTH_TYPE: [],
      MERCHANT_INDUSTRY_CODE: [],
      MERCHANT_LEGAL_ID_TYPE: [],
      MERCHANT_TYPE_CODE: [
        { color: 'error', label: 'older', value: 'INDIRECT' },
      ],
    });
    await older.promise;

    expect(result.merchantTypeOptions.value[0]).toMatchObject({
      color: 'warning',
      value: 'PLATFORM',
    });
    scope.stop();
  });

  it('ignores a batch response that arrives after its scope is disposed', async () => {
    const response = deferred<any>();
    harness.queryBatch.mockReturnValue(response.promise);
    const scope = effectScope();
    const result = scope.run(() => useMerchantClassificationDictionary());
    if (!result) throw new Error('Dictionary scope did not start');

    scope.stop();
    response.resolve({
      MERCHANT_AUTH_TYPE: [],
      MERCHANT_INDUSTRY_CODE: [],
      MERCHANT_LEGAL_ID_TYPE: [],
      MERCHANT_TYPE_CODE: [
        { color: 'error', label: 'late', value: 'INDIRECT' },
      ],
    });
    await response.promise;

    expect(result.merchantTypeOptions.value[0]?.value).toBe('PLATFORM');
    expect(result.error.value).toBeUndefined();
  });
});
