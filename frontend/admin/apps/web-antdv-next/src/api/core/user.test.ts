import { describe, expect, it } from 'vitest';

import { mapCurrentUserResponse } from './user-contract';

const currentUser = {
  avatar: '',
  desc: '',
  homePath: '/dashboard',
  realName: 'Platform Administrator',
  roles: ['platform-admin'],
  token: 'cookie-session',
  userId: '100',
  username: 'admin',
  systemAdministrator: true,
};

describe('current user response mapping', () => {
  it('maps the exact non-secret session marker', () => {
    expect(mapCurrentUserResponse(currentUser)).toEqual(currentUser);
  });

  it('ignores additive response fields', () => {
    expect(
      mapCurrentUserResponse({
        ...currentUser,
        futureField: 'ignored',
      }),
    ).toEqual(currentUser);
  });

  it.each([undefined, null, false, 'true', 1])(
    'defaults a non-true system administrator fact to false',
    (systemAdministrator) => {
      expect(
        mapCurrentUserResponse({
          ...currentUser,
          systemAdministrator,
        }).systemAdministrator,
      ).toBe(false);
    },
  );

  it.each([
    withoutSessionMarker(),
    withSessionMarker(null),
    withSessionMarker('not-a-session-marker'),
  ])('rejects a missing or unsafe token marker', (response) => {
    expect(() => mapCurrentUserResponse(response)).toThrow(
      'Invalid current-user session marker',
    );
  });

  it.each([
    { ...currentUser, desc: null },
    { ...currentUser, roles: ['platform-admin', 1] },
    { ...currentUser, userId: 100 },
  ])('rejects an invalid current-user payload', (response) => {
    expect(() => mapCurrentUserResponse(response)).toThrow(
      'Invalid current-user response',
    );
  });
});

function withSessionMarker(value: unknown) {
  return {
    ...currentUser,
    ...Object.fromEntries([['token', value]]),
  };
}

function withoutSessionMarker() {
  const response: Record<string, unknown> = { ...currentUser };
  Reflect.deleteProperty(response, 'token');
  return response;
}
