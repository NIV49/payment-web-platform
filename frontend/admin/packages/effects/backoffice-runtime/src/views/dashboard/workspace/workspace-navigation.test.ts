import { describe, expect, it } from 'vitest';

import {
  getAccessibleWorkspaceQuickNavItems,
  WORKSPACE_QUICK_NAV_ITEMS,
} from './workspace-navigation';

describe('workspace quick navigation', () => {
  it('targets only product routes provided by the current menu contract', () => {
    expect(WORKSPACE_QUICK_NAV_ITEMS.map(({ url }) => url)).toEqual([
      '/',
      '/dashboard/workspace',
      '/system/menu',
      '/system/role',
      '/dashboard/analytics',
    ]);
  });

  it('does not expose removed demo routes', () => {
    expect(
      WORKSPACE_QUICK_NAV_ITEMS.some(({ url }) => url?.startsWith('/demos')),
    ).toBe(false);
  });

  it('hides shortcuts whose dynamic routes were not registered', () => {
    const accessibleRouteNames = new Set(['Dashboard', 'Workspace']);

    expect(
      getAccessibleWorkspaceQuickNavItems((name) =>
        accessibleRouteNames.has(name),
      ).map(({ url }) => url),
    ).toEqual(['/', '/dashboard/workspace']);
  });
});
