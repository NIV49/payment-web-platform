export const COOKIE_SESSION_MARKER = 'cookie-session' as const;

/**
 * Cookie 会话只把非敏感 marker 放进持久化 store。
 * 真实凭证由 HttpOnly Cookie 携带，绝不能复制到 Authorization。
 */
export function formatSessionAuthorization(token: null | string) {
  void token;
  return null;
}
