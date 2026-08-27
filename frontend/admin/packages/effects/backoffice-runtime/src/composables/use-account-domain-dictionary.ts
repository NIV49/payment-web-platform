import type { ComputedRef } from 'vue';

import type { SystemDictionaryDataApi } from '../api/system/dictionary-data';
import type { AccountDomain } from '../deployment';

import { computed } from 'vue';

import { useAccess } from '@vben/access';

import { PERMISSION_CODES } from '../api/permission-codes';
import { $t } from '../locales';
import { useSystemDictionaries } from './use-system-dictionaries';

const ACCOUNT_DOMAIN_DICTIONARY_TYPE = 'BELONG_SYSTEM';

interface AccountDomainOption {
  color: SystemDictionaryDataApi.DictionaryColor;
  label: string;
  value: AccountDomain;
}

type AccountDomainOptions = readonly Readonly<AccountDomainOption>[];

interface AccountDomainDictionary {
  error: Readonly<{ value: unknown }>;
  getColor: (value: AccountDomain) => SystemDictionaryDataApi.DictionaryColor;
  getLabel: (value: AccountDomain) => string;
  loading: Readonly<{ value: boolean }>;
  options: ComputedRef<AccountDomainOptions>;
  reload: () => Promise<boolean>;
}

interface AccountDomainDictionaryOptions {
  enabled?: boolean;
}

const ACCOUNT_DOMAIN_DEFINITIONS = Object.freeze([
  Object.freeze({
    color: 'processing' as const,
    dictionaryValue: '1',
    labelKey: 'system.user.platformDomain',
    value: 'PLATFORM' as const,
  }),
  Object.freeze({
    color: 'success' as const,
    dictionaryValue: '2',
    labelKey: 'system.user.merchantDomain',
    value: 'MERCHANT' as const,
  }),
  Object.freeze({
    color: 'purple' as const,
    dictionaryValue: '3',
    labelKey: 'system.user.agentDomain',
    value: 'AGENT' as const,
  }),
]);

const DEFINITION_BY_DICTIONARY_VALUE: ReadonlyMap<
  string,
  (typeof ACCOUNT_DOMAIN_DEFINITIONS)[number]
> = new Map(
  ACCOUNT_DOMAIN_DEFINITIONS.map((definition) => [
    definition.dictionaryValue,
    definition,
  ]),
);
const DEFINITION_BY_DOMAIN: ReadonlyMap<
  AccountDomain,
  (typeof ACCOUNT_DOMAIN_DEFINITIONS)[number]
> = new Map(
  ACCOUNT_DOMAIN_DEFINITIONS.map((definition) => [
    definition.value,
    definition,
  ]),
);

function buildAccountDomainOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): AccountDomainOptions {
  const accepted = new Map<AccountDomain, AccountDomainOption>();
  for (const item of items) {
    const definition = DEFINITION_BY_DICTIONARY_VALUE.get(item.value);
    if (!definition || accepted.has(definition.value)) continue;
    accepted.set(
      definition.value,
      createAccountDomainOption(definition.value, item.color),
    );
  }
  for (const definition of ACCOUNT_DOMAIN_DEFINITIONS) {
    if (!accepted.has(definition.value)) {
      accepted.set(
        definition.value,
        createAccountDomainOption(definition.value, definition.color),
      );
    }
  }
  return Object.freeze(
    [...accepted.values()].map((item) => Object.freeze(item)),
  );
}

function createAccountDomainOption(
  value: AccountDomain,
  color: SystemDictionaryDataApi.DictionaryColor,
): AccountDomainOption {
  const definition = DEFINITION_BY_DOMAIN.get(value);
  if (!definition) throw new RangeError('Unsupported account domain');
  return { color, label: $t(definition.labelKey), value };
}

function useAccountDomainDictionary(
  options: Readonly<AccountDomainDictionaryOptions> = {},
): AccountDomainDictionary {
  const { hasAccessByCodes } = useAccess();
  const dictionaries = useSystemDictionaries(
    {
      enabled:
        options.enabled !== false &&
        hasAccessByCodes([PERMISSION_CODES.dictionaryDataView]),
    },
    ACCOUNT_DOMAIN_DICTIONARY_TYPE,
  );
  const domainOptions = computed(() =>
    buildAccountDomainOptions(
      dictionaries.getOptions(ACCOUNT_DOMAIN_DICTIONARY_TYPE),
    ),
  );

  function getOption(value: AccountDomain) {
    return (
      domainOptions.value.find((option) => option.value === value) ??
      createAccountDomainOption(
        value,
        DEFINITION_BY_DOMAIN.get(value)?.color ?? 'default',
      )
    );
  }

  return {
    error: dictionaries.error,
    getColor: (value) => getOption(value).color,
    getLabel: (value) => getOption(value).label,
    loading: dictionaries.loading,
    options: domainOptions,
    reload: dictionaries.reload,
  };
}

export {
  ACCOUNT_DOMAIN_DICTIONARY_TYPE,
  buildAccountDomainOptions,
  useAccountDomainDictionary,
};
export type {
  AccountDomainDictionary,
  AccountDomainDictionaryOptions,
  AccountDomainOption,
  AccountDomainOptions,
};
