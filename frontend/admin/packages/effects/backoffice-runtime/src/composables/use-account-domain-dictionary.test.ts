import { effectScope } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildAccountDomainOptions,
  useAccountDomainDictionary,
} from './use-account-domain-dictionary';

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

describe('account domain dictionary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.canViewDictionaryData = true;
  });

  it('maps only exact 1/2/3 values to fixed backend enums while preserving dictionary order and color', () => {
    expect(
      buildAccountDomainOptions([
        { color: 'purple', label: 'ignored agent', value: '3' },
        { color: 'warning', label: 'ignored invalid', value: 'PLATFORM' },
        { color: 'success', label: 'ignored merchant', value: '2' },
        { color: 'error', label: 'ignored duplicate', value: '3' },
        { color: 'default', label: 'ignored extra', value: '4' },
        { color: 'processing', label: 'ignored platform', value: '1' },
      ]),
    ).toEqual([
      {
        color: 'purple',
        label: 'translated:system.user.agentDomain',
        value: 'AGENT',
      },
      {
        color: 'success',
        label: 'translated:system.user.merchantDomain',
        value: 'MERCHANT',
      },
      {
        color: 'processing',
        label: 'translated:system.user.platformDomain',
        value: 'PLATFORM',
      },
    ]);
  });

  it('appends localized semantic fallbacks for missing values', () => {
    expect(
      buildAccountDomainOptions([
        { color: 'warning', label: 'ignored merchant', value: '2' },
      ]),
    ).toEqual([
      {
        color: 'warning',
        label: 'translated:system.user.merchantDomain',
        value: 'MERCHANT',
      },
      {
        color: 'processing',
        label: 'translated:system.user.platformDomain',
        value: 'PLATFORM',
      },
      {
        color: 'purple',
        label: 'translated:system.user.agentDomain',
        value: 'AGENT',
      },
    ]);
  });

  it('does not query dictionaries without dictionary-data:view or when disabled', async () => {
    harness.canViewDictionaryData = false;
    const permissionScope = effectScope();
    const permissionDenied = requireScopeResult(
      permissionScope.run(() => useAccountDomainDictionary()),
    );
    const disabledScope = effectScope();
    const disabled = requireScopeResult(
      disabledScope.run(() => useAccountDomainDictionary({ enabled: false })),
    );

    expect(permissionDenied.options.value.map(({ value }) => value)).toEqual([
      'PLATFORM',
      'MERCHANT',
      'AGENT',
    ]);
    await expect(permissionDenied.reload()).resolves.toBe(false);
    await expect(disabled.reload()).resolves.toBe(false);
    expect(harness.queryDictionaryDataBatch).not.toHaveBeenCalled();

    permissionScope.stop();
    disabledScope.stop();
  });

  it('exposes a permitted load failure and recovers on explicit reload', async () => {
    const failure = new Error('dictionary unavailable');
    harness.queryDictionaryDataBatch
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce({
        BELONG_SYSTEM: [
          { color: 'purple', label: 'ignored agent', value: '3' },
          { color: 'success', label: 'ignored merchant', value: '2' },
          { color: 'processing', label: 'ignored platform', value: '1' },
        ],
      });

    const scope = effectScope();
    const domains = requireScopeResult(
      scope.run(() => useAccountDomainDictionary()),
    );

    await vi.waitFor(() => expect(domains.loading.value).toBe(false));
    expect(domains.error.value).toBe(failure);
    expect(domains.options.value[0]?.value).toBe('PLATFORM');

    await expect(domains.reload()).resolves.toBe(true);
    expect(domains.error.value).toBeUndefined();
    expect(domains.options.value.map(({ value }) => value)).toEqual([
      'AGENT',
      'MERCHANT',
      'PLATFORM',
    ]);

    scope.stop();
  });
});
