<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { SystemRoleApi } from '#/api';

import { computed } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { Button, message, Modal } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteRole,
  getRoleList,
  PERMISSION_CODES,
  updateRoleStatus,
} from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { $t } from '#/locales';

import { useColumns, useGridFormSchema } from './data';
import {
  canMutateRole,
  ROLE_LIST_SEARCH_BEHAVIOR,
} from './grant-contract';
import Form from './modules/form.vue';

const { hasAccessByCodes } = useAccess();

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const canCreateRole = computed(
  () =>
    hasAccessByCodes([PERMISSION_CODES.roleCreate]) &&
    hasAccessByCodes([PERMISSION_CODES.menuView]),
);

function canEditRole(row: SystemRoleApi.SystemRole) {
  return (
    canMutateRole(row) &&
    hasAccessByCodes([PERMISSION_CODES.roleView]) &&
    hasAccessByCodes([PERMISSION_CODES.roleUpdate]) &&
    hasAccessByCodes([PERMISSION_CODES.menuView]) &&
    hasAccessByCodes([PERMISSION_CODES.roleGrantUpdate])
  );
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    fieldMappingTime: [['createTime', ['startTime', 'endTime']]],
    schema: useGridFormSchema(),
    submitOnChange: ROLE_LIST_SEARCH_BEHAVIOR.submitOnChange,
  },
  gridOptions: {
    columns: useColumns(
      onActionClick,
      onStatusChange,
      canMutateRole,
      canEditRole,
    ),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getRoleList({
            page: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
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
  if (!canMutateRole(row)) return false;
  try {
    const statusLabel = $t(
      newStatus === 1 ? 'common.enabled' : 'common.disabled',
    );
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

function onDelete(row: SystemRoleApi.SystemRole) {
  if (!canMutateRole(row)) return;
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

function onCreate() {
  formDrawerApi.setData({}).open();
}
</script>
<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
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
