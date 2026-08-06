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
  roleGrantUpdate: 'role:grant-update',
  menuView: 'menu:view',
  menuCreate: 'menu:create',
  menuUpdate: 'menu:update',
  menuDelete: 'menu:delete',
  departmentView: 'department:view',
  departmentCreate: 'department:create',
  departmentUpdate: 'department:update',
  departmentDelete: 'department:delete',
} as const;

export type PermissionCode =
  (typeof PERMISSION_CODES)[keyof typeof PERMISSION_CODES];
