<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { SystemDeptApi } from '#/api/system/dept';

import { computed } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { Button, message } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { PERMISSION_CODES } from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { deleteDept, getDeptList } from '#/api/system/dept';
import { $t } from '#/locales';

import { hasPermissionDependencies } from '../permission-dependencies';
import { useColumns } from './data';
import Form from './modules/form.vue';
import { canPerformDepartmentAction } from './selection-contract';

const { hasAccessByCodes } = useAccess();
const canCreateDepartment = computed(() =>
  hasPermissionDependencies(
    [PERMISSION_CODES.departmentCreate],
    hasAccessByCodes,
  ),
);

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

/**
 * 编辑部门
 * @param row
 */
function onEdit(row: SystemDeptApi.SystemDept) {
  if (
    !canPerformDepartmentAction(
      row,
      PERMISSION_CODES.departmentUpdate,
      hasAccessByCodes,
    )
  )
    return;
  formModalApi.setData(row).open();
}

/**
 * 添加下级部门
 * @param row
 */
function onAppend(row: SystemDeptApi.SystemDept) {
  if (
    !canPerformDepartmentAction(
      row,
      PERMISSION_CODES.departmentCreate,
      hasAccessByCodes,
    )
  )
    return;
  formModalApi.setData({ pid: row.id }).open();
}

/**
 * 创建新部门
 */
function onCreate() {
  if (!canCreateDepartment.value) return;
  formModalApi.setData(null).open();
}

/**
 * 删除部门
 * @param row
 */
function onDelete(row: SystemDeptApi.SystemDept) {
  if (
    !canPerformDepartmentAction(
      row,
      PERMISSION_CODES.departmentDelete,
      hasAccessByCodes,
    )
  )
    return;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteDept(row.id, row.rowVersion)
    .then(() => {
      message.success({
        content: $t('ui.actionMessage.deleteSuccess', [row.name]),
        key: 'action_process_msg',
      });
      refreshGrid();
    })
    .catch((error) => {
      hideLoading();
      if (isOptimisticLockConflict(error)) refreshGrid();
    });
}

/**
 * 表格操作按钮的回调函数
 */
function onActionClick({
  code,
  row,
}: OnActionClickParams<SystemDeptApi.SystemDept>) {
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
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridEvents: {},
  gridOptions: {
    columns: useColumns(onActionClick, hasAccessByCodes),
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async (_params) => {
          return await getDeptList();
        },
      },
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

/**
 * 刷新表格
 */
function refreshGrid() {
  gridApi.query();
}
</script>
<template>
  <Page auto-content-height>
    <FormModal @success="refreshGrid" />
    <Grid :table-title="$t('system.dept.list')">
      <template #toolbar-tools>
        <Button
          v-if="canCreateDepartment"
          v-access:code="PERMISSION_CODES.departmentCreate"
          type="primary"
          @click="onCreate"
        >
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('system.dept.name')]) }}
        </Button>
      </template>
    </Grid>
  </Page>
</template>
