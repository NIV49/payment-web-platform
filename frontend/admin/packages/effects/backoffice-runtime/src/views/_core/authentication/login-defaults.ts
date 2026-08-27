const LOGIN_DEFAULT_CREDENTIAL_FIELD = 'password' as const;

interface LoginDefaults {
  password: string;
  username: string;
}

interface LoginDefaultEnvironment {
  dev: boolean;
  password?: string;
  username?: string;
}

interface OidcLoginEnvironment {
  explicitMode?: string;
  prod: boolean;
}

const LOGIN_COPY = {
  AGENT: {
    subTitleKey: 'page.auth.agentLoginSubtitle',
    titleKey: 'page.auth.agentLoginTitle',
  },
  MERCHANT: {
    subTitleKey: 'page.auth.merchantLoginSubtitle',
    titleKey: 'page.auth.merchantLoginTitle',
  },
  PLATFORM: {
    subTitleKey: 'page.auth.platformLoginSubtitle',
    titleKey: 'page.auth.platformLoginTitle',
  },
} as const;

function resolveLoginCopy(accountDomain: string | undefined) {
  if (!accountDomain || !(accountDomain in LOGIN_COPY)) {
    throw new Error('Invalid frontend account domain');
  }
  return LOGIN_COPY[accountDomain as keyof typeof LOGIN_COPY];
}

function resolveOidcLoginMode(environment: OidcLoginEnvironment) {
  if (environment.prod) return true;
  if (
    environment.explicitMode !== undefined &&
    environment.explicitMode !== 'local' &&
    environment.explicitMode !== 'oidc'
  ) {
    throw new Error('Invalid frontend authentication mode');
  }
  return environment.explicitMode === 'oidc';
}

function resolveLoginDefaults(
  environment: LoginDefaultEnvironment,
): LoginDefaults {
  if (!environment.dev) {
    return { password: '', username: '' };
  }
  return {
    [LOGIN_DEFAULT_CREDENTIAL_FIELD]:
      environment[LOGIN_DEFAULT_CREDENTIAL_FIELD] ?? '',
    username: environment.username?.trim() ?? '',
  };
}

export {
  LOGIN_DEFAULT_CREDENTIAL_FIELD,
  resolveLoginCopy,
  resolveLoginDefaults,
  resolveOidcLoginMode,
};
