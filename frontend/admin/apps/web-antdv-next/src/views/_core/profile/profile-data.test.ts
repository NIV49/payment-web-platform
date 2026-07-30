import type { BasicUserInfo } from '@vben/types';

import { describe, expect, it } from 'vitest';

import { createProfileDescriptionItems } from './profile-data';

const labels = {
  name: 'Name',
  roles: 'Roles',
  userId: 'User ID',
  username: 'Login Account',
};

describe('profile description policy', () => {
  it('maps only authoritative current-session identity fields', () => {
    const userInfo: BasicUserInfo = {
      avatar: '',
      realName: 'Platform Administrator',
      roles: ['platform-admin'],
      userId: '100',
      username: 'admin',
    };

    expect(createProfileDescriptionItems(userInfo, labels)).toEqual([
      { content: 'Platform Administrator', key: 'realName', label: 'Name' },
      { content: 'admin', key: 'username', label: 'Login Account' },
      { content: '100', key: 'userId', label: 'User ID' },
      { content: 'platform-admin', key: 'roles', label: 'Roles' },
    ]);
  });

  it('uses a neutral placeholder when session data is unavailable', () => {
    expect(createProfileDescriptionItems(null, labels)).toEqual([
      { content: '-', key: 'realName', label: 'Name' },
      { content: '-', key: 'username', label: 'Login Account' },
      { content: '-', key: 'userId', label: 'User ID' },
      { content: '-', key: 'roles', label: 'Roles' },
    ]);
  });
});
