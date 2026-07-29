import type { SystemUserApi } from '#/api/system/user';

export type UserFormValues = Pick<
  SystemUserApi.MembershipUpdateParams,
  'userVersion'
> &
  SystemUserApi.UserCreateParams;

export function toUserCreateParams(
  values: UserFormValues,
  roleIds: string[],
): SystemUserApi.UserCreateParams {
  return {
    deptId: values.deptId,
    name: values.name,
    remark: values.remark,
    roleIds,
    status: values.status,
    username: values.username,
  };
}

export function toMembershipUpdateParams(
  values: UserFormValues,
  roleIds: string[],
): SystemUserApi.MembershipUpdateParams {
  return {
    deptId: values.deptId,
    roleIds,
    status: values.status,
    userVersion: values.userVersion,
  };
}
