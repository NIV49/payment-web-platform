interface IdentityGovernanceEnvironment {
  explicitMode?: string;
  prod: boolean;
}

function resolveIdentityGovernanceMode(
  environment: IdentityGovernanceEnvironment,
) {
  if (environment.prod) return { oidcWritesEnabled: true };
  if (
    environment.explicitMode !== undefined &&
    environment.explicitMode !== 'local' &&
    environment.explicitMode !== 'oidc'
  ) {
    throw new Error('Invalid identity governance authentication mode');
  }
  return { oidcWritesEnabled: environment.explicitMode === 'oidc' };
}

export { resolveIdentityGovernanceMode };
