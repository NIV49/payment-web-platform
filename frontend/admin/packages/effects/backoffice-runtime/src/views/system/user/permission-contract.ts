import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';

import { expandPermissionDependencies } from '../permission-dependencies';

type AccessCodeChecker = (codes: string[]) => boolean;

const USER_CREATE_PERMISSION_CODES = expandPermissionDependencies([
  PERMISSION_CODES.userCreate,
]);
const USER_EDIT_PERMISSION_CODES = expandPermissionDependencies([
  PERMISSION_CODES.userUpdate,
]);
const USER_DELETE_PERMISSION_CODES = expandPermissionDependencies([
  PERMISSION_CODES.userDelete,
]);
const USER_STATUS_PERMISSION_CODES = expandPermissionDependencies([
  PERMISSION_CODES.userDisable,
]);
const USER_ROLE_ASSIGNMENT_PERMISSION_CODES = expandPermissionDependencies([
  PERMISSION_CODES.userAssignRole,
]);

function hasAllAccessCodes(
  codes: readonly string[],
  hasAccessByCodes: AccessCodeChecker,
) {
  return codes.length > 0 && codes.every((code) => hasAccessByCodes([code]));
}

export {
  hasAllAccessCodes,
  USER_CREATE_PERMISSION_CODES,
  USER_DELETE_PERMISSION_CODES,
  USER_EDIT_PERMISSION_CODES,
  USER_ROLE_ASSIGNMENT_PERMISSION_CODES,
  USER_STATUS_PERMISSION_CODES,
};
