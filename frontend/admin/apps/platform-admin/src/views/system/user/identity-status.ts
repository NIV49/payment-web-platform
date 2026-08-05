import type { SystemUserApi } from '#/api';

export const IDENTITY_STATUS_PRESENTATION = {
  ACTIVE: { color: 'success', label: 'system.user.identityState.active' },
  DISABLED: { color: 'error', label: 'system.user.identityState.disabled' },
  LOCKED: { color: 'warning', label: 'system.user.identityState.locked' },
  PENDING_ACTIVATION: {
    color: 'processing',
    label: 'system.user.identityState.pendingActivation',
  },
} as const satisfies Record<
  SystemUserApi.IdentityStatus,
  { color: string; label: string }
>;

export function identityStatusPresentation(
  status: SystemUserApi.IdentityStatus,
) {
  return IDENTITY_STATUS_PRESENTATION[status];
}
