import { describe, expect, it } from 'vitest';

import {
  LOGIN_DEFAULT_CREDENTIAL_FIELD,
  resolveLoginCopy,
  resolveLoginDefaults,
  resolveOidcLoginMode,
} from './login-defaults';

describe('login defaults', () => {
  it('uses injected local credentials only in development', () => {
    expect(
      resolveLoginDefaults({
        dev: true,
        [LOGIN_DEFAULT_CREDENTIAL_FIELD]: '<LOCAL_ADMIN_PASSWORD>',
        username: 'admin',
      }),
    ).toEqual({
      [LOGIN_DEFAULT_CREDENTIAL_FIELD]: '<LOCAL_ADMIN_PASSWORD>',
      username: 'admin',
    });
  });

  it('keeps production fields empty even if credential variables exist', () => {
    expect(
      resolveLoginDefaults({
        dev: false,
        [LOGIN_DEFAULT_CREDENTIAL_FIELD]: '<LOCAL_ADMIN_PASSWORD>',
        username: 'must-not-ship',
      }),
    ).toEqual({
      password: '',
      username: '',
    });
  });

  it('keeps development fields empty when no local credentials were injected', () => {
    expect(resolveLoginDefaults({ dev: true })).toEqual({
      password: '',
      username: '',
    });
  });

  it('does not invent the missing half of partial development credentials', () => {
    expect(resolveLoginDefaults({ dev: true, username: ' admin ' })).toEqual({
      password: '',
      username: 'admin',
    });
    expect(
      resolveLoginDefaults({
        dev: true,
        [LOGIN_DEFAULT_CREDENTIAL_FIELD]: '<LOCAL_ADMIN_PASSWORD>',
      }),
    ).toEqual({
      [LOGIN_DEFAULT_CREDENTIAL_FIELD]: '<LOCAL_ADMIN_PASSWORD>',
      username: '',
    });
  });
});

describe('oIDC login mode', () => {
  it('keeps production on OIDC even when an environment requests local login', () => {
    expect(resolveOidcLoginMode({ explicitMode: 'local', prod: true })).toBe(
      true,
    );
  });

  it('allows development to select OIDC or local mode explicitly', () => {
    expect(resolveOidcLoginMode({ explicitMode: 'oidc', prod: false })).toBe(
      true,
    );
    expect(resolveOidcLoginMode({ prod: false })).toBe(false);
    expect(resolveOidcLoginMode({ explicitMode: 'local', prod: false })).toBe(
      false,
    );
  });

  it('fails closed on an unknown explicit authentication mode', () => {
    expect(() =>
      resolveOidcLoginMode({ explicitMode: 'unexpected', prod: false }),
    ).toThrow('Invalid frontend authentication mode');
  });
});

describe('account-domain login copy', () => {
  it.each([
    [
      'PLATFORM',
      'page.auth.platformLoginTitle',
      'page.auth.platformLoginSubtitle',
    ],
    [
      'MERCHANT',
      'page.auth.merchantLoginTitle',
      'page.auth.merchantLoginSubtitle',
    ],
    ['AGENT', 'page.auth.agentLoginTitle', 'page.auth.agentLoginSubtitle'],
  ])('uses distinct copy for %s', (domain, titleKey, subTitleKey) => {
    expect(resolveLoginCopy(domain)).toEqual({
      subTitleKey,
      titleKey,
    });
  });

  it('fails closed for an unknown account domain', () => {
    expect(() => resolveLoginCopy('UNKNOWN')).toThrow(
      'Invalid frontend account domain',
    );
  });
});
