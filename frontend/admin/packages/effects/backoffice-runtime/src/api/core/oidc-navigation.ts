const OIDC_START_PATH = '/api/auth/oidc/start';

function isLoopback(hostname: string) {
  return (
    hostname === '::1' ||
    hostname === 'localhost' ||
    hostname.startsWith('127.')
  );
}

function resolveRealmLogoutUrl(value: unknown, currentOrigin: string) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error('Invalid realm logout URL');
  }
  let target: URL;
  let current: URL;
  try {
    target = new URL(value);
    current = new URL(currentOrigin);
  } catch {
    throw new Error('Invalid realm logout URL');
  }
  if (target.username || target.password || target.hash) {
    throw new Error('Invalid realm logout URL');
  }
  if (target.protocol === 'https:') return target.toString();
  if (
    target.protocol === 'http:' &&
    current.protocol === 'http:' &&
    isLoopback(target.hostname) &&
    isLoopback(current.hostname)
  ) {
    return target.toString();
  }
  throw new Error('Invalid realm logout URL');
}

export { OIDC_START_PATH, resolveRealmLogoutUrl };
