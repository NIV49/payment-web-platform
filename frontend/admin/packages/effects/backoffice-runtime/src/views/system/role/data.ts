import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';
import type {
  OnActionClickFn,
  VxeTableGridColumns,
} from '@payment/backoffice-runtime/adapter/vxe-table';
import type { SystemRoleApi } from '@payment/backoffice-runtime/api';
import type {
  AccountDomainOptions,
  CommonStatusOptions,
} from '@payment/backoffice-runtime/composables';

import { asCellTagRenderOptions } from '@payment/backoffice-runtime/adapter/vxe-table';
import { PERMISSION_CODES } from '@payment/backoffice-runtime/api';
import { $t } from '@payment/backoffice-runtime/locales';

const getFallbackStatusOptions = (): CommonStatusOptions => [
  { color: 'success', label: $t('common.enabled'), value: 1 },
  { color: 'error', label: $t('common.disabled'), value: 0 },
];

const getFallbackAccountDomainOptions = (): AccountDomainOptions => [
  {
    color: 'processing',
    label: $t('system.user.platformDomain'),
    value: 'PLATFORM',
  },
  {
    color: 'success',
    label: $t('system.user.merchantDomain'),
    value: 'MERCHANT',
  },
  {
    color: 'purple',
    label: $t('system.user.agentDomain'),
    value: 'AGENT',
  },
];

export function useFormSchema(
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('system.role.roleName'),
      rules: 'required',
    },
    {
      component: 'RadioGroup',
      componentProps: () => ({
        buttonStyle: 'solid',
        options: getStatusOptions(),
        optionType: 'button',
      }),
      defaultValue: 1,
      fieldName: 'status',
      label: $t('system.role.status'),
    },
    {
      component: 'Textarea',
      fieldName: 'remark',
      label: $t('system.role.remark'),
    },
    {
      component: 'Input',
      fieldName: 'menuIds',
      formItemClass: 'items-start',
      label: $t('system.role.navigationMenus'),
      modelPropName: 'modelValue',
    },
  ];
}

export function useGridFormSchema(
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  directorySchema: VbenFormSchema[] = [],
): VbenFormSchema[] {
  return [
    ...directorySchema,
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('system.role.roleName'),
    },
    { component: 'Input', fieldName: 'id', label: $t('system.role.id') },
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getStatusOptions(),
      }),
      fieldName: 'status',
      label: $t('system.role.status'),
    },
    {
      component: 'Input',
      fieldName: 'remark',
      label: $t('system.role.remark'),
    },
    {
      component: 'RangePicker',
      fieldName: 'createTime',
      label: $t('system.role.createTime'),
    },
  ];
}

export function useColumns<T = SystemRoleApi.SystemRole>(
  onActionClick: OnActionClickFn<T>,
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
  canChangeStatus: (row: T) => boolean = () => true,
  canEdit: (row: T) => boolean = () => true,
  canAssignUsers: (row: T) => boolean = () => true,
  canDelete: (row: T) => boolean = () => true,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  showDirectoryContext = false,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
  showOperationColumn = true,
): VxeTableGridColumns {
  return [
    ...(showDirectoryContext
      ? [
          {
            cellRender: {
              name: 'CellTag',
              options: asCellTagRenderOptions(getAccountDomainOptions),
            },
            field: 'accountDomain',
            title: $t('system.accountDomain'),
            width: 140,
          },
          {
            field: 'tenantName',
            minWidth: 180,
            title: $t('system.tenant'),
          },
        ]
      : []),
    {
      field: 'name',
      title: $t('system.role.roleName'),
      width: 200,
    },
    {
      field: 'id',
      title: $t('system.role.id'),
      width: 200,
    },
    {
      cellRender: onStatusChange
        ? {
            attrs: {
              auth: PERMISSION_CODES.roleUpdate,
              beforeChange: onStatusChange,
            },
            name: 'CellSwitch',
            options: asCellTagRenderOptions(getStatusOptions),
            props: {
              disabled: (row: T) => !canChangeStatus(row),
            },
          }
        : {
            name: 'CellTag',
            options: asCellTagRenderOptions(getStatusOptions),
          },
      field: 'status',
      title: $t('system.role.status'),
      width: 100,
    },
    {
      field: 'remark',
      minWidth: 100,
      title: $t('system.role.remark'),
    },
    {
      field: 'createTime',
      title: $t('system.role.createTime'),
      width: 200,
    },
    ...(showOperationColumn
      ? [
          {
            align: 'center' as const,
            cellRender: {
              attrs: {
                nameField: 'name',
                nameTitle: $t('system.role.name'),
                onClick: onActionClick,
              },
              name: 'CellOperation' as const,
              options: [
                {
                  auth: PERMISSION_CODES.roleUpdate,
                  code: 'edit',
                  show: canEdit,
                },
                {
                  auth: PERMISSION_CODES.userAssignRole,
                  code: 'assignUser',
                  show: canAssignUsers,
                  text: $t('system.role.assignUser'),
                },
                {
                  auth: PERMISSION_CODES.roleDelete,
                  code: 'delete',
                  show: canDelete,
                },
              ],
            },
            field: 'operation',
            fixed: 'right' as const,
            title: $t('system.role.operation'),
            width: 240,
          },
        ]
      : []),
  ];
}
