import type { VbenFormSchema } from '#/adapter/form';
import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { SystemDictionaryApi } from '#/api/system/dictionary';

import { z } from '#/adapter/form';
import { PERMISSION_CODES } from '#/api/permission-codes';
import { $t } from '#/locales';

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'dictName',
      label: $t('system.dict.dictName'),
    },
    {
      component: 'Input',
      fieldName: 'dictType',
      label: $t('system.dict.dictType'),
    },
  ];
}

export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'dictName',
      label: $t('system.dict.dictName'),
      rules: z.string().trim().min(1).max(100),
    },
    {
      component: 'Input',
      componentProps: { maxLength: 64 },
      fieldName: 'dictType',
      label: $t('system.dict.dictType'),
      rules: z
        .string()
        .regex(/^[A-Za-z][A-Za-z0-9_]{0,63}$/, $t('system.dict.typeFormat')),
    },
    {
      component: 'InputNumber',
      componentProps: { class: 'w-full' },
      defaultValue: 0,
      fieldName: 'sort',
      label: $t('system.dict.sort'),
      rules: z.coerce.number().int().min(0).max(9999),
    },
    {
      component: 'Textarea',
      fieldName: 'remark',
      label: $t('system.dict.remark'),
      rules: z.string().max(500).optional(),
    },
  ];
}

export function useColumns(
  onActionClick: OnActionClickFn<SystemDictionaryApi.SystemDictionary>,
): VxeTableGridColumns<SystemDictionaryApi.SystemDictionary> {
  return [
    {
      field: 'dictName',
      minWidth: 160,
      title: $t('system.dict.dictName'),
    },
    {
      field: 'dictType',
      minWidth: 180,
      title: $t('system.dict.dictType'),
    },
    {
      field: 'sort',
      title: $t('system.dict.sort'),
      width: 100,
    },
    {
      field: 'remark',
      minWidth: 180,
      title: $t('system.dict.remark'),
    },
    {
      field: 'createTime',
      title: $t('system.dict.createTime'),
      width: 180,
    },
    {
      align: 'center',
      cellRender: {
        attrs: {
          nameField: 'dictName',
          nameTitle: $t('system.dict.name'),
          onClick: onActionClick,
        },
        name: 'CellOperation',
        options: [
          {
            auth: PERMISSION_CODES.dictionaryView,
            code: 'data',
            text: $t('system.dict.data'),
          },
          {
            auth: PERMISSION_CODES.dictionaryUpdate,
            code: 'edit',
          },
          {
            auth: PERMISSION_CODES.dictionaryDelete,
            code: 'delete',
          },
        ],
      },
      field: 'operation',
      fixed: 'right',
      title: $t('system.dict.operation'),
      width: 220,
    },
  ];
}
