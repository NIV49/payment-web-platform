import { requestClient } from './request';

export namespace IdentityGovernanceApi {
  export type Member = {
    currentMembership: boolean;
    displayName: string;
    identityStatus: string;
    membershipId: string;
    membershipStatus: string;
    provisioningStatus: string;
    systemAdministrator: boolean;
  };

  export type MemberPage = { items: Member[]; total: number };
  export type InvitationRole = { roleId: string; roleName: string };
  export type Invitation = {
    invitationId: string;
    membershipId: string;
    status: string;
  };
  export type Recovery = { recoveryId: string; status: string };
  export type TenantBootstrap = {
    firstAdministratorMembershipId: string;
    invitationId: string;
    status: string;
    tenantId: string;
  };

  export type InvitationParams = {
    displayName: string;
    email: string;
    idempotencyKey: string;
    roleIds: string[];
  };

  export type TenantBootstrapParams = {
    entryHost: string;
    firstAdministrator: { displayName: string; email: string };
    idempotencyKey: string;
    tenantCode: string;
    tenantName: string;
    tenantType: 'AGENT' | 'DIRECT_MERCHANT' | 'INDIRECT_MERCHANT';
  };
}

export function getIdentityMembers(page: number, pageSize: number) {
  return requestClient.get<IdentityGovernanceApi.MemberPage>(
    '/identity/members',
    { params: { page, pageSize } },
  );
}

export async function getIdentityInvitationRoles() {
  const result = await requestClient.get<{
    items: IdentityGovernanceApi.InvitationRole[];
  }>('/identity/invitation-roles');
  return result.items;
}

export function createIdentityInvitation(
  params: IdentityGovernanceApi.InvitationParams,
) {
  return requestClient.post<IdentityGovernanceApi.Invitation>(
    '/identity/invitations',
    params,
  );
}

export function requestIdentityMfaRecovery(targetMembershipId: string) {
  return requestClient.post<IdentityGovernanceApi.Recovery>(
    '/identity/mfa-recoveries',
    { idempotencyKey: crypto.randomUUID(), targetMembershipId },
  );
}

export function createIdentityTenantBootstrap(
  params: IdentityGovernanceApi.TenantBootstrapParams,
) {
  return requestClient.post<IdentityGovernanceApi.TenantBootstrap>(
    '/identity/tenant-bootstraps',
    params,
  );
}
