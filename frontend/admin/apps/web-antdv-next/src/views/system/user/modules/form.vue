<script lang="ts" setup>
import type { UserFormValues } from './form-contract';

import type { SystemRoleApi } from '#/api/system/role';
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { useDebounceFn } from '@vueuse/core';
import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createUser,
  getRoleList,
  hasExplicitRoleIds,
  PERMISSION_CODES,
  updateUser,
} from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { $t } from '#/locales';

import { useFormSchema } from '../data';
import { hasAllAccessCodes } from '../permission-contract';
import { toMembershipUpdateParams, toUserCreateParams } from './form-contract';
import {
  buildRoleAssignmentOptions,
  mergeRoleSearchResults,
  resolveRoleAssignmentIds,
  tryLoadRoleAssignmentCatalog,
} from './role-assignment';

const emits = defineEmits(['success']);
const formData = ref<SystemUserApi.SystemUser>();
const { hasAccessByCodes } = useAccess();
const userStore = useUserStore();

const canAssignRoles = computed(() =>
  hasAllAccessCodes(
    [PERMISSION_CODES.userAssignRole, PERMISSION_CODES.roleView],
    hasAccessByCodes,
  ),
);
const canRemoveDisabledRoles = computed(() =>
  Boolean(userStore.userInfo?.systemAdministrator === true),
);
const id = ref<string>();
const isEditing = computed(() => Boolean(id.value));
const canEditIdentity = computed(
  () => isEditing.value && userStore.userInfo?.systemAdministrator === true,
);
const currentDepartmentId = computed(() => formData.value?.deptId);
const roleCatalog = ref<SystemRoleApi.SystemRole[]>([]);
const roleAssignmentReady = ref(false);
const roleSearchLoading = ref(false);
let drawerLoadSequence = 0;
let roleSearchSequence = 0;
const roleOptions = computed(() =>
  buildRoleAssignmentOptions(
    roleCatalog.value,
    formData.value?.roleIds ?? [],
    formData.value?.roleNames ?? [],
    canRemoveDisabledRoles.value,
  ),
);

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(
    canAssignRoles,
    canEditIdentity,
    currentDepartmentId,
    isEditing,
    roleOptions,
    roleSearchLoading,
    onRoleSearch,
  ),
  showDefaultActions: false,
});

const debouncedRoleSearch = useDebounceFn(searchRoleOptions, 300);

function onRoleSearch(value: string) {
  if (!canAssignRoles.value || !roleAssignmentReady.value) return;
  roleSearchLoading.value = true;
  const searchSequence = ++roleSearchSequence;
  void debouncedRoleSearch(value, searchSequence, drawerLoadSequence);
}

async function searchRoleOptions(
  value: string,
  searchSequence: number,
  loadSequence: number,
) {
  try {
    const result = await getRoleList({
      name: value.trim() || undefined,
      page: 1,
      pageSize: 200,
      status: 1,
    });
    const values = await formApi.getValues<UserFormValues>();
    if (
      searchSequence !== roleSearchSequence ||
      loadSequence !== drawerLoadSequence
    ) {
      return;
    }
    roleCatalog.value = mergeRoleSearchResults(
      roleCatalog.value,
      result.items,
      [...(formData.value?.roleIds ?? []), ...(values.roleIds ?? [])],
    );
  } catch {
    if (
      searchSequence === roleSearchSequence &&
      loadSequence === drawerLoadSequence
    ) {
      message.error($t('system.user.roleSearchFailed'));
    }
  } finally {
    if (
      searchSequence === roleSearchSequence &&
      loadSequence === drawerLoadSequence
    ) {
      roleSearchLoading.value = false;
    }
  }
}

async function initializeForm(isOpen: boolean) {
  const loadSequence = ++drawerLoadSequence;
  roleSearchSequence += 1;
  roleSearchLoading.value = false;
  roleAssignmentReady.value = false;
  roleCatalog.value = [];
  drawerApi.setState({
    loading: isOpen,
    showConfirmButton: false,
  });
  if (!isOpen) return;

  const data = drawerApi.getData<SystemUserApi.SystemUser>();
  formApi.resetForm();
  formData.value = data?.id ? data : undefined;
  id.value = data?.id;

  try {
    const catalogResult = canAssignRoles.value
      ? await tryLoadRoleAssignmentCatalog(getRoleList, data?.roleIds ?? [])
      : { ready: true as const, roles: [] };
    if (loadSequence !== drawerLoadSequence) return;
    roleCatalog.value = catalogResult.roles;
    if (!catalogResult.ready) {
      message.error($t('system.user.roleCatalogLoadFailed'));
      return;
    }

    await formApi.setValues(
      data?.id
        ? { ...data, roleIds: data.roleIds ?? [] }
        : {
            credentialVersion: 0,
            identityVersion: 0,
            roleIds: [],
            status: 1,
            userVersion: 0,
          },
    );
    if (loadSequence !== drawerLoadSequence) return;
    roleAssignmentReady.value = true;
    drawerApi.setState({ showConfirmButton: true });
  } catch {
    if (loadSequence !== drawerLoadSequence) return;
    roleCatalog.value = [];
    message.error($t('system.user.roleCatalogLoadFailed'));
  } finally {
    if (loadSequence === drawerLoadSequence) {
      drawerApi.setState({ loading: false });
    }
  }
}

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    if (!roleAssignmentReady.value) return;
    const { valid } = await formApi.validate();
    if (!valid) return;

    const values = await formApi.getValues<UserFormValues>();
    const roleIds = resolveRoleAssignmentIds(
      canAssignRoles.value,
      values.roleIds ?? [],
      formData.value?.roleIds ?? [],
      roleCatalog.value,
      canRemoveDisabledRoles.value,
    );
    if (!hasExplicitRoleIds(roleIds)) return;

    drawerApi.lock();
    try {
      await (id.value
        ? updateUser(
            id.value,
            toMembershipUpdateParams(values, roleIds, canEditIdentity.value),
          )
        : createUser(toUserCreateParams(values, roleIds)));
      emits('success');
      drawerApi.close();
    } catch (error) {
      if (!isOptimisticLockConflict(error)) throw error;
      emits('success');
      drawerApi.close();
    } finally {
      drawerApi.unlock();
    }
  },
  onOpenChange(isOpen) {
    void initializeForm(isOpen);
  },
});

const getDrawerTitle = computed(() =>
  formData.value?.id
    ? $t('ui.actionTitle.edit', [$t('system.user.entity')])
    : $t('ui.actionTitle.create', [$t('system.user.entity')]),
);
</script>

<template>
  <Drawer :title="getDrawerTitle">
    <Form />
  </Drawer>
</template>
