import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

interface UserDirectoryTarget {
  accountDomain: 'AGENT' | 'MERCHANT' | 'PLATFORM';
  tenantId?: string;
}

function hasExactUserDirectoryResponseScope(
  items: unknown,
  target: UserDirectoryTarget,
): items is SystemUserApi.SystemUser[] {
  if (!Array.isArray(items)) return false;

  const expectedTenantId = target.tenantId?.trim();
  return items.every((item) => {
    if (!item || typeof item !== 'object') return false;
    const user = item as Partial<SystemUserApi.SystemUser>;
    return (
      user.accountDomain === target.accountDomain &&
      (!expectedTenantId || user.tenantId === expectedTenantId)
    );
  });
}

export { hasExactUserDirectoryResponseScope };
export type { UserDirectoryTarget };
