import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';
import { describe, expect, it } from 'vitest';

import { getDictionaryColorOptions, useColumns, useFormSchema } from './data';

describe('dictionary data table columns', () => {
  it('does not expose mutation actions in the shared read-only page', () => {
    const columns = useColumns() ?? [];

    expect(columns.some((column) => column.field === 'operation')).toBe(false);
  });

  it('uses dictionary update for every platform data mutation', () => {
    const columns = useColumns(() => {}, true) ?? [];
    const operation = columns.find((column) => column.field === 'operation');
    const options = operation?.cellRender?.options ?? [];

    expect(options).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          auth: PERMISSION_CODES.dictionaryUpdate,
          code: 'edit',
        }),
        expect.objectContaining({
          auth: PERMISSION_CODES.dictionaryUpdate,
          code: 'delete',
        }),
      ]),
    );
  });

  it('keeps the six product colors ordered and defaults new data to default', () => {
    const colors = getDictionaryColorOptions();
    const colorField = useFormSchema().find(
      ({ fieldName }) => fieldName === 'color',
    );

    expect(colors.map(({ value }) => value)).toEqual([
      'default',
      'processing',
      'success',
      'warning',
      'error',
      'purple',
    ]);
    expect(colorField).toMatchObject({
      component: 'Select',
      defaultValue: 'default',
    });
    expect(colorField?.componentProps).toMatchObject({ options: colors });
    const colorRule = colorField?.rules as
      | undefined
      | { safeParse: (value: unknown) => { success: boolean } };
    expect(colorRule?.safeParse('success').success).toBe(true);
    expect(colorRule?.safeParse('blue').success).toBe(false);
  });

  it('renders the label as a colored tag without a standalone color column', () => {
    const colors = getDictionaryColorOptions();
    const columns = useColumns() ?? [];
    const labelColumn = columns.find(({ field }) => field === 'label');

    expect(columns.some(({ field }) => field === 'color')).toBe(false);
    expect(labelColumn?.cellRender).toMatchObject({
      attrs: {
        labelField: 'label',
        valueField: 'color',
      },
      name: 'CellTag',
      options: colors,
    });
  });

  it('keeps color and sort form controls full width', () => {
    const schema = useFormSchema();
    const colorField = schema.find(({ fieldName }) => fieldName === 'color');
    const sortField = schema.find(({ fieldName }) => fieldName === 'sort');

    expect(colorField?.componentProps).toMatchObject({ class: 'w-full' });
    expect(sortField?.componentProps).toMatchObject({ class: 'w-full' });
  });
});
