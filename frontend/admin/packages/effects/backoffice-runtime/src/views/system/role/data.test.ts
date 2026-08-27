import { describe, expect, it, vi } from 'vitest';

import { useColumns, useFormSchema, useGridFormSchema } from './data';

vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('antdv-next', () => ({ Tag: {} }));

const statusOptions = [
  { color: 'purple' as const, label: 'Disabled', value: 0 as const },
  { color: 'processing' as const, label: 'Enabled', value: 1 as const },
];
const getStatusOptions = () => statusOptions;

function resolvedOptions(schema: ReturnType<typeof useFormSchema>) {
  const componentProps = schema.find(
    ({ fieldName }) => fieldName === 'status',
  )?.componentProps;
  expect(componentProps).toBeTypeOf('function');
  return (componentProps as () => { options: unknown }).call(null).options;
}

describe('role status dictionary presentation', () => {
  it('uses numeric dictionary-backed options in edit and search forms', () => {
    expect(resolvedOptions(useFormSchema(getStatusOptions))).toBe(
      statusOptions,
    );
    expect(resolvedOptions(useGridFormSchema(getStatusOptions))).toBe(
      statusOptions,
    );
  });

  it('passes a reactive options getter to read-only status tags', () => {
    const statusColumn = (
      useColumns(
        vi.fn(),
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        getStatusOptions,
      ) ?? []
    ).find(({ field }) => field === 'status');

    expect(statusColumn?.cellRender).toMatchObject({
      name: 'CellTag',
      options: getStatusOptions,
    });
  });

  it('keeps row action labels visually consistent without leading icons', () => {
    const operationColumn = (useColumns(vi.fn()) ?? []).find(
      ({ field }) => field === 'operation',
    );
    const options = operationColumn?.cellRender?.options;

    expect(Array.isArray(options)).toBe(true);
    expect(options).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'assignUser' })]),
    );
    expect(
      (options as Array<{ icon?: string }>).every(({ icon }) => !icon),
    ).toBe(true);
  });

  it('adds dictionary-backed platform and tenant columns to the control plane', () => {
    const domainOptions = [
      {
        color: 'processing' as const,
        label: 'Platform',
        value: 'PLATFORM' as const,
      },
      {
        color: 'success' as const,
        label: 'Merchant',
        value: 'MERCHANT' as const,
      },
    ];
    const getDomainOptions = () => domainOptions;
    const columns =
      useColumns(
        vi.fn(),
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        getStatusOptions,
        true,
        getDomainOptions,
      ) ?? [];

    expect(
      columns.find(({ field }) => field === 'accountDomain')?.cellRender,
    ).toMatchObject({ name: 'CellTag', options: getDomainOptions });
    expect(columns.some(({ field }) => field === 'tenantName')).toBe(true);
  });

  it('removes mutation affordances from a cross-domain read-only directory', () => {
    const writableColumns =
      useColumns(
        vi.fn(),
        vi.fn(),
        undefined,
        undefined,
        undefined,
        undefined,
        getStatusOptions,
        true,
        undefined,
        true,
      ) ?? [];
    const readOnlyColumns =
      useColumns(
        vi.fn(),
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        getStatusOptions,
        true,
        undefined,
        false,
      ) ?? [];

    expect(writableColumns.some(({ field }) => field === 'operation')).toBe(
      true,
    );
    expect(readOnlyColumns.some(({ field }) => field === 'operation')).toBe(
      false,
    );
    expect(
      writableColumns.find(({ field }) => field === 'status')?.cellRender?.name,
    ).toBe('CellSwitch');
    expect(
      readOnlyColumns.find(({ field }) => field === 'status')?.cellRender?.name,
    ).toBe('CellTag');
  });
});
