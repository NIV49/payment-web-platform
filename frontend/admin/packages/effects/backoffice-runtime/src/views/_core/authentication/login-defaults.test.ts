import { describe, expect, it } from 'vitest';

import {
  LOGIN_DEFAULT_CREDENTIAL_FIELD,
  resolveLoginDefaults,
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
