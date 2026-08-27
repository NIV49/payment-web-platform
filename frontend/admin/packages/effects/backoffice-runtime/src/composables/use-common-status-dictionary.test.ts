import { effectScope } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildCommonStatusOptions,
  useCommonStatusDictionary,
} from './use-common-status-dictionary';

const harness = vi.hoisted(() => ({
  canViewDictionaryData: true,
  queryDictionaryDataBatch: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: () => harness.canViewDictionaryData,
  }),
}));

vi.mock('../api/system/dictionary-data', () => ({
  queryDictionaryDataBatch: harness.queryDictionaryDataBatch,
}));

vi.mock('../locales', () => ({ $t: (key: string) => `translated:${key}` }));

function requireScopeResult<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('Expected effect scope result');
  return value;
}

describe('common status dictionary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.canViewDictionaryData = true;
  });

  it('accepts only exact enabled and disabled values and keeps dictionary order and color', () => {
    expect(
      buildCommonStatusOptions([
        { color: 'purple', label: 'ignored disabled', value: '0' },
        { color: 'warning', label: 'ignored extra', value: '2' },
        { color: 'processing', label: 'ignored enabled', value: '1' },
        { color: 'error', label: 'ignored duplicate', value: '0' },
      ]),
    ).toEqual([
      { color: 'purple', label: 'translated:common.disabled', value: 0 },
      { color: 'processing', label: 'translated:common.enabled', value: 1 },
    ]);
  });

  it('appends a safe localized fallback when one fixed status is missing', () => {
    expect(
      buildCommonStatusOptions([
        { color: 'purple', label: 'ignored disabled', value: '0' },
      ]),
    ).toEqual([
      { color: 'purple', label: 'translated:common.disabled', value: 0 },
      { color: 'success', label: 'translated:common.enabled', value: 1 },
    ]);
  });

  it('does not call the batch endpoint without dictionary-data:view', async () => {
    harness.canViewDictionaryData = false;
    const scope = effectScope();
    const status = requireScopeResult(
      scope.run(() => useCommonStatusDictionary()),
    );

    expect(status.options.value).toEqual([
      { color: 'success', label: 'translated:common.enabled', value: 1 },
      { color: 'error', label: 'translated:common.disabled', value: 0 },
    ]);
    await expect(status.reload()).resolves.toBe(false);
    expect(harness.queryDictionaryDataBatch).not.toHaveBeenCalled();

    scope.stop();
  });

  it('exposes a permitted load failure and recovers on explicit reload', async () => {
    const failure = new Error('dictionary unavailable');
    harness.queryDictionaryDataBatch
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce({
        SYS_COMMON_STATUS: [
          { color: 'processing', label: 'ignored enabled', value: '1' },
          { color: 'purple', label: 'ignored disabled', value: '0' },
        ],
      });

    const scope = effectScope();
    const status = requireScopeResult(
      scope.run(() => useCommonStatusDictionary()),
    );

    await vi.waitFor(() => expect(status.loading.value).toBe(false));
    expect(status.error.value).toBe(failure);
    expect(status.options.value[0]?.color).toBe('success');

    await expect(status.reload()).resolves.toBe(true);
    expect(status.error.value).toBeUndefined();
    expect(status.options.value).toEqual([
      { color: 'processing', label: 'translated:common.enabled', value: 1 },
      { color: 'purple', label: 'translated:common.disabled', value: 0 },
    ]);

    scope.stop();
  });
});
