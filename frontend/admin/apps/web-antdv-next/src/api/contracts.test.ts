import type { SystemDeptApi } from './system/dept';
import type { SystemMenuApi } from './system/menu';
import type { SystemRoleApi } from './system/role';
import type { IamRoleGrantApi } from './system/role-grant';
import type { PageResult } from './system/types';
import type { SystemUserApi } from './system/user';

import { describe, expect, expectTypeOf, it } from 'vitest';

import {
  isOptimisticLockConflict,
  OPTIMISTIC_LOCK_CONFLICT,
  resolveApiErrorMessage,
} from './error-contract';
import { PERMISSION_CODES } from './permission-codes';
import { COOKIE_SESSION_MARKER, formatSessionAuthorization } from './session';
import {
  isRegisteredMenuComponent,
  isVbenLocaleKey,
  isVbenRouteName,
  menuTypeRequiresRoutePath,
  resolveVbenMenuComponent,
} from './system/menu-contract';
import { hasExplicitRoleIds } from './system/types';

describe('admin API contracts', () => {
  it('never exposes the cookie session marker as an Authorization header', () => {
    expect(formatSessionAuthorization(COOKIE_SESSION_MARKER)).toBeNull();
    expect(formatSessionAuthorization(null)).toBeNull();
    expect(formatSessionAuthorization('legacy-browser-token')).toBeNull();
  });

  it('treats optimistic lock errors as reload signals and shows readable messages', () => {
    const error = {
      response: {
        data: {
          code: 40_902,
          error: OPTIMISTIC_LOCK_CONFLICT,
          message: 'The record has changed; reload and retry',
        },
      },
    };

    expect(isOptimisticLockConflict(error)).toBe(true);
    expect(resolveApiErrorMessage(error.response.data, 'fallback')).toBe(
      'The record has changed; reload and retry',
    );
  });

  it.each([
    {
      data: {
        code: 40_901,
        error: OPTIMISTIC_LOCK_CONFLICT,
      },
      scenario: 'a different numeric code',
    },
    {
      data: {
        error: OPTIMISTIC_LOCK_CONFLICT,
      },
      scenario: 'a missing code',
    },
    {
      data: {
        code: 40_902,
        error: 'DATA_CONFLICT',
      },
      scenario: 'a different machine error',
    },
    {
      data: {
        code: '40902',
        error: OPTIMISTIC_LOCK_CONFLICT,
      },
      scenario: 'a non-numeric code',
    },
  ])('does not treat $scenario as an optimistic lock conflict', ({ data }) => {
    expect(isOptimisticLockConflict({ response: { data } })).toBe(false);
  });

  it('keeps permission codes unique and in lowercase resource:action form', () => {
    const codes = Object.values(PERMISSION_CODES);

    expect(new Set(codes).size).toBe(codes.length);
    expect(codes.every((code) => /^[a-z-]+:[a-z-]+$/.test(code))).toBe(true);
    expect(codes).toEqual([
      'user:view',
      'user:create',
      'user:update',
      'user:delete',
      'user:disable',
      'user:assign-role',
      'role:view',
      'role:create',
      'role:update',
      'role:delete',
      'role:grant-update',
      'menu:view',
      'menu:create',
      'menu:update',
      'menu:delete',
      'department:view',
      'department:create',
      'department:update',
      'department:delete',
    ]);
  });

  it('defines user and role list contracts as paginated results', () => {
    expectTypeOf<SystemUserApi.SystemUser>().toMatchTypeOf<{
      identityStatus: 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'PENDING_ACTIVATION';
      status: 0 | 1;
    }>();
    expectTypeOf<PageResult<SystemUserApi.SystemUser>>().toMatchTypeOf<{
      items: SystemUserApi.SystemUser[];
      total: number;
    }>();
    expectTypeOf<PageResult<SystemRoleApi.SystemRole>>().toMatchTypeOf<{
      items: SystemRoleApi.SystemRole[];
      total: number;
    }>();
    expectTypeOf<SystemRoleApi.SystemRole>().toMatchTypeOf<{
      assignable: boolean;
      rowVersion: number;
      systemRole: boolean;
    }>();
    expectTypeOf<SystemDeptApi.SystemDept>().toMatchTypeOf<{
      rowVersion: number;
    }>();
    expectTypeOf<SystemMenuApi.SystemMenu>().toMatchTypeOf<{
      rowVersion: number;
    }>();
  });

  it('separates global user creation from membership updates', () => {
    expectTypeOf<SystemUserApi.UserCreateParams>().toEqualTypeOf<{
      deptId: string;
      name: string;
      remark?: string;
      roleIds: string[];
      status: 0 | 1;
      username: string;
    }>();
    expectTypeOf<SystemUserApi.MembershipUpdateParams>().toEqualTypeOf<{
      deptId: string;
      roleIds: string[];
      status: 0 | 1;
      userVersion: number;
    }>();
    expectTypeOf<SystemUserApi.UserStatusParams>().toEqualTypeOf<{
      status: 0 | 1;
      userVersion: number;
    }>();
  });

  it('accepts an explicit empty roleIds array but rejects missing values', () => {
    expect(hasExplicitRoleIds([])).toBe(true);
    expect(hasExplicitRoleIds(['role-1'])).toBe(true);
    expect(hasExplicitRoleIds(null)).toBe(false);
    expect(hasExplicitRoleIds(undefined)).toBe(false);
  });

  it('uses menuIds rather than ambiguous permissions on roles', () => {
    expectTypeOf<SystemRoleApi.SystemRole>().toMatchTypeOf<{
      menuIds: string[];
    }>();
  });

  it('keeps role grants separate from navigation menu ids', () => {
    expectTypeOf<IamRoleGrantApi.ReplaceRoleGrantsParams>().toEqualTypeOf<{
      expectedVersion: number;
      grants: IamRoleGrantApi.GrantIntent[];
      reason: string;
    }>();
    expectTypeOf<IamRoleGrantApi.GrantIntent>().toMatchTypeOf<{
      dimensions: IamRoleGrantApi.GrantDimension[];
      grantKey: string;
      permissionCode: string;
    }>();
  });

  it('requires expectedVersion on non-user administration mutations', () => {
    expectTypeOf<SystemRoleApi.RoleUpdateParams>().toMatchTypeOf<{
      expectedVersion: number;
    }>();
    expectTypeOf<SystemRoleApi.RoleStatusParams>().toMatchTypeOf<{
      expectedVersion: number;
    }>();
    expectTypeOf<SystemDeptApi.DeptUpdateParams>().toMatchTypeOf<{
      expectedVersion: number;
    }>();
    expectTypeOf<SystemMenuApi.MenuUpdateParams>().toMatchTypeOf<{
      expectedVersion: number;
    }>();
  });

  it('uses locale keys rather than translated text in backend menu metadata', () => {
    expect(isVbenLocaleKey('system.title')).toBe(true);
    expect(isVbenLocaleKey('system.user.title')).toBe(true);
    expect(isVbenLocaleKey('page.dashboard.analytics')).toBe(true);
    expect(isVbenLocaleKey('System Management')).toBe(false);
    expect(isVbenLocaleKey('system')).toBe(false);
    expect(isVbenLocaleKey(undefined)).toBe(false);
  });

  it('uses Vben route identifiers rather than display text as menu names', () => {
    expect(isVbenRouteName('SystemUser')).toBe(true);
    expect(isVbenRouteName('system_user2')).toBe(true);
    expect(isVbenRouteName('2SystemUser')).toBe(false);
    expect(isVbenRouteName('system-user')).toBe(false);
    expect(isVbenRouteName('系统用户')).toBe(false);
    expect(isVbenRouteName(undefined)).toBe(false);
  });

  it('accepts only exact page components registered by the current app', () => {
    const components = ['/system/user/list', '/system/role/list'];

    expect(isRegisteredMenuComponent('/system/user/list', components)).toBe(
      true,
    );
    expect(isRegisteredMenuComponent('/system/user/list.vue', components)).toBe(
      false,
    );
    expect(isRegisteredMenuComponent('/system/not-found', components)).toBe(
      false,
    );
    expect(isRegisteredMenuComponent(undefined, components)).toBe(false);
  });

  it('uses Vben IFrameView and route-path rules for non-page menu types', () => {
    expect(resolveVbenMenuComponent('menu', '/system/user/list')).toBe(
      '/system/user/list',
    );
    expect(resolveVbenMenuComponent('embedded')).toBe('IFrameView');
    expect(resolveVbenMenuComponent('link')).toBe('IFrameView');
    expect(resolveVbenMenuComponent('catalog')).toBeUndefined();
    expect(resolveVbenMenuComponent('button')).toBeUndefined();

    expect(menuTypeRequiresRoutePath('catalog')).toBe(true);
    expect(menuTypeRequiresRoutePath('menu')).toBe(true);
    expect(menuTypeRequiresRoutePath('embedded')).toBe(true);
    expect(menuTypeRequiresRoutePath('link')).toBe(true);
    expect(menuTypeRequiresRoutePath('button')).toBe(false);
  });
});
