import { describe, expect, it } from 'vitest';

import {
  createDictionaryDataScopeGuard,
  hasExactDictionaryDataScope,
} from './scope-guard';

describe('dictionary data scope guard', () => {
  it('invalidates an outstanding request when the dictionary type changes', () => {
    const guard = createDictionaryDataScopeGuard();
    const oldRequest = guard.begin('TYPE_A');

    expect(guard.isCurrent(oldRequest)).toBe(true);

    guard.invalidate();
    expect(guard.isCurrent(oldRequest)).toBe(false);

    const currentRequest = guard.begin('TYPE_B');
    expect(guard.isCurrent(currentRequest)).toBe(true);
  });

  it('accepts only pages whose every row belongs to the exact dictionary type', () => {
    expect(
      hasExactDictionaryDataScope(
        { items: [{ dictType: 'TYPE_A' }, { dictType: 'TYPE_A' }], total: 2 },
        'TYPE_A',
      ),
    ).toBe(true);
    expect(
      hasExactDictionaryDataScope(
        { items: [{ dictType: 'TYPE_A' }, { dictType: 'TYPE_B' }], total: 2 },
        'TYPE_A',
      ),
    ).toBe(false);
    expect(hasExactDictionaryDataScope({ items: null }, 'TYPE_A')).toBe(false);
  });
});
