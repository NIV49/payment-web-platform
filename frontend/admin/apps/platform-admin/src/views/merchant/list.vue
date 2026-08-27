<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { useVbenForm, z } from '@payment/backoffice-runtime/adapter/form';
import {
  disableMerchant,
  enableMerchant,
  getPlatformMerchants,
  isMerchantStateConflict,
  PERMISSION_CODES,
} from '@payment/backoffice-runtime/api';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import { createIdempotencyKeyGeneration } from '@payment/backoffice-runtime/views/merchant/contract';
import { createMerchantRequestGuard } from '@payment/backoffice-runtime/views/merchant/request-guard';
import { Alert, Button, message, Popover, Switch, Tag } from 'antdv-next';

import { useVbenVxeGrid, VbenTableAction } from '#/adapter/vxe-table';
import { $t } from '#/locales';

import ClassificationDictionaryAlert from './components/classification-dictionary-alert.vue';
import {
  marketLabel,
  merchantColumns,
  merchantSearchSchema,
  statusOption,
} from './data';
import { useMerchantClassificationDictionary } from './merchant-classification-dictionary';
import { normalizeMerchantTypeCode } from './merchant-presentation';

type StatusAction = 'disable' | 'enable';

interface PendingStatusChange {
  action: StatusAction;
  resolve: (result: false) => void;
  row: MerchantLifecycleApi.MerchantListItem;
}

const { hasAccessByCodes } = useAccess();
const router = useRouter();
const pendingStatusChange = ref<PendingStatusChange>();
const statusCommandFailed = ref(false);
const statusSwitchingMerchantId = ref<string>();
const statusCommandKey = createIdempotencyKeyGeneration(() =>
  crypto.randomUUID(),
);
const classifications = useMerchantClassificationDictionary();
const getMerchantTypeOptions = () => classifications.merchantTypeOptions.value;
const getAuthenticationTypeOptions = () =>
  classifications.authenticationTypeOptions.value;

function hasAllAccess(codes: string[]) {
  return codes.every((code) => hasAccessByCodes([code]));
}

function merchantTypeOption(
  value: MerchantLifecycleApi.MerchantTypeCode | null,
) {
  const normalized = normalizeMerchantTypeCode(value);
  return classifications.merchantTypeOptions.value.find(
    (option) => option.value === normalized,
  );
}

function authenticationTypeOption(
  value: MerchantLifecycleApi.AuthenticationType | null,
) {
  return classifications.authenticationTypeOptions.value.find(
    (option) => option.value === value,
  );
}

const listLoadFailed = ref(false);
const listRequestGuard = createMerchantRequestGuard();
const statusMutationGuard = createMerchantRequestGuard();
let disposed = false;
let queryInProgress = false;
let reloadTimer: ReturnType<typeof setTimeout> | undefined;

const [Grid, gridApi] = useVbenVxeGrid<MerchantLifecycleApi.MerchantListItem>({
  formOptions: {
    schema: merchantSearchSchema(
      getMerchantTypeOptions,
      getAuthenticationTypeOptions,
    ),
    submitOnChange: false,
  },
  gridOptions: {
    columns: merchantColumns(),
    height: 'auto',
    pagerConfig: {
      pageSizes: [10, 20, 30, 50, 100],
    },
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          if (disposed) return { items: [], total: 0 };
          const query = {
            ...encodeMerchantFilters(formValues),
            page: page.currentPage,
            pageSize: page.pageSize,
          } as MerchantLifecycleApi.MerchantListQuery;
          const scope = JSON.stringify(query);
          const request = listRequestGuard.begin(scope);
          queryInProgress = true;
          try {
            const response = await getPlatformMerchants(query);
            if (!listRequestGuard.isCurrent(request, scope)) {
              return { items: [], total: 0 };
            }
            listLoadFailed.value = false;
            return response;
          } catch (error) {
            if (!listRequestGuard.isCurrent(request, scope)) {
              return { items: [], total: 0 };
            }
            listLoadFailed.value = true;
            throw error;
          } finally {
            queryInProgress = false;
            if (!disposed && queuedReloadValues && !reloadInProgress) {
              reloadTimer = setTimeout(() => {
                reloadTimer = undefined;
                if (!disposed && queuedReloadValues && !reloadInProgress) {
                  void drainReloadQueue();
                }
              });
            }
          }
        },
      },
    },
    rowConfig: { keyField: 'merchantId' },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<MerchantLifecycleApi.MerchantListItem>,
});

const [StatusCommandForm, statusCommandFormApi] = useVbenForm({
  handleValuesChange() {
    statusCommandKey.markEdited();
  },
  layout: 'vertical',
  schema: [
    {
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: statusReasonCodes().map((value) => ({
          label: $t(`merchant.reasons.${value}`),
          value,
        })),
      }),
      fieldName: 'reasonCode',
      label: $t('merchant.actions.reasonCode'),
      rules: z.string().min(1),
    },
  ],
  showDefaultActions: false,
});

const [StatusCommandModal, statusCommandModalApi] = useVbenModal({
  async onConfirm() {
    const pending = pendingStatusChange.value;
    if (!pending || disposed) return;
    statusCommandFailed.value = false;
    const { valid } = await statusCommandFormApi.validate();
    if (!valid || disposed) return;
    const { reasonCode } = await statusCommandFormApi.getValues<{
      reasonCode: MerchantLifecycleApi.ReasonCode;
    }>();
    if (disposed) return;
    const scope = `${pending.action}:${pending.row.merchantId}:${statusCommandKey.current()}`;
    const request = statusMutationGuard.begin(scope);
    statusCommandModalApi.lock();
    try {
      const command = {
        expectedVersion: pending.row.rowVersion,
        idempotencyKey: scope.slice(scope.lastIndexOf(':') + 1),
        reasonCode,
      };
      await (pending.action === 'disable'
        ? disableMerchant(pending.row.merchantId, command)
        : enableMerchant(pending.row.merchantId, command));
      if (disposed || !statusMutationGuard.isCurrent(request, scope)) return;
      message.success($t('merchant.actions.success'));
      statusCommandModalApi.close();
      finishStatusChange();
      if (disposed) return;
      await gridApi.query();
    } catch (error) {
      if (disposed || !statusMutationGuard.isCurrent(request, scope)) return;
      if (isOptimisticLockConflict(error) || isMerchantStateConflict(error)) {
        statusCommandModalApi.close();
        finishStatusChange();
        if (disposed) return;
        await gridApi.query();
      } else {
        statusCommandFailed.value = true;
        pending.resolve(false);
      }
    } finally {
      if (!disposed) statusCommandModalApi.lock(false);
    }
  },
  onOpenChange(open) {
    if (!open) {
      statusMutationGuard.invalidate();
      finishStatusChange();
    }
  },
});

const reloadGrid = gridApi.reload;
let queuedReloadValues: Record<string, any> | undefined;
let reloadInProgress = false;
let reloadWaiters: Array<() => void> = [];

gridApi.reload = (values: Record<string, any> = {}) => {
  if (disposed) return Promise.resolve();
  listRequestGuard.invalidate();
  queuedReloadValues = values;
  void gridApi.grid.reloadData?.([]);

  const completed = new Promise<void>((resolve) => reloadWaiters.push(resolve));
  if (!queryInProgress && !reloadInProgress) void drainReloadQueue();
  return completed;
};

async function drainReloadQueue() {
  if (disposed) {
    settleReloadWaiters();
    return;
  }
  reloadInProgress = true;
  try {
    while (queuedReloadValues) {
      if (disposed) break;
      const values = queuedReloadValues;
      queuedReloadValues = undefined;
      await reloadGrid(values);
    }
  } finally {
    reloadInProgress = false;
    settleReloadWaiters();
  }
}

function settleReloadWaiters() {
  const waiters = reloadWaiters;
  reloadWaiters = [];
  waiters.forEach((resolve) => resolve());
}

function openDetail(row: MerchantLifecycleApi.MerchantListItem) {
  void router.push({
    name: 'MerchantDetail',
    params: { merchantId: row.merchantId },
  });
}

function openEdit(row: MerchantLifecycleApi.MerchantListItem) {
  if (!canEdit(row)) return;
  void router.push({
    name: 'MerchantOnboarding',
    params: { merchantId: row.merchantId },
  });
}

function openReview(row: MerchantLifecycleApi.MerchantListItem) {
  if (!row.reviewPending || !canReviewMerchant()) return;
  void router.push({
    name: 'MerchantReview',
    params: { merchantId: row.merchantId },
  });
}

function openCreate() {
  if (!hasAccessByCodes([PERMISSION_CODES.merchantCreate])) return;
  void router.push({ name: 'MerchantOnboarding' });
}

function canEdit(row: MerchantLifecycleApi.MerchantListItem) {
  return (
    (row.status === 'ACTIVE' || row.status === 'DISABLED') &&
    hasAccessByCodes([PERMISSION_CODES.merchantAmend])
  );
}

function canReviewMerchant() {
  return hasAllAccess([
    PERMISSION_CODES.merchantView,
    PERMISSION_CODES.merchantDocumentView,
    PERMISSION_CODES.merchantReview,
  ]);
}

function canChangeStatus(row: MerchantLifecycleApi.MerchantListItem) {
  if (row.status === 'ACTIVE') {
    return hasAccessByCodes([PERMISSION_CODES.merchantDisable]);
  }
  if (row.status === 'DISABLED') {
    return hasAccessByCodes([PERMISSION_CODES.merchantEnable]);
  }
  return false;
}

function onStatusChange(
  nextStatus: MerchantLifecycleApi.MerchantStatus,
  row: MerchantLifecycleApi.MerchantListItem,
) {
  if (
    !canChangeStatus(row) ||
    (nextStatus !== 'ACTIVE' && nextStatus !== 'DISABLED') ||
    nextStatus === row.status ||
    pendingStatusChange.value
  ) {
    return Promise.resolve(false as const);
  }
  const action: StatusAction = nextStatus === 'ACTIVE' ? 'enable' : 'disable';
  statusCommandFailed.value = false;
  statusCommandKey.reset();
  void statusCommandFormApi.reset();
  return new Promise<false>((resolve) => {
    pendingStatusChange.value = { action, resolve, row };
    statusCommandModalApi.open();
  });
}

function statusReasonCodes(): MerchantLifecycleApi.ReasonCode[] {
  return pendingStatusChange.value?.action === 'disable'
    ? ['COMPLIANCE_HOLD', 'RISK_CONTROL']
    : ['COMPLIANCE_CLEARED', 'RISK_CLEARED'];
}

function finishStatusChange() {
  const pending = pendingStatusChange.value;
  pendingStatusChange.value = undefined;
  pending?.resolve(false);
}

async function onStatusSwitch(
  checked: boolean,
  row: MerchantLifecycleApi.MerchantListItem,
) {
  if (statusSwitchingMerchantId.value) return;
  statusSwitchingMerchantId.value = row.merchantId;
  try {
    await onStatusChange(checked ? 'ACTIVE' : 'DISABLED', row);
  } finally {
    if (!disposed) statusSwitchingMerchantId.value = undefined;
  }
}

function encodeMerchantFilters(values: Record<string, any>) {
  const { createdAt, ...filters } = values;
  const normalizedFilters = Object.fromEntries(
    Object.entries(filters).flatMap(([key, value]) => {
      if (value === undefined || value === null) return [];
      if (typeof value === 'string') {
        const normalized = value.trim();
        return normalized.length === 0 ? [] : [[key, normalized]];
      }
      return [[key, value]];
    }),
  );
  if (!Array.isArray(createdAt)) return normalizedFilters;
  const [from, to] = createdAt;
  return {
    ...normalizedFilters,
    createdFrom: from?.startOf('day').toISOString(),
    createdTo: to?.add(1, 'day').startOf('day').toISOString(),
  };
}

onBeforeUnmount(() => {
  disposed = true;
  listRequestGuard.invalidate();
  statusMutationGuard.invalidate();
  queuedReloadValues = undefined;
  if (reloadTimer) clearTimeout(reloadTimer);
  reloadTimer = undefined;
  settleReloadWaiters();
  finishStatusChange();
});
</script>

<template>
  <Page auto-content-height>
    <ClassificationDictionaryAlert
      :error="classifications.error.value"
      :reload="classifications.reload"
    />
    <StatusCommandModal :title="$t('merchant.actions.statusChange')">
      <Alert
        v-if="statusCommandFailed"
        class="mx-4 mb-4"
        show-icon
        type="error"
        :message="$t('merchant.actions.failed')"
      />
      <StatusCommandForm class="mx-4" />
    </StatusCommandModal>
    <Alert
      v-if="listLoadFailed"
      class="mb-4"
      show-icon
      type="error"
      :message="$t('merchant.list.loadFailed')"
    />
    <Grid :table-title="$t('merchant.list.title')">
      <template #toolbar-tools>
        <Button
          v-access:code="PERMISSION_CODES.merchantCreate"
          type="primary"
          @click="openCreate"
        >
          <Plus class="size-5" />
          {{ $t('merchant.onboarding.create') }}
        </Button>
      </template>
      <template #merchantTypeCode="{ row }">
        <Tag
          v-if="row.merchantTypeCode"
          :color="merchantTypeOption(row.merchantTypeCode)?.color"
        >
          {{
            merchantTypeOption(row.merchantTypeCode)?.label ??
            row.merchantTypeCode
          }}
        </Tag>
        <span v-else data-test="merchant-type-fallback">-</span>
      </template>
      <template #authenticationType="{ row }">
        <Tag
          v-if="row.authenticationType"
          :color="authenticationTypeOption(row.authenticationType)?.color"
        >
          {{
            authenticationTypeOption(row.authenticationType)?.label ??
            row.authenticationType
          }}
        </Tag>
        <span v-else data-test="authentication-type-fallback">-</span>
      </template>
      <template #marketCodes="{ row }">
        <div class="flex items-center justify-center gap-1 whitespace-nowrap">
          <Tag v-for="code in row.marketCodes.slice(0, 2)" :key="code">
            {{ marketLabel(code) }}
          </Tag>
          <Popover
            v-if="row.marketCodes.length > 2"
            placement="bottom"
            trigger="click"
          >
            <template #content>
              <div class="flex max-w-64 flex-wrap gap-1">
                <Tag v-for="code in row.marketCodes.slice(2)" :key="code">
                  {{ marketLabel(code) }}
                </Tag>
              </div>
            </template>
            <Tag class="cursor-pointer">...</Tag>
          </Popover>
          <span v-if="row.marketCodes.length === 0">-</span>
        </div>
      </template>
      <template #status="{ row }">
        <Switch
          v-if="row.status === 'ACTIVE' || row.status === 'DISABLED'"
          :checked="row.status === 'ACTIVE'"
          :checked-children="$t('merchant.status.ACTIVE')"
          :disabled="!canChangeStatus(row)"
          :loading="statusSwitchingMerchantId === row.merchantId"
          :un-checked-children="$t('merchant.status.DISABLED')"
          @change="(checked) => onStatusSwitch(Boolean(checked), row)"
        />
        <Tag v-else :color="statusOption(row.status)?.color">
          {{ statusOption(row.status)?.label ?? row.status }}
        </Tag>
      </template>
      <template #action="{ row }">
        <VbenTableAction
          :actions="[
            {
              auth: 'merchant:view',
              onClick: () => openDetail(row),
              text: $t('common.detail'),
            },
            {
              auth: 'merchant:amend',
              disabled: !canEdit(row),
              onClick: () => openEdit(row),
              text: $t('common.edit'),
            },
            ...(row.reviewPending
              ? [
                  {
                    auth: 'merchant:review',
                    disabled: !canReviewMerchant(),
                    onClick: () => openReview(row),
                    text: $t('merchant.detail.reviewAction'),
                  },
                ]
              : []),
          ]"
          :dropdown-actions="[]"
          align="center"
        />
      </template>
    </Grid>
  </Page>
</template>
