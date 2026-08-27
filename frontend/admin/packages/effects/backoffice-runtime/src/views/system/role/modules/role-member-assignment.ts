import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

function mergeRoleMemberCatalog(
  assignedUsers: SystemUserApi.SystemUser[],
  unassignedUsers: SystemUserApi.SystemUser[],
  operatorUserId?: string,
) {
  const usersById = new Map(
    assignedUsers.map((user) => [user.id, user] as const),
  );
  unassignedUsers
    .filter((user) => user.id !== operatorUserId)
    .forEach((user) => usersById.set(user.id, user));
  return [...usersById.values()];
}

export { mergeRoleMemberCatalog };
