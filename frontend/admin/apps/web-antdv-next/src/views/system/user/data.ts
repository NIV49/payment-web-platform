import type { ComputedRef } from 'vue';

import type { DescriptionsItemType } from '@vben/common-ui';

import type { RoleAssignmentOption } from './modules/role-assignment';

import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridColumns } from '#/adapter/vxe-table';
import type { SystemUserApi } from '#/api';

import { h } from 'vue';

import { Tag } from 'antdv-next';

import { getDeptList, PERMISSION_CODES } from '#/api';
import { $t } from '#/locales';

import { identityStatusPresentation } from './identity-status';

export function useFormSchema(
  canAssignRoles: ComputedRef<boolean>,
  isEditing: ComputedRef<boolean>,
  roleOptions: ComputedRef<RoleAssignmentOption[]>,
): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: () => ({ disabled: isEditing.value }),
      fieldName: 'username',
      label: $t('system.user.username'),
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: () => ({ disabled: isEditing.value }),
      fieldName: 'name',
      label: $t('system.user.name'),
      rules: 'required',
    },
    {
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: getDeptList,
        childrenField: 'children',
        class: 'w-full',
        labelField: 'name',
        valueField: 'id',
      },
      fieldName: 'deptId',
      label: $t('system.user.dept'),
      rules: 'required',
    },
    {
      component: 'Select',
      componentProps: () => ({
        class: 'w-full',
        disabled: !canAssignRoles.value,
        mode: 'multiple',
        options: roleOptions.value,
      }),
      description: $t('system.user.rolePermissionTip'),
      fieldName: 'roleIds',
      label: $t('system.user.roles'),
    },
    {
      component: 'RadioGroup',
      componentProps: {
        buttonStyle: 'solid',
        options: [
          { label: $t('common.enabled'), value: 1 },
          { label: $t('common.disabled'), value: 0 },
        ],
        optionType: 'button',
      },
      defaultValue: 1,
      fieldName: 'status',
      label: $t('system.user.membershipStatus'),
    },
    {
      component: 'Textarea',
      componentProps: () => ({ disabled: isEditing.value }),
      fieldName: 'remark',
      label: $t('system.user.remark'),
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

export function useGridFormSchema(): VbenFormSchema[] {
  return [
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
      componentProps: {
        allowClear: true,
        options: [
          { label: $t('common.enabled'), value: 1 },
          { label: $t('common.disabled'), value: 0 },
        ],
      },
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
): DescriptionsItemType[] {
  const enabled = row?.status === 1;
  const identity = row
    ? identityStatusPresentation(row.identityStatus)
    : undefined;
  return [
    { label: $t('system.user.username'), content: row?.username },
    { label: $t('system.user.name'), content: row?.name },
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
          { color: enabled ? 'success' : 'error' },
          {
            default: () =>
              enabled ? $t('common.enabled') : $t('common.disabled'),
          },
        ),
    },
    { label: $t('system.user.createTime'), content: row?.createTime },
    { label: $t('system.user.remark'), content: row?.remark },
  ];
}

export function useColumns<T = SystemUserApi.SystemUser>(
  onStatusChange?: (newStatus: any, row: T) => PromiseLike<boolean | undefined>,
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
