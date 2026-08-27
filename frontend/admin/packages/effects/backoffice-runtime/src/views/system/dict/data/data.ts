import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';
import type {
  OnActionClickFn,
  VxeTableGridColumns,
} from '@payment/backoffice-runtime/adapter/vxe-table';
import type { SystemDictionaryDataApi } from '@payment/backoffice-runtime/api/system/dictionary-data';

import { z } from '@payment/backoffice-runtime/adapter/form';
import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';
import { $t } from '@payment/backoffice-runtime/locales';

export function getDictionaryColorOptions() {
  return [
    {
      color: 'default',
      label: $t('system.dictData.colors.default'),
      value: 'default',
    },
    {
      color: 'processing',
      label: $t('system.dictData.colors.processing'),
      value: 'processing',
    },
    {
      color: 'success',
      label: $t('system.dictData.colors.success'),
      value: 'success',
    },
    {
      color: 'warning',
      label: $t('system.dictData.colors.warning'),
      value: 'warning',
    },
    {
      color: 'error',
      label: $t('system.dictData.colors.error'),
      value: 'error',
    },
    {
      color: 'purple',
      label: $t('system.dictData.colors.purple'),
      value: 'purple',
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'label',
      label: $t('system.dictData.label'),
    },
    {
      component: 'Input',
      fieldName: 'value',
      label: $t('system.dictData.value'),
    },
  ];
}

export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'label',
      label: $t('system.dictData.label'),
      rules: z.string().trim().min(1).max(100),
    },
    {
      component: 'Input',
      fieldName: 'value',
      label: $t('system.dictData.value'),
      rules: z.string().trim().min(1).max(100),
    },
    {
      component: 'Select',
      componentProps: {
        class: 'w-full',
        options: getDictionaryColorOptions(),
      },
      defaultValue: 'default',
      fieldName: 'color',
      label: $t('system.dictData.color'),
      rules: z.enum([
        'default',
        'processing',
        'success',
        'warning',
        'error',
        'purple',
      ]),
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full' },
      defaultValue: 0,
      fieldName: 'sort',
      label: $t('system.dictData.sort'),
      rules: z.coerce.number().int().min(0).max(9999),
    },
    {
      component: 'Textarea',
      fieldName: 'remark',
      label: $t('system.dictData.remark'),
      rules: z.string().max(500).optional(),
    },
  ];
}

export function useColumns(
  onActionClick?: OnActionClickFn<SystemDictionaryDataApi.SystemDictionaryData>,
  writable = false,
): VxeTableGridColumns<SystemDictionaryDataApi.SystemDictionaryData> {
  const columns: VxeTableGridColumns<SystemDictionaryDataApi.SystemDictionaryData> =
    [
      {
        cellRender: {
          attrs: {
            labelField: 'label',
            valueField: 'color',
          },
          name: 'CellTag',
          options: getDictionaryColorOptions(),
        },
        field: 'label',
        minWidth: 160,
        title: $t('system.dictData.label'),
      },
      {
        field: 'value',
        minWidth: 140,
        title: $t('system.dictData.value'),
      },
      {
        field: 'sort',
        title: $t('system.dictData.sort'),
        width: 100,
      },
      {
        field: 'remark',
        minWidth: 180,
        title: $t('system.dictData.remark'),
      },
      {
        field: 'createTime',
        title: $t('system.dictData.createTime'),
        width: 180,
      },
    ];

  if (writable) {
    columns.push({
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'label',
          nameTitle: $t('system.dictData.name'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            auth: PERMISSION_CODES.dictionaryUpdate,
            code: 'edit',
          },
          {
            auth: PERMISSION_CODES.dictionaryUpdate,
            code: 'delete',
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: $t('system.dictData.operation'),
      width: 140,
    });
  }

  return columns;
}
