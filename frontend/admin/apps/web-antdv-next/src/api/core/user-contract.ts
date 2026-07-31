import type { UserInfo } from '@vben/types';

import { COOKIE_SESSION_MARKER } from '../session';

type JsonRecord = Record<string, unknown>;

export type CurrentUserInfo = UserInfo & {
  systemAdministrator: boolean;
};

function isJsonRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.every((item) => typeof item === 'string')
  );
}

export function mapCurrentUserResponse(response: unknown): CurrentUserInfo {
  if (!isJsonRecord(response)) {
    throw new Error('Invalid current-user response');
  }

  if (response.token !== COOKIE_SESSION_MARKER) {
    throw new Error('Invalid current-user session marker');
  }

  if (
    typeof response.avatar !== 'string' ||
    typeof response.desc !== 'string' ||
    typeof response.homePath !== 'string' ||
    typeof response.realName !== 'string' ||
    !isStringArray(response.roles) ||
    typeof response.userId !== 'string' ||
    typeof response.username !== 'string'
  ) {
    throw new Error('Invalid current-user response');
  }

  return {
    avatar: response.avatar,
    desc: response.desc,
    homePath: response.homePath,
    realName: response.realName,
    roles: response.roles,
    systemAdministrator: response.systemAdministrator === true,
    token: 'cookie-session',
    userId: response.userId,
    username: response.username,
  };
}
