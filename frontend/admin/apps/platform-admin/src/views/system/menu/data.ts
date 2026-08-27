import type {
  AccountDomainOptions,
  CommonStatusOptions,
} from '@payment/backoffice-runtime/composables';

import type { OnActionClickFn, VxeTableGridColumns } from '#/adapter/vxe-table';
import type { SystemMenuApi } from '#/api/system/menu';

import { asCellTagRenderOptions } from '#/adapter/vxe-table';
import { PERMISSION_CODES } from '#/api';
import { $t } from '#/locales';

import {
  canPerformMenuAction,
  getMenuActionPresentation,
} from './permission-contract';

type AccessCodeChecker = (codes: string[]) => boolean;

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

export function getMenuTypeOptions() {
  return [
    {
      color: 'processing',
      label: $t('system.menu.typeCatalog'),
      value: 'catalog',
    },
    { color: 'default', label: $t('system.menu.typeMenu'), value: 'menu' },
    { color: 'error', label: $t('system.menu.typeButton'), value: 'button' },
    {
      color: 'success',
      label: $t('system.menu.typeEmbedded'),
      value: 'embedded',
    },
    { color: 'warning', label: $t('system.menu.typeLink'), value: 'link' },
  ];
}

export function useColumns(
  onActionClick: OnActionClickFn<SystemMenuApi.SystemMenu>,
  hasAccessByCodes: AccessCodeChecker,
  getStatusOptions: () => CommonStatusOptions = getFallbackStatusOptions,
  showDirectoryContext = false,
  getAccountDomainOptions: () => AccountDomainOptions = getFallbackAccountDomainOptions,
  showOperationColumn = true,
): VxeTableGridColumns<SystemMenuApi.SystemMenu> {
  return [
    {
      align: 'left',
      field: 'meta.title',
      fixed: 'left',
      slots: { default: 'title' },
      title: $t('system.menu.menuTitle'),
      treeNode: true,
      width: 250,
    },
    {
      align: 'center',
      cellRender: { name: 'CellTag', options: getMenuTypeOptions() },
      field: 'type',
      title: $t('system.menu.type'),
      width: 100,
    },
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
      field: 'authCode',
      title: $t('system.menu.authCode'),
      width: 200,
    },
    {
      align: 'left',
      field: 'path',
      title: $t('system.menu.path'),
      width: 200,
    },

    {
      align: 'left',
      field: 'component',
      formatter: ({ row }) => {
        switch (row.type) {
          case 'catalog':
          case 'menu': {
            return row.component ?? '';
          }
          case 'embedded': {
            return row.meta?.iframeSrc ?? '';
          }
          case 'link': {
            return row.meta?.link ?? '';
          }
        }
        return '';
      },
      minWidth: 200,
      title: $t('system.menu.component'),
    },
    {
      cellRender: {
        name: 'CellTag',
        options: asCellTagRenderOptions(getStatusOptions),
      },
      field: 'status',
      title: $t('system.menu.status'),
      width: 100,
    },

    ...(showOperationColumn
      ? [
          {
            align: 'right' as const,
            cellRender: {
              attrs: {
                nameField: 'name',
                onClick: onActionClick,
              },
              name: 'CellOperation' as const,
              options: [
                {
                  auth: PERMISSION_CODES.menuCreate,
                  code: 'append',
                  show: (row: SystemMenuApi.SystemMenu) =>
                    canPerformMenuAction(
                      row,
                      PERMISSION_CODES.menuCreate,
                      hasAccessByCodes,
                    ),
                  text: $t('system.menu.addChild'),
                },
                {
                  auth: PERMISSION_CODES.menuUpdate,
                  code: 'edit',
                  disabled: (row: SystemMenuApi.SystemMenu) =>
                    getMenuActionPresentation(
                      row,
                      PERMISSION_CODES.menuUpdate,
                      hasAccessByCodes,
                    ).disabled,
                  show: (row: SystemMenuApi.SystemMenu) =>
                    getMenuActionPresentation(
                      row,
                      PERMISSION_CODES.menuUpdate,
                      hasAccessByCodes,
                    ).visible,
                },
                {
                  auth: PERMISSION_CODES.menuDelete,
                  code: 'delete',
                  disabled: (row: SystemMenuApi.SystemMenu) =>
                    getMenuActionPresentation(
                      row,
                      PERMISSION_CODES.menuDelete,
                      hasAccessByCodes,
                    ).disabled,
                  show: (row: SystemMenuApi.SystemMenu) =>
                    getMenuActionPresentation(
                      row,
                      PERMISSION_CODES.menuDelete,
                      hasAccessByCodes,
                    ).visible,
                },
              ],
            },
            field: 'operation',
            fixed: 'right' as const,
            headerAlign: 'center' as const,
            showOverflow: false,
            title: $t('system.menu.operation'),
            width: 200,
          },
        ]
      : []),
  ];
}
