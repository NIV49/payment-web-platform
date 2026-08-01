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

function readLoginDefaultEnvironment(): LoginDefaultEnvironment {
  if (!import.meta.env.DEV) return { dev: false };
  return {
    dev: true,
    [LOGIN_DEFAULT_CREDENTIAL_FIELD]: import.meta.env.VITE_LOCAL_ADMIN_PASSWORD,
    username: import.meta.env.VITE_LOCAL_ADMIN_USERNAME,
  };
}

function resolveLoginDefaults(
  environment: LoginDefaultEnvironment = readLoginDefaultEnvironment(),
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
