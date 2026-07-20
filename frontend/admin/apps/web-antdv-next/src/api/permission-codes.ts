export const PERMISSION_CODES = {
  userView: 'user:view',
  userCreate: 'user:create',
  userUpdate: 'user:update',
  userDelete: 'user:delete',
  userDisable: 'user:disable',
  userAssignRole: 'user:assign-role',
  roleView: 'role:view',
  roleCreate: 'role:create',
  roleUpdate: 'role:update',
  roleDelete: 'role:delete',
  menuView: 'menu:view',
  menuManage: 'menu:manage',
  departmentView: 'department:view',
  departmentManage: 'department:manage',
} as const;

export type PermissionCode =
  (typeof PERMISSION_CODES)[keyof typeof PERMISSION_CODES];
