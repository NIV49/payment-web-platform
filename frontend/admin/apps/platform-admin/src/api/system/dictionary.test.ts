import type { SystemDictionaryApi } from './dictionary';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDictionary,
  createDictionaryData,
  deleteDictionary,
  deleteDictionaryData,
  getDictionaries,
  updateDictionary,
  updateDictionaryData,
} from './dictionary';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/api/request', () => ({ requestClient }));

describe('platform dictionary administration API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('uses the platform dictionary list and create contracts', async () => {
    await getDictionaries({
      dictName: 'Status',
      dictType: 'sys_common_status',
      page: 1,
      pageSize: 20,
    });
    await createDictionary({
      dictName: 'Status',
      dictType: 'sys_common_status',
      remark: 'Shared status labels',
      sort: 10,
    });

    expect(requestClient.get).toHaveBeenCalledWith('/system/dictionaries', {
      params: {
        dictName: 'Status',
        dictType: 'SYS_COMMON_STATUS',
        page: 1,
        pageSize: 20,
      },
    });
    expect(requestClient.post).toHaveBeenCalledWith('/system/dictionaries', {
      dictName: 'Status',
      dictType: 'SYS_COMMON_STATUS',
      remark: 'Shared status labels',
      sort: 10,
    });
  });

  it('binds dictionary updates and deletes to the current row version', async () => {
    await updateDictionary('101', {
      dictName: 'Status',
      dictType: 'sys_common_status',
      expectedVersion: 4,
      remark: 'Updated labels',
      sort: 20,
    });
    await deleteDictionary('101', 5);

    expect(requestClient.put).toHaveBeenCalledWith('/system/dictionaries/101', {
      dictName: 'Status',
      dictType: 'SYS_COMMON_STATUS',
      expectedVersion: 4,
      remark: 'Updated labels',
      sort: 20,
    });
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/dictionaries/101',
      { params: { expectedVersion: 5 } },
    );
  });

  it('uses the platform-only dictionary data mutation contracts', async () => {
    const data: SystemDictionaryApi.DictionaryDataSaveParams = {
      color: 'success',
      dictType: 'sys_common_status',
      label: 'Enabled',
      remark: 'Available for use',
      sort: 10,
      value: '1',
    };

    await createDictionaryData(data);
    await updateDictionaryData('201', { ...data, expectedVersion: 6 });
    await deleteDictionaryData('201', 7);

    expect(requestClient.post).toHaveBeenCalledWith('/system/dictionary-data', {
      ...data,
      dictType: 'SYS_COMMON_STATUS',
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/system/dictionary-data/201',
      { ...data, dictType: 'SYS_COMMON_STATUS', expectedVersion: 6 },
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/dictionary-data/201',
      { params: { expectedVersion: 7 } },
    );
  });
});
