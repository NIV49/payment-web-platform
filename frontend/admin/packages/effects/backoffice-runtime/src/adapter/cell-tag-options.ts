import type { Ref } from 'vue';

import { isRef } from 'vue';

type CellTagOptions<T> = readonly T[];
type CellTagOptionsSource<T> =
  | (() => CellTagOptions<T>)
  | CellTagOptions<T>
  | Readonly<Ref<CellTagOptions<T>>>;

function resolveCellTagOptions<T>(
  source: CellTagOptionsSource<T> | undefined,
  fallback: CellTagOptions<T>,
): CellTagOptions<T> {
  if (typeof source === 'function') return source();
  if (isRef(source)) return source.value;
  return source ?? fallback;
}

// Vxe's public renderer type only declares arrays, while its runtime accepts
// renderer-owned option payloads. Keep the compatibility cast at the adapter
// boundary so business schemas retain strongly typed reactive sources.
function asCellTagRenderOptions<T>(source: CellTagOptionsSource<T>): any[] {
  return source as unknown as any[];
}

export { asCellTagRenderOptions, resolveCellTagOptions };
export type { CellTagOptions, CellTagOptionsSource };
