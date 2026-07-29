import { PERMISSION_CODES } from '#/api/permission-codes';

type AccessCodeChecker = (codes: string[]) => boolean;

const USER_EDIT_PERMISSION_CODES = [
  PERMISSION_CODES.userUpdate,
  PERMISSION_CODES.userDisable,
  PERMISSION_CODES.userAssignRole,
] as const;

function hasAllAccessCodes(
  codes: readonly string[],
  hasAccessByCodes: AccessCodeChecker,
) {
  return codes.length > 0 && codes.every((code) => hasAccessByCodes([code]));
}

export { hasAllAccessCodes, USER_EDIT_PERMISSION_CODES };
