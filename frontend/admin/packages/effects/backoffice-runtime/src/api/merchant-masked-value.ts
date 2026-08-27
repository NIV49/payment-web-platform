export function isMaskedMerchantProtectedValue(
  value: unknown,
): value is string {
  if (typeof value !== 'string') return false;
  const codePoints = [...value];
  if (codePoints.length === 0 || codePoints.length > 128) return false;
  const concealedLength =
    codePoints.length <= 4 ? codePoints.length : codePoints.length - 4;
  return codePoints
    .slice(0, concealedLength)
    .every((codePoint) => codePoint === '*');
}
