<script lang="ts" setup>
import type { VxeTableGridOptions } from '@payment/backoffice-runtime/adapter/vxe-table';
import type {
  SystemDeptApi,
  SystemUserApi,
} from '@payment/backoffice-runtime/api';

import type { UserDirectoryTarget } from './directory-response-scope';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, Tree, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { IconifyIcon, Plus, RotateCw, X } from '@vben/icons';
import { useUserStore } from '@vben/stores';

import {
  useVbenVxeGrid,
  VbenTableAction,
} from '@payment/backoffice-runtime/adapter/vxe-table';
import {
  deleteUser,
  getDeptList,
  getUserList,
  PERMISSION_CODES,
  resetUserPassword,
  updateUser,
  updateUserStatus,
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
  Alert,
  Button,
  Card,
  InputPassword,
  InputSearch,
  message,
  Modal,
  Spin,
  Tooltip,
} from 'antdv-next';

import { createDateRangeFilterCodec } from '../date-range-codec';
import { createPlatformDirectoryRequestGuard } from '../platform-directory';
import { useColumns, useGridFormSchema } from './data';
import { hasExactUserDirectoryResponseScope } from './directory-response-scope';
import AssignRole from './modules/assign-role.vue';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import {
  generateLocalPassword,
  isLocalPasswordResetMode,
  isValidLocalPassword,
} from './password-policy';
import {
  hasAllAccessCodes,
  USER_CREATE_PERMISSION_CODES,
  USER_DELETE_PERMISSION_CODES,
  USER_EDIT_PERMISSION_CODES,
  USER_ROLE_ASSIGNMENT_PERMISSION_CODES,
  USER_STATUS_PERMISSION_CODES,
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
const selectedDirectoryDomain = ref<'AGENT' | 'MERCHANT' | 'PLATFORM'>(
  'PLATFORM',
);
const departmentLoadFailed = ref(false);
const departmentLoaded = ref(false);
const departmentLoading = ref(false);
const { hasAccessByCodes } = useAccess();
const commonStatus = useCommonStatusDictionary();
const commonStatusError = commonStatus.error;
const getStatusOptions = () => commonStatus.options.value;
const userStore = useUserStore();
const isPlatformDeployment =
  getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM';
const canUsePlatformControlPlane =
  isPlatformDeployment && userStore.userInfo?.systemAdministrator === true;
const accountDomains = useAccountDomainDictionary({
  enabled: canUsePlatformControlPlane,
});
const accountDomainError = accountDomains.error;
const getAccountDomainOptions = () => accountDomains.options.value;
const directoryRequestGuard = createPlatformDirectoryRequestGuard();
const localPasswordResetAvailable = isLocalPasswordResetMode({
  explicitMode: import.meta.env.VITE_AUTH_MODE,
  prod: import.meta.env.PROD,
});
const canViewDepartments = computed(
  () =>
    hasAccessByCodes([PERMISSION_CODES.departmentView]) &&
    (!canUsePlatformControlPlane ||
      selectedDirectoryDomain.value === 'PLATFORM'),
);
const filteredDeptList = computed(() =>
  canViewDepartments.value
    ? filterDepartmentTree(deptList.value, inputSearchValue.value)
    : [],
);
const passwordResetTarget = ref<SystemUserApi.SystemUser>();
const passwordResetValue = ref('');
const passwordResetSubmitting = ref(false);
const passwordResetIsValid = computed(() =>
  isValidLocalPassword(passwordResetValue.value),
);
const passwordResetTitle = computed(() =>
  passwordResetTarget.value
    ? $t('system.user.resetPasswordTitle', [passwordResetTarget.value.name])
    : $t('system.user.resetPassword'),
);

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const [AssignRoleDrawer, assignRoleDrawerApi] = useVbenDrawer({
  connectedComponent: AssignRole,
  destroyOnClose: true,
});

const [PasswordResetModal, passwordResetModalApi] = useVbenModal({
  async onConfirm() {
    const target = passwordResetTarget.value;
    if (
      !target ||
      !passwordResetIsValid.value ||
      passwordResetSubmitting.value
    ) {
      return;
    }

    const password = passwordResetValue.value;
    passwordResetSubmitting.value = true;
    passwordResetModalApi.lock();
    try {
      if (isCrossDomainUser(target)) {
        if (
          (target.accountDomain !== 'AGENT' &&
            target.accountDomain !== 'MERCHANT') ||
          !target.tenantId
        ) {
          throw new Error('Invalid cross-domain password reset target binding');
        }
        await resetUserPassword(target.id, {
          accountDomain: target.accountDomain,
          credentialVersion: target.credentialVersion,
          password,
          tenantId: target.tenantId,
        });
      } else {
        await resetUserPassword(target.id, {
          credentialVersion: target.credentialVersion,
          password,
        });
      }
      message.success({
        content: $t('system.user.resetPasswordSuccess', [target.name]),
        key: 'action_process_msg',
      });
      passwordResetModalApi.close();
      onRefresh();
    } catch (error) {
      if (isOptimisticLockConflict(error)) onRefresh();
    } finally {
      passwordResetValue.value = '';
      passwordResetSubmitting.value = false;
      passwordResetModalApi.lock(false);
    }
  },
  onOpenChange(open) {
    if (open) return;
    passwordResetValue.value = '';
    passwordResetTarget.value = undefined;
  },
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    codec: createDateRangeFilterCodec('createTime'),
    schema: useGridFormSchema(
      canUsePlatformControlPlane,
      onAccountDomainChange,
      getStatusOptions,
      getAccountDomainOptions,
    ),
    submitOnChange: USER_LIST_SEARCH_BEHAVIOR.submitOnChange,
  },
  gridOptions: {
    columns: useColumns(
      onStatusChange,
      canChangeUserStatus,
      canUsePlatformControlPlane,
      getStatusOptions,
      getAccountDomainOptions,
    ),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const query = buildUserListQuery(
            page,
            formValues,
            selectedDeptId.value,
          );
          if (!canUsePlatformControlPlane)
            return await getUserList(query, false);

          const target = userDirectoryTarget(query);
          const request =
            target.accountDomain === 'PLATFORM'
              ? directoryRequestGuard.begin({ accountDomain: 'PLATFORM' })
              : directoryRequestGuard.begin({
                  accountDomain: target.accountDomain,
                  tenantId: target.tenantId ?? '*',
                });
          const response = await getUserList(query, true);
          return directoryRequestGuard.isCurrent(request) &&
            hasExactUserDirectoryResponseScope(response?.items, target)
            ? response
            : { items: [], total: 0 };
        },
      },
    },
    rowConfig: {
      keyField: canUsePlatformControlPlane ? 'membershipId' : 'id',
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
  if (!canChangeUserStatus(row)) return false;
  try {
    const statusLabel = commonStatus.getLabel(newStatus as 0 | 1);
    const confirmed = await confirm(
      $t('system.statusChangeConfirm', [row.name, statusLabel]),
      $t('system.statusChangeTitle'),
    );
    if (!confirmed) return false;
    if (isCrossDomainAdministrator(row)) {
      await updateUser(row.id, {
        accountDomain: row.accountDomain,
        credentialVersion: row.credentialVersion,
        deptId: row.deptId,
        identityVersion: row.identityVersion,
        name: row.name,
        roleIds: row.roleIds,
        status: newStatus as 0 | 1,
        tenantId: row.tenantId,
        username: row.username,
        userVersion: row.userVersion,
      });
      row.userVersion += 1;
      row.identityVersion += 1;
    } else {
      const result = await updateUserStatus(row.id, {
        status: newStatus as 0 | 1,
        userVersion: row.userVersion,
      });
      row.userVersion = result.userVersion;
    }
    return true;
  } catch (error) {
    if (isOptimisticLockConflict(error)) onRefresh();
    return false;
  }
}

function onEdit(row: SystemUserApi.SystemUser) {
  if (!canEditUser(row)) return;
  formDrawerApi.setData(row).open();
}

function onDetail(row: SystemUserApi.SystemUser) {
  detailDrawerApi.setData(row).open();
}

function onAssignRole(row: SystemUserApi.SystemUser) {
  if (!canAssignUserRoles(row)) return;
  assignRoleDrawerApi.setData(row).open();
}

function onDelete(row: SystemUserApi.SystemUser) {
  if (!canDeleteUser(row)) return;
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

function onResetPassword(row: SystemUserApi.SystemUser) {
  if (!canResetUserPassword(row)) return;
  passwordResetTarget.value = row;
  regeneratePassword();
  passwordResetModalApi.open();
}

function regeneratePassword() {
  if (passwordResetSubmitting.value) return;
  passwordResetValue.value = generateLocalPassword();
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

function canEditUser(row?: SystemUserApi.SystemUser) {
  return (
    !isReadOnlyCrossDomainUser(row) &&
    hasAllAccessCodes(USER_EDIT_PERMISSION_CODES, hasAccessByCodes)
  );
}

function canDeleteUser(row?: SystemUserApi.SystemUser) {
  return (
    !isCrossDomainUser(row) &&
    hasAllAccessCodes(USER_DELETE_PERMISSION_CODES, hasAccessByCodes)
  );
}

function canChangeUserStatus(row?: SystemUserApi.SystemUser) {
  return (
    !isReadOnlyCrossDomainUser(row) &&
    hasAllAccessCodes(USER_STATUS_PERMISSION_CODES, hasAccessByCodes)
  );
}

function canResetUserPassword(row?: SystemUserApi.SystemUser) {
  return (
    (!isCrossDomainUser(row) || canUsePlatformControlPlane) &&
    localPasswordResetAvailable &&
    userStore.userInfo?.systemAdministrator === true &&
    hasAccessByCodes([PERMISSION_CODES.userUpdate])
  );
}

function canAssignUserRoles(row?: SystemUserApi.SystemUser) {
  return (
    !isCrossDomainUser(row) &&
    hasAllAccessCodes(USER_ROLE_ASSIGNMENT_PERMISSION_CODES, hasAccessByCodes)
  );
}

function isCrossDomainAdministrator(row?: SystemUserApi.SystemUser) {
  return isCrossDomainUser(row) && row?.systemAdministrator === true;
}

function isCrossDomainUser(row?: SystemUserApi.SystemUser) {
  return Boolean(
    canUsePlatformControlPlane &&
    row?.accountDomain &&
    row.accountDomain !== 'PLATFORM',
  );
}

function isReadOnlyCrossDomainUser(row?: SystemUserApi.SystemUser) {
  return isCrossDomainUser(row) && !isCrossDomainAdministrator(row);
}

async function loadDeptList() {
  if (!canViewDepartments.value || departmentLoading.value) return;

  departmentLoading.value = true;
  departmentLoadFailed.value = false;
  try {
    const { departments, error } = await loadDepartmentTree(getDeptList);
    deptList.value = departments;
    departmentLoadFailed.value = Boolean(error);
    departmentLoaded.value = !error;
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

function onAccountDomainChange(value: unknown) {
  if (value !== 'PLATFORM' && value !== 'MERCHANT' && value !== 'AGENT') {
    return;
  }

  selectedDirectoryDomain.value = value;
  invalidateDirectoryRequest();
  selectedDeptId.value = undefined;
  inputSearchValue.value = '';
  if (value === 'PLATFORM' && !departmentLoaded.value) {
    void loadDeptList();
  }
}

function invalidateDirectoryRequest() {
  directoryRequestGuard.invalidate();
  void gridApi.grid.reloadData([]);
}

function userDirectoryTarget(
  query: SystemUserApi.UserListQuery,
): UserDirectoryTarget {
  const accountDomain = query.accountDomain ?? 'PLATFORM';
  return query.tenantId
    ? { accountDomain, tenantId: query.tenantId }
    : { accountDomain };
}

onMounted(() => {
  loadDeptList();
});
</script>
<template>
  <Page auto-content-height>
    <FormDrawer @success="onRefresh" />
    <DetailDrawer @success="onRefresh" />
    <AssignRoleDrawer @success="onRefresh" />
    <CommonStatusDictionaryAlert
      :error="commonStatusError"
      :reload="commonStatus.reload"
    />
    <AccountDomainDictionaryAlert
      :error="accountDomainError"
      :reload="accountDomains.reload"
    />
    <PasswordResetModal
      :confirm-disabled="passwordResetSubmitting || !passwordResetIsValid"
      :title="passwordResetTitle"
    >
      <div class="px-1 py-3">
        <div class="mb-2 flex items-center gap-1 text-sm font-medium">
          <span class="text-destructive">*</span>
          <label for="local-password-reset">{{
            $t('system.user.password')
          }}</label>
          <Tooltip :title="$t('system.user.passwordPolicy')">
            <IconifyIcon
              class="size-4 text-muted-foreground"
              icon="lucide:circle-help"
            />
          </Tooltip>
        </div>
        <InputPassword
          id="local-password-reset"
          v-model:value="passwordResetValue"
          :disabled="passwordResetSubmitting"
          :maxlength="24"
          autocomplete="new-password"
          size="large"
        />
      </div>
      <template #prepend-footer>
        <Button
          danger
          :disabled="passwordResetSubmitting"
          :loading="passwordResetSubmitting"
          type="primary"
          @click="regeneratePassword"
        >
          <RotateCw class="mr-1 size-4" />
          {{ $t('system.user.regeneratePassword') }}
        </Button>
      </template>
    </PasswordResetModal>
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
              {{ $t('ui.actionTitle.create', [$t('system.user.entity')]) }}
            </Button>
          </template>
          <template #action="{ row }">
            <VbenTableAction
              :actions="[
                {
                  text: $t('common.detail'),
                  auth: PERMISSION_CODES.userView,
                  onClick: () => onDetail(row),
                },
                {
                  text: $t('common.edit'),
                  auth: PERMISSION_CODES.userUpdate,
                  ifShow: () => canEditUser(row),
                  onClick: () => onEdit(row),
                },
                {
                  text: $t('system.user.assignRole'),
                  auth: PERMISSION_CODES.userAssignRole,
                  ifShow: () => canAssignUserRoles(row),
                  onClick: () => onAssignRole(row),
                },
                {
                  text: $t('system.user.resetPassword'),
                  auth: PERMISSION_CODES.userUpdate,
                  ifShow: () => canResetUserPassword(row),
                  onClick: () => onResetPassword(row),
                },
                {
                  text: $t('common.delete'),
                  danger: true,
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [row.name]),
                    confirm: () => onDelete(row),
                  },
                  auth: PERMISSION_CODES.userDelete,
                  ifShow: () => canDeleteUser(row),
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
