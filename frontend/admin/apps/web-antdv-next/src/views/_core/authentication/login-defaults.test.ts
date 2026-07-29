import { describe, expect, it } from 'vitest';

import { resolveLoginDefaults } from './login-defaults';

describe('login defaults', () => {
  it('returns empty account fields without accepting environment input', () => {
    expect(resolveLoginDefaults).toHaveLength(0);

    expect(resolveLoginDefaults()).toEqual({
      password: '',
      username: '',
    });
  });
});
