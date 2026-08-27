<script lang="ts" setup>
import type { SystemRoleApi } from '@payment/backoffice-runtime/api/system/role';
import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { getRoleList, replaceUserRoles } from '@payment/backoffice-runtime/api';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import { $t } from '@payment/backoffice-runtime/locales';
import { Alert, Button, Empty, message, Select, Spin } from 'antdv-next';

import {
  buildRoleAssignmentOptions,
  mergeRoleAssignmentIds,
  tryLoadRoleAssignmentCatalog,
} from './role-assignment';

const emits = defineEmits(['success']);
const userStore = useUserStore();
const target = ref<SystemUserApi.SystemUser>();
const roleCatalog = ref<SystemRoleApi.SystemRole[]>([]);
const selectedRoleIds = ref<string[]>([]);
const loadFailed = ref(false);
const ready = ref(false);
let loadSequence = 0;

const canRemoveDisabledRoles = computed(
  () => userStore.userInfo?.systemAdministrator === true,
);
const roleOptions = computed(() =>
  buildRoleAssignmentOptions(
    roleCatalog.value,
    target.value?.roleIds ?? [],
    target.value?.roleNames ?? [],
    canRemoveDisabledRoles.value,
  ),
);
const title = computed(() =>
  $t('system.user.assignRoleTitle', [target.value?.name ?? '']),
);

async function loadRoles() {
  const currentTarget = target.value;
  if (!currentTarget) return;
  const sequence = ++loadSequence;
  ready.value = false;
  loadFailed.value = false;
  drawerApi.setState({ loading: true, showConfirmButton: false });
  const result = await tryLoadRoleAssignmentCatalog(
    getRoleList,
    currentTarget.roleIds,
  );
  if (sequence !== loadSequence || target.value?.id !== currentTarget.id)
    return;
  roleCatalog.value = result.roles;
  loadFailed.value = !result.ready;
  ready.value = result.ready;
  drawerApi.setState({
    loading: false,
    showConfirmButton: result.ready,
  });
}

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const currentTarget = target.value;
    if (!currentTarget || !ready.value) return;
    const roleIds = mergeRoleAssignmentIds(
      selectedRoleIds.value,
      currentTarget.roleIds,
      roleCatalog.value,
      canRemoveDisabledRoles.value,
    );
    drawerApi.lock();
    try {
      await replaceUserRoles(currentTarget.id, {
        roleIds,
        userVersion: currentTarget.userVersion,
      });
      message.success(
        $t('system.user.assignRoleSuccess', [currentTarget.name]),
      );
      emits('success');
      drawerApi.close();
    } catch (error) {
      if (isOptimisticLockConflict(error)) {
        emits('success');
        drawerApi.close();
        return;
      }
      throw error;
    } finally {
      drawerApi.unlock();
    }
  },
  onOpenChange(open) {
    loadSequence += 1;
    ready.value = false;
    loadFailed.value = false;
    roleCatalog.value = [];
    if (!open) {
      target.value = undefined;
      selectedRoleIds.value = [];
      return;
    }
    target.value = drawerApi.getData<SystemUserApi.SystemUser>();
    selectedRoleIds.value = [...(target.value?.roleIds ?? [])];
    void loadRoles();
  },
});
</script>

<template>
  <Drawer :title="title">
    <Alert
      v-if="loadFailed"
      show-icon
      :title="$t('system.user.assignRoleLoadFailed')"
      type="error"
    >
      <template #action>
        <Button size="small" @click="loadRoles">
          {{ $t('common.retry') }}
        </Button>
      </template>
    </Alert>
    <Spin v-else :spinning="!ready">
      <Select
        v-if="roleOptions.length > 0"
        v-model:value="selectedRoleIds"
        class="w-full"
        :disabled="!ready"
        mode="multiple"
        :options="roleOptions"
        option-filter-prop="label"
        show-search
      />
      <Empty
        v-else-if="ready"
        :description="$t('system.user.assignRoleEmpty')"
      />
    </Spin>
  </Drawer>
</template>
