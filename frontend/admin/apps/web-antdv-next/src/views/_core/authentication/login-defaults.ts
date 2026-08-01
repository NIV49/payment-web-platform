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

export { LOGIN_DEFAULT_CREDENTIAL_FIELD, resolveLoginDefaults };
