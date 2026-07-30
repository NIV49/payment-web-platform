import { describe, expect, it } from 'vitest';

import {
  assertNoReservedBackendRoutes,
  PRODUCT_ACCESS_MODE,
} from './product-access';

describe('product access policy', () => {
  it('keeps mixed mode independent of user preferences', () => {
    expect(PRODUCT_ACCESS_MODE).toBe('mixed');
  });

  it.each([
    {
      routes: [{ name: 'Profile', path: '/remote-profile' }],
      scenario: 'reserved route name',
    },
    {
      routes: [{ name: 'RemoteProfile', path: '/profile/' }],
      scenario: 'reserved canonical route path',
    },
    {
      routes: [
        {
          children: [{ name: 'RemoteProfile', path: '/PROFILE' }],
          name: 'RemoteRoot',
          path: '/remote',
        },
      ],
      scenario: 'nested reserved route path',
    },
  ])('rejects a backend $scenario', ({ routes }) => {
    expect(() => assertNoReservedBackendRoutes(routes)).toThrow(
      'Backend route conflicts with reserved local route',
    );
  });

  it('accepts unrelated backend routes', () => {
    expect(() =>
      assertNoReservedBackendRoutes([
        { name: 'System', path: '/system' },
        { name: 'SystemUser', path: '/system/user' },
      ]),
    ).not.toThrow();
  });
});
