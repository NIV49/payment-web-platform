import { describe, expect, it, vi } from 'vitest';

import { merchantColumns, merchantSearchSchema } from './data';
import { normalizeMerchantTypeCode } from './merchant-presentation';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
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
vi.mock('@payment/backoffice-runtime/api/system/dictionary-data', () => ({
  queryDictionaryDataBatch: vi.fn(),
}));
vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  INDUSTRY_CODES: [],
  LEGAL_ID_TYPE_CODES: [],
  MCH003_MERCHANT_TYPES: ['PLATFORM', 'INDIRECT', 'COMMISSION', 'SALES'],
}));

describe('pLATFORM merchant list presentation', () => {
  it('normalizes replay-only DIRECT as PLATFORM for every UI surface', () => {
    expect(normalizeMerchantTypeCode('DIRECT')).toBe('PLATFORM');
    expect(normalizeMerchantTypeCode('INDIRECT')).toBe('INDIRECT');
    expect(normalizeMerchantTypeCode(null)).toBeNull();
  });

  it('provides the accepted filters and a fixed operation slot', () => {
    const searchSchema = merchantSearchSchema();
    expect(searchSchema.map(({ fieldName }) => fieldName)).toEqual([
      'merchantCode',
      'name',
      'merchantTypeCode',
      'authenticationType',
      'status',
      'marketCode',
      'createdAt',
    ]);
    for (const fieldName of [
      'merchantTypeCode',
      'authenticationType',
      'status',
      'marketCode',
    ]) {
      const props = searchSchema.find(
        (schema) => schema.fieldName === fieldName,
      )?.componentProps;
      const resolvedProps =
        typeof props === 'function' ? props({} as never) : props;
      expect(resolvedProps).toMatchObject({ allowClear: true });
    }
    expect(
      searchSchema.find(({ fieldName }) => fieldName === 'merchantCode')
        ?.componentProps,
    ).toMatchObject({ maxlength: 64 });
    const columns = merchantColumns() ?? [];
    const operationColumn = columns[columns.length - 1];
    expect(operationColumn).toBeDefined();
    expect(operationColumn).toMatchObject({
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
    });
    expect(columns.find(({ field }) => field === 'status')).toMatchObject({
      slots: { default: 'status' },
    });
    expect(columns.find(({ field }) => field === 'marketCodes')).toMatchObject({
      align: 'center',
      slots: { default: 'marketCodes' },
    });
    expect(
      columns.find(({ field }) => field === 'merchantTypeCode'),
    ).toMatchObject({ slots: { default: 'merchantTypeCode' } });
    expect(
      columns.find(({ field }) => field === 'authenticationType'),
    ).toMatchObject({ slots: { default: 'authenticationType' } });
    expect(columns.map(({ field }) => field)).toEqual(
      expect.arrayContaining([
        'merchantTypeCode',
        'legalPersonName',
        'authenticationType',
      ]),
    );
    expect(
      searchSchema.some(({ fieldName }) => fieldName === 'businessEmail'),
    ).toBe(false);
    expect(columns.find(({ field }) => field === 'createdAt')).toMatchObject({
      formatter: 'formatDateTime',
    });
    expect(columns.find(({ field }) => field === 'updatedAt')).toMatchObject({
      formatter: 'formatDateTime',
    });
  });
});
