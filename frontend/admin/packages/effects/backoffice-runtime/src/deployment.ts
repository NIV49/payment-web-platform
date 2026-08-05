import type { ComponentRecordType } from '@vben/types';

export type AccountDomain = 'AGENT' | 'MERCHANT' | 'PLATFORM';

export type BackofficeDeployment = {
  accountDomain: AccountDomain;
  menuPageComponents: readonly string[];
  pageMap: ComponentRecordType;
  routeNames: readonly string[];
  routePaths: readonly string[];
};

const ACCOUNT_DOMAINS = ['AGENT', 'MERCHANT', 'PLATFORM'] as const;

export function parseAccountDomain(value: unknown): AccountDomain {
  if (
    typeof value !== 'string' ||
    !ACCOUNT_DOMAINS.includes(value as AccountDomain)
  ) {
    throw new Error('Invalid backoffice account domain');
  }
  return value as AccountDomain;
}

let activeDeployment: BackofficeDeployment | undefined;

export function installBackofficeDeployment(
  deployment: BackofficeDeployment,
): void {
  if (activeDeployment) {
    throw new Error('Backoffice deployment is already installed');
  }
  activeDeployment = deployment;
}

export function getBackofficeDeployment(): BackofficeDeployment {
  if (!activeDeployment) {
    throw new Error('Backoffice deployment is not installed');
  }
  return activeDeployment;
}
