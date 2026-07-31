<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SystemDeptApi, SystemUserApi } from '#/api';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, Tree, useVbenDrawer } from '@vben/common-ui';
import { Plus, RotateCw, X } from '@vben/icons';

import {
  Alert,
  Button,
  Card,
  InputSearch,
  message,
  Modal,
  Spin,
  Tooltip,
} from 'antdv-next';

import { useVbenVxeGrid, VbenTableAction } from '#/adapter/vxe-table';
import {
  deleteUser,
  getDeptList,
  getUserList,
  PERMISSION_CODES,
  updateUserStatus,
} from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { $t } from '#/locales';

import { useColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import {
  hasAllAccessCodes,
  USER_CREATE_PERMISSION_CODES,
  USER_EDIT_PERMISSION_CODES,
} from './permission-contract';
import {
  buildUserListQuery,
  filterDepartmentTree,
  loadDepartmentTree,
  resolveDepartmentId,
  USER_LIST_SEARCH_BEHAVIOR,
} from './query-contract';

const deptList = ref<SystemDeptApi.SystemDept[]>([]);
const inputSearchValue = ref('');
const selectedDeptId = ref<string>();
const departmentLoadFailed = ref(false);
const departmentLoading = ref(false);
const { hasAccessByCodes } = useAccess();
const canViewDepartments = computed(() =>
  hasAccessByCodes([PERMISSION_CODES.departmentView]),
);
const filteredDeptList = computed(() =>
  filterDepartmentTree(deptList.value, inputSearchValue.value),
);

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    fieldMappingTime: [['createTime', ['startTime', 'endTime']]],
    schema: useGridFormSchema(),
    submitOnChange: USER_LIST_SEARCH_BEHAVIOR.submitOnChange,
  },
  gridOptions: {
    columns: useColumns(onStatusChange),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getUserList(
            buildUserListQuery(page, formValues, selectedDeptId.value),
          );
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
  } as VxeTableGridOptions<SystemUserApi.SystemUser>,
});

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
  row: SystemUserApi.SystemUser,
) {
  try {
    const statusLabel = $t(
      newStatus === 1 ? 'common.enabled' : 'common.disabled',
    );
    const confirmed = await confirm(
      $t('system.statusChangeConfirm', [row.name, statusLabel]),
      $t('system.statusChangeTitle'),
    );
    if (!confirmed) return false;
    const result = await updateUserStatus(row.id, {
      status: newStatus as 0 | 1,
      userVersion: row.userVersion,
    });
    row.userVersion = result.userVersion;
    return true;
  } catch (error) {
    if (isOptimisticLockConflict(error)) onRefresh();
    return false;
  }
}

function onEdit(row: SystemUserApi.SystemUser) {
  formDrawerApi.setData(row).open();
}

function onDetail(row: SystemUserApi.SystemUser) {
  detailDrawerApi.setData(row).open();
}

function onDelete(row: SystemUserApi.SystemUser) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteUser(row.id, row.userVersion)
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

function canCreateUser() {
  return hasAllAccessCodes(USER_CREATE_PERMISSION_CODES, hasAccessByCodes);
}

function canEditUser() {
  return hasAllAccessCodes(USER_EDIT_PERMISSION_CODES, hasAccessByCodes);
}

async function loadDeptList() {
  if (!canViewDepartments.value || departmentLoading.value) return;

  departmentLoading.value = true;
  departmentLoadFailed.value = false;
  try {
    const { departments, error } = await loadDepartmentTree(getDeptList);
    deptList.value = departments;
    departmentLoadFailed.value = Boolean(error);
    if (error) console.error('Failed to load department list:', error);
  } finally {
    departmentLoading.value = false;
  }
}

function selectDept(selection: unknown) {
  const departmentId = resolveDepartmentId(selection);
  if (!departmentId) return;

  selectedDeptId.value = departmentId;
  gridApi.query();
}

function clearDeptFilter() {
  selectedDeptId.value = undefined;
  gridApi.query();
}

onMounted(() => {
  loadDeptList();
});
</script>
<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
    <DetailDrawer @success="onRefresh" />
    <div class="flex size-full">
      <Card v-if="canViewDepartments" class="w-1/6">
        <div class="mb-2 flex items-center gap-1">
          <InputSearch
            v-model:value="inputSearchValue"
            :placeholder="$t('system.user.placeholder')"
          />
          <Tooltip :title="$t('system.user.clearDeptFilter')">
            <Button
              :aria-label="$t('system.user.clearDeptFilter')"
              :disabled="!selectedDeptId"
              type="text"
              @click="clearDeptFilter"
            >
              <X class="size-4" />
            </Button>
          </Tooltip>
        </div>
        <Alert
          v-if="departmentLoadFailed"
          show-icon
          :title="$t('system.user.departmentLoadFailed')"
          type="error"
        >
          <template #action>
            <Tooltip :title="$t('system.user.retryDepartmentLoad')">
              <Button
                :aria-label="$t('system.user.retryDepartmentLoad')"
                size="small"
                type="text"
                @click="loadDeptList"
              >
                <RotateCw class="size-4" />
              </Button>
            </Tooltip>
          </template>
        </Alert>
        <Spin v-else :spinning="departmentLoading">
          <Tree
            label-field="name"
            value-field="id"
            :model-value="selectedDeptId"
            :tree-data="filteredDeptList"
            :default-expanded-level="2"
            @select="selectDept"
          />
        </Spin>
      </Card>

      <div :class="canViewDepartments ? 'ml-4 w-5/6' : 'w-full'">
        <Grid :table-title="$t('system.user.list')">
          <template #toolbar-tools>
            <Button
              v-if="canCreateUser()"
              v-access:code="PERMISSION_CODES.userCreate"
              type="primary"
              @click="onCreate"
            >
              <Plus class="size-5" />
              {{ $t('ui.actionTitle.create', [$t('system.user.name')]) }}
            </Button>
          </template>
          <template #action="{ row }">
            <VbenTableAction
              :actions="[
                {
                  text: $t('common.detail'),
                  icon: 'lucide:eye',
                  auth: PERMISSION_CODES.userView,
                  onClick: () => onDetail(row),
                },
                {
                  text: $t('common.edit'),
                  icon: 'lucide:edit',
                  auth: PERMISSION_CODES.userUpdate,
                  ifShow: canEditUser,
                  onClick: () => onEdit(row),
                },
              ]"
              :dropdown-actions="[
                {
                  text: $t('common.delete'),
                  icon: 'lucide:trash-2',
                  danger: true,
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [row.name]),
                    confirm: () => onDelete(row),
                  },
                  auth: PERMISSION_CODES.userDelete,
                },
              ]"
              align="center"
            />
          </template>
        </Grid>
      </div>
    </div>
  </Page>
</template>
