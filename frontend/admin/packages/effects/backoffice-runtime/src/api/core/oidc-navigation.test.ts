import { describe, expect, it } from 'vitest';

import { OIDC_START_PATH, resolveRealmLogoutUrl } from './oidc-navigation';

describe('oidc browser navigation', () => {
  it('uses the same-origin bff start endpoint', () => {
    expect(OIDC_START_PATH).toBe('/api/auth/oidc/start');
  });

  it('accepts https realm logout and loopback development http', () => {
    expect(
      resolveRealmLogoutUrl(
        'https://idp.example.test/realms/MERCHANT/protocol/openid-connect/logout',
        'https://merchant.example.test',
      ),
    ).toBe(
      'https://idp.example.test/realms/MERCHANT/protocol/openid-connect/logout',
    );
    expect(
      resolveRealmLogoutUrl(
        'http://127.0.0.1:8180/realms/MERCHANT/protocol/openid-connect/logout',
        'http://127.0.0.1:6002',
      ),
    ).toBe(
      'http://127.0.0.1:8180/realms/MERCHANT/protocol/openid-connect/logout',
    );
  });

  it('rejects non-http and non-loopback cleartext logout targets', () => {
    expect(() =>
      resolveRealmLogoutUrl(
        'javascript:alert(1)',
        'https://merchant.example.test',
      ),
    ).toThrow('Invalid realm logout URL');
    expect(() =>
      resolveRealmLogoutUrl(
        'http://idp.example.test/realms/MERCHANT/logout',
        'https://merchant.example.test',
      ),
    ).toThrow('Invalid realm logout URL');
  });
});
