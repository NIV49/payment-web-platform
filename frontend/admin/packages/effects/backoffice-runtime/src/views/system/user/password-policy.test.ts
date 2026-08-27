import { describe, expect, it } from 'vitest';

import {
  generateLocalPassword,
  isLocalPasswordResetMode,
  isValidLocalPassword,
  LOCAL_PASSWORD_SPECIAL_CHARACTERS,
  secureRandomIndex,
} from './password-policy';

describe('local password policy', () => {
  it('generates every supported length with all required character classes and exactly four specials', () => {
    for (
      let requestedLength = 16;
      requestedLength <= 24;
      requestedLength += 1
    ) {
      let call = 0;
      const password = generateLocalPassword((upperBound) => {
        call += 1;
        return call === 1 ? requestedLength - 16 : (call - 2) % upperBound;
      });

      expect(password).toHaveLength(requestedLength);
      expect(password).toMatch(/[0-9]/);
      expect(password).toMatch(/[A-Z]/);
      expect(password).toMatch(/[a-z]/);
      expect(
        [...password].filter((character) =>
          LOCAL_PASSWORD_SPECIAL_CHARACTERS.includes(character),
        ),
      ).toHaveLength(4);
      expect(isValidLocalPassword(password)).toBe(true);
    }
  });

  it('uses rejection sampling instead of modulo-biased random values', () => {
    let calls = 0;
    const values = [4_294_967_295, 17];

    expect(
      secureRandomIndex(10, (target) => {
        const value = values[calls++];
        if (value === undefined) throw new Error('Random source exhausted');
        target[0] = value;
        return target;
      }),
    ).toBe(7);
    expect(calls).toBe(2);
  });

  it('enables local reset only in an explicit non-production local build', () => {
    expect(
      isLocalPasswordResetMode({ explicitMode: 'local', prod: false }),
    ).toBe(true);
    expect(
      isLocalPasswordResetMode({ explicitMode: 'oidc', prod: false }),
    ).toBe(false);
    expect(
      isLocalPasswordResetMode({ explicitMode: 'local', prod: true }),
    ).toBe(false);
    expect(() =>
      isLocalPasswordResetMode({ explicitMode: undefined, prod: false }),
    ).toThrow('Invalid local password reset authentication mode');
  });

  it.each([
    'Aa1!Aa1!Aa1!Aa1',
    'aaaaaaaaaaaa!!!!',
    'AAAAAAAAAAAA!!!!',
    '111111111111!!!!',
    'Aa1!Aa1!Aa1!Aa1!!',
    'Aa1!Aa1!Aa1!Aa1?',
    'Aa1!Aa1!Aa1!Aa1\u4E2D',
  ])('rejects a password outside the exact contract: %s', (password) => {
    expect(isValidLocalPassword(password)).toBe(false);
  });
});
