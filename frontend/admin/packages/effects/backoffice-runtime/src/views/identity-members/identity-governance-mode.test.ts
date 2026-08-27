import { describe, expect, it } from 'vitest';

import { resolveIdentityGovernanceMode } from './identity-governance-mode';

describe('identity governance mode', () => {
  it('keeps member reads but hides OIDC-only writes in local development', () => {
    expect(
      resolveIdentityGovernanceMode({ explicitMode: 'local', prod: false }),
    ).toEqual({ oidcWritesEnabled: false });
  });

  it('enables identity lifecycle writes for OIDC and all production builds', () => {
    expect(
      resolveIdentityGovernanceMode({ explicitMode: 'oidc', prod: false }),
    ).toEqual({ oidcWritesEnabled: true });
    expect(
      resolveIdentityGovernanceMode({ explicitMode: 'local', prod: true }),
    ).toEqual({ oidcWritesEnabled: true });
  });

  it('fails closed for an unknown development authentication mode', () => {
    expect(() =>
      resolveIdentityGovernanceMode({
        explicitMode: 'unexpected',
        prod: false,
      }),
    ).toThrow('Invalid identity governance authentication mode');
  });
});
