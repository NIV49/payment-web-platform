import type { SystemDictionaryDataApi } from './dictionary-data';

import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';

import {
  getDictionaryData,
  getDictionaryTypeOptions,
  queryDictionaryDataBatch,
} from './dictionary-data';

const requestClient = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('../request', () => ({ requestClient }));

describe('shared dictionary data API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requestClient.post.mockResolvedValue({});
  });

  it('queries one dictionary type through the all-domain read endpoint', async () => {
    await getDictionaryData({
      dictType: 'sys_user_sex',
      page: 2,
      pageSize: 20,
    });

    expect(requestClient.get).toHaveBeenCalledWith('/system/dictionary-data', {
      params: {
        dictType: 'SYS_USER_SEX',
        page: 2,
        pageSize: 20,
      },
    });
  });

  it('loads live dictionary type options through the all-domain endpoint', async () => {
    await getDictionaryTypeOptions();

    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/dictionary-types/options',
    );
  });

  it('deduplicates batch dictionary types before sending the shared query', async () => {
    requestClient.post.mockResolvedValue({
      SYS_COMMON_STATUS: [],
      SYS_CURRENCY: [],
    });

    await queryDictionaryDataBatch([
      'SYS_COMMON_STATUS',
      'sys_currency',
      'sys_common_status',
    ]);

    expect(requestClient.post).toHaveBeenCalledWith('/dict/queryBatch', {
      dictTypes: ['SYS_COMMON_STATUS', 'SYS_CURRENCY'],
    });
  });

  it.each([
    [
      'paged query',
      () => getDictionaryData({ dictType: 'bad-type', page: 1, pageSize: 20 }),
    ],
    ['batch query', () => queryDictionaryDataBatch(['bad type'])],
  ])(
    'rejects an invalid dictionary type before the %s request',
    async (_name, query) => {
      await expect(query()).rejects.toThrow('Invalid dictionary type');
      expect(requestClient.get).not.toHaveBeenCalled();
      expect(requestClient.post).not.toHaveBeenCalled();
    },
  );

  it('models response colors as required product enum values', () => {
    expectTypeOf<SystemDictionaryDataApi.DictionaryBatchItem>().toMatchTypeOf<{
      color:
        | 'default'
        | 'error'
        | 'processing'
        | 'purple'
        | 'success'
        | 'warning';
    }>();
    expectTypeOf<SystemDictionaryDataApi.SystemDictionaryData>().toMatchTypeOf<{
      color: SystemDictionaryDataApi.DictionaryColor;
    }>();
  });

  it.each([
    { dictTypes: [] },
    { dictTypes: Array.from({ length: 65 }, (_, index) => `type_${index}`) },
    { dictTypes: Array.from({ length: 65 }, () => 'SYS_USER_SEX') },
  ])(
    'rejects a batch outside the 1..64 unique type boundary before sending: $dictTypes',
    async ({ dictTypes }) => {
      await expect(queryDictionaryDataBatch(dictTypes)).rejects.toThrow(
        'Dictionary batch query requires 1 to 64 types',
      );
      expect(requestClient.post).not.toHaveBeenCalled();
    },
  );

  it('rejects an unknown product color in a batch response', async () => {
    requestClient.post.mockResolvedValue({
      SYS_USER_SEX: [{ color: 'blue', label: 'Unknown', value: 'unknown' }],
    });

    await expect(queryDictionaryDataBatch(['SYS_USER_SEX'])).rejects.toThrow(
      'Invalid dictionary batch response',
    );
  });

  it('rejects a batch response that omits a requested dictionary type', async () => {
    requestClient.post.mockResolvedValue({ PAY_CHANNEL: [] });

    await expect(
      queryDictionaryDataBatch(['PAY_CHANNEL', 'CASH_MODEL']),
    ).rejects.toThrow('Invalid dictionary batch response');
  });

  it('accepts an explicit empty array for an unknown dictionary type', async () => {
    requestClient.post.mockResolvedValue({ UNKNOWN_DICTIONARY: [] });

    await expect(
      queryDictionaryDataBatch(['UNKNOWN_DICTIONARY']),
    ).resolves.toEqual({ UNKNOWN_DICTIONARY: [] });
  });
});
