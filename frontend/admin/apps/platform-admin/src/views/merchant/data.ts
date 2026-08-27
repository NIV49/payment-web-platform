import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';

import type {
  AuthenticationTypeOptions,
  MerchantTypeOptions,
} from './merchant-classification-dictionary';

import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridColumns } from '#/adapter/vxe-table';

import { $t } from '#/locales';

import {
  buildAuthenticationTypeOptions,
  buildMerchantTypeOptions,
} from './merchant-classification-dictionary';

const MARKET_OPTIONS = [
  { color: 'success', label: $t('merchant.markets.BRA'), value: 'BRA' },
  { color: 'processing', label: $t('merchant.markets.PHL'), value: 'PHL' },
] as const;
const FALLBACK_MERCHANT_TYPE_OPTIONS = buildMerchantTypeOptions([]);
const FALLBACK_AUTHENTICATION_TYPE_OPTIONS = buildAuthenticationTypeOptions([]);

const STATUS_OPTIONS: Array<{
  color: string;
  label: string;
  value: MerchantLifecycleApi.MerchantStatus;
}> = [
  {
    color: 'processing',
    label: $t('merchant.status.PENDING_REVIEW'),
    value: 'PENDING_REVIEW',
  },
  {
    color: 'warning',
    label: $t('merchant.status.REVIEW_REJECTED'),
    value: 'REVIEW_REJECTED',
  },
  { color: 'success', label: $t('merchant.status.ACTIVE'), value: 'ACTIVE' },
  {
    color: 'default',
    label: $t('merchant.status.DISABLED'),
    value: 'DISABLED',
  },
  {
    color: 'error',
    label: $t('merchant.status.TERMINATED'),
    value: 'TERMINATED',
  },
];

function merchantSearchSchema(
  getMerchantTypeOptions: () => MerchantTypeOptions = () =>
    FALLBACK_MERCHANT_TYPE_OPTIONS,
  getAuthenticationTypeOptions: () => AuthenticationTypeOptions = () =>
    FALLBACK_AUTHENTICATION_TYPE_OPTIONS,
): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { maxlength: 64 },
      fieldName: 'merchantCode',
      label: $t('merchant.fields.merchantCode'),
    },
    {
      component: 'Input',
      fieldName: 'name',
      label: $t('merchant.fields.merchantName'),
    },
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getMerchantTypeOptions(),
      }),
      fieldName: 'merchantTypeCode',
      label: $t('merchant.fields.merchantType'),
    },
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: getAuthenticationTypeOptions(),
      }),
      fieldName: 'authenticationType',
      label: $t('merchant.fields.authenticationType'),
    },
    {
      component: 'Select',
      componentProps: { allowClear: true, options: STATUS_OPTIONS },
      fieldName: 'status',
      label: $t('merchant.fields.merchantStatus'),
    },
    {
      component: 'Select',
      componentProps: { allowClear: true, options: MARKET_OPTIONS },
      fieldName: 'marketCode',
      label: $t('merchant.fields.market'),
    },
    {
      component: 'RangePicker',
      fieldName: 'createdAt',
      label: $t('merchant.fields.createdAt'),
    },
  ];
}

function merchantColumns(): VxeTableGridColumns<MerchantLifecycleApi.MerchantListItem> {
  return [
    {
      field: 'merchantCode',
      minWidth: 190,
      title: $t('merchant.fields.merchantCode'),
    },
    {
      field: 'displayName',
      minWidth: 180,
      title: $t('merchant.fields.merchantName'),
    },
    {
      field: 'merchantTypeCode',
      minWidth: 150,
      slots: { default: 'merchantTypeCode' },
      title: $t('merchant.fields.merchantType'),
    },
    {
      field: 'legalPersonName',
      minWidth: 160,
      title: $t('merchant.fields.legalPersonName'),
    },
    {
      field: 'authenticationType',
      minWidth: 180,
      slots: { default: 'authenticationType' },
      title: $t('merchant.fields.authenticationType'),
    },
    {
      field: 'legalName',
      minWidth: 220,
      title: $t('merchant.fields.subjectName'),
    },
    {
      align: 'center',
      field: 'marketCodes',
      minWidth: 210,
      slots: { default: 'marketCodes' },
      title: $t('merchant.fields.market'),
    },
    {
      field: 'status',
      minWidth: 140,
      slots: { default: 'status' },
      title: $t('merchant.fields.merchantStatus'),
    },
    {
      field: 'statusReasonCode',
      formatter: ({ cellValue }) =>
        cellValue ? $t(`merchant.reasons.${cellValue}`) : '-',
      minWidth: 180,
      title: $t('merchant.fields.statusRemarks'),
    },
    {
      field: 'remarks',
      minWidth: 180,
      title: $t('merchant.fields.merchantRemarks'),
    },
    {
      field: 'createdAt',
      formatter: 'formatDateTime',
      minWidth: 180,
      title: $t('merchant.fields.createdAt'),
    },
    {
      field: 'updatedAt',
      formatter: 'formatDateTime',
      minWidth: 180,
      title: $t('merchant.fields.updatedAt'),
    },
    {
      align: 'center',
      field: 'action',
      fixed: 'right',
      slots: { default: 'action' },
      title: $t('merchant.fields.operation'),
      width: 180,
    },
  ];
}

function marketLabel(code: string) {
  return MARKET_OPTIONS.find((option) => option.value === code)?.label ?? code;
}

function statusOption(status: MerchantLifecycleApi.MerchantStatus) {
  return STATUS_OPTIONS.find((option) => option.value === status);
}

export {
  MARKET_OPTIONS,
  marketLabel,
  merchantColumns,
  merchantSearchSchema,
  STATUS_OPTIONS,
  statusOption,
};
