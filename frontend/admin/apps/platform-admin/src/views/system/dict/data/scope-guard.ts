interface DictionaryDataScopeToken {
  dictType: string;
  generation: number;
}

function createDictionaryDataScopeGuard() {
  let generation = 0;
  let currentDictType: string | undefined;

  return {
    begin(dictType: string): DictionaryDataScopeToken {
      generation += 1;
      currentDictType = dictType;
      return { dictType, generation };
    },
    invalidate() {
      generation += 1;
      currentDictType = undefined;
    },
    isCurrent(token: DictionaryDataScopeToken) {
      return (
        token.generation === generation && token.dictType === currentDictType
      );
    },
  };
}

function hasExactDictionaryDataScope(page: unknown, dictType: string): boolean {
  if (!isRecord(page) || !Array.isArray(page.items)) return false;
  return page.items.every(
    (item) => isRecord(item) && item.dictType === dictType,
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export { createDictionaryDataScopeGuard, hasExactDictionaryDataScope };
