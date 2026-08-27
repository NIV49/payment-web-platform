import { computed } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useColumns, useSchema } from './data';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('antdv-next', () => ({ Tag: {} }));

const statusOptions = [
  { color: 'purple' as const, label: 'Disabled', value: 0 as const },
  { color: 'processing' as const, label: 'Enabled', value: 1 as const },
];
const getStatusOptions = () => statusOptions;

describe('department status dictionary presentation', () => {
  it('uses dictionary-backed numeric options in forms and tags', () => {
    const schema = useSchema(
      computed(() => undefined),
      computed(() => undefined),
      getStatusOptions,
    );
    const componentProps = schema.find(
      ({ fieldName }) => fieldName === 'status',
    )?.componentProps;
    const statusColumn = (
      useColumns(
        vi.fn(),
        vi.fn(() => true),
        getStatusOptions,
      ) ?? []
    ).find(({ field }) => field === 'status');

    expect(componentProps).toBeTypeOf('function');
    expect((componentProps as () => { options: unknown })().options).toBe(
      statusOptions,
    );
    expect(statusColumn?.cellRender).toMatchObject({
      name: 'CellTag',
      options: getStatusOptions,
    });
  });
});
