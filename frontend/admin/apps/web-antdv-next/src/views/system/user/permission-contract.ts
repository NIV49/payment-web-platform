import { PERMISSION_CODES } from '#/api/permission-codes';

type AccessCodeChecker = (codes: string[]) => boolean;

const USER_CREATE_PERMISSION_CODES = [
  PERMISSION_CODES.userCreate,
  PERMISSION_CODES.departmentView,
  PERMISSION_CODES.roleView,
] as const;

const USER_EDIT_PERMISSION_CODES = [
  PERMISSION_CODES.userUpdate,
  PERMISSION_CODES.userDisable,
  PERMISSION_CODES.userAssignRole,
  PERMISSION_CODES.departmentView,
  PERMISSION_CODES.roleView,
] as const;

function hasAllAccessCodes(
  codes: readonly string[],
  hasAccessByCodes: AccessCodeChecker,
) {
  return codes.length > 0 && codes.every((code) => hasAccessByCodes([code]));
}

export {
  hasAllAccessCodes,
  USER_CREATE_PERMISSION_CODES,
  USER_EDIT_PERMISSION_CODES,
};
