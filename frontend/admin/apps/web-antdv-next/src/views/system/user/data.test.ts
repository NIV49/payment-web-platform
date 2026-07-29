import type { VbenFormSchema } from '#/adapter/form';

import { computed, ref } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useFormSchema } from './data';

vi.mock('antdv-next', () => ({ Tag: {} }));

function resolveDisabled(schema: VbenFormSchema[], fieldName: string) {
  const componentProps = schema.find(
    (field) => field.fieldName === fieldName,
  )?.componentProps;

  if (typeof componentProps !== 'function') return undefined;
  return (componentProps as () => { disabled?: boolean })().disabled;
}

describe('system user form schema', () => {
  it('makes global identity fields read-only only while editing', () => {
    const editing = ref(true);
    const schema = useFormSchema(
      computed(() => true),
      computed(() => editing.value),
    );

    for (const fieldName of ['username', 'name', 'remark']) {
      expect(resolveDisabled(schema, fieldName)).toBe(true);
    }

    editing.value = false;

    for (const fieldName of ['username', 'name', 'remark']) {
      expect(resolveDisabled(schema, fieldName)).toBe(false);
    }
  });
});
