import { effectScope } from 'vue';

import { useSystemDictionaries } from '@payment/backoffice-runtime/composables';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const queryDictionaryDataBatch = vi.hoisted(() => vi.fn());

vi.mock('../api/system/dictionary-data', () => ({
  queryDictionaryDataBatch,
}));

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

function requireScopeResult<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('Expected effect scope result');
  return value;
}

describe('useSystemDictionaries', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads canonical unique types and exposes options and item lookups', async () => {
    queryDictionaryDataBatch.mockResolvedValue({
      CASH_MODEL: [],
      PAY_CHANNEL: [{ color: 'processing', label: 'Bank', value: 'bank' }],
    });

    const scope = effectScope();
    const dictionaries = requireScopeResult(
      scope.run(() =>
        useSystemDictionaries('pay_channel', 'PAY_CHANNEL', 'cash_model'),
      ),
    );

    expect(dictionaries.loading.value).toBe(true);
    expect(dictionaries.getOptions('pay_channel')).toEqual([]);

    await vi.waitFor(() => expect(dictionaries.loading.value).toBe(false));

    expect(queryDictionaryDataBatch).toHaveBeenCalledOnce();
    expect(queryDictionaryDataBatch).toHaveBeenCalledWith([
      'PAY_CHANNEL',
      'CASH_MODEL',
    ]);
    expect(dictionaries.getOptions('PAY_CHANNEL')).toEqual([
      { color: 'processing', label: 'Bank', value: 'bank' },
    ]);
    expect(dictionaries.getItem('PAY_CHANNEL', 'bank')).toEqual({
      color: 'processing',
      label: 'Bank',
      value: 'bank',
    });
    expect(dictionaries.getLabel('PAY_CHANNEL', 'bank')).toBe('Bank');
    expect(dictionaries.getLabel('PAY_CHANNEL', 'missing')).toBeUndefined();
    expect(dictionaries.error.value).toBeUndefined();

    scope.stop();
  });

  it('exposes a failed load and allows an explicit retry', async () => {
    const failure = new Error('dictionary unavailable');
    queryDictionaryDataBatch
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce({ PAY_CHANNEL: [] });

    const scope = effectScope();
    const dictionaries = requireScopeResult(
      scope.run(() => useSystemDictionaries('PAY_CHANNEL')),
    );

    await vi.waitFor(() => expect(dictionaries.loading.value).toBe(false));
    expect(dictionaries.error.value).toBe(failure);
    await expect(dictionaries.reload()).resolves.toBe(true);
    expect(dictionaries.error.value).toBeUndefined();

    scope.stop();
  });

  it('does not request dictionaries when loading is disabled', async () => {
    const scope = effectScope();
    const dictionaries = requireScopeResult(
      scope.run(() => useSystemDictionaries({ enabled: false }, 'PAY_CHANNEL')),
    );

    expect(dictionaries.loading.value).toBe(false);
    expect(dictionaries.getOptions('PAY_CHANNEL')).toEqual([]);
    await expect(dictionaries.reload()).resolves.toBe(false);
    expect(queryDictionaryDataBatch).not.toHaveBeenCalled();
    expect(dictionaries.error.value).toBeUndefined();

    scope.stop();
  });

  it('rejects undeclared lookups and invalid batch boundaries', () => {
    const scope = effectScope();
    const dictionaries = requireScopeResult(
      scope.run(() => useSystemDictionaries('PAY_CHANNEL')),
    );

    expect(() => dictionaries.getOptions('CASH_MODEL')).toThrow(
      'Dictionary type was not declared',
    );
    expect(() =>
      (useSystemDictionaries as (...dictTypes: string[]) => unknown)(),
    ).toThrow('Dictionary lookup requires 1 to 64 types');
    expect(() =>
      (useSystemDictionaries as (...dictTypes: string[]) => unknown)(
        ...Array.from({ length: 65 }, () => 'PAY_CHANNEL'),
      ),
    ).toThrow('Dictionary lookup requires 1 to 64 types');

    scope.stop();
  });

  it('keeps the latest reload result when requests finish out of order', async () => {
    const first = createDeferred<Record<string, unknown[]>>();
    const second = createDeferred<Record<string, unknown[]>>();
    queryDictionaryDataBatch
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const scope = effectScope();
    const dictionaries = requireScopeResult(
      scope.run(() => useSystemDictionaries('PAY_CHANNEL')),
    );
    const reload = dictionaries.reload();

    second.resolve({
      PAY_CHANNEL: [{ color: 'success', label: 'New', value: 'new' }],
    });
    await reload;
    first.resolve({
      PAY_CHANNEL: [{ color: 'warning', label: 'Old', value: 'old' }],
    });
    await first.promise;
    await Promise.resolve();

    expect(dictionaries.getLabel('PAY_CHANNEL', 'new')).toBe('New');
    expect(dictionaries.getLabel('PAY_CHANNEL', 'old')).toBeUndefined();
    expect(dictionaries.loading.value).toBe(false);

    scope.stop();
  });
});
