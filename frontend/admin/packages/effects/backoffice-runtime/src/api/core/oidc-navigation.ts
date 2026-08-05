const OIDC_START_PATH = '/api/auth/oidc/start';

function isLoopback(hostname: string) {
  return (
    hostname === '::1' ||
    hostname === 'localhost' ||
    hostname.startsWith('127.')
  );
}

function resolveOidcRedirectUrl(value: unknown, currentOrigin: string) {
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

type OidcCallback =
  | { kind: 'login'; value: string }
  | { kind: 'step-up'; value: string };

function parseOidcCallbackQuery(
  handoff: unknown,
  stepUp: unknown,
): OidcCallback {
  const validHandoff = validOpaque(handoff);
  const validStepUp = validOpaque(stepUp);
  if (validHandoff === validStepUp) {
    throw new Error('Invalid OIDC callback');
  }
  if (validStepUp) return { kind: 'step-up', value: stepUp };
  if (validHandoff) return { kind: 'login', value: handoff };
  throw new Error('Invalid OIDC callback');
}

function validOpaque(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 512;
}

const resolveRealmLogoutUrl = resolveOidcRedirectUrl;

export {
  OIDC_START_PATH,
  parseOidcCallbackQuery,
  resolveOidcRedirectUrl,
  resolveRealmLogoutUrl,
};
