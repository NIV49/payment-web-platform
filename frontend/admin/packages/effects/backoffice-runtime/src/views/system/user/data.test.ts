import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';

import { computed, ref } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useColumns, useFormSchema, useGridFormSchema } from './data';

vi.mock('antdv-next', () => ({ Tag: {} }));

function resolveDisabled(schema: VbenFormSchema[], fieldName: string) {
  const componentProps = schema.find(
    (field) => field.fieldName === fieldName,
  )?.componentProps;

  if (typeof componentProps !== 'function') return undefined;
  return (componentProps as () => { disabled?: boolean })().disabled;
}

describe('system user form schema', () => {
  it('keeps the fixed operation column compact for two actions and overflow', () => {
    const operationColumn = (useColumns() ?? []).find(
      ({ field }) => field === 'operation',
    );

    expect(operationColumn).toMatchObject({ fixed: 'right', width: 220 });
  });

  it('uses dictionary-backed numeric status options in forms and read-only tags', () => {
    const statusOptions = [
      { color: 'purple' as const, label: 'Disabled', value: 0 as const },
      { color: 'processing' as const, label: 'Enabled', value: 1 as const },
    ];
    const getStatusOptions = () => statusOptions;
    const schema = useGridFormSchema(false, undefined, getStatusOptions);
    const componentProps = schema.find(
      (field) => field.fieldName === 'status',
    )?.componentProps;
    const statusColumn = (
      useColumns(undefined, undefined, false, getStatusOptions) ?? []
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

  it('uses one dictionary-backed account-domain option set in forms and table tags', () => {
    const accountDomainOptions = [
      {
        color: 'success' as const,
        label: 'Merchant Platform',
        value: 'MERCHANT' as const,
      },
      {
        color: 'processing' as const,
        label: 'Platform',
        value: 'PLATFORM' as const,
      },
      {
        color: 'purple' as const,
        label: 'Agent Platform',
        value: 'AGENT' as const,
      },
    ];
    const getAccountDomainOptions = () => accountDomainOptions;
    const gridSchema = useGridFormSchema(
      true,
      undefined,
      undefined,
      getAccountDomainOptions,
    );
    const gridProps = gridSchema.find(
      (field) => field.fieldName === 'accountDomain',
    )?.componentProps;
    const formSchema = useFormSchema(
      computed(() => true),
      computed(() => true),
      computed(() => undefined),
      computed(() => false),
      computed(() => []),
      ref(false),
      vi.fn(),
      ref(0),
      true,
      undefined,
      getAccountDomainOptions,
    );
    const formProps = formSchema.find(
      (field) => field.fieldName === 'accountDomain',
    )?.componentProps;
    const accountDomainColumn = (
      useColumns(
        undefined,
        undefined,
        true,
        undefined,
        getAccountDomainOptions,
      ) ?? []
    ).find(({ field }) => field === 'accountDomain');

    expect(gridProps).toBeTypeOf('function');
    expect((gridProps as () => { options: unknown })().options).toBe(
      accountDomainOptions,
    );
    expect(formProps).toBeTypeOf('function');
    expect((formProps as () => { options: unknown })().options).toBe(
      accountDomainOptions,
    );
    expect(accountDomainColumn?.cellRender).toMatchObject({
      name: 'CellTag',
      options: getAccountDomainOptions,
    });
    expect(accountDomainColumn?.formatter).toBeUndefined();
  });

  it('forwards control-plane account domain changes to the list owner', () => {
    const onAccountDomainChange = vi.fn();
    const schema = useGridFormSchema(true, onAccountDomainChange);
    const componentProps = schema.find(
      (field) => field.fieldName === 'accountDomain',
    )?.componentProps;

    expect(componentProps).toBeTypeOf('function');
    const props = (
      componentProps as () => {
        onChange?: (value: unknown) => void;
      }
    )();
    props.onChange?.('MERCHANT');

    expect(onAccountDomainChange).toHaveBeenCalledWith('MERCHANT');
  });

  it('does not expose cross-domain selectors outside the platform control plane', () => {
    const schema = useFormSchema(
      computed(() => true),
      computed(() => false),
      computed(() => undefined),
      computed(() => false),
      computed(() => []),
      ref(false),
      vi.fn(),
      ref(0),
      false,
    );

    expect(schema.some((field) => field.fieldName === 'accountDomain')).toBe(
      false,
    );
    expect(schema.some((field) => field.fieldName === 'tenantId')).toBe(false);
  });

  it('requires a tenant only when platform manages a tenant administrator', () => {
    const editing = ref(false);
    const schema = useFormSchema(
      computed(() => true),
      computed(() => true),
      computed(() => undefined),
      computed(() => editing.value),
      computed(() => []),
      ref(false),
      vi.fn(),
      ref(0),
      true,
    );
    const dependencies = schema.find(
      (field) => field.fieldName === 'tenantId',
    )?.dependencies;

    expect(dependencies).toBeDefined();
    if (!dependencies || !('resolve' in dependencies)) return;
    const resolveDependencies = dependencies.resolve;
    expect(resolveDependencies).toBeTypeOf('function');
    if (!resolveDependencies) return;

    const platformState = resolveDependencies({
      values: { accountDomain: 'PLATFORM' },
    } as never);
    const merchantState = resolveDependencies({
      values: { accountDomain: 'MERCHANT' },
    } as never);

    expect(platformState).toMatchObject({ show: false });
    expect(merchantState).toMatchObject({ show: true });
    expect((merchantState as { rules: unknown }).rules).toBeDefined();
    expect(
      (platformState as { componentProps: { params: unknown } }).componentProps
        .params,
    ).toEqual({ accountDomain: 'PLATFORM' });
    expect(
      (merchantState as { componentProps: { params: unknown } }).componentProps
        .params,
    ).toEqual({ accountDomain: 'MERCHANT' });
    expect(resolveDisabled(schema, 'accountDomain')).toBe(false);
    expect(
      (merchantState as { componentProps: { disabled: boolean } })
        .componentProps.disabled,
    ).toBe(false);

    editing.value = true;
    const editingMerchantState = resolveDependencies({
      values: { accountDomain: 'MERCHANT' },
    } as never);
    expect(resolveDisabled(schema, 'accountDomain')).toBe(true);
    expect(
      (editingMerchantState as { componentProps: { disabled: boolean } })
        .componentProps.disabled,
    ).toBe(true);
  });

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
