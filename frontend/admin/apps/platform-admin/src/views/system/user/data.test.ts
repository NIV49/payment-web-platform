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
  it('allows only a system administrator to edit global identity fields', () => {
    const editing = ref(true);
    const systemAdministrator = ref(false);
    const schema = useFormSchema(
      computed(() => true),
      computed(() => systemAdministrator.value),
      computed(() => '10'),
      computed(() => editing.value),
      computed(() => []),
      ref(false),
      vi.fn(),
      ref(0),
    );

    for (const fieldName of ['username', 'name', 'remark']) {
      expect(resolveDisabled(schema, fieldName)).toBe(true);
    }

    systemAdministrator.value = true;

    for (const fieldName of ['username', 'name', 'remark']) {
      expect(resolveDisabled(schema, fieldName)).toBe(false);
    }

    editing.value = false;
    systemAdministrator.value = false;

    for (const fieldName of ['username', 'name', 'remark']) {
      expect(resolveDisabled(schema, fieldName)).toBe(false);
    }
  });

  it('reactively reloads department options after the edited user is known', () => {
    const currentDepartmentId = ref<string>();
    const departmentRequestVersion = ref(0);
    const schema = useFormSchema(
      computed(() => true),
      computed(() => true),
      computed(() => currentDepartmentId.value),
      computed(() => true),
      computed(() => []),
      ref(false),
      vi.fn(),
      departmentRequestVersion,
    );
    const componentProps = schema.find(
      (field) => field.fieldName === 'deptId',
    )?.componentProps;

    expect(componentProps).toBeTypeOf('function');
    const resolveProps = componentProps as () => {
      params: { currentDepartmentId?: string };
    };
    expect(resolveProps().params).toEqual({
      currentDepartmentId: undefined,
      requestVersion: 0,
    });

    currentDepartmentId.value = '30';
    departmentRequestVersion.value = 1;

    expect(resolveProps().params).toEqual({
      currentDepartmentId: '30',
      requestVersion: 1,
    });
  });
});
