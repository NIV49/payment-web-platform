import { describe, expect, it } from 'vitest';

import { createRoleRequestGuard } from './role-request-guard';

describe('role-scoped request guard', () => {
  it('rejects a late role A response after role B becomes active', async () => {
    const guard = createRoleRequestGuard();
    const appliedRoleIds: string[] = [];
    const roleA = Promise.withResolvers<string>();
    const roleB = Promise.withResolvers<string>();

    async function load(roleId: string, response: Promise<string>) {
      const identity = guard.begin(roleId);
      const responseRoleId = await response;
      if (guard.isCurrent(identity, roleId)) {
        appliedRoleIds.push(responseRoleId);
      }
    }

    const loadingA = load('A', roleA.promise);
    guard.invalidate();
    const loadingB = load('B', roleB.promise);

    roleB.resolve('B');
    await loadingB;
    roleA.resolve('A');
    await loadingA;

    expect(appliedRoleIds).toEqual(['B']);
  });
});
