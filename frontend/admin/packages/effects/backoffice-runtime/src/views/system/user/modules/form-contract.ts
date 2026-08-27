import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

export type UserFormValues = Pick<
  SystemUserApi.SystemAdministratorUserUpdateParams,
  'credentialVersion' | 'identityVersion' | 'userVersion'
> &
  SystemUserApi.UserCreateParams;

export function preserveUserScope(
  values: UserFormValues,
  existing?: Pick<SystemUserApi.SystemUser, 'accountDomain' | 'tenantId'>,
): UserFormValues {
  if (!existing) return values;
  return {
    ...values,
    accountDomain: existing.accountDomain,
    tenantId: existing.tenantId,
  };
}

export function toUserCreateParams(
  values: UserFormValues,
  roleIds: string[],
): SystemUserApi.UserCreateParams {
  return {
    ...(values.accountDomain ? { accountDomain: values.accountDomain } : {}),
    deptId: values.deptId,
    name: values.name,
    remark: values.remark,
    roleIds,
    status: values.status,
    ...(values.tenantId ? { tenantId: values.tenantId } : {}),
    username: values.username,
  };
}

export function toMembershipUpdateParams(
  values: UserFormValues,
  roleIds: string[],
  canEditIdentity = false,
): SystemUserApi.UserUpdateParams {
  const membership: SystemUserApi.MembershipUpdateParams = {
    deptId: values.deptId,
    roleIds,
    status: values.status,
    userVersion: values.userVersion,
  };
  if (!canEditIdentity) return membership;

  return {
    ...membership,
    ...(values.accountDomain ? { accountDomain: values.accountDomain } : {}),
    credentialVersion: values.credentialVersion,
    identityVersion: values.identityVersion,
    name: values.name,
    remark: values.remark,
    ...(values.tenantId ? { tenantId: values.tenantId } : {}),
    username: values.username,
  };
}
