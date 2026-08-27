import type { AccountDomain } from '../../deployment';

import { getInstalledBackofficeDeployment } from '../../deployment';

type PlatformDirectoryTarget =
  | {
      accountDomain: 'AGENT' | 'MERCHANT';
      tenantId: string;
    }
  | {
      accountDomain: 'PLATFORM';
      tenantId?: never;
    };

interface PlatformDirectoryContext {
  accountDomain: AccountDomain;
  managementMode: 'READ_ONLY' | 'SAME_TENANT';
  tenantId: string;
  tenantName: string;
}

interface PlatformDirectoryContextCandidate {
  accountDomain?: unknown;
  children?: unknown;
  managementMode?: unknown;
  tenantId?: unknown;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function hasExactPlatformDirectoryResponseContext(
  items: unknown,
  target: PlatformDirectoryTarget,
): boolean {
  if (!Array.isArray(items)) return false;
  if (items.length === 0) return true;

  const expectedManagementMode =
    target.accountDomain === 'PLATFORM' ? 'SAME_TENANT' : 'READ_ONLY';
  const first = items[0];
  let expectedTenantId: string | undefined;
  if (target.accountDomain === 'PLATFORM') {
    expectedTenantId =
      isRecord(first) &&
      typeof first.tenantId === 'string' &&
      first.tenantId.trim().length > 0
        ? first.tenantId
        : undefined;
  } else {
    expectedTenantId = target.tenantId.trim();
  }
  if (!expectedTenantId) return false;

  const isExactItem = (value: unknown): boolean => {
    if (!isRecord(value)) return false;
    const item = value as PlatformDirectoryContextCandidate;
    if (
      item.accountDomain !== target.accountDomain ||
      item.managementMode !== expectedManagementMode ||
      item.tenantId !== expectedTenantId
    ) {
      return false;
    }
    if (item.children === undefined) return true;
    return (
      Array.isArray(item.children) &&
      item.children.every((child) => isExactItem(child))
    );
  };

  return items.every((item) => isExactItem(item));
}

function buildPlatformDirectoryTargetParams(target: PlatformDirectoryTarget): {
  accountDomain: AccountDomain;
  tenantId?: string;
} {
  if (getInstalledBackofficeDeployment()?.accountDomain !== 'PLATFORM') {
    throw new Error(
      'Platform administration directory is unavailable in this deployment',
    );
  }

  if (target.accountDomain === 'PLATFORM') {
    if ('tenantId' in target && target.tenantId) {
      throw new Error('PLATFORM directory is fixed to the current tenant');
    }
    return { accountDomain: 'PLATFORM' };
  }

  if (
    (target.accountDomain !== 'AGENT' && target.accountDomain !== 'MERCHANT') ||
    typeof target.tenantId !== 'string' ||
    target.tenantId.trim().length === 0
  ) {
    throw new Error(
      'Cross-domain administration directory requires an exact tenant',
    );
  }

  return {
    accountDomain: target.accountDomain,
    tenantId: target.tenantId.trim(),
  };
}

export {
  buildPlatformDirectoryTargetParams,
  hasExactPlatformDirectoryResponseContext,
};
export type { PlatformDirectoryContext, PlatformDirectoryTarget };
