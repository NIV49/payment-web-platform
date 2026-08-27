<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon, Plus } from '@vben/icons';
import { useUserStore } from '@vben/stores';

import { MenuBadge } from '@vben-core/menu-ui';

import AccountDomainDictionaryAlert from '@payment/backoffice-runtime/components/account-domain-dictionary-alert';
import CommonStatusDictionaryAlert from '@payment/backoffice-runtime/components/common-status-dictionary-alert';
import {
  useAccountDomainDictionary,
  useCommonStatusDictionary,
} from '@payment/backoffice-runtime/composables';
import {
  createPlatformDirectoryRequestGuard,
  hasExactPlatformDirectoryResponseContext,
  splitPlatformDirectoryQuery,
  usePlatformDirectoryFilterSchema,
} from '@payment/backoffice-runtime/views/system/platform-directory';
import { Button, message } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { PERMISSION_CODES } from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import {
  deleteMenu,
  getMenuList,
  getPlatformMenuDirectory,
  SystemMenuApi,
} from '#/api/system/menu';
import { $t } from '#/locales';

import { hasPermissionDependencies } from '../permission-dependencies';
import { useColumns } from './data';
import Form from './modules/form.vue';
import { canPerformMenuAction } from './permission-contract';

const { hasAccessByCodes } = useAccess();
const userStore = useUserStore();
const canUsePlatformControlPlane =
  userStore.userInfo?.systemAdministrator === true;
const selectedDirectoryDomain = ref<'AGENT' | 'MERCHANT' | 'PLATFORM'>(
  'PLATFORM',
);
const commonStatus = useCommonStatusDictionary();
const commonStatusError = commonStatus.error;
const getStatusOptions = () => commonStatus.options.value;
const accountDomainDictionary = useAccountDomainDictionary({
  enabled: canUsePlatformControlPlane,
});
const accountDomainDictionaryError = accountDomainDictionary.error;
const getAccountDomainOptions = () => accountDomainDictionary.options.value;
const directoryRequestGuard = createPlatformDirectoryRequestGuard();
const canCreateMenu = computed(
  () =>
    isWritableDirectory() &&
    hasPermissionDependencies([PERMISSION_CODES.menuCreate], hasAccessByCodes),
);

function isWritableDirectory() {
  return (
    !canUsePlatformControlPlane || selectedDirectoryDomain.value === 'PLATFORM'
  );
}

function getGridColumns() {
  return useColumns(
    onActionClick,
    hasAccessByCodes,
    getStatusOptions,
    canUsePlatformControlPlane,
    getAccountDomainOptions,
    isWritableDirectory(),
  );
}

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: canUsePlatformControlPlane
    ? {
        schema: usePlatformDirectoryFilterSchema(
          getAccountDomainOptions,
          onAccountDomainChange,
          onTenantChange,
        ),
        submitOnChange: false,
      }
    : undefined,
  gridOptions: {
    columns: getGridColumns(),
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async (_params, formValues) => {
          if (!canUsePlatformControlPlane) return await getMenuList();
          const { target } = splitPlatformDirectoryQuery(formValues);
          if (!target) return [];
          const request = directoryRequestGuard.begin(target);
          const response = await getPlatformMenuDirectory(target);
          return directoryRequestGuard.isCurrent(request) &&
            hasExactPlatformDirectoryResponseContext(response, target)
            ? response
            : [];
        },
      },
    },
    rowConfig: {
      keyField: 'id',
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      zoom: true,
    },
    treeConfig: {
      parentField: 'pid',
      rowField: 'id',
      transform: false,
    },
  } as VxeTableGridOptions,
});

function onActionClick({
  code,
  row,
}: OnActionClickParams<SystemMenuApi.SystemMenu>) {
  switch (code) {
    case 'append': {
      onAppend(row);
      break;
    }
    case 'delete': {
      onDelete(row);
      break;
    }
    case 'edit': {
      onEdit(row);
      break;
    }
    default: {
      break;
    }
  }
}

function onRefresh() {
  gridApi.query();
}

function onAccountDomainChange(value: unknown) {
  if (value !== 'PLATFORM' && value !== 'MERCHANT' && value !== 'AGENT') return;
  selectedDirectoryDomain.value = value;
  invalidateDirectoryRequest();
  void gridApi.formApi.setFieldValue('tenantId', undefined);
  gridApi.setGridOptions({ columns: getGridColumns() });
}

function onTenantChange(_value: unknown) {
  invalidateDirectoryRequest();
}

function invalidateDirectoryRequest() {
  directoryRequestGuard.invalidate();
  void gridApi.grid.reloadData([]);
}
function onEdit(row: SystemMenuApi.SystemMenu) {
  if (!canPerformMenuAction(row, PERMISSION_CODES.menuUpdate, hasAccessByCodes))
    return;
  formDrawerApi.setData(row).open();
}
function onCreate() {
  if (!canCreateMenu.value) return;
  formDrawerApi.setData({}).open();
}
function onAppend(row: SystemMenuApi.SystemMenu) {
  if (!canPerformMenuAction(row, PERMISSION_CODES.menuCreate, hasAccessByCodes))
    return;
  formDrawerApi.setData({ pid: row.id }).open();
}

function onDelete(row: SystemMenuApi.SystemMenu) {
  if (!canPerformMenuAction(row, PERMISSION_CODES.menuDelete, hasAccessByCodes))
    return;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteMenu(row.id, row.rowVersion)
    .then(() => {
      message.success({
        content: $t('ui.actionMessage.deleteSuccess', [row.name]),
        key: 'action_process_msg',
      });
      onRefresh();
    })
    .catch((error) => {
      hideLoading();
      if (isOptimisticLockConflict(error)) onRefresh();
    });
}
</script>
<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
    <CommonStatusDictionaryAlert
      :error="commonStatusError"
      :reload="commonStatus.reload"
    />
    <AccountDomainDictionaryAlert
      :error="accountDomainDictionaryError"
      :reload="accountDomainDictionary.reload"
    />
    <Grid :table-title="$t('system.menu.list')">
      <template #toolbar-tools>
        <Button
          v-if="canCreateMenu"
          v-access:code="PERMISSION_CODES.menuCreate"
          type="primary"
          @click="onCreate"
        >
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('system.menu.name')]) }}
        </Button>
      </template>
      <template #title="{ row }">
        <div class="flex w-full items-center gap-1">
          <div class="size-5 shrink-0">
            <IconifyIcon
              v-if="row.type === 'button'"
              icon="carbon:security"
              class="size-full"
            />
            <IconifyIcon
              v-else-if="row.meta?.icon"
              :icon="row.meta?.icon || 'carbon:circle-dash'"
              class="size-full"
            />
          </div>
          <span class="flex-auto">{{ $t(row.meta?.title) }}</span>
          <div class="items-center justify-end"></div>
        </div>
        <MenuBadge
          v-if="row.meta?.badgeType"
          class="menu-badge"
          :badge="row.meta.badge"
          :badge-type="row.meta.badgeType"
          :badge-variants="row.meta.badgeVariants"
        />
      </template>
    </Grid>
  </Page>
</template>
<style lang="scss" scoped>
.menu-badge {
  top: 50%;
  right: 0;
  transform: translateY(-50%);

  & > :deep(div) {
    padding-top: 0;
    padding-bottom: 0;
  }
}
</style>
