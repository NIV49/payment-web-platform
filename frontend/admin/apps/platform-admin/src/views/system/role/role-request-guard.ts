interface RoleRequestIdentity {
  roleId: string;
  sequence: number;
}

function createRoleRequestGuard() {
  let sequence = 0;
  return {
    begin(roleId: string): RoleRequestIdentity {
      sequence += 1;
      return { roleId, sequence };
    },
    invalidate() {
      sequence += 1;
    },
    isCurrent(identity: RoleRequestIdentity, currentRoleId?: string) {
      return (
        identity.sequence === sequence && identity.roleId === currentRoleId
      );
    },
  };
}

export { createRoleRequestGuard };
export type { RoleRequestIdentity };
