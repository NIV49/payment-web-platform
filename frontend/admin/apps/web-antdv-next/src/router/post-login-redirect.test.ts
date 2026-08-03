import { describe, expect, it } from 'vitest';

import { resolvePostLoginPath } from './post-login-redirect';

describe('resolvePostLoginPath', () => {
  const isAccessible = (path: string) =>
    ['/system/dept', '/system/user'].includes(path);

  it('uses the user home when a stale double-encoded root redirect remains', () => {
    expect(
      resolvePostLoginPath({
        homePath: '/system/user',
        isAccessible,
        requestedPath: '/system/user',
        requestedRedirect: '%252F',
      }),
    ).toBe('/system/user');
  });

  it('uses the user home when the requested redirect is not accessible', () => {
    expect(
      resolvePostLoginPath({
        homePath: '/system/user',
        isAccessible,
        requestedPath: '/system/user',
        requestedRedirect: '/system/menu',
      }),
    ).toBe('/system/user');
  });

  it('preserves an accessible requested redirect', () => {
    expect(
      resolvePostLoginPath({
        homePath: '/system/user',
        isAccessible,
        requestedPath: '/system/user',
        requestedRedirect: '%2Fsystem%2Fdept',
      }),
    ).toBe('/system/dept');
  });

  it('does not decode percent-encoded query values in an absolute app path', () => {
    expect(
      resolvePostLoginPath({
        homePath: '/system/user',
        isAccessible: (path) => path.startsWith('/system/user'),
        requestedPath: '/system/user',
        requestedRedirect: '/system/user?completion=100%25',
      }),
    ).toBe('/system/user?completion=100%25');
  });
});
