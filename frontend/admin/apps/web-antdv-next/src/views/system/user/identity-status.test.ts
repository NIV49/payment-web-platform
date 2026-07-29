import { describe, expect, it } from 'vitest';

import { identityStatusPresentation } from './identity-status';

describe('identity status presentation', () => {
  it.each([
    ['PENDING_ACTIVATION', 'pendingActivation'],
    ['ACTIVE', 'active'],
    ['DISABLED', 'disabled'],
    ['LOCKED', 'locked'],
  ] as const)('maps %s to a distinct localized state', (status, label) => {
    expect(identityStatusPresentation(status).label).toContain(label);
  });
});
