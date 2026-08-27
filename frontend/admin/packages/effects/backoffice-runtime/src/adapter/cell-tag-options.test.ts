import { ref } from 'vue';

import { describe, expect, it } from 'vitest';

import { resolveCellTagOptions } from './cell-tag-options';

describe('resolveCellTagOptions', () => {
  it('resolves reactive and getter-backed option lists without copying them', () => {
    const fallback = [{ label: 'fallback', value: 1 }];
    const reactiveOptions = ref([{ label: 'reactive', value: 1 }]);
    const getterOptions = [{ label: 'getter', value: 0 }];

    expect(resolveCellTagOptions(reactiveOptions, fallback)).toBe(
      reactiveOptions.value,
    );
    expect(resolveCellTagOptions(() => getterOptions, fallback)).toBe(
      getterOptions,
    );
  });

  it('uses the supplied fallback only when options are absent', () => {
    const fallback = [{ label: 'fallback', value: 1 }];

    expect(resolveCellTagOptions(undefined, fallback)).toBe(fallback);
    expect(resolveCellTagOptions([], fallback)).toEqual([]);
  });
});
