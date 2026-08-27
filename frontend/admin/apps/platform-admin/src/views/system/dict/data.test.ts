import { describe, expect, it } from 'vitest';

import { PERMISSION_CODES } from '#/api/permission-codes';

import { useColumns, useFormSchema } from './data';

describe('platform dictionary table columns', () => {
  it('keeps the sort control aligned with the full-width form fields', () => {
    const sort = useFormSchema().find((field) => field.fieldName === 'sort');

    expect(sort?.component).toBe('InputNumber');
    expect(sort?.componentProps).toEqual(
      expect.objectContaining({ class: 'w-full' }),
    );
  });

  it('links each dictionary to its data and protects every mutation action', () => {
    const columns = useColumns(() => {}) ?? [];
    const operation = columns.find((column) => column.field === 'operation');
    const options = operation?.cellRender?.options ?? [];

    expect(options).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          auth: PERMISSION_CODES.dictionaryView,
          code: 'data',
        }),
        expect.objectContaining({
          auth: PERMISSION_CODES.dictionaryUpdate,
          code: 'edit',
        }),
        expect.objectContaining({
          auth: PERMISSION_CODES.dictionaryDelete,
          code: 'delete',
        }),
      ]),
    );
  });
});
