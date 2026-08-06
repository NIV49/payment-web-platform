import {
  installBackofficeDeployment,
  parseAccountDomain,
} from '@payment/backoffice-runtime/deployment-internal';
import { describe, expect, it } from 'vitest';

import {
  assertNoReservedBackendRoutes,
  assertValidBackendRoutes,
  assertValidBackendRoutesForPolicy,
  PRODUCT_ACCESS_MODE,
} from './product-access';

const agentPolicy = {
  accountDomain: 'AGENT' as const,
  menuPageComponents: ['/dashboard/workspace/index'],
  routeNames: ['AgentDashboard', 'AgentWorkspace'],
  routePaths: ['/dashboard', '/dashboard/workspace'],
};
const merchantPolicy = {
  accountDomain: 'MERCHANT' as const,
  menuPageComponents: ['/dashboard/workspace/index'],
  routeNames: ['MerchantDashboard', 'MerchantWorkspace'],
  routePaths: ['/dashboard', '/dashboard/workspace'],
};

installBackofficeDeployment({
  accountDomain: 'PLATFORM',
  menuPageComponents: ['/dashboard/workspace/index', '/system/user/list'],
  pageMap: {},
  routeNames: [],
  routePaths: [],
});

describe('product access policy', () => {
  it('keeps mixed mode independent of user preferences', () => {
    expect(PRODUCT_ACCESS_MODE).toBe('mixed');
  });

  it.each([
    {
      routes: [{ name: 'Profile', path: '/remote-profile' }],
      scenario: 'reserved route name',
    },
    {
      routes: [{ name: 'RemoteProfile', path: '/profile/' }],
      scenario: 'reserved canonical route path',
    },
    {
      routes: [
        {
          children: [{ name: 'RemoteProfile', path: '/PROFILE' }],
          name: 'System',
          path: '/system',
          type: 'catalog',
        },
      ],
      scenario: 'nested reserved route path',
    },
    ...['Root', 'Authentication', 'Login', 'FallbackNotFound'].map((name) => ({
      routes: [{ name, path: `/remote-${name.toLowerCase()}` }],
      scenario: `core route name ${name}`,
    })),
    ...['/', '/auth', '/auth/login/', '/:path(.*)*'].map((path) => ({
      routes: [{ name: `Remote${path}`, path }],
      scenario: `core canonical route path ${path}`,
    })),
  ])('rejects a backend $scenario', ({ routes }) => {
    expect(() => assertNoReservedBackendRoutes(routes)).toThrow(
      'Backend route conflicts with reserved local route',
    );
  });

  it('accepts unrelated backend routes', () => {
    expect(() =>
      assertNoReservedBackendRoutes([
        {
          children: [
            {
              component: '/system/user/list',
              name: 'SystemUser',
              path: '/system/user',
              type: 'menu',
            },
          ],
          name: 'System',
          path: '/system',
          type: 'catalog',
        },
      ]),
    ).not.toThrow();
  });

  it.each([
    {
      component: 'IFrameView',
      meta: { iframeSrc: 'https://example.invalid/report' },
      name: 'EmbeddedReport',
      path: '/reports/embedded',
      type: 'embedded',
    },
    {
      component: 'IFrameView',
      meta: { link: 'https://example.invalid/help' },
      name: 'ExternalHelp',
      path: '/help/external',
      type: 'link',
    },
    {
      component: '/system/user/list',
      name: 'CustomUserDirectory',
      path: '/custom/user-directory',
      type: 'menu',
    },
  ])('keeps a valid platform route contract for $type', (route) => {
    expect(() => assertNoReservedBackendRoutes([route])).not.toThrow();
  });

  it.each(['AGENT', 'MERCHANT', 'PLATFORM'] as const)(
    'accepts the %s account domain',
    (domain) => expect(parseAccountDomain(domain)).toBe(domain),
  );

  it.each([
    ['agent', agentPolicy, 'AgentDashboard', 'AgentWorkspace'],
    ['merchant', merchantPolicy, 'MerchantDashboard', 'MerchantWorkspace'],
  ] as const)(
    'enforces the %s deployment route allowlist',
    (_, policy, catalogName, pageName) => {
      const allowedPage = {
        component: '/dashboard/workspace/index',
        name: pageName,
        path: 'workspace',
        type: 'menu',
      };
      const allowedCatalog = {
        children: [allowedPage],
        name: catalogName,
        path: '/dashboard',
        type: 'catalog',
      };
      const allowed = [allowedCatalog];
      expect(() =>
        assertValidBackendRoutesForPolicy(allowed, policy),
      ).not.toThrow();
      expect(() =>
        assertValidBackendRoutesForPolicy(
          [{ ...allowedCatalog, name: 'WrongDashboard' }],
          policy,
        ),
      ).toThrow('Backend route is outside the current account-domain boundary');
      expect(() =>
        assertValidBackendRoutesForPolicy(
          [{ ...allowedCatalog, path: '/wrong-dashboard' }],
          policy,
        ),
      ).toThrow('Backend route is outside the current account-domain boundary');
      expect(() =>
        assertValidBackendRoutesForPolicy(
          [
            {
              ...allowedCatalog,
              children: [
                {
                  ...allowedPage,
                  component: '/system/user/list',
                },
              ],
            },
          ],
          policy,
        ),
      ).toThrow('Backend route is outside the current account-domain boundary');
      expect(() =>
        assertValidBackendRoutesForPolicy(
          [
            {
              component: 'IFrameView',
              meta: { link: 'https://example.invalid' },
              name: pageName,
              path: '/dashboard/workspace',
              type: 'link',
            },
          ],
          policy,
        ),
      ).toThrow('Backend route is outside the current account-domain boundary');
    },
  );

  it.each([undefined, '', 'TENANT', 'platform'])(
    'rejects an untrusted account domain value',
    (domain) =>
      expect(() => parseAccountDomain(domain)).toThrow(
        'Invalid backoffice account domain',
      ),
  );

  it('rejects an executable component outside the deployment allowlist', () => {
    expect(() =>
      assertValidBackendRoutes([
        {
          component: '/payments/ledger/index',
          name: 'Ledger',
          path: '/system',
          type: 'menu',
        },
      ]),
    ).toThrow('Backend route is outside the current account-domain boundary');
  });
});
