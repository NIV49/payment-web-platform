const DICTIONARY_TYPE_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

export function normalizeDictionaryType(dictType: string) {
  if (!DICTIONARY_TYPE_PATTERN.test(dictType)) {
    throw new TypeError('Invalid dictionary type');
  }
  return dictType.toUpperCase();
}
