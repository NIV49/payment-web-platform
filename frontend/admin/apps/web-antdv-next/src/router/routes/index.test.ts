import { describe, expect, it } from 'vitest';

import { accessRoutes } from './index';

describe('product mixed route allowlist', () => {
  it('registers only the hidden Profile route from local modules', () => {
    expect(accessRoutes.map((route) => route.name)).toEqual(['Profile']);
  });
});
