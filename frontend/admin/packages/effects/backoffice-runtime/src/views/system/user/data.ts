import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';
import type { VxeTableGridColumns } from '@payment/backoffice-runtime/adapter/vxe-table';
import type { SystemUserApi } from '@payment/backoffice-runtime/api';
import type {
  AccountDomainOptions,
  CommonStatusOptions,
} from '@payment/backoffice-runtime/composables';

import type { ComputedRef, Ref } from 'vue';

import type { DescriptionsItemType } from '@vben/common-ui';

import type { RoleAssignmentOption } from './modules/role-assignment';

import { h } from 'vue';

import { z } from '@payment/backoffice-runtime/adapter/form';
import { asCellTagRenderOptions } from '@payment/backoffice-runtime/adapter/vxe-table';
import {
  getDeptList,
  getTenantOptions,
  PERMISSION_CODES,
} from '@payment/backoffice-runtime/api';
import { $t } from '@payment/backoffice-runtime/locales';
import { Tag } from 'antdv-next';

import { identityStatusPresentation } from './identity-status';
import { buildUserDepartmentOptions } from './query-contract';

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
  canAssignRoles: ComputedRef<boolean>,
  canEditIdentity: ComputedRef<boolean>,
  currentDepartmentId: ComputedRef<string | undefined>,
  isEditing: ComputedRef<boolean>,
  roleOptions: ComputedRef<RoleAssignmentOption[]>,
  roleSearchLoading: Ref<boolean>,
  onRoleSearch: (value: string) => void,
  departmentRequestVersion: Ref<number>,
  isPlatformDeployment = false,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
): VbenFormSchema[] {
  const accountScope: VbenFormSchema[] = isPlatformDeployment
    ? [
        {
          component: 'Select',
          componentProps: () => ({
            class: 'w-full',
            disabled: isEditing.value,
            options: getAccountDomainOptions(),
          }),
          defaultValue: 'PLATFORM',
          fieldName: 'accountDomain',
          label: $t('system.user.accountDomain'),
          rules: 'required',
        },
        {
          component: 'ApiSelect',
          dependencies: {
            resolve: ({ values }) => {
              const accountDomain = values.accountDomain as
                | 'AGENT'
                | 'MERCHANT'
                | 'PLATFORM';
              return {
                componentProps: {
                  api: (params?: { accountDomain?: string }) => {
                    const requestedDomain = params?.accountDomain;
                    return requestedDomain === 'AGENT' ||
                      requestedDomain === 'MERCHANT'
                      ? getTenantOptions(requestedDomain)
                      : Promise.resolve([]);
                  },
                  class: 'w-full',
                  disabled: isEditing.value,
                  labelField: 'name',
                  params: { accountDomain },
                  valueField: 'id',
                },
                rules:
                  accountDomain === 'PLATFORM'
                    ? z.string().optional()
                    : z.string().min(1),
                show: accountDomain !== 'PLATFORM',
              };
            },
            triggerFields: ['accountDomain'],
          },
          fieldName: 'tenantId',
          label: $t('system.user.tenant'),
        },
      ]
    : [];
  return [
    ...accountScope,
    {
      component: 'Input',
      componentProps: () => ({
        disabled: isEditing.value && !canEditIdentity.value,
      }),
      fieldName: 'username',
      label: $t('system.user.username'),
      rules: z.string().trim().email().max(100),
    },
    {
      component: 'Input',
      componentProps: () => ({
        disabled: isEditing.value && !canEditIdentity.value,
      }),
      fieldName: 'name',
      label: $t('system.user.name'),
      rules: 'required',
    },
    {
      component: 'ApiTreeSelect',
      componentProps: () => ({
        allowClear: true,
        api: async (params?: {
          currentDepartmentId?: string;
          requestVersion?: number;
        }) =>
          buildUserDepartmentOptions(
            await getDeptList(),
            params?.currentDepartmentId,
          ),
        childrenField: 'children',
        class: 'w-full',
        labelField: 'name',
        params: {
          currentDepartmentId: currentDepartmentId.value,
          requestVersion: departmentRequestVersion.value,
        },
        valueField: 'id',
      }),
      fieldName: 'deptId',
      label: $t('system.user.dept'),
      rules: 'required',
      ...(isPlatformDeployment
        ? {
            dependencies: {
              resolve: ({ values }) => ({
                show: values.accountDomain === 'PLATFORM',
              }),
              triggerFields: ['accountDomain'],
            },
          }
        : {}),
    },
    {
      component: 'Select',
      componentProps: () => ({
        class: 'w-full',
        disabled: !canAssignRoles.value,
        filterOption: false,
        loading: roleSearchLoading.value,
        mode: 'multiple',
        onSearch: onRoleSearch,
        options: roleOptions.value,
        showSearch: true,
      }),
      description: $t('system.user.rolePermissionTip'),
      fieldName: 'roleIds',
      label: $t('system.user.roles'),
      ...(isPlatformDeployment
        ? {
            dependencies: {
              resolve: ({ values }) => ({
                show: values.accountDomain === 'PLATFORM',
              }),
              triggerFields: ['accountDomain'],
            },
          }
        : {}),
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
      label: $t('system.user.membershipStatus'),
    },
    {
      component: 'Textarea',
      componentProps: () => ({
        disabled: isEditing.value && !canEditIdentity.value,
      }),
      fieldName: 'remark',
      label: $t('system.user.remark'),
    },
    {
      component: 'InputNumber',
      defaultValue: 0,
      fieldName: 'identityVersion',
      formItemClass: 'hidden',
      hideLabel: true,
    },
    {
      component: 'InputNumber',
      defaultValue: 0,
      fieldName: 'credentialVersion',
      formItemClass: 'hidden',
      hideLabel: true,
    },
    {
      component: 'InputNumber',
      defaultValue: 0,
      fieldName: 'userVersion',
      formItemClass: 'hidden',
      hideLabel: true,
    },
  ];
}

export function useGridFormSchema(
  isPlatformDeployment = false,
  onAccountDomainChange?: (value: unknown) => void,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
): VbenFormSchema[] {
  return [
    ...(isPlatformDeployment
      ? [
          {
            component: 'Select' as const,
            componentProps: () => ({
              allowClear: false,
              onChange: onAccountDomainChange,
              options: getAccountDomainOptions(),
            }),
            defaultValue: 'PLATFORM',
            fieldName: 'accountDomain',
            label: $t('system.user.accountDomain'),
          },
        ]
      : []),
    {
      component: 'Input',
      fieldName: 'username',
      label: $t('system.user.username'),
    },
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('system.user.name'),
    },
    { component: 'Input', fieldName: 'id', label: $t('system.user.id') },
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getStatusOptions(),
      }),
      fieldName: 'status',
      label: $t('system.user.membershipStatus'),
    },
    {
      component: 'RangePicker',
      fieldName: 'createTime',
      label: $t('system.user.createTime'),
    },
  ];
}

export function useDescriptionItems(
  row?: SystemUserApi.SystemUser,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
): DescriptionsItemType[] {
  const enabled = row?.status === 1;
  const membershipStatus = getStatusOptions().find(
    ({ value }) => value === row?.status,
  );
  const identity = row
    ? identityStatusPresentation(row.identityStatus)
    : undefined;
  const accountDomain = row?.accountDomain
    ? getAccountDomainOptions().find(({ value }) => value === row.accountDomain)
    : undefined;
  return [
    { label: $t('system.user.username'), content: row?.username },
    { label: $t('system.user.name'), content: row?.name },
    ...(row?.accountDomain
      ? [
          {
            label: $t('system.user.accountDomain'),
            content: () =>
              h(
                Tag,
                { color: accountDomain?.color ?? 'default' },
                { default: () => accountDomain?.label ?? row.accountDomain },
              ),
          },
          { label: $t('system.user.tenant'), content: row.tenantName },
        ]
      : []),
    { label: $t('system.user.id'), content: row?.id },
    {
      label: $t('system.user.dept'),
      content: row?.deptName || row?.deptId,
    },
    {
      label: $t('system.user.roles'),
      content: row?.roleNames?.join(', ') || row?.roleIds?.join(', '),
    },
    {
      label: $t('system.user.identityStatus'),
      content: () =>
        identity
          ? h(
              Tag,
              { color: identity.color },
              { default: () => $t(identity.label) },
            )
          : undefined,
    },
    {
      label: $t('system.user.membershipStatus'),
      content: () =>
        h(
          Tag,
          { color: membershipStatus?.color ?? (enabled ? 'success' : 'error') },
          {
            default: () =>
              membershipStatus?.label ??
              (enabled ? $t('common.enabled') : $t('common.disabled')),
          },
        ),
    },
    { label: $t('system.user.createTime'), content: row?.createTime },
    { label: $t('system.user.remark'), content: row?.remark },
  ];
}

export function useColumns<T = SystemUserApi.SystemUser>(
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
  canChangeStatus: (row: T) => boolean = () => true,
  isPlatformDeployment = false,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
): VxeTableGridColumns {
  return [
    {
      field: 'username',
      title: $t('system.user.username'),
      width: 180,
    },
    {
      field: 'name',
      title: $t('system.user.name'),
      width: 160,
    },
    ...(isPlatformDeployment
      ? [
          {
            cellRender: {
              name: 'CellTag',
              options: asCellTagRenderOptions(getAccountDomainOptions),
            },
            field: 'accountDomain',
            title: $t('system.user.accountDomain'),
            width: 130,
          },
          {
            field: 'tenantName',
            minWidth: 160,
            title: $t('system.user.tenant'),
          },
        ]
      : []),
    {
      field: 'id',
      title: $t('system.user.id'),
      width: 200,
    },
    {
      field: 'identityStatus',
      formatter: ({ cellValue }) =>
        $t(
          identityStatusPresentation(cellValue as SystemUserApi.IdentityStatus)
            .label,
        ),
      title: $t('system.user.identityStatus'),
      width: 120,
    },
    {
      cellRender: {
        attrs: {
          auth: PERMISSION_CODES.userDisable,
          beforeChange: onStatusChange,
        },
        name: onStatusChange ? 'CellSwitch' : 'CellTag',
        options: asCellTagRenderOptions(getStatusOptions),
        props: {
          disabled: (row: T) => !canChangeStatus(row),
        },
      },
      field: 'status',
      title: $t('system.user.membershipStatus'),
      width: 120,
    },
    {
      field: 'remark',
      minWidth: 120,
      title: $t('system.user.remark'),
    },
    {
      field: 'createTime',
      title: $t('system.user.createTime'),
      width: 180,
    },
    {
      align: 'center',
      field: 'operation',
      fixed: 'right',
      slots: { default: 'action' },
      title: $t('system.user.operation'),
      width: 220,
    },
  ];
}
