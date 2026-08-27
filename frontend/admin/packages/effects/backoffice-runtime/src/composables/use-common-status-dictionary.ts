import type { ComputedRef } from 'vue';

import type { SystemDictionaryDataApi } from '../api/system/dictionary-data';

import { computed } from 'vue';

import { useAccess } from '@vben/access';

import { PERMISSION_CODES } from '../api/permission-codes';
import { $t } from '../locales';
import { useSystemDictionaries } from './use-system-dictionaries';

const COMMON_STATUS_DICTIONARY_TYPE = 'SYS_COMMON_STATUS';

type CommonStatusValue = 0 | 1;

interface CommonStatusOption {
  color: SystemDictionaryDataApi.DictionaryColor;
  label: string;
  value: CommonStatusValue;
}

type CommonStatusOptions = readonly Readonly<CommonStatusOption>[];

interface CommonStatusDictionary {
  error: Readonly<{ value: unknown }>;
  getColor: (
    value: CommonStatusValue,
  ) => SystemDictionaryDataApi.DictionaryColor;
  getLabel: (value: CommonStatusValue) => string;
  loading: Readonly<{ value: boolean }>;
  options: ComputedRef<CommonStatusOptions>;
  reload: () => Promise<boolean>;
}

const FALLBACK_STATUS_ITEMS: readonly Readonly<
  Pick<CommonStatusOption, 'color' | 'value'>
>[] = Object.freeze([
  Object.freeze({ color: 'success', value: 1 }),
  Object.freeze({ color: 'error', value: 0 }),
]);

function buildCommonStatusOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): CommonStatusOptions {
  const accepted = new Map<CommonStatusValue, CommonStatusOption>();
  for (const item of items) {
    const value = parseCommonStatusValue(item.value);
    if (value === undefined || accepted.has(value)) continue;
    accepted.set(value, createCommonStatusOption(value, item.color));
  }
  for (const fallback of FALLBACK_STATUS_ITEMS) {
    if (!accepted.has(fallback.value)) {
      accepted.set(
        fallback.value,
        createCommonStatusOption(fallback.value, fallback.color),
      );
    }
  }
  return Object.freeze(
    [...accepted.values()].map((item) => Object.freeze(item)),
  );
}

function createCommonStatusOption(
  value: CommonStatusValue,
  color: SystemDictionaryDataApi.DictionaryColor,
): CommonStatusOption {
  return {
    color,
    label: $t(value === 1 ? 'common.enabled' : 'common.disabled'),
    value,
  };
}

function parseCommonStatusValue(value: string): CommonStatusValue | undefined {
  if (value === '1') return 1;
  if (value === '0') return 0;
  return undefined;
}

function useCommonStatusDictionary(): CommonStatusDictionary {
  const { hasAccessByCodes } = useAccess();
  const dictionaries = useSystemDictionaries(
    {
      enabled: hasAccessByCodes([PERMISSION_CODES.dictionaryDataView]),
    },
    COMMON_STATUS_DICTIONARY_TYPE,
  );
  const options = computed(() =>
    buildCommonStatusOptions(
      dictionaries.getOptions(COMMON_STATUS_DICTIONARY_TYPE),
    ),
  );

  function getOption(value: CommonStatusValue) {
    return (
      options.value.find((option) => option.value === value) ??
      createCommonStatusOption(value, value === 1 ? 'success' : 'error')
    );
  }

  return {
    error: dictionaries.error,
    getColor: (value) => getOption(value).color,
    getLabel: (value) => getOption(value).label,
    loading: dictionaries.loading,
    options,
    reload: dictionaries.reload,
  };
}

export {
  buildCommonStatusOptions,
  COMMON_STATUS_DICTIONARY_TYPE,
  useCommonStatusDictionary,
};
export type {
  CommonStatusDictionary,
  CommonStatusOption,
  CommonStatusOptions,
  CommonStatusValue,
};
