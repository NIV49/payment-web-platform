<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '@payment/backoffice-runtime/adapter/vxe-table';
import type { SystemRoleApi } from '@payment/backoffice-runtime/api';
import type { AccountDomain } from '@payment/backoffice-runtime/deployment-internal';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { Plus } from '@vben/icons';
import { useUserStore } from '@vben/stores';

import { useVbenVxeGrid } from '@payment/backoffice-runtime/adapter/vxe-table';
import {
  deleteRole,
  getPlatformRoleDirectory,
  getRoleList,
  PERMISSION_CODES,
  updateRoleStatus,
} from '@payment/backoffice-runtime/api';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import AccountDomainDictionaryAlert from '@payment/backoffice-runtime/components/account-domain-dictionary-alert';
import CommonStatusDictionaryAlert from '@payment/backoffice-runtime/components/common-status-dictionary-alert';
import {
  useAccountDomainDictionary,
  useCommonStatusDictionary,
} from '@payment/backoffice-runtime/composables';
import { getInstalledBackofficeDeployment } from '@payment/backoffice-runtime/deployment-internal';
import { $t } from '@payment/backoffice-runtime/locales';
import {
  createPlatformDirectoryRequestGuard,
  hasExactPlatformDirectoryResponseContext,
  splitPlatformDirectoryQuery,
  usePlatformDirectoryFilterSchema,
} from '@payment/backoffice-runtime/views/system/platform-directory';
import { Button, message, Modal } from 'antdv-next';

import { createDateRangeFilterCodec } from '../date-range-codec';
import { useColumns, useGridFormSchema } from './data';
import {
  canConfigureRole,
  canMutateRole,
  hasPermissionDependencies,
  ROLE_LIST_SEARCH_BEHAVIOR,
} from './grant-contract';
import AssignUser from './modules/assign-user.vue';
import Form from './modules/form.vue';

const { hasAccessByCodes } = useAccess();
const userStore = useUserStore();
const canUsePlatformControlPlane =
  getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM' &&
  userStore.userInfo?.systemAdministrator === true;
const selectedDirectoryDomain = ref<AccountDomain>('PLATFORM');
const commonStatus = useCommonStatusDictionary();
const commonStatusError = commonStatus.error;
const getStatusOptions = () => commonStatus.options.value;
const accountDomainDictionary = useAccountDomainDictionary({
  enabled: canUsePlatformControlPlane,
});
const accountDomainDictionaryError = accountDomainDictionary.error;
const getAccountDomainOptions = () => accountDomainDictionary.options.value;
const directoryRequestGuard = createPlatformDirectoryRequestGuard();

function isWritableDirectory() {
  return (
    !canUsePlatformControlPlane || selectedDirectoryDomain.value === 'PLATFORM'
  );
}

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [AssignUserDrawer, assignUserDrawerApi] = useVbenDrawer({
  connectedComponent: AssignUser,
  destroyOnClose: true,
});

const canCreateRole = computed(
  () =>
    isWritableDirectory() &&
    canConfigureRole(
      userStore.userInfo?.systemAdministrator === true,
      PERMISSION_CODES.roleCreate,
      hasAccessByCodes,
    ),
);

function getGridColumns() {
  const writableDirectory = isWritableDirectory();
  return useColumns(
    onActionClick,
    writableDirectory ? onStatusChange : undefined,
    canChangeRoleStatus,
    canEditRole,
    canAssignUsers,
    canDeleteRole,
    getStatusOptions,
    canUsePlatformControlPlane,
    getAccountDomainOptions,
    writableDirectory,
  );
}

function canEditRole(row: SystemRoleApi.SystemRole) {
  return (
    canMutateRole(row) &&
    canConfigureRole(
      userStore.userInfo?.systemAdministrator === true,
      PERMISSION_CODES.roleUpdate,
      hasAccessByCodes,
    )
  );
}

function canDeleteRole(row: SystemRoleApi.SystemRole) {
  return (
    canMutateRole(row) &&
    hasPermissionDependencies([PERMISSION_CODES.roleDelete], hasAccessByCodes)
  );
}

function canChangeRoleStatus(row: SystemRoleApi.SystemRole) {
  return (
    canMutateRole(row) &&
    hasPermissionDependencies([PERMISSION_CODES.roleUpdate], hasAccessByCodes)
  );
}

function canAssignUsers(row: SystemRoleApi.SystemRole) {
  return (
    canMutateRole(row) &&
    hasPermissionDependencies(
      [PERMISSION_CODES.userAssignRole, PERMISSION_CODES.roleView],
      hasAccessByCodes,
    )
  );
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    codec: createDateRangeFilterCodec('createTime'),
    schema: useGridFormSchema(
      getStatusOptions,
      canUsePlatformControlPlane
        ? usePlatformDirectoryFilterSchema(
            getAccountDomainOptions,
            onAccountDomainChange,
            onTenantChange,
          )
        : [],
    ),
    submitOnChange: ROLE_LIST_SEARCH_BEHAVIOR.submitOnChange,
  },
  gridOptions: {
    columns: getGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const pageQuery = {
            page: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          };
          if (!canUsePlatformControlPlane) return await getRoleList(pageQuery);

          const { query, target } = splitPlatformDirectoryQuery(pageQuery);
          if (!target) return { items: [], total: 0 };
          const request = directoryRequestGuard.begin(target);
          const response = await getPlatformRoleDirectory(query, target);
          return directoryRequestGuard.isCurrent(request) &&
            hasExactPlatformDirectoryResponseContext(response?.items, target)
            ? response
            : { items: [], total: 0 };
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
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<SystemRoleApi.SystemRole>,
});

function onActionClick(e: OnActionClickParams<SystemRoleApi.SystemRole>) {
  switch (e.code) {
    case 'assignUser': {
      onAssignUser(e.row);
      break;
    }
    case 'delete': {
      onDelete(e.row);
      break;
    }
    case 'edit': {
      onEdit(e.row);
      break;
    }
  }
}

/**
 * 将Antd的Modal.confirm封装为promise，方便在异步函数中调用。
 * @param content 提示内容
 * @param title 提示标题
 */
function confirm(content: string, title: string) {
  return new Promise<boolean>((resolve) => {
    Modal.confirm({
      content,
      onCancel() {
        resolve(false);
      },
      onOk() {
        resolve(true);
      },
      title,
    });
  });
}

/**
 * 状态开关即将改变
 * @param newStatus 期望改变的状态值
 * @param row 行数据
 * @returns 返回false则中止改变，返回其他值（undefined、true）则允许改变
 */
async function onStatusChange(
  newStatus: number,
  row: SystemRoleApi.SystemRole,
) {
  if (!canChangeRoleStatus(row)) return false;
  try {
    const statusLabel = commonStatus.getLabel(newStatus as 0 | 1);
    const confirmed = await confirm(
      $t('system.statusChangeConfirm', [row.name, statusLabel]),
      $t('system.statusChangeTitle'),
    );
    if (!confirmed) return false;
    await updateRoleStatus(row.id, {
      expectedVersion: row.rowVersion,
      status: newStatus as 0 | 1,
    });
    row.rowVersion += 1;
    return true;
  } catch (error) {
    if (isOptimisticLockConflict(error)) onRefresh();
    return false;
  }
}

function onEdit(row: SystemRoleApi.SystemRole) {
  if (!canEditRole(row)) return;
  formDrawerApi.setData(row).open();
}

function onAssignUser(row: SystemRoleApi.SystemRole) {
  if (!canAssignUsers(row)) return;
  assignUserDrawerApi.setData(row).open();
}

function onDelete(row: SystemRoleApi.SystemRole) {
  if (!canDeleteRole(row)) return;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteRole(row.id, row.rowVersion)
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

function onCreate() {
  if (!canCreateRole.value) return;
  formDrawerApi.setData({}).open();
}
</script>
<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
    <AssignUserDrawer />
    <CommonStatusDictionaryAlert
      :error="commonStatusError"
      :reload="commonStatus.reload"
    />
    <AccountDomainDictionaryAlert
      :error="accountDomainDictionaryError"
      :reload="accountDomainDictionary.reload"
    />
    <Grid :table-title="$t('system.role.list')">
      <template #toolbar-tools>
        <Button v-if="canCreateRole" type="primary" @click="onCreate">
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('system.role.name')]) }}
        </Button>
      </template>
    </Grid>
  </Page>
</template>
