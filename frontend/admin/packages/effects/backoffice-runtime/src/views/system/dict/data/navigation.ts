import type { SystemDictionaryDataApi } from '@payment/backoffice-runtime/api/system/dictionary-data';

import { normalizeDictionaryType } from '@payment/backoffice-runtime/api/system/dictionary-type';

export function resolveDictionaryTypeQuery(value: unknown) {
  if (typeof value !== 'string') return undefined;
  try {
    return normalizeDictionaryType(value);
  } catch {
    return undefined;
  }
}

export function dictionaryDataRouteLocation(value: unknown) {
  const dictType = resolveDictionaryTypeQuery(value);
  if (!dictType) return undefined;
  return {
    name: 'SystemDictionaryDataIndex',
    query: { dictType },
  };
}

export function dictionaryTypeSelectOptions(
  options: readonly SystemDictionaryDataApi.DictionaryTypeOption[],
) {
  return options.map(({ dictName, dictType }) => ({
    label: `${dictName} (${dictType})`,
    value: dictType,
  }));
}
