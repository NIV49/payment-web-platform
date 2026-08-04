export type AccountDomain = 'AGENT' | 'MERCHANT' | 'PLATFORM';

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
