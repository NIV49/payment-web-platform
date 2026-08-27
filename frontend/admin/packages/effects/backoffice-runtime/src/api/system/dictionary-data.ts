import type { PageResult } from './types';

import { requestClient } from '../request';
import { normalizeDictionaryType } from './dictionary-type';

const DICTIONARY_COLORS = new Set<SystemDictionaryDataApi.DictionaryColor>([
  'default',
  'error',
  'processing',
  'purple',
  'success',
  'warning',
]);

export namespace SystemDictionaryDataApi {
  export type DictionaryColor =
    | 'default'
    | 'error'
    | 'processing'
    | 'purple'
    | 'success'
    | 'warning';

  export interface DictionaryBatchItem {
    color: DictionaryColor;
    label: string;
    value: string;
  }

  export type DictionaryBatchResult = Record<string, DictionaryBatchItem[]>;

  export interface DictionaryDataQuery {
    dictType: string;
    label?: string;
    page: number;
    pageSize: number;
    value?: string;
  }

  export interface DictionaryTypeOption {
    dictName: string;
    dictType: string;
  }

  export interface SystemDictionaryData {
    color: DictionaryColor;
    createTime: string;
    dictCode: string;
    dictType: string;
    label: string;
    remark?: string;
    rowVersion: number;
    sort: number;
    value: string;
  }
}

async function getDictionaryTypeOptions() {
  return requestClient.get<SystemDictionaryDataApi.DictionaryTypeOption[]>(
    '/system/dictionary-types/options',
  );
}

async function getDictionaryData(
  params: SystemDictionaryDataApi.DictionaryDataQuery,
) {
  const dictType = normalizeDictionaryType(params.dictType);
  return requestClient.get<
    PageResult<SystemDictionaryDataApi.SystemDictionaryData>
  >('/system/dictionary-data', { params: { ...params, dictType } });
}

async function queryDictionaryDataBatch(dictTypes: readonly string[]) {
  if (dictTypes.length === 0 || dictTypes.length > 64) {
    throw new RangeError('Dictionary batch query requires 1 to 64 types');
  }
  const uniqueDictTypes = [
    ...new Set(dictTypes.map((dictType) => normalizeDictionaryType(dictType))),
  ];

  const response = await requestClient.post<unknown>('/dict/queryBatch', {
    dictTypes: uniqueDictTypes,
  });
  assertDictionaryBatchResult(response, uniqueDictTypes);
  return response;
}

function assertDictionaryBatchResult(
  value: unknown,
  requestedTypes: readonly string[],
): asserts value is SystemDictionaryDataApi.DictionaryBatchResult {
  if (!isRecord(value))
    throw new TypeError('Invalid dictionary batch response');
  for (const dictType of requestedTypes) {
    if (!Object.hasOwn(value, dictType)) {
      throw new TypeError('Invalid dictionary batch response');
    }
    const items = value[dictType];
    if (!Array.isArray(items)) {
      throw new TypeError('Invalid dictionary batch response');
    }
    for (const item of items) {
      if (
        !isRecord(item) ||
        typeof item.label !== 'string' ||
        typeof item.value !== 'string' ||
        !DICTIONARY_COLORS.has(
          item.color as SystemDictionaryDataApi.DictionaryColor,
        )
      ) {
        throw new TypeError('Invalid dictionary batch response');
      }
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export {
  getDictionaryData,
  getDictionaryTypeOptions,
  queryDictionaryDataBatch,
};
