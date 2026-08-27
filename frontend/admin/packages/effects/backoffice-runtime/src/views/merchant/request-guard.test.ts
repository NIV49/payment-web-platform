import { describe, expect, it } from 'vitest';

import { createMerchantRequestGuard } from './request-guard';

describe('merchant detail and application request guard', () => {
  it('rejects a late Merchant A response after Merchant B becomes active', () => {
    const guard = createMerchantRequestGuard();
    const merchantA = guard.begin('merchant:1');
    const merchantB = guard.begin('merchant:2');

    expect(guard.isCurrent(merchantA, 'merchant:1')).toBe(false);
    expect(guard.isCurrent(merchantB, 'merchant:2')).toBe(true);
  });

  it('invalidates in-flight work when a drawer or page is closed', () => {
    const guard = createMerchantRequestGuard();
    const request = guard.begin('merchant:1');

    guard.invalidate();

    expect(guard.isCurrent(request, 'merchant:1')).toBe(false);
  });
});
