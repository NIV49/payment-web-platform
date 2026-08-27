import type {
  PageResult,
  SystemDictionaryDataApi,
} from '@payment/backoffice-runtime/api';

import { normalizeDictionaryType } from '@payment/backoffice-runtime/api';
import { requestClient } from '@payment/backoffice-runtime/api/request';

export namespace SystemDictionaryApi {
  export interface DictionaryListQuery {
    dictName?: string;
    dictType?: string;
    page: number;
    pageSize: number;
  }

  export type DictionarySaveParams = Omit<
    SystemDictionary,
    'createTime' | 'dictId' | 'rowVersion'
  >;

  export type DictionaryUpdateParams = DictionarySaveParams & {
    expectedVersion: number;
  };

  export type DictionaryDataSaveParams = Omit<
    SystemDictionaryDataApi.SystemDictionaryData,
    'createTime' | 'dictCode' | 'rowVersion'
  >;

  export type DictionaryDataUpdateParams = DictionaryDataSaveParams & {
    expectedVersion: number;
  };

  export interface SystemDictionary {
    createTime: string;
    dictId: string;
    dictName: string;
    dictType: string;
    remark?: string;
    rowVersion: number;
    sort: number;
  }
}

async function getDictionaries(
  params: SystemDictionaryApi.DictionaryListQuery,
) {
  const normalizedParams = params.dictType
    ? { ...params, dictType: normalizeDictionaryType(params.dictType) }
    : params;
  return requestClient.get<PageResult<SystemDictionaryApi.SystemDictionary>>(
    '/system/dictionaries',
    { params: normalizedParams },
  );
}

async function createDictionary(
  data: SystemDictionaryApi.DictionarySaveParams,
) {
  return requestClient.post('/system/dictionaries', {
    ...data,
    dictType: normalizeDictionaryType(data.dictType),
  });
}

async function updateDictionary(
  dictId: string,
  data: SystemDictionaryApi.DictionaryUpdateParams,
) {
  return requestClient.put(`/system/dictionaries/${dictId}`, {
    ...data,
    dictType: normalizeDictionaryType(data.dictType),
  });
}

async function deleteDictionary(dictId: string, expectedVersion: number) {
  return requestClient.delete(`/system/dictionaries/${dictId}`, {
    params: { expectedVersion },
  });
}

async function createDictionaryData(
  data: SystemDictionaryApi.DictionaryDataSaveParams,
) {
  return requestClient.post('/system/dictionary-data', {
    ...data,
    dictType: normalizeDictionaryType(data.dictType),
  });
}

async function updateDictionaryData(
  dictCode: string,
  data: SystemDictionaryApi.DictionaryDataUpdateParams,
) {
  return requestClient.put(`/system/dictionary-data/${dictCode}`, {
    ...data,
    dictType: normalizeDictionaryType(data.dictType),
  });
}

async function deleteDictionaryData(dictCode: string, expectedVersion: number) {
  return requestClient.delete(`/system/dictionary-data/${dictCode}`, {
    params: { expectedVersion },
  });
}

export {
  createDictionary,
  createDictionaryData,
  deleteDictionary,
  deleteDictionaryData,
  getDictionaries,
  updateDictionary,
  updateDictionaryData,
};
