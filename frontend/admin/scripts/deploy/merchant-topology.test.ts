import { describe, expect, it } from 'vitest';

import { deployment as agentDeployment } from '../../apps/agent-admin/src/deployment';
import { deployment as merchantDeployment } from '../../apps/merchant-admin/src/deployment';
import { deployment as platformDeployment } from '../../apps/platform-admin/src/deployment';
import { assertValidBackendRoutesForPolicy } from '../../packages/effects/backoffice-runtime/src/router/product-access';

const platformRoutes = [
  {
    children: [
      {
        component: '/merchant/list',
        meta: { authCode: 'merchant:view', title: 'merchant.list.title' },
        name: 'MerchantList',
        path: '/merchant/list',
        type: 'menu',
      },
    ],
    component: null,
    meta: { title: 'merchant.title' },
    name: 'MerchantManagement',
    path: '/merchant',
    type: 'catalog',
  },
];

const merchantRoutes = [
  {
    component: '/merchant/profile',
    meta: { authCode: 'merchant:self-view', title: 'merchant.profile.title' },
    name: 'MerchantProfile',
    path: '/merchant/profile',
    type: 'menu',
  },
];

describe('mCH-001 three-application route topology', () => {
  it('accepts the exact PLATFORM merchant catalog and page', () => {
    expect(() =>
      assertValidBackendRoutesForPolicy(platformRoutes, platformDeployment),
    ).not.toThrow();
    expect(platformDeployment.menuPageComponents).toContain('/merchant/list');
  });

  it('accepts only the MERCHANT self-service profile page', () => {
    expect(() =>
      assertValidBackendRoutesForPolicy(merchantRoutes, merchantDeployment),
    ).not.toThrow();
    expect(merchantDeployment.menuPageComponents).toContain(
      '/merchant/profile',
    );
    expect(merchantDeployment.menuPageComponents).not.toContain(
      '/merchant/list',
    );
  });

  it('keeps every MCH-001 page and route out of the AGENT artifact', () => {
    expect(agentDeployment.menuPageComponents).not.toContain('/merchant/list');
    expect(agentDeployment.menuPageComponents).not.toContain(
      '/merchant/profile',
    );
    expect(agentDeployment.routePaths).not.toContain('/merchant');
    expect(agentDeployment.routeNames).not.toContain('MerchantManagement');
    expect(() =>
      assertValidBackendRoutesForPolicy(platformRoutes, agentDeployment),
    ).toThrow('Backend route is outside the current account-domain boundary');
    expect(() =>
      assertValidBackendRoutesForPolicy(merchantRoutes, agentDeployment),
    ).toThrow('Backend route is outside the current account-domain boundary');
  });

  it('does not statically import Merchant API or page modules into the AGENT entry graph', () => {
    const agentDeploymentSource = String(agentDeployment.pageMap);
    expect(agentDeploymentSource).not.toContain('/merchant/');
    expect(agentDeploymentSource).not.toContain('/platform/merchants');
    expect(agentDeploymentSource).not.toContain('/merchant/application');
  });
});
