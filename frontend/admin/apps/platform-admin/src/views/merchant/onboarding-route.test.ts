import { describe, expect, it } from 'vitest';

import { deployment } from '../../deployment';

describe('pLATFORM merchant onboarding route', () => {
  it('registers separate hidden full-page create/edit, detail, and review routes', () => {
    expect(deployment.accessRoutes).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          meta: expect.objectContaining({
            activePath: '/merchant/list',
            hideInMenu: true,
          }),
          name: 'MerchantOnboarding',
          path: '/merchant/onboarding/:merchantId?',
        }),
        expect.objectContaining({
          meta: expect.objectContaining({
            activePath: '/merchant/list',
            hideInMenu: true,
          }),
          name: 'MerchantDetail',
          path: '/merchant/detail/:merchantId',
        }),
        expect.objectContaining({
          meta: expect.objectContaining({
            activePath: '/merchant/list',
            hideInMenu: true,
          }),
          name: 'MerchantReview',
          path: '/merchant/review/:merchantId',
        }),
      ]),
    );
    expect(
      deployment.accessRoutes?.find(
        ({ name }) => name === 'MerchantOnboarding',
      ),
    ).toMatchObject({
      meta: {
        activePath: '/merchant/list',
        hideInMenu: true,
      },
      path: '/merchant/onboarding/:merchantId?',
    });
  });
});
