import type { SystemDictionaryDataApi } from '../api/system/dictionary-data';

import { onScopeDispose, readonly, ref, shallowRef } from 'vue';

import { queryDictionaryDataBatch } from '../api/system/dictionary-data';
import { normalizeDictionaryType } from '../api/system/dictionary-type';

type DictionaryItem = SystemDictionaryDataApi.DictionaryBatchItem;
type DictionaryOptions = readonly Readonly<DictionaryItem>[];
type DictionaryMap = Readonly<Record<string, DictionaryOptions>>;

interface DictionaryLookupOptions {
  enabled?: boolean;
}

const EMPTY_OPTIONS: DictionaryOptions = Object.freeze([]);

function useSystemDictionaries(
  ...dictTypes: readonly [string, ...string[]]
): ReturnType<typeof createDictionaryLookup>;
function useSystemDictionaries(
  options: Readonly<DictionaryLookupOptions>,
  ...dictTypes: readonly [string, ...string[]]
): ReturnType<typeof createDictionaryLookup>;
function useSystemDictionaries(
  optionsOrType: Readonly<DictionaryLookupOptions> | string,
  ...remainingTypes: readonly string[]
) {
  const options =
    typeof optionsOrType === 'string' ? {} : (optionsOrType ?? {});
  const dictTypes =
    typeof optionsOrType === 'string'
      ? [optionsOrType, ...remainingTypes]
      : remainingTypes;
  return createDictionaryLookup(options.enabled !== false, dictTypes);
}

function createDictionaryLookup(
  enabled: boolean,
  dictTypes: readonly string[],
) {
  if (dictTypes.length === 0 || dictTypes.length > 64) {
    throw new RangeError('Dictionary lookup requires 1 to 64 types');
  }

  const declaredTypes = [
    ...new Set(dictTypes.map((dictType) => normalizeDictionaryType(dictType))),
  ];
  const declaredTypeSet = new Set(declaredTypes);
  const dictionaries = shallowRef<DictionaryMap>(createEmptyMap(declaredTypes));
  const loadError = shallowRef<unknown>();
  const loading = ref(false);
  let disposed = false;
  let requestVersion = 0;

  function requireDeclaredType(dictType: string) {
    const normalizedType = normalizeDictionaryType(dictType);
    if (!declaredTypeSet.has(normalizedType)) {
      throw new RangeError('Dictionary type was not declared');
    }
    return normalizedType;
  }

  function getOptions(dictType: string): DictionaryOptions {
    return dictionaries.value[requireDeclaredType(dictType)] ?? EMPTY_OPTIONS;
  }

  function getItem(dictType: string, value: string) {
    return getOptions(dictType).find((item) => item.value === value);
  }

  function getLabel(dictType: string, value: string) {
    return getItem(dictType, value)?.label;
  }

  async function reload() {
    if (disposed || !enabled) return false;

    const currentRequest = ++requestVersion;
    loading.value = true;
    loadError.value = undefined;

    try {
      const response = await queryDictionaryDataBatch(declaredTypes);
      if (disposed || currentRequest !== requestVersion) return false;
      dictionaries.value = createDictionaryMap(declaredTypes, response);
      return true;
    } catch (error) {
      if (!disposed && currentRequest === requestVersion) {
        loadError.value = error;
      }
      return false;
    } finally {
      if (!disposed && currentRequest === requestVersion) {
        loading.value = false;
      }
    }
  }

  void reload();
  onScopeDispose(() => {
    disposed = true;
    requestVersion += 1;
  });

  return {
    dictionaries: readonly(dictionaries),
    error: readonly(loadError),
    getItem,
    getLabel,
    getOptions,
    loading: readonly(loading),
    reload,
  };
}

function createEmptyMap(dictTypes: readonly string[]): DictionaryMap {
  return Object.freeze(
    Object.fromEntries(dictTypes.map((dictType) => [dictType, EMPTY_OPTIONS])),
  );
}

function createDictionaryMap(
  dictTypes: readonly string[],
  response: SystemDictionaryDataApi.DictionaryBatchResult,
): DictionaryMap {
  return Object.freeze(
    Object.fromEntries(
      dictTypes.map((dictType) => [
        dictType,
        Object.freeze(
          (response[dictType] ?? EMPTY_OPTIONS).map((item) =>
            Object.freeze({ ...item }),
          ),
        ),
      ]),
    ),
  );
}

export { useSystemDictionaries };
export type {
  DictionaryItem,
  DictionaryLookupOptions,
  DictionaryMap,
  DictionaryOptions,
};
