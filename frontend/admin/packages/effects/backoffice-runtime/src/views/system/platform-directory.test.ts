import { describe, expect, it, vi } from 'vitest';

import {
  createPlatformDirectoryRequestGuard,
  resolvePlatformDirectoryTarget,
  splitPlatformDirectoryQuery,
  usePlatformDirectoryFilterSchema,
} from './platform-directory';

vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('@payment/backoffice-runtime/api/system/user', () => ({
  getTenantOptions: vi.fn(),
}));
vi.mock('antdv-next', () => ({}));

describe('platform administration directory filters', () => {
  it('defaults the platform selector to the current PLATFORM workspace', () => {
    const schema = usePlatformDirectoryFilterSchema(() => []);
    const domain = schema.find(
      ({ fieldName }) => fieldName === 'accountDomain',
    );

    expect(domain).toMatchObject({
      defaultValue: 'PLATFORM',
      fieldName: 'accountDomain',
    });
  });

  it('requires an exact tenant for merchant and agent targets', () => {
    expect(
      resolvePlatformDirectoryTarget({ accountDomain: 'PLATFORM' }),
    ).toEqual({ accountDomain: 'PLATFORM' });
    expect(
      resolvePlatformDirectoryTarget({
        accountDomain: 'MERCHANT',
        tenantId: '2001',
      }),
    ).toEqual({ accountDomain: 'MERCHANT', tenantId: '2001' });
    expect(
      resolvePlatformDirectoryTarget({ accountDomain: 'AGENT' }),
    ).toBeUndefined();
  });

  it('never forwards selector fields into role business filters', () => {
    expect(
      splitPlatformDirectoryQuery({
        accountDomain: 'MERCHANT',
        name: 'Risk',
        page: 1,
        pageSize: 20,
        tenantId: '2001',
      }),
    ).toEqual({
      query: { name: 'Risk', page: 1, pageSize: 20 },
      target: { accountDomain: 'MERCHANT', tenantId: '2001' },
    });
  });

  it('invalidates old directory requests when the selection changes', () => {
    const guard = createPlatformDirectoryRequestGuard();
    const merchantRequest = guard.begin({
      accountDomain: 'MERCHANT',
      tenantId: '2001',
    });

    expect(guard.isCurrent(merchantRequest)).toBe(true);

    guard.invalidate();
    expect(guard.isCurrent(merchantRequest)).toBe(false);

    const agentRequest = guard.begin({
      accountDomain: 'AGENT',
      tenantId: '3001',
    });
    expect(guard.isCurrent(agentRequest)).toBe(true);
  });

  it('makes a newer query supersede an older query in the same directory', () => {
    const guard = createPlatformDirectoryRequestGuard();
    const first = guard.begin({ accountDomain: 'PLATFORM' });
    const second = guard.begin({ accountDomain: 'PLATFORM' });

    expect(guard.isCurrent(first)).toBe(false);
    expect(guard.isCurrent(second)).toBe(true);
  });

  it('exposes tenant changes so pages can invalidate in-flight requests', async () => {
    const onTenantChange = vi.fn();
    const schema = usePlatformDirectoryFilterSchema(
      () => [],
      undefined,
      onTenantChange,
    );
    const tenant = schema.find(({ fieldName }) => fieldName === 'tenantId');
    const resolved = await tenant?.dependencies?.resolve?.({
      values: { accountDomain: 'MERCHANT' },
    } as never);

    expect(resolved?.componentProps).toMatchObject({
      onChange: onTenantChange,
    });
  });
});
