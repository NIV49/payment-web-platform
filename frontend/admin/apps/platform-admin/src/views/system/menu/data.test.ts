import { describe, expect, it, vi } from 'vitest';

import { useColumns } from './data';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('antdv-next', () => ({ Tag: {} }));

describe('menu status dictionary presentation', () => {
  it('passes dictionary-backed numeric options to status tags', () => {
    const statusOptions = [
      { color: 'purple' as const, label: 'Disabled', value: 0 as const },
      { color: 'processing' as const, label: 'Enabled', value: 1 as const },
    ];
    const getStatusOptions = () => statusOptions;
    const statusColumn = (
      useColumns(
        vi.fn(),
        vi.fn(() => true),
        getStatusOptions,
      ) ?? []
    ).find(({ field }) => field === 'status');

    expect(statusColumn?.cellRender).toMatchObject({
      name: 'CellTag',
      options: getStatusOptions,
    });
  });

  it('adds dictionary-backed platform and tenant columns to the control plane', () => {
    const domainOptions = [
      {
        color: 'processing' as const,
        label: 'Platform',
        value: 'PLATFORM' as const,
      },
      { color: 'purple' as const, label: 'Agent', value: 'AGENT' as const },
    ];
    const getDomainOptions = () => domainOptions;
    const columns =
      useColumns(
        vi.fn(),
        vi.fn(() => true),
        undefined,
        true,
        getDomainOptions,
      ) ?? [];

    expect(
      columns.find(({ field }) => field === 'accountDomain')?.cellRender,
    ).toMatchObject({ name: 'CellTag', options: getDomainOptions });
    expect(columns.some(({ field }) => field === 'tenantName')).toBe(true);
  });

  it('omits the operation column for a cross-domain read-only directory', () => {
    const writableColumns =
      useColumns(
        vi.fn(),
        vi.fn(() => true),
        undefined,
        true,
        undefined,
        true,
      ) ?? [];
    const readOnlyColumns =
      useColumns(
        vi.fn(),
        vi.fn(() => true),
        undefined,
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
  });
});
