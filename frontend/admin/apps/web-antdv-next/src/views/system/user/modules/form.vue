<script lang="ts" setup>
import type { UserFormValues } from './form-contract';

import type { SystemUserApi } from '#/api/system/user';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createUser,
  hasExplicitRoleIds,
  PERMISSION_CODES,
  updateUser,
} from '#/api';
import { $t } from '#/locales';

import { useFormSchema } from '../data';
import { toMembershipUpdateParams, toUserCreateParams } from './form-contract';

const emits = defineEmits(['success']);
const formData = ref<SystemUserApi.SystemUser>();
const { hasAccessByCodes } = useAccess();

const canAssignRoles = computed(() =>
  hasAccessByCodes([PERMISSION_CODES.userAssignRole]),
);
const id = ref<string>();
const isEditing = computed(() => Boolean(id.value));

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(canAssignRoles, isEditing),
  showDefaultActions: false,
});

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;

    const values = await formApi.getValues<UserFormValues>();
    const roleIds =
      id.value && !canAssignRoles.value
        ? formData.value?.roleIds
        : values.roleIds;
    if (!hasExplicitRoleIds(roleIds)) return;

    drawerApi.lock();
    try {
      await (id.value
        ? updateUser(id.value, toMembershipUpdateParams(values, roleIds))
        : createUser(toUserCreateParams(values, roleIds)));
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
