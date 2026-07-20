import { describe, expect, it } from 'vitest';

import { resolveLoginDefaults } from './login-defaults';

describe('login development defaults', () => {
  it('prefills the configured local development account', () => {
    expect(resolveLoginDefaults(true, ' admin ', 'Admin@123456')).toEqual({
      password: 'Admin@123456',
      username: 'admin',
    });
  });

  it('never exposes development credentials in a production build', () => {
    expect(resolveLoginDefaults(false, 'admin', 'Admin@123456')).toEqual({
      password: '',
      username: '',
    });
  });
});
