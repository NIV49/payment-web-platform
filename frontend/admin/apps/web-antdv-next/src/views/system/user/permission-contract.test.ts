import { describe, expect, it, vi } from 'vitest';

import { PERMISSION_CODES } from '#/api/permission-codes';

import {
  hasAllAccessCodes,
  USER_CREATE_PERMISSION_CODES,
  USER_EDIT_PERMISSION_CODES,
} from './permission-contract';

describe('user administration permission contract', () => {
  it('requires user creation and both option sources before showing create', () => {
    expect(USER_CREATE_PERMISSION_CODES).toEqual([
      PERMISSION_CODES.userCreate,
      PERMISSION_CODES.departmentView,
      PERMISSION_CODES.roleView,
    ]);
  });

  it('requires update, disable, assign-role, and both option sources before showing user edit', () => {
    expect(USER_EDIT_PERMISSION_CODES).toEqual([
      PERMISSION_CODES.userUpdate,
      PERMISSION_CODES.userDisable,
      PERMISSION_CODES.userAssignRole,
      PERMISSION_CODES.departmentView,
      PERMISSION_CODES.roleView,
    ]);

    const grantedCodes = new Set<string>(USER_EDIT_PERMISSION_CODES);
    const hasAccessByCodes = vi.fn((codes: string[]) =>
      codes.some((code) => grantedCodes.has(code)),
    );

    expect(
      hasAllAccessCodes(USER_EDIT_PERMISSION_CODES, hasAccessByCodes),
    ).toBe(true);
    expect(hasAccessByCodes.mock.calls).toEqual(
      USER_EDIT_PERMISSION_CODES.map((code) => [[code]]),
    );
  });

  it('fails closed when one required code is missing from an OR-based checker', () => {
    const grantedCodes = new Set<string>([
      PERMISSION_CODES.userDisable,
      PERMISSION_CODES.userUpdate,
    ]);
    const hasAccessByCodes = (codes: string[]) =>
      codes.some((code) => grantedCodes.has(code));

    expect(hasAccessByCodes([...USER_EDIT_PERMISSION_CODES])).toBe(true);
    expect(
      hasAllAccessCodes(USER_EDIT_PERMISSION_CODES, hasAccessByCodes),
    ).toBe(false);
  });

  it('fails closed when no permission codes are declared', () => {
    expect(hasAllAccessCodes([], () => true)).toBe(false);
  });
});
