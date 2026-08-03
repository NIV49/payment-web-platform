interface PostLoginPathOptions {
  homePath: string;
  isAccessible: (path: string) => boolean;
  requestedPath: string;
  requestedRedirect?: unknown;
}

function normalizeRedirect(value: unknown) {
  if (typeof value !== 'string' || value.length === 0) {
    return undefined;
  }

  let path = value;
  for (let index = 0; index < 2 && !path.startsWith('/'); index += 1) {
    try {
      const decoded = decodeURIComponent(path);
      if (decoded === path) break;
      path = decoded;
    } catch {
      return undefined;
    }
  }

  if (
    path === '/' ||
    !path.startsWith('/') ||
    path.startsWith('//') ||
    path.startsWith('/auth/login')
  ) {
    return undefined;
  }
  return path;
}

function resolvePostLoginPath({
  homePath,
  isAccessible,
  requestedPath,
  requestedRedirect,
}: PostLoginPathOptions) {
  const redirectPath = normalizeRedirect(requestedRedirect);
  if (redirectPath && isAccessible(redirectPath)) {
    return redirectPath;
  }
  if (isAccessible(requestedPath)) {
    return requestedPath;
  }
  return homePath;
}

export { resolvePostLoginPath };
