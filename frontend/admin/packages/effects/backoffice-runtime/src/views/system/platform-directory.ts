import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';
import type { AccountDomainOptions } from '@payment/backoffice-runtime/composables';

import type { Recordable } from '@vben/types';

import type { PlatformDirectoryTarget } from '../../api/system/platform-directory';

import { z } from '@payment/backoffice-runtime/adapter/form';
import { getTenantOptions } from '@payment/backoffice-runtime/api/system/user';
import { $t } from '@payment/backoffice-runtime/locales';

export { hasExactPlatformDirectoryResponseContext } from '@payment/backoffice-runtime/api';

interface PlatformDirectoryFormValues extends Recordable<any> {
  accountDomain?: unknown;
  tenantId?: unknown;
}

interface PlatformDirectoryRequestToken {
  generation: number;
  targetKey: string;
}

function targetKey(target: PlatformDirectoryTarget) {
  return target.accountDomain === 'PLATFORM'
    ? target.accountDomain
    : `${target.accountDomain}:${target.tenantId}`;
}

function createPlatformDirectoryRequestGuard() {
  let generation = 0;
  let currentTargetKey: string | undefined;

  return {
    begin(target: PlatformDirectoryTarget): PlatformDirectoryRequestToken {
      generation += 1;
      currentTargetKey = targetKey(target);
      return { generation, targetKey: currentTargetKey };
    },
    invalidate() {
      generation += 1;
      currentTargetKey = undefined;
    },
    isCurrent(token: PlatformDirectoryRequestToken) {
      return (
        token.generation === generation && token.targetKey === currentTargetKey
      );
    },
  };
}

function resolvePlatformDirectoryTarget(
  values: PlatformDirectoryFormValues,
): PlatformDirectoryTarget | undefined {
  const accountDomain = values.accountDomain ?? 'PLATFORM';
  if (accountDomain === 'PLATFORM') return { accountDomain };
  if (
    (accountDomain === 'AGENT' || accountDomain === 'MERCHANT') &&
    typeof values.tenantId === 'string' &&
    values.tenantId.trim().length > 0
  ) {
    return { accountDomain, tenantId: values.tenantId.trim() };
  }
  return undefined;
}

function splitPlatformDirectoryQuery(values: PlatformDirectoryFormValues) {
  const {
    accountDomain: _accountDomain,
    tenantId: _tenantId,
    ...query
  } = values;
  return {
    query,
    target: resolvePlatformDirectoryTarget(values),
  };
}

function usePlatformDirectoryFilterSchema(
  getAccountDomainOptions: () => AccountDomainOptions,
  onAccountDomainChange?: (value: unknown) => void,
  onTenantChange?: (value: unknown) => void,
): VbenFormSchema[] {
  return [
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: false,
        onChange: onAccountDomainChange,
        options: getAccountDomainOptions(),
      }),
      defaultValue: 'PLATFORM',
      fieldName: 'accountDomain',
      label: $t('system.accountDomain'),
    },
    {
      component: 'ApiSelect',
      dependencies: {
        resolve: ({ values }) => {
          const accountDomain = values.accountDomain;
          const requiresTenant =
            accountDomain === 'AGENT' || accountDomain === 'MERCHANT';
          return {
            componentProps: {
              api: (params?: { accountDomain?: unknown }) => {
                const requestedDomain = params?.accountDomain;
                return requestedDomain === 'AGENT' ||
                  requestedDomain === 'MERCHANT'
                  ? getTenantOptions(requestedDomain)
                  : Promise.resolve([]);
              },
              labelField: 'name',
              onChange: onTenantChange,
              params: { accountDomain },
              placeholder: $t('system.selectTenant'),
              valueField: 'id',
            },
            rules: requiresTenant
              ? z.string().trim().min(1)
              : z.string().optional(),
            show: requiresTenant,
          };
        },
        triggerFields: ['accountDomain'],
      },
      fieldName: 'tenantId',
      label: $t('system.tenant'),
    },
  ];
}

export {
  createPlatformDirectoryRequestGuard,
  resolvePlatformDirectoryTarget,
  splitPlatformDirectoryQuery,
  usePlatformDirectoryFilterSchema,
};
