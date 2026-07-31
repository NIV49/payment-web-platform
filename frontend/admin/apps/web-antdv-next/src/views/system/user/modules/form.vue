<script lang="ts" setup>
import type { UserFormValues } from './form-contract';

import type { SystemRoleApi } from '#/api/system/role';
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer } from '@vben/common-ui';

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
  loadRoleAssignmentCatalog,
  resolveRoleAssignmentIds,
} from './role-assignment';

const emits = defineEmits(['success']);
const formData = ref<SystemUserApi.SystemUser>();
const { hasAccessByCodes } = useAccess();

const canAssignRoles = computed(() =>
  hasAllAccessCodes(
    [PERMISSION_CODES.userAssignRole, PERMISSION_CODES.roleView],
    hasAccessByCodes,
  ),
);
const canRemoveDisabledRoles = computed(() =>
  hasAccessByCodes([PERMISSION_CODES.roleGrantUpdate]),
);
const id = ref<string>();
const isEditing = computed(() => Boolean(id.value));
const roleCatalog = ref<SystemRoleApi.SystemRole[]>([]);
const roleOptions = computed(() =>
  buildRoleAssignmentOptions(
    roleCatalog.value,
    formData.value?.roleIds ?? [],
    formData.value?.roleNames ?? [],
    canRemoveDisabledRoles.value,
  ),
);

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(canAssignRoles, isEditing, roleOptions),
  showDefaultActions: false,
});

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
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
        ? updateUser(id.value, toMembershipUpdateParams(values, roleIds))
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
  async onOpenChange(isOpen) {
    if (!isOpen) return;

    const data = drawerApi.getData<SystemUserApi.SystemUser>();
    formApi.resetForm();
    formData.value = data?.id ? data : undefined;
    id.value = data?.id;
    roleCatalog.value = canAssignRoles.value
      ? await loadRoleAssignmentCatalog(getRoleList)
      : [];
    await formApi.setValues(
      data?.id
        ? { ...data, roleIds: data.roleIds ?? [] }
        : { roleIds: [], status: 1, userVersion: 0 },
    );
  },
});

const getDrawerTitle = computed(() =>
  formData.value?.id
    ? $t('ui.actionTitle.edit', [$t('system.user.name')])
    : $t('ui.actionTitle.create', [$t('system.user.name')]),
);
</script>

<template>
  <Drawer :title="getDrawerTitle">
    <Form />
  </Drawer>
</template>
