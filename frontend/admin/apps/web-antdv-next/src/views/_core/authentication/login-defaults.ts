interface LoginDefaults {
  password: string;
  username: string;
}

function resolveLoginDefaults(
  isDevelopment: boolean,
  username?: string,
  password?: string,
): LoginDefaults {
  if (!isDevelopment) {
    return { password: '', username: '' };
  }

  return {
    password: password ?? '',
    username: username?.trim() ?? '',
  };
}

export { resolveLoginDefaults };
